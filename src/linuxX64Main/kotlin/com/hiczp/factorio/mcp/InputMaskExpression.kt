package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Native queue mask computation. Button names, event kinds and later GUI substitutions require separate evidence. */
internal data class InputMaskExpression(
    val site: Long,
    val field: Long,
    val code: Long,
    val expression: ScalarExpression.Value
) {
    companion object {
        fun resolve(image: ElfImage, input: MouseInputLayout): InputMaskExpression {
            val header = EventHeader.resolve(image)
            val function = image.symbol("_ZN16InputHandlerAgui12processEventERK5Event")
            val flow = X64ControlFlow.resolve(image, function)
            val target = image.symbol("_ZNSt5dequeIN4agui10MouseInputESaIS1_EE16_M_push_back_auxIJRKS1_EEEvDpOT_")
            val sink = flow.instructions.single {
                it.operation == Operation.CALL &&
                        (it.destination as? Immediate)?.value?.plus(function.address) == target.address
            }.offset
            val frame = SysVLocalArgument(flow)
            val storage = frame.argument(sink, 6, input.queue.extent)
            val known = listOf(input.time to 8, input.alt.field to 1, input.control to 1, input.shift to 1)
            val candidates = flow.instructions.mapNotNull { instruction ->
                if (instruction.operation != Operation.MOV || instruction.offset !in flow.reachable) return@mapNotNull null
                val memory = instruction.destination as? Memory ?: return@mapNotNull null
                val register = instruction.source as? Register ?: return@mapNotNull null
                if (memory.width != 2 || register.width != 2) return@mapNotNull null
                val address = frame.address(instruction.offset, memory) ?: return@mapNotNull null
                val offset = address - storage
                if (offset < 0 || offset > input.queue.extent - 2 || known.any {
                        offset < it.first + it.second && it.first < offset + 2
                    }) return@mapNotNull null
                val copied = LocalFieldCopies(flow).fromRegister(instruction.offset, sink, register.number, 2)
                if (copied != listOf(LocalFieldCopies.Field(offset, 2))) return@mapNotNull null
                // Controller conversion can fill this same queue field through a separate native mapping.
                // It cannot establish the direct Event scalar computation selected here.
                val expression = try {
                    ScalarExpression(flow, mapOf(6 to header.extent.toLong())).before(instruction.offset, register)
                } catch (_: IllegalArgumentException) {
                    return@mapNotNull null
                } catch (_: IllegalStateException) {
                    return@mapNotNull null
                }
                InputMaskExpression(instruction.offset, offset, validate(header, expression), expression)
            }
            return candidates.singleOrNull() ?: error("Native mouse input has no unique bounded mask computation")
        }

        fun validate(header: EventHeader, expression: ScalarExpression.Value): Long {
            require(expression.width == 2)
            val reads = ScalarExpression.inputs(expression).map { it.field }.toSet()
            require(reads.all { it.reference.argument == 6 && it.width == 4 })
            val type = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, header.type), 4)
            require(type in reads && reads.size == 2)
            val code = (reads - type).single().reference.offset
            require(
                code >= 0 && code <= header.extent - 4 &&
                        (code + 4 <= header.type || header.type + 4 <= code) &&
                        (code + 4 <= header.time || header.time + 8 <= code)
            )
            fun unwrapped(value: ScalarExpression.Value): ScalarExpression.Value {
                var result = value
                var depth = 0
                while (result is ScalarExpression.Narrow) {
                    require(++depth <= 128 && result.width in listOf(1, 2, 4))
                    result = result.value
                }
                return result
            }

            fun one(value: ScalarExpression.Value): Boolean = ScalarExpression.inputs(value).isEmpty() &&
                    ScalarExpression.evaluate(value) { error("Unexpected mask input") } == 1L

            val pending = ArrayDeque<ScalarExpression.Value>()
            pending.add(expression)
            var work = 0
            var shifts = 0
            while (pending.isNotEmpty()) {
                require(++work <= 1024)
                val value = unwrapped(pending.removeFirst())
                when (value) {
                    is ScalarExpression.Select -> pending.addAll(listOf(value.yes, value.no))
                    is ScalarExpression.Binary -> {
                        require(value.operation == Operation.SHL && value.width == 4 && one(value.left)) {
                            "Native mask is not a guarded single-bit shift"
                        }
                        val count = unwrapped(value.right) as? ScalarExpression.Input
                            ?: error("Native mask count is transformed")
                        require(count.field == SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, code), 4))
                        ++shifts
                    }

                    else -> require(one(value)) { "Native mask has a non-unit fallback" }
                }
            }
            require(shifts > 0)
            return code
        }
    }
}
