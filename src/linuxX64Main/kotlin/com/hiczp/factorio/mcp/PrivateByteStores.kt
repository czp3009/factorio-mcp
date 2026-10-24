package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Last private-frame writers at one terminal borrow. All incoming paths must agree on each byte's
 * writer.
 */
internal class PrivateByteStores(private val flow: X64ControlFlow, private val call: Long) {
    data class Byte(val store: Instruction, val index: Int)

    private val frame = SysVLocalArgument(flow)
    private val writes =
        flow.instructions
            .mapNotNull { instruction ->
                val target = instruction.destination as? Memory ?: return@mapNotNull null
                if (
                    instruction.offset !in flow.reachable ||
                        instruction.operation in
                            setOf(
                                Operation.CMP,
                                Operation.TEST,
                                Operation.SCALAR_COMPARE,
                                Operation.LEA,
                            )
                )
                    return@mapNotNull null
                val offset = frame.address(instruction.offset, target) ?: return@mapNotNull null
                require(
                    offset >= checkNotNull(frame.registers(instruction.offset)[4]) &&
                        offset <= -target.width
                )
                instruction.offset to (offset until offset + target.width)
            }
            .toMap()

    init {
        require(
            flow.body[call]?.operation == Operation.CALL && flow.successors.getValue(call).isEmpty()
        )
        ConstructorValues(flow.withoutTerminal(call), emptyMap())
    }

    fun bytes(argument: Int, extent: Int, offsets: Set<Long>): Map<Long, Byte> {
        require(extent in 1..256 && offsets.all { it in 0 until extent.toLong() })
        val start = frame.argument(call, argument, extent)
        return offsets.associateWith { byte ->
            val location = start + byte
            val pending = ArrayDeque(flow.predecessors[call].orEmpty())
            val seen = mutableSetOf<Long>()
            val found = mutableSetOf<Long>()
            while (pending.isNotEmpty()) {
                val site = pending.removeFirst()
                if (!seen.add(site)) continue
                require(seen.size <= 4096)
                val range = writes[site]
                if (range != null && location in range) found += site
                else {
                    require(site != 0L) {
                        "Private payload byte is uninitialized on an incoming path"
                    }
                    pending.addAll(flow.predecessors[site].orEmpty())
                }
            }
            val site = found.singleOrNull() ?: error("Private payload byte has ambiguous writers")
            Byte(flow.body.getValue(site), (location - writes.getValue(site).first).toInt())
        }
    }
}
