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
    ids: Set<String>?,
    search: String?,
    offset: Int,
    limit: Int,
): JsonObject {
    val matches =
        controls
            .filter { control ->
                (ids == null || control.id in ids) &&
                        (search == null || control.id.contains(search, ignoreCase = true))
            }
            .sortedBy { it.id }
    return buildJsonObject {
        put("state", state)
        put("ui_frame", frame)
        put("registry_count", registryCount)
        put("snapshot_complete", !truncated)
        put("matched_count", matches.size)
        put("offset", offset)
        val page = matches.drop(offset).take(limit)
        if (offset + page.size < matches.size) put("next_offset", offset + page.size)
        putJsonArray("controls") { page.forEach { add(it.json()) } }
        if (ids != null)
            putJsonArray(if (truncated) "unobserved_ids" else "missing_ids") {
                (ids - controls.map { it.id }.toSet()).sorted().forEach { add(it) }
            }
    }
}
