package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Raw usage discriminator and its native ContinuousAction conversion. Does not assign enum names. */
internal data class ControlUsage(val field: Long, val comparison: Long, val whenEqual: Long, val whenDifferent: Long) {
    companion object {
        fun resolve(image: ElfImage, extent: Long): ControlUsage = resolve(
            image, extent,
            "_ZNK12ControlInput8isActiveEb9NamedBoolI11GuiCheckTagEbS0_I17CheckModifiersTagE",
            "_ZNK17ControlInputValue8isActiveEbNS_16ContinuousActionEbb9NamedBoolI17CheckModifiersTagE",
            "_ZNK17ControlInputValue14checkModifiersENS_16ContinuousActionE",
            "_ZN17ControlInputValue27checkRequiredModifiersMatchEPKS_"
        )

        fun resolve(
            image: ElfImage,
            extent: Long,
            control: String,
            value: String,
            modifiers: String,
            required: String
        ): ControlUsage {
            val entry = image.symbol(control)
            val check = image.symbol(value)
            val modifier = image.symbol(modifiers)
            val requirement = image.symbol(required)
            val frames = EhFrames(image)
            frames.function(entry)
            frames.function(check)
            frames.function(modifier)
            frames.function(requirement)
            val modifierArgument =
                spillArgument(image.functionBytes(modifier, 512), modifier.address, requirement.address)
            val checkFlow = X64ControlFlow.resolve(image, check)
            val copies =
                PrivateValueCopies(checkFlow, emptyMap(), scalarArguments = listOf(6, 2, 1, 8, 9).associateWith { 4 })
            val calls = checkFlow.instructions.filter {
                it.operation == Operation.CALL &&
                        it.destination == Immediate(modifier.address - check.address)
            }
            require(calls.isNotEmpty())
            val argument = calls.map { copies.argument(it.offset, Register(modifierArgument, 4)) }.distinct().single()
            val linked = LinkedReceiverPrefix.resolve(image, control, extent)
            val bytes = image.functionBytes(entry, 8192)
            require(bytes.size == entry.size && linked.end < bytes.size)
            return analyze(
                bytes.slice(linked.end, bytes.size - linked.end), entry.address + linked.end,
                check.address, linked.receiver, argument, extent
            )
        }

        /** A unique original int-width scalar saved privately before the selected first call. */
        fun spillArgument(bytes: BinaryView, address: Long, callee: Long): Int {
            require(bytes.size in 1..512 && address >= 0 && callee >= 0)
            val instructions = mutableListOf<X64Instructions.Instruction>()
            val decoder = X64Instructions(bytes)
            var position = 0L
            repeat(128) {
                require(position < bytes.size)
                val instruction = decoder.decode(position)
                instructions += instruction
                position += instruction.size
                require(instruction.operation !in listOf(Operation.JCC, Operation.JMP, Operation.RET))
                if (instruction.operation != Operation.CALL) return@repeat
                require(instruction.destination == Immediate(callee - address))
                val flow = X64ControlFlow(instructions)
                val frame = SysVLocalArgument(flow)
                val copies =
                    PrivateValueCopies(flow, emptyMap(), scalarArguments = listOf(6, 2, 1, 8, 9).associateWith { 4 })
                val inputs = instructions.mapNotNull { store ->
                    val target = store.destination as? Memory ?: return@mapNotNull null
                    val source = store.source as? Register ?: return@mapNotNull null
                    if (store.operation != Operation.MOV || target.width != 4 || source.width != 4) return@mapNotNull null
                    val location =
                        frame.address(store.offset, target) ?: error("Scalar prefix writes outside its private frame")
                    require(location >= checkNotNull(frame.registers(store.offset)[4]) && location <= -4)
                    copies.argument(store.offset, source)
                }.distinct()
                return inputs.singleOrNull() ?: error("Enum check lacks a unique original scalar spill")
            }
            error("Enum scalar spill exceeds prefix bound")
        }

        fun analyze(
            bytes: BinaryView,
            address: Long,
            callee: Long,
            receiver: Int,
            argument: Int,
            extent: Long
        ): ControlUsage {
            require(bytes.size in 1..8192 && address >= 0 && callee >= 0 && extent in 8..4096)
            require(receiver in listOf(3, 12, 13, 14, 15) && argument in listOf(6, 2, 1, 8, 9))
            val instructions = X64Instructions(bytes).all()
            require(instructions.filter { it.operation in listOf(Operation.JCC, Operation.JMP) }.all {
                (it.destination as? Immediate)?.value?.let { target -> target in 0 until bytes.size } == true
            })
            val flow = X64ControlFlow(instructions)
            val arguments = SysVArgumentFlow(flow, entryReferences = mapOf(receiver to SysVArgumentFlow.Reference(7)))
            val scalars = ScalarExpression(flow, mapOf(7 to extent), arguments)
            val calls =
                instructions.filter { it.operation == Operation.CALL && it.destination == Immediate(callee - address) }
            require(calls.size == 2)
            return calls.map { conversion(scalars.before(it.offset, Register(argument, 4)), extent) }.distinct()
                .single()
        }

        fun conversion(expression: ScalarExpression.Value, extent: Long): ControlUsage {
            require(extent in 8..4096 && expression.width == 4)
            fun strip(value: ScalarExpression.Value): ScalarExpression.Value =
                if (value is ScalarExpression.Narrow && value.width == value.value.width) strip(value.value) else value

            val input = ScalarExpression.inputs(expression).single()
            require(
                input.field.reference.argument == 7 && input.field.width == 4 && input.width == 4 &&
                        input.field.reference.offset in 0..extent - 4
            )
            val selections = mutableListOf<Pair<ScalarExpression.Select, Long>>()
            fun inspect(node: ScalarExpression.Value) {
                when (node) {
                    is ScalarExpression.Narrow -> inspect(node.value)
                    is ScalarExpression.Binary -> {
                        inspect(node.left)
                        inspect(node.right)
                    }

                    is ScalarExpression.Select -> {
                        val left = strip(node.left)
                        val right = strip(node.right)
                        val constant = if (left == input) right as? ScalarExpression.Literal
                        else if (right == input) left as? ScalarExpression.Literal else null
                        if (constant != null && constant.width == 4 && node.condition in listOf(
                                4,
                                5
                            )
                        ) selections += node to constant.value
                        inspect(node.left)
                        inspect(node.right)
                        inspect(node.yes)
                        inspect(node.no)
                    }

                    else -> Unit
                }
            }
            inspect(expression)
            val (selection, constant) = selections.distinct().single()
            fun replace(node: ScalarExpression.Value): ScalarExpression.Value = if (node == selection)
                ScalarExpression.Literal(0, node.width) else when (node) {
                is ScalarExpression.Narrow -> node.copy(value = replace(node.value))
                is ScalarExpression.Binary -> node.copy(left = replace(node.left), right = replace(node.right))
                is ScalarExpression.Select -> node.copy(
                    left = replace(node.left), right = replace(node.right),
                    yes = replace(node.yes), no = replace(node.no)
                )

                else -> node
            }
            require(
                ScalarExpression.inputs(selection.yes).isEmpty() && ScalarExpression.inputs(selection.no).isEmpty() &&
                        ScalarExpression.inputs(replace(expression)).isEmpty()
            ) { "Usage conversion depends on more than its equality discriminator" }
            val comparison = constant and 0xffffffffL
            val equal = ScalarExpression.evaluate(expression) { comparison }
            val different = ScalarExpression.evaluate(expression) { comparison xor 1 }
            require(expression.width == 4 && equal != different)
            return ControlUsage(input.field.reference.offset, comparison, equal, different)
        }
    }
}
