package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal data class InputPosition(val x: Int, val y: Int)

internal data class InputMotion(val tick: Long, val position: InputPosition)

internal fun validateInputMotion(motion: List<InputMotion>, ticks: Long) {
    require(motion.size <= 64) { "Mouse motion exceeds 64 points" }
    var previous = 1L
    motion.forEach {
        require(it.tick > previous && it.tick <= ticks) {
            "Motion ticks must increase strictly within 2..operation ticks"
        }
        require(it.position.x >= 0 && it.position.y >= 0) {
            "Viewport coordinates must be nonnegative"
        }
        previous = it.tick
    }
}

internal sealed interface InputControl {
    data class Keyboard(val key: String) : InputControl

    data class Mouse(
        val button: String?,
        val position: InputPosition?,
        val wheel: String? = null,
        val motion: List<InputMotion> = emptyList(),
    ) : InputControl
}

internal data class InputOperation(val controls: List<InputControl>, val ticks: Long)

internal data class InputSequenceRequest(
    val operations: List<InputOperation>,
    val stopPrevious: Boolean,
)

internal data class InputSequenceResult(
    val completed: Boolean,
    val completedOperations: Int,
    val evaluatedTicks: Long,
    val reason: String? = null,
) {
    fun json() = buildJsonObject {
        put("status", if (completed) "completed" else "aborted")
        put("completed_operations", completedOperations)
        put("evaluated_ticks", evaluatedTicks)
        reason?.let { put("reason", it) }
    }
}

/** Admission finishes independently of the resident-owned task's eventual completion. */
internal interface GameInputTask {
    suspend fun awaitResult(): InputSequenceResult

    /** Cancel unfinished work, wait for input release, then close only local task resources. */
    suspend fun close(): InputSequenceResult
}

internal fun parseInputSequence(args: JsonObject): InputSequenceRequest {
    require(args.keys.all { it in setOf("operations", "stop_previous") }) {
        "Unknown input argument"
    }
    val operations = args.getValue("operations").jsonArray
    require(operations.size <= 256) { "Input sequence exceeds 256 operations" }
    val stopPrevious = args["stop_previous"]?.booleanArgument() ?: false
    return InputSequenceRequest(
        operations.map { item ->
            val operation = item.jsonObject
            require(operation.keys.all { it in setOf("controls", "ticks") }) {
                "Unknown input operation field"
            }
            val ticks =
                operation["ticks"]?.let {
                    val value = it.jsonPrimitive
                    require(!value.isString) { "ticks must be an integer" }
                    requireNotNull(value.longOrNull) { "ticks must be an integer" }
                } ?: 1L
            require(ticks in 1..4294967295L) { "ticks must be in 1..4294967295" }
            val controls = operation.getValue("controls").jsonArray
            require(controls.size <= 8) { "A combination permits at most eight controls" }
            val keys = mutableSetOf<String>()
            val buttons = mutableSetOf<String>()
            var position: InputPosition? = null
            var hasWheel = false
            var hasMotion = false
            fun position(value: JsonElement): InputPosition {
                val point = value.jsonObject
                require(
                    point.keys == setOf("space", "x", "y") &&
                            point.getValue("space").stringArgument() == "viewport"
                ) {
                    "Mouse position requires viewport space and x/y client-content pixels"
                }
                return InputPosition(
                    point.getValue("x").intArgument(),
                    point.getValue("y").intArgument(),
                )
                    .also {
                        require(it.x >= 0 && it.y >= 0) {
                            "Viewport coordinates must be nonnegative"
                        }
                    }
            }
            InputOperation(
                controls.map { entry ->
                    val control = entry.jsonObject
                    when (control.getValue("device").stringArgument()) {
                        "keyboard" -> {
                            require(control.keys == setOf("device", "key")) {
                                "Keyboard controls require only device and key"
                            }
                            val key = control.getValue("key").stringArgument()
                            require(
                                key.length in 1..64 &&
                                        key.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }
                            ) {
                                "Use uppercase key names from input_bindings"
                            }
                            require(keys.add(key)) { "Duplicate keyboard control: $key" }
                            InputControl.Keyboard(key)
                        }

                        "mouse" -> {
                            require(
                                control.keys.all {
                                    it in setOf("device", "button", "position", "wheel", "motion")
                                }
                            ) {
                                "Unknown mouse control field"
                            }
                            val button = control["button"]?.stringArgument()
                            val wheel = control["wheel"]?.stringArgument()
                            if (wheel != null) {
                                require(wheel in setOf("up", "down")) { "wheel must be up or down" }
                                require(!hasWheel) { "A combination permits only one wheel event" }
                                hasWheel = true
                            }
                            if (button != null) {
                                require(
                                    button in
                                            setOf("left", "right", "middle", "button_4", "button_5")
                                ) {
                                    "Unsupported mouse button"
                                }
                                require(buttons.add(button)) { "Duplicate mouse button: $button" }
                            }
                            val point =
                                control["position"]?.let(::position)?.also { point ->
                                    require(position == null || position == point) {
                                        "A combination cannot target different mouse positions"
                                    }
                                    position = point
                                }
                            val motion =
                                control["motion"]?.jsonArray?.let { points ->
                                    require(!hasMotion) {
                                        "A combination permits only one mouse motion path"
                                    }
                                    hasMotion = true
                                    require(points.size in 1..64) {
                                        "Mouse motion requires 1..64 points"
                                    }
                                    points
                                        .map { entry ->
                                            val item = entry.jsonObject
                                            require(item.keys == setOf("tick", "position")) {
                                                "Motion points require tick and position"
                                            }
                                            val tick = item.getValue("tick").jsonPrimitive
                                            require(!tick.isString) {
                                                "Motion tick must be an integer"
                                            }
                                            InputMotion(
                                                requireNotNull(tick.longOrNull) {
                                                    "Motion tick must be an integer"
                                                },
                                                position(item.getValue("position")),
                                            )
                                        }
                                        .also { validateInputMotion(it, ticks) }
                                } ?: emptyList()
                            require(
                                button != null ||
                                        point != null ||
                                        wheel != null ||
                                        motion.isNotEmpty()
                            ) {
                                "Mouse control requires a button, position, wheel or motion"
                            }
                            InputControl.Mouse(button, point, wheel, motion)
                        }

                        else -> error("Supported input devices are keyboard and mouse")
                    }
                },
                ticks,
            )
        },
        stopPrevious,
    )
}
