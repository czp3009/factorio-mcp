@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.FmAction
import com.hiczp.factorio.mcp.nativebridge.FmRequestBuffer
import kotlinx.cinterop.*
import platform.posix.memset

internal fun FmRequestBuffer.bindRequest(root: FmRequestBuffer, slice: RequestSlice) {
    require(slice.offset >= 0 && slice.size >= 0 && slice.offset.toLong() + slice.size <= root.size.toLong())
    require(root.size == 0uL || root.address >= 4096uL)
    require(root.address <= Long.MAX_VALUE.toULong() - root.size)
    address = if (slice.size == 0) 0uL else root.address + slice.offset.toULong()
    size = slice.size.toULong()
}

internal fun writeAction(
    target: FmAction,
    action: UiAction?,
    keys: List<UInt> = emptyList(),
    payload: RequestPayload = RequestPayload(),
    root: FmRequestBuffer? = null,
) {
    require(keys.size <= 8) { "UI key chord exceeds wire capacity" }
    require(action != null || keys.isEmpty()) { "Keys require a UI action" }
    require(action == null || action.text.size <= 16384) { "UI text exceeds dispatch budget" }
    require(action?.maxDepth == null || action.maxDepth >= 0) { "UI depth must be nonnegative" }
    memset(target.ptr, 0, sizeOf<FmAction>().toULong())
    if (action == null) return
    target.fields = action.fields.uiFieldMask()
    target.maxDepth = uiDepth(action.maxDepth)
    target.count = action.path.size
    if (action.path.isNotEmpty()) target.path.bindRequest(checkNotNull(root), payload.selector)
    target.kind = action.kind
    target.button = action.button
    target.alt = if (action.alt) 1 else 0
    target.control = if (action.control) 1 else 0
    target.shift = if (action.shift) 1 else 0
    target.x = action.x
    target.y = action.y
    target.textCount = action.text.size.toUInt()
    target.keyCount = keys.size.toUInt()
    keys.forEachIndexed { index, key -> target.keys[index] = key }
    action.text.forEachIndexed { index, value -> target.text[index] = value.toUInt() }
    target.collection = if (action.collection == null) 0u else 1u
    target.optionOffset = action.collection?.optionOffset?.toUInt() ?: 0u
    target.optionLimit = action.collection?.optionLimit?.toUInt() ?: 0u
    target.textSlice.source = action.collection?.textSource ?: 0u
    target.textSlice.offset = action.collection?.offset?.toUInt() ?: 0u
    target.textSlice.limit = action.collection?.limit?.toUInt() ?: 0u
    target.graph.collection = action.collection?.graphCollection?.toUInt() ?: 0u
    target.graph.series = action.collection?.graphSeries?.toUInt() ?: 0u
    target.graph.offset = action.collection?.offset?.toUInt() ?: 0u
    target.graph.limit = action.collection?.limit?.toUInt() ?: 0u
}
