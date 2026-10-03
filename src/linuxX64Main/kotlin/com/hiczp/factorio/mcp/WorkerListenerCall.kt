package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Pointer
import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import com.hiczp.factorio.mcp.X64Instructions.*

/** The native worker's completion callback, with the unchanged worker passed as its second argument. */
internal object WorkerListenerCall {
    fun analyze(bytes: BinaryView, address: Long, workerSize: Long, listener: Long, slot: Int): Long {
        require(bytes.size in 1..8192 && workerSize in 8..65536 && listener in 0..workerSize - 8 &&
                listener % 8 == 0L && slot in 0..8191)
        val flow = X64ControlFlow(X64Instructions(bytes).all(2048))
        val provenance = SysVReceiverFlow(bytes, address, workerSize)
        val calls = flow.instructions.filter { instruction ->
            if (instruction.offset !in flow.reachable || !SysVVirtualCall.isCandidate(instruction, slot)) return@filter false
            // Observe the game's unchanged call. Earlier calls may borrow their own locals; discard those
            // saved values rather than treating them as a new adapter ABI or retaining an escaped frame alias.
            val registers = provenance.borrowedCallValues(instruction.offset)
            val receiver = SysVVirtualCall.receiver(instruction, registers, slot) as? Pointer ?: return@filter false
            receiver.base == Receiver() && receiver.offset == listener && registers[6] == Receiver()
        }
        val call = calls.singleOrNull() ?: error("Worker lacks a unique completion callback with its original receiver")
        val returned = call.offset + call.size
        require(returned in flow.body && flow.successors.getValue(call.offset) == listOf(returned))
        return returned
    }
}
