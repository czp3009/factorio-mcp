@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.jpeg.*
import com.hiczp.factorio.mcp.png.*
import kotlinx.cinterop.*
import platform.posix.memset

internal actual object PlatformImageEncoder {
    actual fun encode(image: RgbImage, format: ImageFormat, quality: Int): ByteArray =
        when (format) {
            ImageFormat.PNG -> encodePng(image)
            ImageFormat.JPG -> encodeJpeg(image, quality)
        }

    private fun encodeJpeg(image: RgbImage, quality: Int): ByteArray = memScoped {
        val encoder = checkNotNull(tjInitCompress()) { "Cannot initialize JPEG encoder" }
        val buffer = alloc<CPointerVar<UByteVar>>()
        buffer.value = null
        val size = alloc<ULongVar>()
        size.value = 0uL
        try {
            image.pixels.usePinned { input ->
                check(
                    tjCompress2(
                        encoder,
                        input.addressOf(0).reinterpret(),
                        image.width,
                        image.width * 3,
                        image.height,
                        TJPF_RGB.toInt(),
                        buffer.ptr,
                        size.ptr,
                        TJSAMP_420.toInt(),
                        quality,
                        0,
                    ) == 0
                ) { "JPEG encoding failed: ${tjGetErrorStr2(encoder)?.toKString()}" }
            }
            check(size.value in 1uL..ImageEncoder.MAX_BYTES.toULong()) { "JPEG exceeds output bound" }
            checkNotNull(buffer.value).readBytes(size.value.toInt())
        } finally {
            tjFree(buffer.value)
            tjDestroy(encoder)
        }
    }

    private fun encodePng(source: RgbImage): ByteArray = memScoped {
        val (width, height, rgb) = source
        val image = alloc<png_image>()
        memset(image.ptr, 0, sizeOf<png_image>().toULong())
        image.version = PNG_IMAGE_VERSION.toUInt()
        image.width = width.toUInt()
        image.height = height.toUInt()
        image.format = PNG_FORMAT_RGB.toUInt()
        val size = alloc<png_alloc_size_tVar>()
        // A bounded destination avoids encoding the image twice just to query its size.
        val output = ByteArray(ImageEncoder.MAX_BYTES)
        size.value = output.size.toULong()
        try {
            val success =
                rgb.usePinned { input ->
                    output.usePinned { destination ->
                        png_image_write_to_memory(
                            image.ptr,
                            destination.addressOf(0),
                            size.ptr,
                            0,
                            input.addressOf(0),
                            width * 3,
                            null,
                        )
                    }
                }
            check(success != 0) {
                "PNG encoding failed or exceeded the image limit: ${image.message.toKString()}"
            }
            check(size.value in 1uL..output.size.toULong()) { "Invalid PNG size" }
            output.copyOf(size.value.toInt())
        } finally {
            png_image_free(image.ptr)
        }
    }
}
