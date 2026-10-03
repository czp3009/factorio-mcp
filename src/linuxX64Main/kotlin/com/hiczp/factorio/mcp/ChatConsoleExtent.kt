package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.ConstructorValues.*
import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Complete console extent from its named inline destruction and original owned-pointer sized deletion. */
internal object ChatConsoleExtent {
    fun resolve(image: ElfImage, member: Long, playerSize: Long, lists: ChatConsoleLists): Long {
        val function = image.symbol("_ZN6PlayerD2Ev")
        val deletion = image.symbol("_ZdlPvm")
        EhFrames(image).function(deletion)
        val ranges = image.inlines.find(function, "~Player", setOf("~OutputConsole"))
            .flatMap { it.ranges }.map { DwarfRanges.Range(it.start - function.address, it.end - function.address) }
        return analyze(
            X64ControlFlow.resolve(image, function), deletion.address - function.address,
            ranges, member, playerSize, lists
        )
    }

    fun analyze(
        flow: X64ControlFlow, deletion: Long, ranges: List<DwarfRanges.Range>, member: Long,
        playerSize: Long, lists: ChatConsoleLists
    ): Long {
        require(playerSize in 8..(64 * 1024 * 1024) && member in 0..playerSize - 8 && member % 8 == 0L)
        require(ranges.size in 1..64 && ranges.all { it.start >= 0 && it.start < it.end })
        fun inDestruction(site: Long) = ranges.any { site >= it.start && site < it.end }
        fun followsDestruction(call: X64Instructions.Instruction): Boolean = ranges.any { range ->
            if (call.offset < range.end || call.offset - range.end > 32) return@any false
            val setup = flow.instructions.filter { it.offset in range.end until call.offset }
            if (setup.isEmpty() || setup.first().offset != range.end ||
                setup.last().offset + setup.last().size != call.offset
            ) return@any false
            if (setup.any { it.operation !in listOf(Operation.MOV, Operation.NOP) }) return@any false
            val path = setup + call
            path.zipWithNext().all { (left, right) -> flow.predecessors[right.offset] == setOf(left.offset) } &&
                    flow.predecessors[setup.first().offset].orEmpty().let { entries ->
                        entries.isNotEmpty() && entries.all(::inDestruction)
                    }
        }

        val calls = flow.instructions.filter { instruction ->
            instruction.offset in flow.reachable && instruction.operation == Operation.CALL &&
                    instruction.destination == Immediate(deletion) && (ranges.any {
                instruction.offset >= it.start && instruction.offset + instruction.size <= it.end
            } || followsDestruction(instruction))
        }
        require(calls.isNotEmpty()) { "Console inline destructor has no sized deletion" }
        val matches = calls.mapNotNull { call ->
            val values = ConstructorValues(flow.reaching(call.offset), emptyMap())
            val pointer = values.register(call.offset, 7) as? Load ?: return@mapNotNull null
            if (pointer.base != Argument(7) || pointer.member != member) return@mapNotNull null
            val size = values.register(call.offset, 6) as? Constant ?: error("Console deletion size is not constant")
            size.value.also { require(it in 8..4096) }
        }
        val size = matches.singleOrNull() ?: error("Console owned-member deletion is absent or ambiguous")
        require(lists.lists.size == 2 && lists.lists.all { list ->
            listOf(list.sentinel, list.next, list.previous, list.count).all { it in 0..size - 8 && it % 8 == 0L }
        }) { "Console list fields exceed its complete-object extent" }
        return size
    }
}
