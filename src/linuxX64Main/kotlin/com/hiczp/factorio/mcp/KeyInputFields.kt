package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Field correspondence between the named keyboard dequeue and the independently verified reference event. */
internal data class KeyInputFields(
    val key: Long,
    val extended: Long,
    val character: Long,
    val control: Long,
    val time: Long
) {
    companion object {
        fun resolve(image: ElfImage, event: KeyEventFields, inputMember: Long): KeyInputFields {
            require(inputMember in 0..4096)
            val function = image.symbol("_ZN4agui3Gui5logicEb")
            val complete = X64ControlFlow.resolve(image, function)
            val dispatch =
                image.symbol("_ZN4agui6Widget30_dispatchKeyboardListenerEventENS_8KeyEvent17KeyboardEventEnumERKS1_")
            val sink = complete.instructions.single {
                it.operation == Operation.CALL &&
                        it.destination == Immediate(dispatch.address - function.address)
            }
            // InlineMemberConstruction independently checks first-dispatch prefix closure for this same function.
            val flow = X64ControlFlow(complete.instructions.takeWhile { it.offset <= sink.offset })
            val values = ConstructorValues(flow, emptyMap())
            val debug = DwarfInlines(image)
            val dequeue = debug.find(function, "logic", setOf("dequeueKeyboardInput"))
            val construction = debug.find(function, "logic", setOf("setKeyEvent"))
                .minBy { it.ranges.minOf { range -> range.start } }
            val reads = flow.instructions.mapNotNull { instruction ->
                if (instruction.operation !in listOf(
                        Operation.MOV, Operation.MOVZX, Operation.SCALAR_MOV,
                        Operation.VECTOR_MOV
                    ) || instruction.destination !is Register
                ) return@mapNotNull null
                val memory = instruction.source as? Memory ?: return@mapNotNull null
                if (memory.relative || memory.index != null || memory.base == null) return@mapNotNull null
                if (dequeue.none { inline ->
                        inline.ranges.any {
                            function.address + instruction.offset >= it.start &&
                                    function.address + instruction.offset + instruction.size <= it.end
                        }
                    }) return@mapNotNull null
                val element = values.register(instruction.offset, memory.base) as? ConstructorValues.Load
                    ?: return@mapNotNull null
                val input = element.base as? ConstructorValues.Load ?: return@mapNotNull null
                if (input.base != ConstructorValues.Argument(7) || input.member != inputMember) return@mapNotNull null
                require(element.member in 0..4096 && memory.displacement in 0..256L - memory.width)
                Triple(element, instruction.offset, InlineArgumentFields.Field(memory.displacement, memory.width))
            }
            require(reads.isNotEmpty()) { "Keyboard dequeue has no typed input reads" }
            val copies = PrivateValueCopies(flow, reads.associate {
                it.second to PrivateValueCopies.Read(it.first.site, it.third)
            })
            val sources = mutableSetOf<Long>()
            fun source(offset: Long, width: Int): Long {
                val assignment = flow.instructions.single { instruction ->
                    val memory = instruction.destination as? Memory
                    val base =
                        memory?.base?.let { values.register(instruction.offset, it) } as? ConstructorValues.Argument
                    instruction.operation in listOf(Operation.MOV, Operation.SCALAR_MOV) && memory != null &&
                            !memory.relative && memory.index == null && memory.width == width && base?.register == 7 &&
                            base.adjustment + memory.displacement == event.construction.member + offset &&
                            construction.ranges.any {
                                function.address + instruction.offset >= it.start &&
                                        function.address + instruction.offset + instruction.size <= it.end
                            }
                }
                val register = assignment.source as? Register ?: error("Keyboard event field is not a scalar copy")
                return copies.field(assignment.offset, register).also {
                    require(it.field.width == width)
                    sources += it.source
                }.field.offset
            }
            return KeyInputFields(
                source(event.key, 4), source(event.extended, 4), source(event.character, 4),
                source(event.control, 1), source(event.time, 8)
            ).also {
                require(sources.size == 1) { "Keyboard event fields originate from different input elements" }
            }
        }
    }
}
