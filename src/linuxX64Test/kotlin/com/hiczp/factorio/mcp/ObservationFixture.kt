@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxObservationBuffer
import kotlinx.cinterop.*
import platform.posix.memset

internal fun readFixtureObservation(address: Long, size: Int): ByteArray =
    requireNotNull(address.toCPointer<ByteVar>()).readBytes(size)

internal inline fun <reified T : CVariable> MemScope.observationArray(
    buffer: FmLinuxObservationBuffer,
    count: Int,
): CPointer<T> {
    val values = allocArray<T>(count)
    val size = sizeOf<T>() * count
    memset(values, 0, size.toULong())
    buffer.address = values.rawValue.toLong().toULong()
    buffer.size = size.toULong()
    return values
}
