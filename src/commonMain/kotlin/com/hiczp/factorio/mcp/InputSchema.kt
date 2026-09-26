package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

private fun inputPositionSchema() = buildJsonObject {
    put("type", "object")
    put(
        "description",
        "Game viewport pixels from its top-left, not desktop or world coordinates. Choose within the viewport; coordinates are not clamped. Initial position moves before button downs; omission keeps the current game pointer position. For UI, a move and click in one operation may precede hover processing; prefer ui_action or a position-only operation first.",
    )
    put("additionalProperties", false)
    putJsonArray("required") {
        add("space")
        add("x")
        add("y")
    }
    putJsonObject("properties") {
        putJsonObject("space") { put("const", "viewport") }
        listOf("x", "y").forEach { name ->
            putJsonObject(name) {
                put("type", "integer")
                put("minimum", 0)
                put("maximum", Int.MAX_VALUE)
            }
        }
    }
}

internal fun inputSequenceProperties(): JsonObject = buildJsonObject {
    putJsonObject("stop_previous") {
        put("type", "boolean")
        put("default", false)
        put(
            "description",
            "Cancel and clean up the active input before starting this sequence. With empty operations, stop only.",
        )
    }
    putJsonObject("operations") {
        put("type", "array")
        put("maxItems", 256)
        put(
            "description",
            "Ordered, closed combinations with no extra gap between operations. Empty operations is a no-op unless stop_previous is true.",
        )
        putJsonObject("items") {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { add("controls") }
            putJsonObject("properties") {
                putJsonObject("ticks") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 4294967295L)
                    put("default", 1)
                    put(
                        "description",
                        "Number of local-player input evaluations to hold this combination; not wall-clock time or guaranteed server effect ticks.",
                    )
                }
                putJsonObject("controls") {
                    put("type", "array")
                    put("maxItems", 8)
                    put(
                        "description",
                        "Controls held together; empty means wait. Keys/buttons must be unique; initial mouse positions must agree. At most one wheel and one motion path per combination. Include modifier keys as keyboard controls (e.g. LSHIFT).",
                    )
                    putJsonObject("items") {
                        putJsonArray("oneOf") {
                            add(
                                buildJsonObject {
                                    put("type", "object")
                                    put("additionalProperties", false)
                                    putJsonArray("required") {
                                        add("device")
                                        add("key")
                                    }
                                    putJsonObject("properties") {
                                        putJsonObject("device") { put("const", "keyboard") }
                                        putJsonObject("key") {
                                            put("type", "string")
                                            put("minLength", 1)
                                            put("maxLength", 64)
                                            put("pattern", "^[A-Z0-9_]+$")
                                            put(
                                                "description",
                                                "Uppercase key name from input_bindings; not a control ID or typed text.",
                                            )
                                        }
                                    }
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("type", "object")
                                    put("additionalProperties", false)
                                    putJsonArray("required") { add("device") }
                                    putJsonObject("properties") {
                                        putJsonObject("device") { put("const", "mouse") }
                                        putJsonObject("wheel") {
                                            put("type", "string")
                                            putJsonArray("enum") {
                                                add("up")
                                                add("down")
                                            }
                                            put(
                                                "description",
                                                "One wheel event after the combination's button downs; never repeated while ticks elapse.",
                                            )
                                        }
                                        putJsonObject("button") {
                                            put("type", "string")
                                            putJsonArray("enum") {
                                                listOf(
                                                    "left",
                                                    "right",
                                                    "middle",
                                                    "button_4",
                                                    "button_5",
                                                )
                                                    .forEach { add(it) }
                                            }
                                        }
                                        put("position", inputPositionSchema())
                                        putJsonObject("motion") {
                                            put("type", "array")
                                            put("minItems", 1)
                                            put("maxItems", 64)
                                            put(
                                                "description",
                                                "One optional path per combination. Move at strictly increasing one-based ticks in 2..ticks while buttons remain held. No interpolation.",
                                            )
                                            putJsonObject("items") {
                                                put("type", "object")
                                                put("additionalProperties", false)
                                                putJsonArray("required") {
                                                    add("tick")
                                                    add("position")
                                                }
                                                putJsonObject("properties") {
                                                    putJsonObject("tick") {
                                                        put("type", "integer")
                                                        put("minimum", 2)
                                                        put("maximum", 4294967295L)
                                                    }
                                                    put("position", inputPositionSchema())
                                                }
                                            }
                                        }
                                    }
                                    putJsonArray("anyOf") {
                                        listOf("button", "position", "wheel", "motion").forEach { name ->
                                            add(
                                                buildJsonObject {
                                                    putJsonArray("required") { add(name) }
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
