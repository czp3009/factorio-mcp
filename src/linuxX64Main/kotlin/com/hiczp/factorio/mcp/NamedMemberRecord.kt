package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Original typed explorer calls associate a private constant member name with its receiver-relative storage. */
internal object NamedMemberRecord {
    const val CONSUMER = "_ZN6Values12recordManualEPKcRKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEEmmR24PrototypeExplorerWidgets"

    data class Record(val name: String, val offset: Long, val width: Long, val alignment: Long, val call: Long)

    fun analyze(
        flow: X64ControlFlow, consume: Long, string: NativeStringLayout, objectSize: Long,
        names: Set<String>,
    ): Map<String, Record> {
        require(objectSize in 1..(16 * 1024 * 1024) && names.isNotEmpty() && names.size <= 64)
        val arguments = SysVArgumentFlow(flow)
        val literals = LocalStringArgument(flow, string)
        val records = mutableMapOf<String, MutableSet<Record>>()
        val failures = mutableListOf<String>()
        for (instruction in flow.instructions) {
            if (instruction.offset !in flow.reachable || instruction.operation != Operation.CALL ||
                instruction.destination != Immediate(consume)) continue
            try {
                val name = literals.text(instruction.offset, 6)
                if (name !in names) continue
                val member = arguments.register(instruction.offset, 7)
                    ?: error("Named member loses its original receiver")
                require(member.argument == 7 &&
                        arguments.register(instruction.offset, 8) == SysVArgumentFlow.Reference(6)) {
                    "Named member and explorer do not come from the original arguments"
                }
                val width = literals.constant(instruction.offset, 2)
                val alignment = literals.constant(instruction.offset, 1)
                require(width in 1..objectSize && alignment in listOf(1L, 2L, 4L, 8L, 16L) &&
                        width % alignment == 0L && member.offset in 0..objectSize - width &&
                        member.offset % alignment == 0L) { "Named member exceeds its independent object extent" }
                records.getOrPut(name) { mutableSetOf() } += Record(name, member.offset, width, alignment, instruction.offset)
            } catch (failure: IllegalArgumentException) {
                failures += failure.message.orEmpty()
            } catch (failure: IllegalStateException) {
                failures += failure.message.orEmpty()
            }
        }
        return names.associateWith { name ->
            records[name]?.singleOrNull() ?: error("No unique named member record for $name: ${failures.take(4).joinToString()}")
        }
    }
}
