package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal val defaultGuiFields = listOf(
    "caption", "tooltip", "elem_tooltip", "tags", "style", "sprite", "hovered_sprite",
    "clicked_sprite", "toggled", "auto_toggle", "number", "elem_type", "elem_value",
)

internal fun validateGuiMatch(value: JsonObject) {
    val pending = ArrayDeque<JsonElement>()
    pending.addLast(value)
    while (pending.isNotEmpty()) {
        when (val current = pending.removeLast()) {
            is JsonObject -> pending.addAll(current.values)
            is JsonArray -> pending.addAll(current)
            is JsonPrimitive -> require(current != JsonNull) {
                "GUI predicates require observed values; null is not a property value"
            }
        }
    }
}

internal fun guiArguments(action: UiAction?, api: RuntimeApi): JsonObject = buildJsonObject {
    val requested = if (action?.collection != null || action != null && UiField.GUI_PROPERTIES !in action.fields)
        emptyList() else action?.guiFields ?: defaultGuiFields
    require((action?.guiDepth ?: 8) >= 0) { "gui_depth must be nonnegative" }
    val catalog = api.catalog
    val readable = catalog.getValue("LuaGuiElement").jsonObject.getValue("attributes").jsonArray
        .map { it.stringArgument() }.toSet()
    val fields = if (requested == listOf("*")) readable.sorted() else requested
    require(fields.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) { "Invalid gui_fields attribute" }
    require(fields.all { it in readable }) { "gui_fields contains an unreadable LuaGuiElement attribute" }
    put("api", catalog)
    fun predicate(value: JsonElement): JsonObject = buildJsonObject {
        when (value) {
            is JsonObject -> {
                put("kind", "object")
                put("fields", JsonObject(value.mapValues { predicate(it.value) }))
            }
            is JsonArray -> {
                put("kind", "array")
                put("values", JsonArray(value.map(::predicate)))
                put("count", value.size)
            }
            is JsonPrimitive -> {
                put("kind", "value")
                put("value", value)
            }
        }
    }
    put("steps", action?.path?.size ?: 0)
    putJsonObject("matches") {
        action?.path?.forEachIndexed { index, step ->
            step.gui?.let { put((index + 1).toString(), predicate(it)) }
        }
    }
    putJsonArray("fields") { fields.forEach { add(it) } }
    put("depth", observationDepth(action?.guiDepth, 8))
    put("children", action == null || action.collection == null && UiField.GUI_CHILDREN in action.fields)
    put("properties", action == null || action.collection == null && UiField.GUI_PROPERTIES in action.fields)
    if (action?.collection != null) put("collection_only", true)
    action?.collection?.takeIf { it.source == "gui" }?.let { collection ->
        putJsonObject("collection") {
            put("path", collection.path)
            put("offset", collection.offset)
            put("limit", collection.limit)
        }
    }
}
