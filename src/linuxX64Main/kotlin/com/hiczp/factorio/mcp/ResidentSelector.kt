@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxUiSelector
import com.hiczp.factorio.mcp.linuxbridge.FmRequestBuffer

internal fun FmRequestBuffer.bindRequest(root: FmRequestBuffer, slice: RequestSlice) {
    require(slice.offset >= 0 && slice.size >= 0 && slice.offset.toLong() + slice.size <= root.size.toLong())
    require(root.size == 0uL || root.address >= 4096uL)
    require(root.address <= Long.MAX_VALUE.toULong() - root.size)
    address = if (slice.size == 0) 0uL else root.address + slice.offset.toULong()
    size = slice.size.toULong()
}

/** Writes only typed request views while the caller owns the resident command payload. */
internal fun FmLinuxUiSelector.writePath(action: UiAction?, payload: RequestPayload, root: FmRequestBuffer) {
    require(action?.maxDepth == null || action.maxDepth >= 0) { "UI depth must be nonnegative" }
    count = action?.path?.size?.toUInt() ?: 0u
    maxDepth = uiDepth(action?.maxDepth).toUInt()
    path.bindRequest(root, payload.selector)
    collection = if (action?.collection == null) 0u else 1u
    optionOffset = action?.collection?.optionOffset?.toUInt() ?: 0u
    optionLimit = action?.collection?.optionLimit?.toUInt() ?: 0u
    fields = action?.fields?.uiFieldMask() ?: defaultUiFields.uiFieldMask()
    textSlice.source = action?.collection?.textSource ?: 0u
    textSlice.offset = action?.collection?.offset?.toUInt() ?: 0u
    textSlice.limit = action?.collection?.limit?.toUInt() ?: 0u
    graph.collection = action?.collection?.graphCollection?.toUInt() ?: 0u
    graph.series = action?.collection?.graphSeries?.toUInt() ?: 0u
    graph.offset = action?.collection?.offset?.toUInt() ?: 0u
    graph.limit = action?.collection?.limit?.toUInt() ?: 0u
}
