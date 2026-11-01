@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.png.*
import com.hiczp.factorio.mcp.jpeg.*
import kotlin.test.assertEquals
import kotlinx.cinterop.*
import platform.posix.memset

private fun decodeJpeg(bytes: ByteArray): RgbImage = memScoped {
    val decoder = checkNotNull(tjInitDecompress())
    try {
        bytes.usePinned { input ->
            val width = alloc<IntVar>()
            val height = alloc<IntVar>()
            val subsampling = alloc<IntVar>()
            assertEquals(0, tjDecompressHeader2(decoder, input.addressOf(0).reinterpret(),
                bytes.size.toULong(), width.ptr, height.ptr, subsampling.ptr))
            val output = ByteArray(ImageEncoder.rgbSize(width.value, height.value))
            output.usePinned {
                assertEquals(0, tjDecompress2(decoder, input.addressOf(0).reinterpret(),
                    bytes.size.toULong(), it.addressOf(0).reinterpret(), width.value,
                    width.value * 3, height.value, TJPF_RGB, 0))
            }
            RgbImage(width.value, height.value, output)
        }
    } finally {
        tjDestroy(decoder)
    }
}

internal actual fun decodeImage(bytes: ByteArray): RgbImage = if (bytes[0] == 0xff.toByte()) decodeJpeg(bytes) else memScoped {
    val image = alloc<png_image>()
    memset(image.ptr, 0, sizeOf<png_image>().toULong())
    image.version = PNG_IMAGE_VERSION.toUInt()
    try {
        bytes.usePinned {
            assertEquals(
                1,
                png_image_begin_read_from_memory(image.ptr, it.addressOf(0), bytes.size.toULong()),
            )
        }
        image.format = PNG_FORMAT_RGB.toUInt()
        val rgb = ByteArray(image.width.toInt() * image.height.toInt() * 3)
        rgb.usePinned {
            assertEquals(1, png_image_finish_read(image.ptr, null, it.addressOf(0), 0, null))
        }
        RgbImage(image.width.toInt(), image.height.toInt(), rgb)
    } finally {
        png_image_free(image.ptr)
    }
}
