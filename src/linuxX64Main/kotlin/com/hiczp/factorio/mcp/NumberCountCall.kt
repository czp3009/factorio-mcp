package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Connects an abstract virtual entry to its original receiver and scalar double return use. */
internal object NumberCountCall {
    fun analyze(bytes: BinaryView, address: Long, slot: Int): Long {
        require(bytes.size in 1..8192 && slot in 0..255)
        val provenance = SysVReceiverFlow(bytes, address, receiverSize = 8)
        val instructions = provenance.instructions
        val candidates = instructions.filter { instruction ->
            instruction.offset in provenance.reachable && SysVVirtualCall.isCandidate(instruction, slot)
        }.filter { call ->
            // Only observations before the next control transfer count. Copies of XMM0 may move
            // independently of unrelated integer setup, but overwriting it destroys the evidence.
            val returned = mutableSetOf(16)
            var used = false
            for (instruction in instructions.dropWhile { it.offset <= call.offset }) {
                if (instruction.operation in setOf(Operation.CALL, Operation.RET, Operation.JMP, Operation.JCC)) break
                val destination = instruction.destination as? Register
                val source = instruction.source as? Register
                if (instruction.operation == Operation.VECTOR_MOV && source?.number in returned && source?.width == 16 &&
                    destination != null && destination.number in 16..31 && destination.width == 16) {
                    returned += destination.number
                } else if (instruction.operation == Operation.SCALAR_MOV && source?.number in returned && source?.width == 8) {
                    if (destination != null && destination.width == 8) returned += destination.number
                    else if (instruction.destination is Memory && instruction.destination.width == 8) used = true
                } else {
                    if (instruction.operation == Operation.SCALAR_COMPARE &&
                        listOfNotNull(destination, source).any { it.number in returned && it.width == 8 }) used = true
                    if (destination != null && instruction.operation !in setOf(Operation.CMP, Operation.TEST,
                            Operation.SCALAR_COMPARE, Operation.NOP, Operation.ENDBR)) returned -= destination.number
                }
            }
            used
        }.filter { call ->
            // Establish the return contract before expanding receiver paths through unrelated callbacks.
            val values = provenance.borrowedCallValues(call.offset)
            SysVVirtualCall.receiver(call, values, slot) == SysVReceiverFlow.Receiver()
        }
        return candidates.singleOrNull()?.offset ?: error("Number count has no unique receiver/double-return call")
    }
}
