package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Pointer
import com.hiczp.factorio.mcp.SysVReceiverFlow.Value
import com.hiczp.factorio.mcp.X64Instructions.*

/** Connects either indirect-call encoding to the selected primary-vtable entry and live RDI receiver. */
internal object SysVVirtualCall {
    fun isCandidate(call: Instruction, slot: Int): Boolean {
        require(slot in 0..8191)
        if (call.operation != Operation.CALL) return false
        return when (val target = call.destination) {
            is Memory -> target.width == 8 && !target.relative && target.index == null && target.base != null &&
                    target.displacement == slot * 8L
            is Register -> target.width == 8
            else -> false
        }
    }

    fun receiver(call: Instruction, values: List<Value>, slot: Int): Value? {
        require(call.operation == Operation.CALL && values.size == 32 && slot in 0..8191)
        if (!isCandidate(call, slot)) return null
        val table = when (val target = call.destination) {
            is Memory -> values[checkNotNull(target.base)] as? Pointer

            is Register -> {
                val function = values[target.number] as? Pointer ?: return null
                if (function.offset != slot * 8L) return null
                function.base as? Pointer
            }

            else -> null
        } ?: return null
        return table.base.takeIf { table.offset == 0L && it == values[7] }
    }
}
