@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.*
import kotlinx.cinterop.*

private inline fun <reified T : CStructVar, R> FmUiGraphBuffer.graphRecords(
    noinline read: (Long, Int) -> ByteArray,
    transform: (T) -> R,
): List<R> = memScoped {
    val buffer = alloc<FmObservationBuffer>()
    buffer.address = address
    buffer.size = size
    require(size % sizeOf<T>().toULong() == 0uL && size <= Int.MAX_VALUE.toULong())
    buffer.readObservationRecords<T, R>((size / sizeOf<T>().toULong()).toUInt(), read, transform)
}

private fun FmUiGraphBuffer.graphBytes(read: (Long, Int) -> ByteArray): ByteArray = memScoped {
    val buffer = alloc<FmObservationBuffer>()
    buffer.address = address
    buffer.size = size
    buffer.readObservationBytes(read)
}

internal fun FmUiGraph.readGraph(read: (Long, Int) -> ByteArray): WidgetGraph? {
    require(available <= 1u)
    if (available == 0u) return null
    require(collection <= 2u && total <= Int.MAX_VALUE.toUInt() && offset <= Int.MAX_VALUE.toUInt())
    val rows = series.graphRecords<FmUiGraphSeries, WidgetGraphSeries>(read) { row ->
        require(row.index <= Int.MAX_VALUE.toUInt() && row.total <= Int.MAX_VALUE.toUInt() &&
            row.offset <= Int.MAX_VALUE.toUInt() && row.values.size % 4uL == 0uL &&
            row.values.size / 4uL <= (row.total - minOf(row.offset, row.total)).toULong())
        val bytes = row.values.graphBytes(read)
        val values = if (bytes.isEmpty()) emptyList() else bytes.usePinned { pinned ->
            val floats = pinned.addressOf(0).reinterpret<FloatVar>()
            List(bytes.size / 4) { floats[it].toDouble() }
        }
        WidgetGraphSeries(row.index.toInt(), row.label.graphBytes(read).decodeToString(), values, row.total.toInt(), row.offset.toInt())
    }
    return WidgetGraph(
        sampleCount.toLong(), minimum.toDouble(), maximum.toDouble(), rows,
        collection.toInt(), total.toInt(), offset.toInt(),
    )
}
