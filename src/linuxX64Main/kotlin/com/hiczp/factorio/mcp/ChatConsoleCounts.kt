package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.ConstructorValues.Argument
import com.hiczp.factorio.mcp.X64Instructions.Memory
import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Console count words cross-checked against named native list-size decrements. Never removes messages. */
internal object ChatConsoleCounts {
    fun verify(image: ElfImage, lists: ChatConsoleLists) {
        val function = image.symbol("_ZN13OutputConsole22removeMessagesByPlayerERK6Player")
        val ranges = image.inlines.find(function, "removeMessagesByPlayer", setOf("_M_dec_size"))
            .flatMap { it.ranges }.map { DwarfRanges.Range(it.start - function.address, it.end - function.address) }
        analyze(X64ControlFlow.resolve(image, function), ranges, lists.lists.map { it.count }.toSet())
    }

    fun analyze(flow: X64ControlFlow, ranges: List<DwarfRanges.Range>, counts: Set<Long>) {
        require(counts.size == 2 && counts.all { it in 0..4088 && it % 8 == 0L } && ranges.size in 1..16)
        val values = ConstructorValues(flow, emptyMap())
        val decrements = flow.instructions.filter { it.offset in flow.reachable && it.operation == Operation.DEC }
        require(decrements.size == counts.size)
        val fields = decrements.map { instruction ->
            require(ranges.any { instruction.offset >= it.start && instruction.offset + instruction.size <= it.end }) {
                "Console decrement lacks native list-size inline identity"
            }
            val memory = instruction.destination as? Memory ?: error("Console list size is not a memory field")
            require(memory.width == 8 && !memory.relative && memory.index == null && memory.base != null)
            val owner = values.register(instruction.offset, memory.base) as? Argument
                ?: error("Console count does not belong to the original receiver")
            require(owner.register == 7)
            (owner.adjustment + memory.displacement).also { require(it in counts) }
        }
        require(fields.toSet() == counts && ranges.all { range ->
            range.start >= 0 && range.start < range.end && decrements.any {
                it.offset >= range.start && it.offset + it.size <= range.end
            }
        }) { "Native list-size updates disagree with the two reset count fields" }
    }
}
