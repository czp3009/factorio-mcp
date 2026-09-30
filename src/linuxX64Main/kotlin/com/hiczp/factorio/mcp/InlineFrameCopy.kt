package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Complete external-object copies in a named inline entry's straight load/store prefix. Not a lifetime proof. */
internal object InlineFrameCopy {
    data class Proof(val frame: Long, val extent: Int, val sourceRegister: Int)
    private data class ByteSource(val register: Int, val offset: Long)

    fun resolve(flow: X64ControlFlow, ranges: List<DwarfRanges.Range>, extent: Int): Proof {
        require(extent in 1..4096 && ranges.isNotEmpty())
        val first = ranges.minOf { it.start }
        require(first in flow.reachable)
        val frame = SysVLocalArgument(flow)
        val registers = MutableList<List<ByteSource?>>(32) { List(16) { null } }
        // An unmodified entry register is an address identity, not permission to read its pointee.
        val addresses = MutableList<Int?>(32) { it.takeIf { frame.registers(first)[it] == null && it < 16 } }
        val bytes = mutableMapOf<Long, ByteSource>()
        var cursor = first
        var steps = 0
        while (cursor in flow.body && ++steps <= 256) {
            val instruction = flow.body.getValue(cursor)
            if (!ranges.any { it.start <= cursor && cursor + instruction.size <= it.end }) break
            if (instruction.operation in listOf(Operation.CALL, Operation.JMP, Operation.JCC, Operation.RET)) break
            val target = instruction.destination
            val source = instruction.source
            if (instruction.operation in listOf(Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV)) {
                val value = when (source) {
                    is Register -> registers[source.number].take(source.width)
                    is Memory -> {
                        val identity = source.base?.let { addresses[it] }
                        if (identity != null && !source.relative && source.index == null &&
                            source.displacement >= 0 && source.displacement <= extent - source.width
                        ) {
                            List(source.width) { ByteSource(identity, source.displacement + it) }
                        } else List(source.width) { null }
                    }

                    else -> emptyList()
                }
                when (target) {
                    is Register -> {
                        val identity = (source as? Register)?.takeIf { it.width == 8 && target.width == 8 }
                            ?.let { addresses[it.number] }
                        registers[target.number] =
                            List(16) { index -> value.getOrNull(index).takeIf { index < target.width } }
                        addresses[target.number] = identity
                    }

                    is Memory -> {
                        val location = frame.address(cursor, target) ?: error("Inline copy writes outside its frame")
                        require(location >= checkNotNull(frame.registers(cursor)[4]) && location <= -target.width)
                        repeat(target.width) { byte ->
                            bytes.remove(location + byte)
                            value.getOrNull(byte)?.let { bytes[location + byte] = it }
                        }
                    }

                    else -> error("Unsupported inline copy destination")
                }
            } else if (instruction.operation !in listOf(
                    Operation.NOP,
                    Operation.ENDBR,
                    Operation.CMP,
                    Operation.TEST
                )
            ) {
                break
            }
            val next = cursor + instruction.size
            if (flow.successors.getValue(cursor) != listOf(next)) break
            cursor = next
        }
        require(steps <= 256) { "Inline copy prefix exceeds bound" }
        val candidates = bytes.filterValues { it.offset == 0L }.mapNotNull { (start, source) ->
            if ((0 until extent).all { bytes[start + it] == ByteSource(source.register, it.toLong()) }) {
                Proof(start, extent, source.register)
            } else null
        }
        return candidates.singleOrNull() ?: error("Inline entry has no unique complete local object copy")
    }
}
