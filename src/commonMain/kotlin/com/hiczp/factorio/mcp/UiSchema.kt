package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

private fun choice(vararg values: String, description: String? = null) = buildJsonObject {
    put("type", "string")
    putJsonArray("enum") { values.forEach { add(it) } }
    description?.let { put("description", it) }
}

private fun objectSchema(
    properties: JsonObject,
    vararg required: String,
    description: String? = null,
) = buildJsonObject {
    put("type", "object")
    put("properties", properties)
    put("additionalProperties", false)
    if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    description?.let { put("description", it) }
}

internal fun selectorSchema(): JsonObject =
    objectSchema(
        buildJsonObject {
            putJsonObject("path") {
                put("type", "array")
                put("minItems", 1)
                put("maxItems", 16)
                put(
                    "items",
                    objectSchema(
                        buildJsonObject {
                            put(
                                "axis",
                                choice(
                                    "child",
                                    "descendant",
                                    description =
                                        "Relative to each preceding match; first step starts above all UI roots. descendant excludes the context itself.",
                                ),
                            )
                            put(
                                "match",
                                objectSchema(
                                    buildJsonObject {
                                        putJsonObject("native_type") { put("type", "string") }
                                        putJsonObject("text") { put("type", "string") }
                                        putJsonObject("enabled") { put("type", "boolean") }
                                        putJsonObject("visible") {
                                            put("type", "boolean")
                                            put(
                                                "description",
                                                "Own widget flag only; unknown visibility matches neither true nor false. Omit to search without a visibility filter.",
                                            )
                                        }
                                        put(
                                            "prototype",
                                            objectSchema(
                                                buildJsonObject {
                                                    putJsonObject("name") { put("type", "string") }
                                                    putJsonObject("native_type") {
                                                        put("type", "string")
                                                    }
                                                },
                                                "name",
                                            ),
                                        )
                                    },
                                    description =
                                        "All supplied predicates must match exactly. Empty object matches any node. Use observed native_type/text/prototype names; truncated values cannot be exact-match targets.",
                                ),
                            )
                            putJsonObject("position") {
                                put("type", "integer")
                                put("minimum", 1)
                                put(
                                    "description",
                                    "One-based position per context after matching, in parent-first document order (not ui_read's postorder). Omit to keep all matches.",
                                )
                            }
                        },
                        "axis",
                        "match",
                    ),
                )
            }
        },
        "path",
        description =
            "Live structural selector, never a snapshot ID. ui_read returns matched subtrees; ui_action requires exactly one target. Narrow by ancestor and observed properties when labels repeat.",
    )

internal fun uiActionProperties(): JsonObject = buildJsonObject {
    put("selector", selectorSchema())
    put(
        "action",
        choice(
            "click",
            "set_text",
            "press_key",
            description =
                "click: selector required; optional button/modifiers/position. set_text: selector and text required. press_key: key required; optional modifiers/selector. Other action-specific arguments are rejected.",
        ),
    )
    putJsonObject("key") {
        put("type", "string")
        put("minLength", 1)
        put("maxLength", 64)
        put("pattern", "^[A-Z0-9_]+$")
        put(
            "description",
            "Required for press_key. Uppercase key name from input_bindings, such as TAB, ESCAPE or RETURN.",
        )
    }
    put("button", choice("left", "right", "middle", description = "click only; defaults to left."))
    putJsonObject("modifiers") {
        put("type", "array")
        put("items", choice("alt", "control", "shift"))
        put("uniqueItems", true)
        put("maxItems", 3)
        put("description", "click or press_key only. Held for this gesture; defaults to none.")
    }
    put(
        "position",
        objectSchema(
            buildJsonObject {
                listOf("x", "y").forEach { coordinate ->
                    putJsonObject(coordinate) {
                        put("type", "number")
                        put("minimum", 0)
                        put("maximum", 1)
                    }
                }
            },
            "x",
            "y",
            description =
                "click only. Point relative to widget bounds: top-left {x:0,y:0}, bottom-right {x:1,y:1}; defaults to center. Not viewport pixels.",
        ),
    )
    putJsonObject("text") {
        put("type", "string")
        put("maxLength", 1024)
        put(
            "description",
            "set_text only: replace the full text, including empty string to clear. At most 1024 Unicode characters; newline is allowed, other control characters are rejected. Native editing restrictions still apply.",
        )
    }
}
