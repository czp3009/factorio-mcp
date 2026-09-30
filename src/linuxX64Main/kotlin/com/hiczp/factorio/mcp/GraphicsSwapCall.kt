package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Native SDL backend-call location. This alone does not authorize patching its device or calling its entries. */
internal data class GraphicsSwapCall(val device: Long, val member: Long, val caller: Long) {
    companion object {
        fun resolve(image: ElfImage): GraphicsSwapCall {
            val function = image.symbol("_ZN23GraphicsInterfaceOpenGL11swapBuffersEv")
            EhFrames(image).function(function)
            val device = image.symbol("_this")
            require(device.type == 1 && device.size == 8L)
            val bytes = image.functionBytes(function, 16384)
            // No values are carried across instructions outside the three-instruction candidate window.
            // A wide multiply's RAX/RDX effects cannot be skipped inside a candidate.
            return analyze(
                X64Instructions(bytes, allowUnsignedWideMultiply = true).all(4096),
                function.address, device.address
            )
        }

        fun analyze(instructions: List<X64Instructions.Instruction>, address: Long, device: Long): GraphicsSwapCall {
            require(address > 0 && device > 0 && device % 8 == 0L && instructions.size in 3..4096)
            val matches = instructions.windowed(3).mapNotNull { (load, window, call) ->
                val global = load.source as? Memory ?: return@mapNotNull null
                val entry = call.destination as? Memory ?: return@mapNotNull null
                if (load.operation != Operation.MOV || load.destination != Register(7, 8) ||
                    !global.relative || global.base != null || global.index != null || global.width != 8 ||
                    window.operation != Operation.MOV || window.destination != Register(6, 8) ||
                    window.source != Register(0, 8) || call.operation != Operation.CALL ||
                    entry.base != 7 || entry.index != null || entry.relative || entry.width != 8 ||
                    entry.displacement !in 0..65528 || entry.displacement % 8 != 0L ||
                    load.offset + load.size != window.offset || window.offset + window.size != call.offset
                )
                    return@mapNotNull null
                val next = address + load.offset + load.size
                require(next > address && next <= Long.MAX_VALUE - 65536)
                if (global.displacement < -next || global.displacement > Long.MAX_VALUE - next ||
                    next + global.displacement != device
                )
                    return@mapNotNull null
                GraphicsSwapCall(device, entry.displacement, address + call.offset + call.size)
            }
            return matches.singleOrNull() ?: error("SDL swap backend call is missing or ambiguous")
        }
    }
}
