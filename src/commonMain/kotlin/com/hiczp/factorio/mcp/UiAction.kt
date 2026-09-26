package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal data class UiStep(
    val child: Boolean,
    val position: Int,
    val type: String?,
    val text: String?,
    val enabled: Boolean?,
    val prototype: UiPrototypeMatch? = null,
    val visible: Boolean? = null,
)

internal data class UiPrototypeMatch(val name: String, val nativeType: String? = null)

internal data class UiAction(
    val path: List<UiStep>,
    val kind: Int = 0,
    val button: Int = 0,
    val alt: Boolean = false,
    val control: Boolean = false,
    val shift: Boolean = false,
    val x: Double = 0.5,
    val y: Double = 0.5,
    val text: List<Int> = emptyList(),
    val keys: List<String> = emptyList(),
)

internal fun parseSelector(value: JsonElement): List<UiStep> {
    val selector = value.jsonObject
    require(selector.keys == setOf("path")) { "selector requires only path" }
    val path = selector.getValue("path").jsonArray
    require(path.size in 1..16) { "selector.path must contain 1..16 steps" }
    return path.map { element ->
        val step = element.jsonObject
        require(step.keys.all { it in setOf("axis", "match", "position") }) {
            "Unknown selector step field"
        }
        val axis = step.getValue("axis").stringArgument()
        require(axis in setOf("child", "descendant")) { "Unknown selector axis" }
        val match = step.getValue("match").jsonObject
        require(
            match.keys.all { it in setOf("native_type", "text", "enabled", "prototype", "visible") }
        ) {
            "Unknown selector predicate"
        }
        val position = step["position"]?.intArgument() ?: 0
        require(position >= 0 && ("position" !in step || position > 0)) {
            "position must be positive and one-based"
        }
        fun string(name: String, maxBytes: Int): String? =
            match[name]?.jsonPrimitive?.let {
                require(
                    it.isString &&
                            '\u0000' !in it.content &&
                            it.content.encodeToByteArray().size < maxBytes
                ) {
                    "Invalid selector $name"
                }
                it.content
            }

        val type = string("native_type", 160)
        require(type == null || type.isNotEmpty()) { "native_type must not be empty" }
        UiStep(
            axis == "child",
            position,
            type,
            string("text", 1024),
            match["enabled"]?.booleanArgument(),
            match["prototype"]?.jsonObject?.let { prototype ->
                require(prototype.keys.all { it in setOf("native_type", "name") }) {
                    "Unknown prototype predicate"
                }
                fun identity(name: String, capacity: Int): String =
                    prototype.getValue(name).stringArgument().also {
                        require(
                            it.isNotEmpty() &&
                                    '\u0000' !in it &&
                                    it.encodeToByteArray().size < capacity
                        ) {
                            "Invalid prototype $name"
                        }
                    }
                UiPrototypeMatch(
                    identity("name", 256),
                    if ("native_type" in prototype) identity("native_type", 160) else null,
                )
            },
            match["visible"]?.booleanArgument(),
        )
    }
}

internal fun parseUiAction(args: JsonObject): UiAction {
    val action = args.getValue("action").stringArgument()
    if (action == "press_key") {
        require(args.keys.none { it in setOf("button", "position", "text") }) {
            "press_key accepts key, modifiers and an optional selector"
        }
        val key = args.getValue("key").stringArgument()
        require(
            key.isNotEmpty() &&
                    key.length <= 64 &&
                    key.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }
        ) {
            "key must use the uppercase input_bindings key vocabulary"
        }
        val modifiers = args["modifiers"]?.jsonArray?.map { it.stringArgument() } ?: emptyList()
        val modifierKeys = mapOf("control" to "LCTRL", "shift" to "LSHIFT", "alt" to "LALT")
        require(
            modifiers.distinct().size == modifiers.size && modifiers.all { it in modifierKeys }
        ) {
            "Unsupported or duplicate key modifier"
        }
        val keys = modifiers.map { modifierKeys.getValue(it) } + key
        require(keys.distinct().size == keys.size) { "Key chord contains duplicate keys" }
        return UiAction(args["selector"]?.let(::parseSelector) ?: emptyList(), 3, keys = keys)
    }
    require("key" !in args) { "key is only valid for press_key" }
    val path = parseSelector(args.getValue("selector"))
    return when (action) {
        "click" -> {
            require("text" !in args) { "text is only valid for set_text" }
            val button = args["button"]?.stringArgument() ?: "left"
            require(button in listOf("left", "right", "middle")) { "Unsupported mouse button" }
            val modifiers = args["modifiers"]?.jsonArray?.map { it.stringArgument() } ?: emptyList()
            require(
                modifiers.distinct().size == modifiers.size &&
                        modifiers.all { it in setOf("alt", "control", "shift") }
            ) {
                "modifiers supports alt, control and shift without duplicates"
            }
            val point = args["position"]?.jsonObject
            require(point == null || point.keys == setOf("x", "y")) { "position requires x and y" }
            val x = point?.getValue("x")?.doubleArgument() ?: 0.5
            val y = point?.getValue("y")?.doubleArgument() ?: 0.5
            require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0) {
                "position is relative to the widget, in 0..1"
            }
            UiAction(
                path,
                1,
                listOf("left", "right", "middle").indexOf(button),
                "alt" in modifiers,
                "control" in modifiers,
                "shift" in modifiers,
                x,
                y,
            )
        }

        "set_text" -> {
            require(args.keys.none { it in setOf("button", "modifiers", "position") }) {
                "Mouse parameters are only valid for click"
            }
            val text = args.getValue("text").jsonPrimitive
            require(text.isString) { "text must be a string" }
            UiAction(path, 2, text = unicodeScalars(text.content))
        }

        else -> error("Unsupported UI action")
    }
}

internal fun unicodeScalars(text: String): List<Int> = buildList {
    var index = 0
    while (index < text.length) {
        val first = text[index++].code
        val code =
            if (first in 0xD800..0xDBFF) {
                require(index < text.length && text[index].code in 0xDC00..0xDFFF) {
                    "Invalid Unicode surrogate"
                }
                0x10000 + ((first - 0xD800) shl 10) + (text[index++].code - 0xDC00)
            } else {
                require(first !in 0xDC00..0xDFFF) { "Invalid Unicode surrogate" }
                first
            }
        require(code >= 32 || code == 10) { "Text contains unsupported control characters" }
        add(code)
        require(size <= 1024) { "Text exceeds 1024 Unicode characters" }
    }
}
