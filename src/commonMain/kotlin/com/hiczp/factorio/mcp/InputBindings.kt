package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal data class BindingSnapshot(
    val slot: String,
    val type: String,
    val name: String?,
    val code: Int,
    val modifiers: List<String>,
    val nativeModifierBits: Int,
) {
    fun json() = buildJsonObject {
        put("slot", slot)
        put("type", type)
        if (type != "Nothing") {
            name?.let { put("name", it) }
            put("native_code", code)
            putJsonArray("modifiers") { modifiers.forEach { add(it) } }
            put("native_modifier_bits", nativeModifierBits)
        }
    }
}

internal data class ControlSnapshot(
    val id: String,
    val linked: String?,
    val bindingOwner: String,
    val custom: Boolean,
    val enabled: Boolean?,
    val spectating: Boolean?,
    val cutscene: Boolean?,
    val gui: Boolean,
    val nativeUsage: Int,
    val bindings: List<BindingSnapshot>,
    val effective: List<BindingSnapshot>,
) {
    fun json() = buildJsonObject {
        put("id", id)
        put("custom", custom)
        put("gui_input", gui)
        put("native_usage", nativeUsage)
        enabled?.let { put("enabled", it) }
        spectating?.let { put("enabled_while_spectating", it) }
        cutscene?.let { put("enabled_while_in_cutscene", it) }
        linked?.let { put("linked_control", it) }
        put("binding_owner", bindingOwner)
        put("has_binding", effective.any { it.type in setOf("Keyboard", "MouseButton", "MouseWheel") })
        putJsonArray("bindings") {
            bindings.filter { it.type != "Nothing" }.forEach { add(it.json()) }
        }
        if (bindingOwner != id)
            putJsonArray("effective_bindings") {
                effective.filter { it.type != "Nothing" }.forEach { add(it.json()) }
            }
    }
}

internal fun GameSnapshot.bindingsJson(
    request: BindingReadRequest,
): JsonObject {
    val page = checkNotNull(bindingPage) { "Native control selection metadata is absent" }
    require(page.matchedCount in controls.size..registryCount && page.presence.size == (request.ids?.size ?: 0))
    require(controls.size <= (request.limit ?: Int.MAX_VALUE))
    val expectedSize = minOf((page.matchedCount.toLong() - request.offset).coerceAtLeast(0),
        (request.limit ?: Int.MAX_VALUE).toLong()).toInt()
    require(controls.size == expectedSize && page.hasMore == (request.offset.toLong() + controls.size < page.matchedCount))
    return buildJsonObject {
        put("state", state)
        put("ui_frame", frame)
        put("registry_count", registryCount)
        put("snapshot_complete", !truncated)
        put("matched_count", page.matchedCount)
        put("offset", request.offset)
        put("has_more", page.hasMore)
        if (page.hasMore) put("next_offset", request.offset + controls.size)
        putJsonArray("controls") { controls.forEach { add(it.json()) } }
        if (request.ids != null)
            putJsonArray(if (truncated) "unobserved_ids" else "missing_ids") {
                request.ids.filterIndexed { index, _ -> !page.presence[index] }.sorted().forEach { add(it) }
            }
    }
}
