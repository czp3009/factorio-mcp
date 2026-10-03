package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Complete bounded original-receiver bytes copied into a selected native reference argument. */
internal object LocalObjectCopy {
    fun analyze(flow: X64ControlFlow, call: Long, receiver: Int, argument: Int, receiverSize: Long, extent: Int): Long {
        require(receiver in listOf(7, 6, 2, 1, 8, 9) && receiver != argument &&
                receiverSize in 8..64 * 1024 * 1024 && extent in 1..4096)
        SysVLocalArgument(flow).argument(call, argument, extent)
        val arguments = SysVArgumentFlow(flow)
        val copies = LocalFieldCopies(flow)
        val bytes = mutableMapOf<Long, Long>()
        val members = mutableSetOf<Long>()
        val prefix = mutableSetOf<Long>()
        var cursor = call
        while (prefix.size < 256) {
            val previous = flow.predecessors[cursor]?.singleOrNull() ?: break
            val instruction = flow.body.getValue(previous)
            if (previous >= cursor || instruction.operation in
                setOf(Operation.CALL, Operation.JCC, Operation.JMP, Operation.RET)) break
            prefix += previous
            cursor = previous
        }
        for (load in flow.instructions) {
            if (load.offset !in prefix || load.operation !in
                setOf(Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV) ||
                load.destination !is Register || load.source !is Memory) continue
            val source = arguments.source(load.offset) ?: continue
            if (source.reference.argument != receiver || source.reference.offset !in 8..receiverSize - source.width) continue
            if (load.offset >= call) continue
            val fields = copies.fromRead(load.offset, call, argument)
            if (fields.isEmpty()) continue
            // Every path to the consumer must pass this source; a partial branch copy supplies no evidence.
            val pending = ArrayDeque<Long>()
            val visited = mutableSetOf<Long>()
            pending.add(0)
            while (pending.isNotEmpty()) {
                val site = pending.removeFirst()
                if (site == load.offset || !visited.add(site)) continue
                require(site != call) { "Native object copy can bypass a source load" }
                pending.addAll(flow.successors.getValue(site))
            }
            for (field in fields) {
                require(field.width == source.width && field.offset in 0..extent - field.width)
                val member = source.reference.offset - field.offset
                require(member in 8..receiverSize - extent)
                members += member
                repeat(field.width) { byte ->
                    val previous = bytes.put(field.offset + byte, source.reference.offset + byte)
                    require(previous == null) { "Native object copy overlaps" }
                }
            }
        }
        require(bytes.size == extent && (0 until extent).all { it.toLong() in bytes }) {
            "Native reference does not receive a complete original object copy"
        }
        return members.singleOrNull() ?: error("Native object copy disagrees on its source member")
    }
}
