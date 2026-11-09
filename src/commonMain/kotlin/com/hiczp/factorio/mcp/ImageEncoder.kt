package com.hiczp.factorio.mcp

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

internal enum class ImageFormat(val argument: String, val mimeType: String) {
    JPG("jpg", "image/jpeg"),
    PNG("png", "image/png");

    companion object {
        fun parse(value: String): ImageFormat =
            entries.singleOrNull { it.argument == value }
                ?: throw IllegalArgumentException("format must be jpg or png")
    }
}

/** Owned, top-down RGB pixels copied from the game's rendered frame. */
internal data class RgbImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(pixels.size == ImageEncoder.rgbSize(width, height)) { "Invalid RGB frame size" }
    }
}

internal data class EncodedImage(
    val width: Int,
    val height: Int,
    val format: ImageFormat,
    val quality: Int?,
    val bytes: ByteArray,
)

internal data class ScreenshotResult(val snapshot: GameSnapshot, val image: EncodedImage)

/** Portable resizing and platform encoding outside the game's rendering callback. */
internal object ImageEncoder {
    const val DEFAULT_JPEG_QUALITY = 85

    fun encodedSize(size: ULong): Int {
        check(size in 1uL..Int.MAX_VALUE.toULong()) { "Encoded image exceeds byte-array representation" }
        return size.toInt()
    }

    fun validateQuality(format: ImageFormat, quality: Int?) {
        require(quality == null || quality in 1..100) { "quality must be an integer from 1 to 100" }
        require(quality == null || format == ImageFormat.JPG) { "quality applies only to jpg" }
    }

    fun rgbSize(width: Int, height: Int): Int {
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216) {
            "Frame dimensions exceed capture bounds"
        }
        return width * height * 3
    }

    fun encode(image: RgbImage, format: ImageFormat, quality: Int? = null): EncodedImage {
        validateQuality(format, quality)
        val jpegQuality = quality ?: DEFAULT_JPEG_QUALITY
        return EncodedImage(
            image.width,
            image.height,
            format,
            jpegQuality.takeIf { format == ImageFormat.JPG },
            PlatformImageEncoder.encode(image, format, jpegQuality).also {
                check(it.isNotEmpty()) { "Encoded image is empty" }
            },
        )
    }

    suspend fun resize(image: RgbImage, maxHeight: Int?): RgbImage {
        require(maxHeight == null || maxHeight > 0) { "max_height must be positive" }
        currentCoroutineContext().ensureActive()
        if (maxHeight == null || image.height <= maxHeight) return image
        val height = maxHeight
        val width = max(1, (image.width.toDouble() * height / image.height).roundToInt())
        val output = ByteArray(rgbSize(width, height))
        val scaleX = image.width.toDouble() / width
        val scaleY = image.height.toDouble() / height
        // Area averaging retains thin features and avoids nearest-neighbor aliasing when shrinking.
        for (y in 0 until height) {
            yield()
            val top = y * scaleY
            val bottom = min(image.height.toDouble(), (y + 1) * scaleY)
            for (x in 0 until width) {
                val left = x * scaleX
                val right = min(image.width.toDouble(), (x + 1) * scaleX)
                var red = 0.0
                var green = 0.0
                var blue = 0.0
                for (sy in top.toInt() until min(image.height, ceil(bottom).toInt())) {
                    val vertical = min(bottom, sy + 1.0) - max(top, sy.toDouble())
                    for (sx in left.toInt() until min(image.width, ceil(right).toInt())) {
                        val weight = vertical * (min(right, sx + 1.0) - max(left, sx.toDouble()))
                        val source = (sy * image.width + sx) * 3
                        red += (image.pixels[source].toInt() and 255) * weight
                        green += (image.pixels[source + 1].toInt() and 255) * weight
                        blue += (image.pixels[source + 2].toInt() and 255) * weight
                    }
                }
                val area = (right - left) * (bottom - top)
                val destination = (y * width + x) * 3
                output[destination] = (red / area).roundToInt().coerceIn(0, 255).toByte()
                output[destination + 1] = (green / area).roundToInt().coerceIn(0, 255).toByte()
                output[destination + 2] = (blue / area).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return RgbImage(width, height, output)
    }
}

internal expect object PlatformImageEncoder {
    fun encode(image: RgbImage, format: ImageFormat, quality: Int): ByteArray
}
