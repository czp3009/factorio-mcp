@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.FM_MAX_PATH
import com.hiczp.factorio.mcp.nativebridge.FmAction
import kotlinx.cinterop.*
import platform.posix.memset

internal fun writeAction(target: FmAction, action: UiAction?, keys: List<UInt> = emptyList()) {
    // Validate the typed boundary too: callers need not have used the JSON parser.
    require(keys.size <= 8) { "UI key chord exceeds wire capacity" }
    require(action != null || keys.isEmpty()) { "Keys require a UI action" }
    require(action == null || action.path.size <= FM_MAX_PATH) { "UI path exceeds wire capacity" }
    require(action == null || action.text.size <= 1024) { "UI text exceeds wire capacity" }
    val predicates =
        action?.path?.map { step ->
            fun encode(value: String?, capacity: Int): ByteArray? =
                value?.let {
                    require('\u0000' !in it) { "UI predicate contains a null character" }
                    it.encodeToByteArray().also { bytes ->
                        require(bytes.size < capacity) { "UI predicate exceeds wire capacity" }
                    }
                }
            step.prototype?.let {
                require(
                    it.name.isNotEmpty() && (it.nativeType == null || it.nativeType.isNotEmpty())
                ) {
                    "Empty prototype predicate"
                }
            }
            listOf(
                encode(step.type, 160),
                encode(step.text, 1024),
                encode(step.prototype?.name, 256),
                encode(step.prototype?.nativeType, 160),
            )
        }
    memset(target.ptr, 0, sizeOf<FmAction>().toULong())
    if (action == null) return
    target.count = action.path.size
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
    action.path.forEachIndexed { index, step ->
        val wire = target.path[index]
        wire.child = if (step.child) 1 else 0
        wire.position = step.position
        wire.enabled = step.enabled?.let { if (it) 1 else 0 } ?: -1
        wire.hasVisible = if (step.visible == null) 0 else 1
        wire.visible = if (step.visible == true) 1 else 0
        wire.hasText = if (step.text == null) 0 else 1
        predicates!![index][0]?.forEachIndexed { offset, value -> wire.type[offset] = value }
        predicates[index][1]?.forEachIndexed { offset, value -> wire.text[offset] = value }
        predicates[index][2]?.forEachIndexed { offset, value -> wire.prototypeName[offset] = value }
        predicates[index][3]?.forEachIndexed { offset, value -> wire.prototypeType[offset] = value }
    }
}
