@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxFrameHookSite
import platform.posix.PROT_READ
import platform.posix.PROT_WRITE

/**
 * A current callback candidate; native installation must recheck it on the frontend main thread.
 */
internal data class FrameHookBinding(val slot: Long, val original: Long, val protection: Int) {
    fun writeTo(output: FmLinuxFrameHookSite) {
        output.slot = slot.toULong()
        output.original = original.toULong()
        output.protection = protection.toUInt()
    }

    companion object {
        fun bind(
            swap: GraphicsSwapCall,
            bias: Long,
            pageSize: Long,
            mappings: List<ProcMapping>,
            read: (Long, Int) -> ByteArray,
        ): FrameHookBinding {
            require(
                bias >= 0 && bias % 8 == 0L && pageSize >= 8 && pageSize and (pageSize - 1) == 0L
            )
            fun relocated(value: Long): Long {
                require(value > 0 && value <= Long.MAX_VALUE - bias)
                return value + bias
            }

            fun word(address: Long): Long {
                require(address > 0 && address % 8 == 0L && address <= Long.MAX_VALUE - 8)
                require(
                    mappings.any { it.readable && address >= it.start && address <= it.end - 8 }
                )
                val bytes = read(address, 8)
                require(bytes.size == 8)
                return BinaryView(bytes).unsigned(0, 8)
            }
            val entry = relocated(swap.api.slot)
            val original = word(entry)
            require(original == relocated(swap.api.implementation)) {
                "SDL presentation API is not initialized to its verified implementation"
            }
            require(mappings.any { it.executable && original >= it.start && original < it.end })
            val page = entry and -pageSize
            require(page <= Long.MAX_VALUE - pageSize)
            val region =
                mappings.singleOrNull {
                    it.readable &&
                        !it.executable &&
                        it.permissions[3] == 'p' &&
                        page >= it.start &&
                        page + pageSize <= it.end
                } ?: error("SDL swap entry page has unsupported protection")
            require(word(entry) == original) { "SDL swap binding changed while observed" }
            return FrameHookBinding(
                entry,
                original,
                PROT_READ or if (region.writable) PROT_WRITE else 0,
            )
        }
    }
}
