package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Event members consumed by native hotkey classification, arrow handling and character insertion.
 */
internal object KeyEventReads {
    fun resolve(image: ElfImage): Map<String, NativeField> {
        val function = image.symbol("_ZN4agui7TextBox23textInputHandleKeyEventERKNS_8KeyEventE")
        val flow = X64ControlFlow.resolve(image, function)
        val instances =
            image.inlines.find(
                function,
                "textInputHandleKeyEvent",
                setOf("getHotkeyType", "handleArrowKey"),
            )
        fun ranges(name: String) =
            instances
                .filter { it.name == name }
                .flatMap { it.ranges }
                .map { DwarfRanges.Range(it.start - function.address, it.end - function.address) }
        return analyze(
            flow,
            ranges("getHotkeyType"),
            ranges("handleArrowKey"),
            image.symbol("_ZN4agui7TextBox18addToNextCharacterEi").address - function.address,
        )
    }

    fun analyze(
        flow: X64ControlFlow,
        hotkeys: List<DwarfRanges.Range>,
        arrows: List<DwarfRanges.Range>,
        insertion: Long,
    ): Map<String, NativeField> {
        val arguments = SysVArgumentFlow(flow)
        val scalars = ScalarExpression(flow, mapOf(6 to 256L), arguments)
        fun within(instruction: Instruction, ranges: List<DwarfRanges.Range>) =
            ranges.any {
                instruction.offset >= it.start && instruction.offset + instruction.size <= it.end
            }
        require(
            (hotkeys + arrows).all {
                it.start in flow.body && it.end in flow.body && it.start < it.end
            }
        )
        fun bounded(read: SysVArgumentFlow.Read): NativeField {
            require(
                read.reference.argument == 6 &&
                    read.width in listOf(1, 4) &&
                    read.reference.offset in 0..256L - read.width
            )
            return NativeField(read.reference.offset, read.width)
        }
        val hotkeyFields =
            flow.instructions
                .filter {
                    it.offset in flow.reachable &&
                        within(it, hotkeys) &&
                        it.operation in setOf(Operation.CMP, Operation.TEST)
                }
                .flatMap { instruction ->
                    listOfNotNull(
                            instruction.destination as? Register,
                            instruction.source as? Register,
                        )
                        .flatMap {
                            ScalarExpression.inputs(scalars.before(instruction.offset, it)).map {
                                input ->
                                bounded(input.field)
                            }
                        }
                }
                .toSet()
        val key = hotkeyFields.single { it.width == 4 }
        val control = hotkeyFields.single { it.width == 1 }
        require(hotkeyFields.size == 2)
        val extended =
            flow.instructions
                .filter { it.offset in flow.reachable && within(it, arrows) }
                .mapNotNull { arguments.source(it.offset) }
                .filter { it.reference.argument == 6 && it.width == 4 }
                .map(::bounded)
                .distinct()
                .single()
        val sinks =
            flow.instructions.filter {
                it.offset in flow.reachable &&
                    it.operation in setOf(Operation.CALL, Operation.JMP) &&
                    it.destination == Immediate(insertion)
            }
        require(sinks.isNotEmpty())
        val characters = mutableSetOf<NativeField>()
        val visited = mutableSetOf<Pair<Long, Register>>()
        val active = mutableSetOf<Pair<Long, Register>>()
        fun sources(site: Long, register: Register) {
            val key = site to register
            require(register.width == 4 && visited.size + active.size < 128)
            if (key in visited) return
            require(active.add(key)) { "Character argument has cyclic value provenance" }
            val definitions = scalars.definitions(site, register.number)
            require(definitions.isNotEmpty())
            for (definition in definitions) {
                require(
                    definition.operation == Operation.MOV && definition.destination == register
                ) {
                    "Character insertion transforms its native event member"
                }
                when (val source = definition.source) {
                    is Register -> sources(definition.offset, source)
                    is Immediate -> Unit // A native branch can insert a literal character.
                    is Memory ->
                        characters +=
                            bounded(checkNotNull(arguments.memory(definition.offset, source)))
                    else -> error("Character insertion has an unknown scalar input")
                }
            }
            active.remove(key)
            visited += key
        }
        for (sink in sinks) {
            require(arguments.register(sink.offset, 7) == SysVArgumentFlow.Reference(7))
            sources(sink.offset, Register(6, 4))
        }
        val character = characters.single().also { require(it.width == 4) }
        require(setOf(key.offset, extended.offset, character.offset, control.offset).size == 4)
        return mapOf(
            "key" to key,
            "extended" to extended,
            "character" to character,
            "control" to control,
        )
    }
}
