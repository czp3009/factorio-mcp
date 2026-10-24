package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation

/**
 * Identifies an exact return address in a decoded entry prefix, without claiming argument or path
 * provenance.
 */
internal object DirectCallSite {
    fun firstReturn(bytes: BinaryView, target: Long): Long {
        require(bytes.size in 1..4096)
        val decoder = X64Instructions(bytes)
        var position = 0L
        repeat(512) {
            require(position < bytes.size) {
                "Selected direct call is absent from the bounded prefix"
            }
            val instruction = decoder.decode(position)
            position += instruction.size
            require(instruction.operation !in listOf(Operation.RET, Operation.TRAP)) {
                "Selected direct call follows a terminal entry instruction"
            }
            if (
                instruction.operation == Operation.CALL &&
                    instruction.destination == Immediate(target)
            )
                return position
        }
        error("Selected direct call exceeds the instruction bound")
    }
}
