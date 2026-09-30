@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxFrameHookSite
import platform.posix.PROT_READ
import platform.posix.PROT_WRITE

/** A current callback candidate; native installation must recheck it on the frontend main thread. */
internal data class FrameHookBinding(
    val global: Long,
    val device: Long,
    val original: Long,
    val size: Long,
    val member: Long,
    val protection: Int,
) {
    fun writeTo(output: FmLinuxFrameHookSite) {
        output.deviceGlobal = global.toULong()
        output.device = device.toULong()
        output.original = original.toULong()
        output.deviceSize = size.toUInt()
        output.member = member.toUInt()
        output.protection = protection.toUInt()
    }

    companion object {
        fun bind(
            swap: GraphicsSwapCall, backends: List<SdlDeviceAllocation>, bias: Long, pageSize: Long,
            mappings: List<ProcMapping>, read: (Long, Int) -> ByteArray
        ): FrameHookBinding {
            require(bias >= 0 && bias % 8 == 0L && pageSize >= 8 && pageSize and (pageSize - 1) == 0L)
            require(backends.isNotEmpty() && backends.size <= 16 && swap.member >= 0 && swap.member % 8 == 0L)
            fun relocated(value: Long): Long {
                require(value > 0 && value <= Long.MAX_VALUE - bias)
                return value + bias
            }

            fun word(address: Long): Long {
                require(address > 0 && address % 8 == 0L && address <= Long.MAX_VALUE - 8)
                require(mappings.any { it.readable && address >= it.start && address <= it.end - 8 })
                val bytes = read(address, 8)
                require(bytes.size == 8)
                return BinaryView(bytes).unsigned(0, 8)
            }
            for (backend in backends) require(
                backend.member == swap.member && backend.size in 8..65536 &&
                        backend.member <= backend.size - 8
            )
            val global = relocated(swap.device)
            val device = word(global)
            require(device > 0 && device % 8 == 0L && device <= Long.MAX_VALUE - 65536)
            val entry = device + swap.member
            val original = word(entry)
            val backend = backends.singleOrNull { relocated(it.backend) == original }
                ?: error("Current SDL swap callback is not a verified backend")
            require(mappings.any { it.executable && original >= it.start && original < it.end })
            require(mappings.any {
                it.readable && !it.executable && it.permissions[3] == 'p' &&
                        device >= it.start && device <= it.end - backend.size
            }) {
                "SDL device does not fit a private readable data mapping"
            }
            val page = entry and -pageSize
            require(page <= Long.MAX_VALUE - pageSize)
            val region = mappings.singleOrNull {
                it.readable && !it.executable && it.permissions[3] == 'p' &&
                        page >= it.start && page + pageSize <= it.end
            }
                ?: error("SDL swap entry page has unsupported protection")
            require(word(global) == device && word(entry) == original) { "SDL swap binding changed while observed" }
            return FrameHookBinding(
                global, device, original, backend.size, swap.member,
                PROT_READ or if (region.writable) PROT_WRITE else 0
            )
        }
    }
}
