package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

private fun inputObject(properties: JsonObject, required: List<String>) = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("properties", properties)
    putJsonArray("required") { required.forEach { add(it) } }
}

private fun inputCoordinates(space: String) =
    inputObject(
        buildJsonObject {
            listOf("x", "y").forEach { name ->
                putJsonObject(name) {
                    put("type", if (space == "viewport") "integer" else "number")
                    put("minimum", if (space == "viewport") 0 else -1000000)
                    put("maximum", if (space == "viewport") Int.MAX_VALUE else 1000000)
                }
            }
        },
        listOf("x", "y"),
    )

private fun inputPointerSchema(moving: Boolean) = buildJsonObject {
    putJsonArray("oneOf") {
        listOf("viewport", "world").forEach { space ->
            add(
                inputObject(
                    buildJsonObject {
                        putJsonObject("space") { put("const", space) }
                        if (space == "world")
                            putJsonObject("snap") {
                                put("const", "tile_center")
                                put(
                                    "description",
                                    "Snap to floor(x)+0.5, floor(y)+0.5. Omit to preserve exact world positions.",
                                )
                            }
                        if (moving) {
                            put("from", inputCoordinates(space))
                            put("to", inputCoordinates(space))
                        } else
                            inputCoordinates(space).getValue("properties").jsonObject.forEach {
                                (name, schema) ->
                                put(name, schema)
                            }
                    },
                    listOf("space") + if (moving) listOf("from", "to") else listOf("x", "y"),
                )
            )
        }
    }
}

internal fun inputSequenceProperties(): JsonObject = buildJsonObject {
    putJsonObject("stop_previous") {
        put("type", "boolean")
        put("default", false)
        put(
            "description",
            "Cancel and release the active input before starting this timeline. An empty timeline stops only.",
        )
    }
    putJsonObject("timeline") {
        put("type", "array")
        put("maxItems", MAX_INPUT_ENTRIES)
        put(
            "description",
            "Independent concurrent entries, dispatched in array order within each tick. Same-button overlaps share a hold; later pointer entries win. Empty is a no-op unless stop_previous is true.",
        )
        putJsonObject("items") {
            putJsonArray("oneOf") {
                listOf("key", "button", "position", "motion", "wheel").forEach { action ->
                    val properties = buildJsonObject {
                        putJsonObject("device") {
                            put("const", if (action == "key") "keyboard" else "mouse")
                        }
                        when (action) {
                            "key" ->
                                putJsonObject(action) {
                                    put("type", "string")
                                    put("minLength", 1)
                                    put("maxLength", 64)
                                    put("pattern", "^[A-Z0-9_]+$")
                                    put(
                                        "description",
                                        "Physical key name from input_bindings. Add modifier keys as separate entries.",
                                    )
                                }
                            "button",
                            "wheel" ->
                                putJsonObject(action) {
                                    put("type", "string")
                                    putJsonArray("enum") {
                                        (if (action == "button")
                                                listOf(
                                                    "left",
                                                    "right",
                                                    "middle",
                                                    "button_4",
                                                    "button_5",
                                                )
                                            else listOf("up", "down"))
                                            .forEach { add(it) }
                                    }
                                    if (action == "wheel")
                                        put(
                                            "description",
                                            "One impulse at the start of each interval.",
                                        )
                                }
                            else -> put(action, inputPointerSchema(action == "motion"))
                        }
                        putJsonObject("tick") {
                            put("type", "string")
                            put("minLength", 1)
                            put("maxLength", 2048)
                            put(
                                "pattern",
                                "^\\s*[0-9]+(?:\\s*-\\s*[0-9]+)?(?:\\s*,\\s*[0-9]+(?:\\s*-\\s*[0-9]+)?)*\\s*$",
                            )
                            put(
                                "description",
                                "Inclusive relative ticks in 0..4294967295, e.g. 100-200,201,300-400. At most 64 intervals per entry; overlaps are allowed and supplied order is preserved. Tick 0 is the first eligible local input evaluation after admission. Each motion interval restarts the path; a single tick uses to. Positions apply each active tick.",
                            )
                        }
                    }
                    add(inputObject(properties, listOf("device", action, "tick")))
                    if (action == "motion")
                        add(
                            inputObject(
                                buildJsonObject {
                                    properties
                                        .filterKeys { it != "tick" }
                                        .forEach { (name, schema) -> put(name, schema) }
                                    putJsonObject("start_tick") {
                                        put("type", "integer")
                                        put("minimum", 0)
                                        put("maximum", MAX_INPUT_TICK)
                                        put("default", 0)
                                    }
                                    putJsonObject("per_point_ticks") {
                                        put("type", "integer")
                                        put("minimum", 1)
                                        put("maximum", MAX_INPUT_TICK)
                                        put(
                                            "description",
                                            "Dwell at each generated point, including endpoints. Point count is ceil(max(abs(dx),abs(dy)))+1: pixel spacing for viewport, tile spacing for world. The final tick must fit 0..4294967295. Cannot combine with tick.",
                                        )
                                    }
                                },
                                listOf("device", action, "per_point_ticks"),
                            )
                        )
                }
            }
        }
    }
}
