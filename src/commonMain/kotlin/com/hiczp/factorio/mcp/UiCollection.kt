package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal data class UiCollection(val source: String, val path: JsonArray, val offset: Int, val limit: Int) {
    val optionOffset: Int
        get() = if (source == "options") path.firstOrNull()?.jsonObject?.get("index")?.intArgument() ?: offset else 0

    val optionLimit: Int
        get() = if (source == "options") if (path.isEmpty()) limit else 1 else 0

    val textSource: UInt
        get() = when (source) {
            "text" -> 1u
            "tooltip_title" -> 2u
            "tooltip_text" -> 3u
            "options" -> if (path.isEmpty()) 0u else 4u
            else -> 0u
        }
}

internal fun parseUiCollection(value: JsonElement): UiCollection {
    val objectValue = value.jsonObject
    require(objectValue.keys.all { it in setOf("source", "path", "offset", "limit") }) { "Unknown collection field" }
    val source = objectValue.getValue("source").stringArgument()
    require(source in setOf("options", "gui", "graph", "text", "tooltip_title", "tooltip_text")) { "Unknown UI collection source" }
    val path = objectValue["path"]?.jsonArray ?: JsonArray(emptyList())
    require(source in setOf("gui", "options", "graph") || "path" !in objectValue) { "path requires source:gui, options or graph" }
    for (raw in path) {
        val step = raw.jsonObject
        require(step.size == 1) { "Collection path steps require one property or index" }
        when (step.keys.single()) {
            "property" -> require(step.getValue("property").stringArgument().matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                "Invalid collection property"
            }
            "index" -> {
                val index = step.getValue("index").jsonPrimitive
                require(index.isString || index.doubleOrNull?.isFinite() == true) {
                    "Invalid collection index"
                }
            }
            else -> throw IllegalArgumentException("Collection paths permit only property/index reads")
        }
    }
    if (source == "options" && path.isNotEmpty()) {
        require(path.size == 1 && path.single().jsonObject.keys == setOf("index")) {
            "Option text paths require one zero-based index"
        }
        require(path.single().jsonObject.getValue("index").intArgument() >= 0) { "Option index must be nonnegative" }
    }
    val offset = objectValue["offset"]?.intArgument() ?: 0
    val limit = objectValue["limit"]?.intArgument() ?: 64
    require(offset >= 0 && limit > 0) { "Collection offset must be nonnegative and limit positive" }
    return UiCollection(source, path, offset, limit).also { it.graphCollection }
}

internal fun uiCollectionSchema() = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    putJsonArray("required") { add("source") }
    putJsonObject("properties") {
        putJsonObject("source") {
            put("type", "string")
            putJsonArray("enum") {
                for (source in listOf("options", "gui", "graph", "text", "tooltip_title", "tooltip_text")) add(source)
            }
        }
        putJsonObject("path") {
            put("type", "array")
            put("description", "gui: property/index steps from LuaGuiElement. options: one zero-based index selects option text. graph: series pages the native series; series/index/values pages one series' samples using its zero-based index. Methods are not called.")
            putJsonObject("items") {
                put("type", "object")
                put("additionalProperties", false)
                putJsonObject("properties") {
                    putJsonObject("property") { put("type", "string") }
                    putJsonObject("index") {
                        putJsonArray("type") {
                            add("number")
                            add("string")
                        }
                    }
                }
                putJsonArray("oneOf") {
                    for (name in listOf("property", "index")) addJsonObject { putJsonArray("required") { add(name) } }
                }
            }
        }
        putJsonObject("offset") {
            put("type", "integer")
            put("minimum", 0)
            put("maximum", Int.MAX_VALUE)
            put("default", 0)
        }
        putJsonObject("limit") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", Int.MAX_VALUE)
            put("default", 64)
        }
    }
    put("description", "Read one selected widget's collection or string instead of its subtree. Requires a selector resolving exactly one widget. Reuse next_offset. Options retain zero-based indices; GUI tables retain their keys. String offsets/limits count UTF-8 bytes and must respect character boundaries. Each page is a fresh observation.")
}

internal fun GameSnapshot.uiCollectionJson(request: UiCollection): JsonObject {
    val node = nodes.filter { it.selected }.singleOrNull() ?: error("UI collection selector must resolve exactly one widget")
    fun unavailable(reason: String) = buildJsonObject {
        putJsonObject("read_status") {
            put("status", "unavailable")
            put("message", reason)
        }
    }
    val value = when (request.source) {
        "graph" -> node.properties?.graph?.collectionJson() ?: unavailable("The widget has no readable native graph")
        "gui" -> node.guiIndex?.let { index -> guiJson?.let(::GuiSnapshot)?.element(index)?.get("collection")?.jsonObject }
            ?.let { collection ->
                if (collection["entries"] is JsonObject && collection.getValue("entries").jsonObject.isEmpty())
                    JsonObject(collection + ("entries" to JsonArray(emptyList())))
                else collection
            }
            ?: unavailable(guiUnavailableReason ?: "The widget has no readable LuaGuiElement")
        "options" -> node.properties?.options?.let { options -> if (request.path.isNotEmpty()) {
            require(request.optionOffset < options.total) { "Option index is out of range" }
            val option = options.values.singleOrNull() ?: error("Selected option text is unavailable")
            JsonObject(checkNotNull(textRange) { "Native option slice metadata is absent" }.json(option.text) +
                    if (option.truncated) mapOf("text_truncated" to JsonPrimitive(true)) else emptyMap())
        } else buildJsonObject {
            put("value_type", "collection")
            put("index_base", 0)
            put("offset", options.offset)
            put("total", options.total)
            val next = options.offset.toLong() + options.values.size
            put("has_more", next < options.total)
            put("truncated", next < options.total)
            if (next < options.total) put("next_offset", next)
            putJsonArray("entries") {
                options.values.forEachIndexed { index, option -> addJsonObject {
                    put("key", options.offset + index)
                    put("value", option.text)
                    if (option.truncated) put("text_truncated", true)
                } }
            }
        } } ?: unavailable(node.properties?.optionsUnavailableReason ?: "The widget has no readable native option collection")
        else -> {
            val text = when (request.source) {
                "text" -> node.text
                "tooltip_title" -> node.properties?.tooltip?.title
                else -> node.properties?.tooltip?.text
            }
            text?.let { checkNotNull(textRange) { "Native text slice metadata is absent" }.json(it) }
                ?: unavailable("Stored text is unavailable")
        }
    }
    return buildJsonObject {
        put("state", state)
        put("ui_frame", frame)
        put("native_type", node.type.removePrefix("class "))
        put("source", request.source)
        if (request.source == "gui" || request.path.isNotEmpty()) put("path", request.path)
        put("collection", value)
    }
}
