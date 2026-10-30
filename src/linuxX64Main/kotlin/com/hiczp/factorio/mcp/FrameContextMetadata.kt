@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxFrameContextConfig

/** Selected-file graphics context evidence, checked against loaded code/data before native use. */
internal data class FrameContextMetadata(
    val size: GraphicsFrameSize,
    val swap: GraphicsSwapCall,
    val apiSlots: Map<String, Long>,
    val rendering: RenderingMetadata,
    val renderingControl: RenderingControlMetadata,
    val evidence: ElfEvidence,
) {
    fun verifyLoaded(image: ElfImage, process: ProcessHandle, bias: Long) {
        renderingControl.verifyLoaded(image, process, bias)
        evidence.verify(image, bias, process::readMemory)
    }

    fun writeTo(output: FmLinuxFrameContextConfig, bias: Long) {
        require(bias >= 0 && bias % 8 == 0L)
        fun address(value: Long): ULong {
            require(value > 0 && value <= Long.MAX_VALUE - bias)
            return (value + bias).toULong()
        }

        fun field(value: Long): UInt {
            require(value in 0..(64 * 1024 * 1024))
            return value.toUInt()
        }
        output.global = address(size.global)
        output.caller = address(swap.caller)
        output.pixels = address(size.pixels.implementation)
        output.graphicsTable = address(size.graphicsTable)
        output.windowTable = address(size.windowTable)
        output.globalSize = field(size.globalSize)
        output.globalWindow = field(size.globalWindow)
        output.graphicsSize = field(size.graphicsSize)
        output.graphicsWindow = field(size.graphicsWindow)
        output.windowSize = field(size.windowSize)
        output.windowGraphics = field(size.windowGraphics)
        output.nativeWindow = field(size.nativeWindow)
    }

    companion object {
        fun resolve(image: ElfImage, control: RenderingControlMetadata = RenderingControlMetadata.resolve(image)): FrameContextMetadata {
            val pointers = mutableMapOf<Long, Long>()
            val scalars = mutableMapOf<Long, Long>()
            val (resolved, readonly) =
                image.withReadonlyEvidence {
                    image.withFunctionEvidence {
                        val size = GraphicsFrameSize.resolve(image, control.window)
                        val swap = GraphicsSwapCall.resolve(image, size)
                        val rendering = RenderingMetadata.resolve(image, control)
                        val lookup =
                            SdlDynamicApi.resolve(image, "SDL_GL_GetProcAddress", listOf(7))
                        for (api in listOf(size.pixels, swap.api, lookup)) pointers[api.slot] =
                            api.implementation
                        val apiSlots = GlLoaderBinding.resolve(image, FrameApiBinding.names, lookup)
                        val words = image.pointers
                        for (type in listOf("23GraphicsInterfaceOpenGL", "9SDLWindow", "16GraphicsSettings",
                            "16SimpleConfigItemIbE", "17StorageConfigItemIbE", "14ConfigItemBase")) {
                            val identity = ItaniumType.resolve(image, type)
                            scalars[identity.addressPoint - 16] = 0
                            pointers[identity.addressPoint - 8] = identity.typeInfo
                            pointers[identity.typeInfo + 8] =
                                words.words(identity.typeInfo + 8, 1).single().pointer()
                        }
                        val graphics = ItaniumVtable.resolve(image, "_ZTV23GraphicsInterfaceOpenGL")
                        val window = ItaniumVtable.resolve(image, "_ZTV9SDLWindow")
                        val methods =
                            listOf(
                                graphics.method(
                                    image,
                                    "_ZN23GraphicsInterfaceOpenGL11swapBuffersEv",
                                ),
                                window.method(image, "_ZN9SDLWindow9getWindowEv"),
                                window.method(image, "_ZNK9SDLWindow4swapEv"),
                                window.method(image, "_ZNK9SDLWindow13flushIfNeededEv"),
                            )
                        // The poll slot only establishes the file-derived current-window
                        // association. Capture
                        // checks that object's concrete live type and does not invoke polling; our
                        // keyboard
                        // hook may legitimately own its slot. Keep actual graphics/getWindow
                        // entries checked.
                        for (method in methods) pointers[method.entryAddress] =
                            method.function.address
                        Resolved(size, swap, apiSlots, rendering)
                    }
                }
            return FrameContextMetadata(
                resolved.first.size,
                resolved.first.swap,
                resolved.first.apiSlots,
                resolved.first.rendering,
                control,
                ElfEvidence(resolved.second, readonly, pointers, scalars),
            )
        }

        private data class Resolved(
            val size: GraphicsFrameSize,
            val swap: GraphicsSwapCall,
            val apiSlots: Map<String, Long>,
            val rendering: RenderingMetadata,
        )
    }
}
