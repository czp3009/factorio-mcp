package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Original byte argument whose nonzero entry path admits the first named native check. */
internal object CallGateArgument {
    fun resolve(image: ElfImage, caller: String, callee: String): Int {
        val from = image.symbol(caller)
        val to = image.symbol(callee)
        EhFrames(image).function(from)
        EhFrames(image).function(to)
        return analyze(image.functionBytes(from, 512), from.address, to.address)
    }

    fun analyze(bytes: BinaryView, address: Long, callee: Long): Int {
        require(bytes.size in 1..512 && address >= 0 && callee >= 0)
        val decoder = X64Instructions(bytes)
        val instructions = mutableListOf<X64Instructions.Instruction>()
        var position = 0L
        repeat(128) {
            require(position < bytes.size && position < 512)
            val instruction = decoder.decode(position)
            instructions += instruction
            position += instruction.size
            require(instruction.operation !in setOf(Operation.RET, Operation.JMP))
            if (instruction.operation != Operation.CALL) return@repeat
            require(instruction.destination == Immediate(callee - address)) {
                "First call is not the selected native check"
            }
            val flow = X64ControlFlow(instructions)
            val arguments = SysVArgumentFlow(flow)
            val definitions = ScalarExpression(flow)
            val gates =
                instructions.mapNotNull { branch ->
                    if (branch.operation != Operation.JCC || branch.offset !in flow.reachable)
                        return@mapNotNull null
                    val test = definitions.flagDefinition(branch.offset)
                    val input = test.destination as? Register ?: return@mapNotNull null
                    if (input.width != 1) return@mapNotNull null
                    val skipCondition =
                        when {
                            test.operation == Operation.TEST && test.source == input -> 4
                            test.operation == Operation.CMP && test.source == Immediate(1) -> 5
                            else -> return@mapNotNull null
                        }
                    val target =
                        (branch.destination as? Immediate)?.value ?: error("Indirect native gate")
                    require(
                        branch.condition == skipCondition &&
                            target >= position &&
                            target < bytes.size
                    ) {
                        "Original byte does not guard the native check's nonzero path"
                    }
                    val original =
                        arguments.register(test.offset, input.number)
                            ?: error("Native gate byte was changed")
                    require(original.offset == 0L && original.argument != 7)
                    val visited = mutableSetOf<Long>()
                    val pending = ArrayDeque<Long>()
                    pending.add(0)
                    while (pending.isNotEmpty()) {
                        val site = pending.removeFirst()
                        if (!visited.add(site) || site == branch.offset) continue
                        require(site != instruction.offset) {
                            "Native check can bypass its byte gate"
                        }
                        pending.addAll(flow.successors.getValue(site))
                    }
                    original.argument
                }
            return gates.singleOrNull() ?: error("Native check has no unique original-byte gate")
        }
        error("Native check gate exceeds prefix bound")
    }
}

/**
 * The linked owner's byte flag forwarded to both native value checks. No action-eligibility
 * evaluation.
 */
internal object ControlGuiFlag {
    fun resolve(image: ElfImage, extent: Long): NativeAccessor =
        resolve(
            image,
            extent,
            ControlEntries.ACTIVE,
            ControlEntries.VALUE_ACTIVE,
            "_ZN10InputState5inGuiEb",
        )

    fun resolve(
        image: ElfImage,
        extent: Long,
        control: String,
        value: String,
        guiCheck: String,
    ): NativeAccessor {
        val entry = image.symbol(control)
        val check = image.symbol(value)
        val argument = CallGateArgument.resolve(image, value, guiCheck)
        val linked = LinkedReceiverPrefix.resolve(image, control, extent)
        val bytes = image.functionBytes(entry, 8192)
        require(bytes.size == entry.size && linked.end < bytes.size)
        return analyze(
            bytes.slice(linked.end, bytes.size - linked.end),
            entry.address + linked.end,
            check.address,
            linked.receiver,
            argument,
            extent,
        )
    }

    fun analyze(
        bytes: BinaryView,
        address: Long,
        callee: Long,
        receiver: Int,
        argument: Int,
        extent: Long,
    ): NativeAccessor {
        require(bytes.size in 1..8192 && address >= 0 && callee >= 0)
        require(
            receiver in listOf(3, 12, 13, 14, 15) &&
                argument in listOf(6, 2, 1, 8, 9) &&
                extent in 8..4096
        )
        val instructions = X64Instructions(bytes).all()
        require(
            instructions
                .filter { it.operation in setOf(Operation.JCC, Operation.JMP) }
                .all {
                    (it.destination as? Immediate)?.value?.let { target ->
                        target in 0 until bytes.size
                    } == true
                }
        ) {
            "Control suffix leaves its verified linked-owner region"
        }
        val flow = X64ControlFlow(instructions)
        val arguments =
            SysVArgumentFlow(
                flow,
                entryReferences = mapOf(receiver to SysVArgumentFlow.Reference(7)),
            )
        val scalars = ScalarExpression(flow, mapOf(7 to extent), arguments)
        val calls =
            instructions.filter {
                it.operation == Operation.CALL && it.destination == Immediate(callee - address)
            }
        require(calls.isNotEmpty() && calls.all { it.offset in flow.reachable })
        val fields =
            calls
                .mapNotNull { call ->
                    val expression = scalars.before(call.offset, Register(argument, 1))
                    val inputs = ScalarExpression.inputs(expression)
                    // Explicit caller overrides can coexist with the stored default. They do not
                    // create a new field.
                    if (inputs.isEmpty()) {
                        require(
                            ScalarExpression.evaluate(expression) {
                                error("Unexpected GUI override input")
                            } in 0..1
                        )
                        return@mapNotNull null
                    }
                    val input =
                        inputs.singleOrNull() ?: error("GUI flag is not a single native member")
                    require(input.field.reference.argument == 7 && input.field.width == 1)
                    if (
                        (0L..255L).all { raw ->
                            ScalarExpression.evaluate(expression) { raw } == raw
                        }
                    ) {
                        return@mapNotNull NativeAccessor(input.field.reference.offset, 1, 0xffUL, 0)
                            .withinObject(extent)
                    }
                    val bits =
                        (0..7).filter { shift ->
                            (0L..255L).all { raw ->
                                ScalarExpression.evaluate(expression) { raw } ==
                                    (raw shr shift and 1)
                            }
                        }
                    val shift =
                        bits.singleOrNull()
                            ?: error("GUI check argument is not one unchanged native flag bit")
                    NativeAccessor(input.field.reference.offset, 1, 1uL shl shift, shift)
                        .withinObject(extent)
                }
                .distinct()
        return fields.singleOrNull()
            ?: error("Native control value checks disagree on their GUI flag")
    }
}
