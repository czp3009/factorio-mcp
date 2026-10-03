@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxViewportLayout

/** Cached viewport observations with verified native integer/packed-value entries and loaded-image evidence. */
internal data class ViewportLayout(
    val display: ViewportDisplayLayout,
    val coordinates: ViewportCoordinates,
    val framebuffer: ItaniumType,
    val framebufferSize: Long,
    val backing: FramebufferDimension,
    val evidence: ElfEvidence,
) {
    fun verifyLoaded(image: ElfImage, process: ProcessHandle, bias: Long) =
        evidence.verify(image, bias, process::readMemory)

    fun writeTo(output: FmLinuxViewportLayout, bias: Long) {
        fun address(value: Long): ULong {
            require(value > 0 && bias >= 0 && value <= Long.MAX_VALUE - bias)
            return (value + bias).toULong()
        }
        output.framebufferVtable = address(framebuffer.addressPoint)
        output.framebufferTypeInfo = address(framebuffer.typeInfo)
        output.width = address(display.width.function.address)
        output.height = address(display.height.function.address)
        output.mapPosition = address(coordinates.mapPosition.address)
        output.renderer = display.renderer.toUInt()
        output.rendererSize = display.rendererSize.toUInt()
        output.framebufferReference = display.framebufferReference.toUInt()
        output.framebufferSize = framebufferSize.toUInt()
        output.primary = backing.primary.toUInt()
        output.fallback = backing.fallback.toUInt()
        output.widthSlot = display.width.slot.toUInt()
        output.heightSlot = display.height.slot.toUInt()
        output.surface = coordinates.surface.toUInt()
        output.position = coordinates.position.toUInt()
        output.fractionBits = coordinates.fractionBits.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage, viewSize: Long): ViewportLayout {
            val pointers = mutableMapOf<Long, Long>()
            val scalars = mutableMapOf<Long, Long>()
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val display = ViewportDisplayLayout.resolve(image, viewSize)
                    val coordinates = ViewportCoordinates.resolve(image, viewSize)
                    val framebuffer = ItaniumType.resolve(image, "17FramebufferOpenGL")
                    val table = ItaniumVtable.resolve(image, "_ZTV17FramebufferOpenGL")
                    require(table.function(image, display.width.slot) == display.width.function &&
                            table.function(image, display.height.slot) == display.height.function)
                    val size = SysVObjectSize.resolve(image, "17FramebufferOpenGL")
                    val width = FramebufferDimension.analyze(X64ControlFlow.resolve(image, display.width.function), size)
                    val height = FramebufferDimension.analyze(X64ControlFlow.resolve(image, display.height.function), size)
                    require(width == height) { "Framebuffer dimensions select different backing objects" }
                    require(ItaniumClass.resolve(image, "17FramebufferOpenGL").directBase(
                        ItaniumClass.resolve(image, "11Framebuffer"), size, maxOf(width.primary, width.fallback) + 8
                    ) == 0L) { "Dimension getters do not receive the primary Framebuffer base" }
                    scalars[framebuffer.addressPoint - 16] = 0
                    pointers[framebuffer.addressPoint - 8] = framebuffer.typeInfo
                    pointers[framebuffer.typeInfo + 8] = image.pointers.words(framebuffer.typeInfo + 8, 1).single().pointer()
                    for (method in listOf(display.width, display.height)) {
                        pointers[framebuffer.addressPoint + method.slot * 8L] = method.function.address
                    }
                    ViewportLayout(display, coordinates, framebuffer, size, width,
                        ElfEvidence(emptyList(), emptyList(), emptyMap(), emptyMap()))
                }
            }
            return resolved.first.copy(evidence = ElfEvidence(resolved.second, readonly, pointers, scalars))
        }
    }
}
