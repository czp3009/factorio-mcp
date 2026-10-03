package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Native binding discriminator whose save branch constructs an empty returned string. */
internal object ControlEmptyKind {
    fun resolve(
        image: ElfImage, extent: Long, type: Long, code: Long,
        string: NativeStringLayout, debug: DwarfInlines = image.inlines,
        function: String = "_ZNK17ControlInputValue4saveB5cxx11Ev"
    ): Int {
        val entry = image.symbol(function)
        EhFrames(image).function(entry)
        val tables = X64JumpTables.resolve(image, entry)
        val flow = X64ControlFlow(X64Instructions(image.functionBytes(entry, 32768)).all(8192), tables)
        val ranges = debug.find(entry, "save", setOf("basic_string"))
            .filter { it.ranges.size == 1 }.map { it.ranges.single() }
            .map { DwarfRanges.Range(it.start - entry.address, it.end - entry.address) }
        val cleanup = image.symbols().filter { it.name in setOf("_ZdlPv", "_ZdlPvm") && it.size > 0 }
            .map { it.address - entry.address }.toSet()
        return analyze(flow, tables, extent, type, code, string, ranges, cleanup)
    }

    fun analyze(
        flow: X64ControlFlow, tables: List<X64JumpTables.Table>, extent: Long, type: Long, code: Long,
        string: NativeStringLayout, ranges: List<DwarfRanges.Range>, cleanup: Set<Long> = emptySet()
    ): Int {
        // Line-only inline metadata can attribute the exit jump to the constructor's last source line.
        // Verify the construction separately, then include that jump in the unchanged-return proof.
        val constructors = ranges.map { range ->
            val last = flow.instructions.singleOrNull { it.offset + it.size == range.end }
            if (last?.operation in setOf(Operation.JMP, Operation.RET))
                range.copy(end = checkNotNull(last).offset) else range
        }
        val output = EmptyStringOutput.analyze(flow, string, constructors)
        require(output.argument == 7) { "Binding save does not use the native hidden string return argument" }
        val switch = MemberSwitch(flow, tables, 6, extent, type, code)
        val kinds = output.constructors.map { site ->
            val end = constructors.filter { it.start == site }.map { it.end }.distinct().single()
            unchangedReturn(flow, end, cleanup)
            switch.case(site)
        }.distinct()
        return kinds.singleOrNull() ?: error("Binding save has no unique empty value type")
    }

    private fun unchangedReturn(flow: X64ControlFlow, start: Long, cleanup: Set<Long>) {
        val frame = SysVLocalArgument(flow)
        val pending = ArrayDeque<Long>()
        val seen = mutableSetOf<Long>()
        pending.add(start)
        var returns = 0
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (!seen.add(site)) continue
            require(seen.size <= 96) { "Empty binding return path exceeds bound" }
            val instruction = flow.body.getValue(site)
            val target = instruction.destination as? Register
            when (instruction.operation) {
                Operation.NOP -> Unit
                Operation.MOV -> {
                    require(target != null && target.width == 8 && target.number != 4)
                    when (val source = instruction.source) {
                        is Register -> require(source.width == 8)
                        is Immediate -> Unit
                        is Memory -> {
                            val offset =
                                frame.address(site, source) ?: error("Empty binding return reads outside its frame")
                            require(source.width == 8 && offset >= checkNotNull(frame.registers(site)[4]) && offset <= -8)
                        }

                        else -> error("Unsupported empty binding return value")
                    }
                }

                Operation.CMP -> require(target?.width == 8 && instruction.source is Register)
                Operation.INC -> require(target == Register(6, 8))
                Operation.CALL -> require((instruction.destination as? Immediate)?.value in cleanup) {
                    "Empty binding return calls more than native deallocation"
                }

                Operation.ADD -> require(target == Register(4, 8) && instruction.source is Immediate)
                Operation.POP -> require(target?.width == 8 && target.number != 4)
                Operation.JMP, Operation.JCC -> require((instruction.destination as? Immediate)?.value in flow.body)
                Operation.RET -> returns++
                else -> error("Empty binding return may modify its string: ${instruction.operation}")
            }
            val next = flow.successors.getValue(site)
            require(next.all { it > site } && (next.isNotEmpty() || instruction.operation == Operation.RET)) {
                "Empty binding return loops or leaves its body"
            }
            pending.addAll(next)
        }
        require(returns > 0)
    }
}
