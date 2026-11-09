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
    put(
        "description",
        if (moving)
            "One straight pointer path. Use multiple motion entries for turns and a separate button entry spanning the drag; motion alone presses no button."
        else "Set the pointer position on every active tick; this does not press a button.",
    )
    putJsonArray("oneOf") {
        listOf("viewport", "world").forEach { space ->
            add(
                inputObject(
                    buildJsonObject {
                        putJsonObject("space") {
                            put("const", space)
                            put(
                                "description",
                                if (space == "world")
                                    "Tile coordinates on the currently viewed surface, projected through the current view each tick. Positions must stay onscreen."
                                else "Game-content pixels from the top-left of the viewport.",
                            )
                        }
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
            "If a timeline is active, false rejects the call; true cancels and releases it before starting this one. With an empty timeline, true stops only.",
        )
    }
    putJsonObject("timeline") {
        put("type", "array")
        put("maxItems", MAX_INPUT_ENTRIES)
        put(
            "description",
            "All entries share the same tick clock. Overlap tick ranges for chords and simultaneous holds; offset ranges for successive actions. Array order controls dispatch within a tick, not sequential execution. Later active pointer entries win; same-button overlaps share a hold. Empty is a no-op when idle, or stops an active timeline with stop_previous:true.",
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
                                        "Physical key name from input_bindings. For a chord, schedule each key separately with overlapping tick ranges. Put modifier entries such as LCTRL, LSHIFT and LALT before the modified key.",
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
                                    if (action == "button")
                                        put(
                                            "description",
                                            "Hold this button throughout each inclusive tick interval. For a continuous drag, span all motion segments and turns with one hold in the same call.",
                                        )
                                    else
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
                                "Inclusive relative ticks: 0-59 holds for 60 evaluations; 0,10,20 schedules repeated presses. Tick 0 is the first eligible local input evaluation after admission, shared by all entries; gaps advance the clock. At most 64 intervals with endpoints in 0..4294967295; overlaps and supplied order are preserved. Each motion interval restarts its path; one tick uses to.",
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
                                        put("description", "Start this motion on the shared tick clock when using per_point_ticks.")
                                    }
                                    putJsonObject("per_point_ticks") {
                                        put("type", "integer")
                                        put("minimum", 1)
                                        put("maximum", MAX_INPUT_TICK)
                                        put(
                                            "description",
                                            "Input evaluations to hold each generated point, including endpoints. Use world coordinates and tile_center snapping for tile-by-tile drags. Point count is ceil(max(abs(dx),abs(dy)))+1; duration is point count times per_point_ticks. The final tick must fit 0..4294967295. Cannot combine with tick.",
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
