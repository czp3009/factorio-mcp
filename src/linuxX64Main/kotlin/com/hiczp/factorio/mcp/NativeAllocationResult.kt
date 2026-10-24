package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Exact allocator-result provenance, independently of the meaning of the fields written through it.
 */
internal object NativeAllocationResult {
    fun find(flow: X64ControlFlow, allocator: Long, size: Long): Instruction {
        require(size in 8..16 * 1024 * 1024)
        val constants = RegisterConstant(flow)
        return flow.instructions
            .filter { call ->
                call.operation == Operation.CALL &&
                    call.destination == Immediate(allocator) &&
                    call.offset in flow.reachable &&
                    runCatching { constants.value(call.offset, Register(7, 8)) == size }
                        .getOrDefault(false)
            }
            .singleOrNull() ?: error("Object has no unique bounded allocation")
    }

    fun verify(flow: X64ControlFlow, allocation: Instruction, store: Instruction, base: Int) {
        require(
            allocation.operation == Operation.CALL &&
                flow.body[allocation.offset] == allocation &&
                flow.body[store.offset] == store &&
                flow.dominates(allocation.offset, store.offset)
        ) {
            "Object initialization has an entry bypassing allocation"
        }
        require(
            ReturnedPointerOrigins(flow).register(store.offset, base) ==
                ReturnedPointerOrigins.Value(allocation.offset)
        ) {
            "Object store does not use this allocation's original result"
        }
    }
}
