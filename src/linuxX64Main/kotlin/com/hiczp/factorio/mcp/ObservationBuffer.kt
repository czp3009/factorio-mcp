@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxObservationBuffer
import kotlinx.cinterop.*

private const val observationTransferBytes = 1024 * 1024

/** Copies immutable resident-owned data while the channel still owns the completed command. */
internal fun FmLinuxObservationBuffer.validateObservationBuffer(expected: Long) {
    require(expected >= 0 && size == expected.toULong()) { "Resident observation size is invalid" }
    require(if (size == 0uL) address == 0uL else address in 1uL..Long.MAX_VALUE.toULong() - size) {
        "Resident observation address is invalid"
    }
}

internal inline fun <reified T : CStructVar, R> FmLinuxObservationBuffer.readObservationRecords(
    count: UInt,
    readMemory: (Long, Int) -> ByteArray,
    transform: (T) -> R,
): List<R> {
    require(count <= Int.MAX_VALUE.toUInt()) { "Resident observation count exceeds integer representation" }
    val stride = sizeOf<T>()
    validateObservationBuffer(count.toLong() * stride)
    val output = ArrayList<R>()
    var index = 0
    val perChunk = maxOf(1, (observationTransferBytes / stride).toInt())
    while (index < count.toInt()) {
        val length = minOf(perChunk, count.toInt() - index)
        val bytes = readMemory(address.toLong() + index * stride, (length * stride).toInt())
        require(bytes.size == (length * stride).toInt()) { "Incomplete resident observation transfer" }
        bytes.usePinned { pinned ->
            val records = pinned.addressOf(0).reinterpret<T>()
            repeat(length) { output.add(transform(records[it])) }
        }
        index += length
    }
    return output
}

internal fun FmLinuxObservationBuffer.readObservationBytes(readMemory: (Long, Int) -> ByteArray): ByteArray {
    require(size <= Int.MAX_VALUE.toULong()) { "Resident observation exceeds byte-array representation" }
    validateObservationBuffer(size.toLong())
    val output = ByteArray(size.toInt())
    var offset = 0
    while (offset < output.size) {
        val length = minOf(observationTransferBytes, output.size - offset)
        val chunk = readMemory(address.toLong() + offset, length)
        require(chunk.size == length) { "Incomplete resident observation transfer" }
        chunk.copyInto(output, offset)
        offset += length
    }
    return output
}
