package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import com.hiczp.factorio.mcp.X64Instructions.*

/** Poll the original Window argument through its primary table, borrowing a fresh bounded local Event. */
internal object EventPollSite {
    data class Proof(
        val call: Long,
        val returned: Long,
        val eventFromEntry: Long,
        val callerReturnFromStack: Long,
        val defaults: Map<Long, Int>,
    )

    fun analyze(
        bytes: BinaryView, address: Long, slot: Int, header: EventHeader,
        flow: X64ControlFlow = X64ControlFlow(X64Instructions(bytes).all(2048)),
    ): Proof {
        require(bytes.size in 1..8192 && slot in 0..8191 && header.extent in 16..4096)
        require(flow.instructions == X64Instructions(bytes).all(2048))
        // nextEvent returns its aggregate through RDI; RSI is the original Window reference.
        // Only that reference's primary vptr is used to identify the dispatch, never an inferred member.
        val calls = flow.instructions.filter { instruction ->
            if (instruction.offset !in flow.reachable || !SysVVirtualCall.isCandidate(instruction, slot)) return@filter false
            // Point provenance needs only the entry through this call. The complete CFG independently
            // verifies later switches and excludes reentry; their payload-copy paths do not lend this Event.
            val values = SysVReceiverFlow(bytes.slice(0, instruction.offset + instruction.size),
                address, 8, receiverRegister = 6)
            val registers = values.borrowedCallValues(instruction.offset)
            SysVVirtualCall.receiver(instruction, registers, slot) == Receiver()
        }
        val call = calls.singleOrNull() ?: error("Event pump lacks a unique poll of its original Window")
        val returned = call.offset + call.size
        require(returned in flow.body && flow.successors.getValue(call.offset) == listOf(returned))
        val pending = ArrayDeque(flow.successors.getValue(call.offset))
        val visited = mutableSetOf<Long>()
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            require(next != call.offset) { "Event pump reuses its admitted local Event in a polling loop" }
            if (visited.add(next)) pending.addAll(flow.successors.getValue(next))
        }
        val prefix = flow.reaching(call.offset)
        val locals = SysVLocalArgument(prefix)
        val eventFromEntry = locals.argument(call.offset, 6, header.extent)
        val stack = checkNotNull(locals.registers(call.offset)[4])
        require(stack in -16384..0)
        val defaults = LocalDefaults.before(prefix, call.offset, header.extent,
            listOf(InlineArgumentFields.Field(header.type, 4), InlineArgumentFields.Field(header.time, 8)))
        require((header.time until header.time + 8).all { defaults[it] == 0 }) {
            "Fresh event header does not have the native empty timestamp"
        }
        return Proof(call.offset, returned, eventFromEntry, -stack, defaults)
    }
}
