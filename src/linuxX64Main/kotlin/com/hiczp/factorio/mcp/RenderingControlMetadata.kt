@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmRenderingControl

/** Native rendering predicate evidence; independent of pixel readback and screenshot hooks. */
internal data class RenderingControlMetadata(
    val window: FrontendWindow,
    val headless: Long,
    val settingsSize: Long,
    val guard: RenderingGuard,
    val evidence: ElfEvidence,
) {
    fun write(output: FmRenderingControl, bias: Long) {
        require(bias >= 0 && window.global in 1..Long.MAX_VALUE - bias && headless in 1..Long.MAX_VALUE - bias)
        output.global = (window.global + bias).toULong()
        output.headless = (headless + bias).toULong()
        output.globalSize = window.globalSize.toUInt()
        output.suppressed = guard.suppressed.toUInt()
    }

    fun verifyLoaded(image: ElfImage, process: ProcessHandle, bias: Long) =
        evidence.verify(image, bias, process::readMemory)

    companion object {
        fun resolve(image: ElfImage): RenderingControlMetadata {
            val pointers = mutableMapOf<Long, Long>()
            val scalars = mutableMapOf<Long, Long>()
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val window = FrontendWindow.resolve(image)
                    val headless = image.symbol("_ZN13GlobalContext12headlessModeE")
                    require(headless.type == 1 && headless.size == 1L)
                    val settingsSize = SysVObjectSize.resolve(image, "16GraphicsSettings")
                    val type = ItaniumType.resolve(image, "9SDLWindow")
                    scalars[type.addressPoint - 16] = 0
                    pointers[type.addressPoint - 8] = type.typeInfo
                    pointers[type.typeInfo + 8] = image.pointers.words(type.typeInfo + 8, 1).single().pointer()
                    val flush = ItaniumVtable.resolve(image, "_ZTV9SDLWindow")
                        .method(image, "_ZNK9SDLWindow13flushIfNeededEv")
                    pointers[flush.entryAddress] = flush.function.address
                    val swap = image.symbol("_ZN13GlobalContext17swapRenderBuffersEv")
                    EhFrames(image).function(swap)
                    val guard = RenderingGuard.analyze(
                        image.functionBytes(swap, 512), swap.address, window.global, headless.address,
                        window.globalSize, settingsSize, window.member, flush.slot,
                    )
                    Resolved(window, headless.address, settingsSize, guard)
                }
            }
            val value = resolved.first
            return RenderingControlMetadata(value.window, value.headless, value.settingsSize, value.guard,
                ElfEvidence(resolved.second, readonly, pointers, scalars))
        }

        private data class Resolved(
            val window: FrontendWindow, val headless: Long, val settingsSize: Long, val guard: RenderingGuard,
        )
    }
}
