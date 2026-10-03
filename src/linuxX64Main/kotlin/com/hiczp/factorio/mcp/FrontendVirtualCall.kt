package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * All possible decoded near calls ending at an observed native return address. The stopped thread
 * independently supplies registers holding its verified GUI table. No instruction start is guessed:
 * every possible interpretation must dispatch the same slot through that table.
 */
internal object FrontendVirtualCall {
    fun verify(bytes: BinaryView, slot: Int, tableRegisters: Set<Int>) {
        require(bytes.size in 1..15 && slot >= 0 && tableRegisters.all { it in 0..15 })
        val decoder = X64Instructions(bytes)
        val calls =
            (0 until bytes.size).mapNotNull { start ->
                runCatching { decoder.decode(start) }
                    .getOrNull()
                    ?.takeIf { it.operation == Operation.CALL && it.offset + it.size == bytes.size }
            }
        require(calls.isNotEmpty()) { "Frontend return has no decoded near call" }
        require(
            calls.all { call ->
                val memory = call.destination as? Memory
                memory != null &&
                    memory.width == 8 &&
                    !memory.relative &&
                    memory.index == null &&
                    memory.displacement == slot * 8L &&
                    memory.base in tableRegisters
            }
        ) {
            "Frontend call is ambiguous or does not use the verified GUI virtual slot"
        }
    }
}
