package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

/**
 * The game walker emits descendants before their parent. Never invent missing parents after
 * truncation.
 */
internal fun GameSnapshot.uiJson(bounds: Boolean, filtered: Boolean = false): JsonObject {
    val parents = IntArray(nodes.size) { -1 }
    val pending = mutableListOf<Int>()
    nodes.forEachIndexed { index, node ->
        while (pending.isNotEmpty() && nodes[pending.last()].depth > node.depth) {
            val child = pending.removeAt(pending.lastIndex)
            if (nodes[child].depth == node.depth + 1) parents[child] = index
        }
        pending += index
    }
    val selected = BooleanArray(nodes.size)
    // Postorder guarantees that parents follow children; reverse propagation is linear.
    for (index in nodes.indices.reversed()) {
        val node = nodes[index]
        val parent = parents[index]
        selected[index] = !filtered || node.selected || (parent >= 0 && selected[parent])
    }
    val spriteIds = mutableSetOf<Int>()
    val pendingSprites = ArrayDeque<Int>()
    nodes.forEachIndexed { index, node ->
        if (selected[index])
            node.properties?.icons?.let {
                pendingSprites.addAll(listOf(it.normal, it.hovered, it.disabled))
            }
    }
    while (pendingSprites.isNotEmpty()) {
        val index = pendingSprites.removeFirst()
        if (index < 0 || !spriteIds.add(index)) continue
        val sprite = sprites[index]
        pendingSprites.add(sprite.next)
        pendingSprites.add(sprite.extra)
    }
    return buildJsonObject {
        put("frame", frame)
        put("state", state)
        put("truncated", truncated)
        put("order", "postorder")
        put("identity_scope", "snapshot")
        propertiesUnavailableReason?.let { put("properties_unavailable_reason", it) }
        slotIdentityUnavailableReason?.let { put("slot_identity_unavailable_reason", it) }
        numberUnavailableReason?.let { put("number_unavailable_reason", it) }
        visibilityUnavailableReason?.let { put("visibility_unavailable_reason", it) }
        progressUnavailableReason?.let { put("progress_unavailable_reason", it) }
        elementUnavailableReason?.let { put("element_unavailable_reason", it) }
        spriteUnavailableReason?.let { put("sprite_unavailable_reason", it) }
        qualityConditionUnavailableReason?.let { put("quality_condition_unavailable_reason", it) }
        switchUnavailableReason?.let { put("switch_unavailable_reason", it) }
        if (spriteIds.isNotEmpty()) {
            put("sprite_identity_scope", "snapshot")
            putJsonArray("sprites") {
                sprites.forEachIndexed { index, sprite ->
                    if (index in spriteIds) add(sprite.json(index))
                }
            }
        }
        if (nodes.any { it.visible != null }) put("visibility_basis", "own_widget_flags")
        if (filtered) put("selector_nodes_omitted", selected.count { !it })
        putJsonArray("nodes") {
            nodes.forEachIndexed { index, node ->
                if (!selected[index]) return@forEachIndexed
                add(
                    buildJsonObject {
                        put("id", index)
                        put("depth", node.depth)
                        val parent = parents[index]
                        if (parent >= 0 && selected[parent]) put("parent", parent)
                        put("type", node.type.removePrefix("class "))
                        widgetText(node)
                        put("enabled", node.enabled)
                        node.flaggedForDestruction?.let { put("flagged_for_destruction", it) }
                        node.visible?.let { put("visible", it) }
                        node.renderEnabled?.let { put("render_enabled", it) }
                        node.hiddenBySearch?.let { put("hidden_by_search", it) }
                        node.properties?.let { put("properties", it.json()) }
                        if (filtered) put("matched", node.selected)
                        if (node.typeTruncated) put("type_truncated", true)
                        if (bounds)
                            putJsonObject("bounds") {
                                put("x", node.x)
                                put("y", node.y)
                                put("width", node.width)
                                put("height", node.height)
                            }
                    }
                )
            }
        }
    }
}

private fun WidgetProperties.json(): JsonObject = buildJsonObject {
    switch?.let {
        putJsonObject("switch") {
            put("state_value", it.stateValue)
            put("state", it.state)
            put("allow_none", it.allowNone)
        }
    }
    qualityCondition?.let {
        putJsonObject("quality_condition") {
            put("quality_index", it.qualityIndex)
            put("comparison_value", it.comparisonValue)
            put("comparison", it.comparison)
            put("quality_name", it.qualityName?.let(::JsonPrimitive) ?: JsonNull)
            put("quality_lookup", it.qualityLookup)
            if (it.qualityNameTruncated) put("quality_name_truncated", true)
        }
    }
    icons?.let {
        putJsonObject("icons") {
            spriteReference("normal", it.normal)
            spriteReference("hovered", it.hovered)
            spriteReference("disabled", it.disabled)
        }
    }
    element?.let { element ->
        put(
            "element",
            if (!element.present) JsonNull
            else if (element.stackCount != null)
                buildJsonObject {
                    put("native_type", "ItemStack")
                    put("count", element.stackCount)
                    put("item", element.item?.json() ?: JsonNull)
                }
            else element.item?.json() ?: JsonNull,
        )
    }
    progress?.let { progress ->
        putJsonObject("progress") {
            put("value", progress.value.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: JsonNull)
            if (!progress.value.isFinite()) put("value_non_finite", true)
            put("direction", progress.direction)
            put("has_text", progress.hasText)
        }
    }
    checkState?.let { put("check_state", it) }
    toggled?.let { put("toggled", it) }
    selectedIndex?.let {
        put("selected_index", if (it < 0) JsonNull else JsonPrimitive(it))
        put("index_base", 0)
    }
    options?.let { options ->
        put("options_total", options.total)
        put("options_truncated", options.values.size < options.total)
        putJsonArray("options") {
            options.values.forEachIndexed { index, option ->
                addJsonObject {
                    put("index", index)
                    put("text", option.text)
                    if (option.truncated) put("text_truncated", true)
                }
            }
        }
    }
    optionsUnavailableReason?.let { put("options_unavailable_reason", it) }
    fun identity(property: String, identity: WidgetPrototype) {
        putJsonObject(property) {
            put("name", identity.name?.let(::JsonPrimitive) ?: JsonNull)
            put("native_type", identity.nativeType?.let(::JsonPrimitive) ?: JsonNull)
            if (identity.nameTruncated) put("name_truncated", true)
            if (identity.typeTruncated) put("type_truncated", true)
        }
    }
    prototype?.let { identity("prototype", it) }
    quality?.let { identity("quality", it) }
    number?.let { number ->
        putJsonObject("number") {
            put("draw_requested", number.drawRequested)
            put("value", number.value?.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: JsonNull)
            if (number.value?.isFinite() == false) put("value_non_finite", true)
            number.showZero?.let { put("show_zero", it) }
            number.unknown?.let { put("unknown", it) }
            number.infinite?.let { put("infinite", it) }
        }
    }
    slider?.let { slider ->
        fun number(name: String, value: Double) {
            put(name, if (value.isFinite()) JsonPrimitive(value) else JsonNull)
        }
        number("value", slider.value)
        number("minimum", slider.minimum)
        number("maximum", slider.maximum)
        number("step", slider.step)
    }
}

private fun JsonObjectBuilder.spriteReference(name: String, value: Int) {
    put(name, if (value >= 0) JsonPrimitive(value) else JsonNull)
    if (value == -2) put("${name}_truncated", true)
}

private fun WidgetSprite.json(id: Int): JsonObject = buildJsonObject {
    put("id", id)
    put("filename", filename?.let(::JsonPrimitive) ?: JsonNull)
    if (filenameTruncated) put("filename_truncated", true)
    put("intentionally_empty", intentionallyEmpty)
    put("x", x)
    put("y", y)
    put("width", width)
    put("height", height)
    fun number(name: String, value: Double) {
        put(name, value.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: JsonNull)
        if (!value.isFinite()) put("${name}_non_finite", true)
    }
    number("scale", scale)
    number("shift_x", shiftX)
    number("shift_y", shiftY)
    putJsonObject("tint") {
        listOf("r", "g", "b", "a").forEachIndexed { index, name ->
            val value = tint[index]
            put(name, value.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: JsonNull)
            if (!value.isFinite()) put("${name}_non_finite", true)
        }
    }
    spriteReference("next", next)
    spriteReference("extra", extra)
}

private fun WidgetItem.json(): JsonObject = buildJsonObject {
    put("native_type", nativeType)
    if (typeTruncated) put("type_truncated", true)
    fun number(name: String, value: Double) {
        put(name, value.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: JsonNull)
        if (!value.isFinite()) put("${name}_non_finite", true)
    }
    number("health", health)
    durabilityLeft?.let { number("durability_left", it) }
    magazineLeft?.let { number("magazine_left", it) }
}

internal fun GameSnapshot.statusJson(pid: Int): JsonObject = buildJsonObject {
    put("attached", attached)
    put("pid", pid)
    put("state", state)
    put("ui_ready", attached)
    put("frame", frame)
    put("paused", paused?.let(::JsonPrimitive) ?: JsonNull)
    inputTransfer?.let { put("input_transfer", it.toJson()) }
}

internal fun detachedStatus() = buildJsonObject {
    put("attached", false)
    put("state", "detached")
    put("ui_ready", false)
}

internal fun exitedStatus(pid: Int) = buildJsonObject {
    put("attached", false)
    put("state", "exited")
    put("pid", pid)
    put("ui_ready", false)
}
