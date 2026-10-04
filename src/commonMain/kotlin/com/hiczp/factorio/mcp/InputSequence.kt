package com.hiczp.factorio.mcp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlinx.serialization.json.*

internal const val MAX_INPUT_TICK = 4294967295L
internal const val MAX_INPUT_ENTRIES = 256
internal const val MAX_INPUT_INTERVALS = 64

internal data class InputInterval(val first: Long, val last: Long)

internal data class InputPoint(val x: Double, val y: Double)

internal data class InputPath(
    val space: String,
    val from: InputPoint,
    val to: InputPoint,
    val tileCenters: Boolean = false,
) {
    val pointCount: Long
        get() = ceil(max(abs(to.x - from.x), abs(to.y - from.y))).toLong() + 1
}

internal sealed interface InputControl {
    data class Keyboard(val key: String) : InputControl

    data class MouseButton(val button: String) : InputControl

    data class Pointer(val path: InputPath, val moving: Boolean) : InputControl

    data class Wheel(val direction: String) : InputControl
}

internal data class InputEntry(
    val control: InputControl,
    val intervals: List<InputInterval>,
    val perPointTicks: Long = 0,
)

internal data class InputSequenceRequest(val timeline: List<InputEntry>, val stopPrevious: Boolean)

internal data class InputSequenceResult(
    val completed: Boolean,
    val completedEntries: Int,
    val evaluatedTicks: Long,
    val reason: String? = null,
) {
    fun json() = buildJsonObject {
        put("status", if (completed) "completed" else "aborted")
        put("completed_entries", completedEntries)
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

internal fun parseInputIntervals(value: String): List<InputInterval> {
    require(value.length in 1..2048) { "tick must contain 1..2048 characters" }
    val parts = value.split(',')
    require(parts.size in 1..MAX_INPUT_INTERVALS) { "tick permits at most 64 intervals" }
    return parts.map { part ->
        val match =
            requireNotNull(Regex("([0-9]+)(?:\\s*-\\s*([0-9]+))?").matchEntire(part.trim())) {
                "tick requires inclusive intervals, for example 0-100,105,110-200"
            }
        val first = requireNotNull(match.groupValues[1].toLongOrNull()) { "tick is out of range" }
        val last =
            if (match.groupValues[2].isEmpty()) first
            else requireNotNull(match.groupValues[2].toLongOrNull()) { "tick is out of range" }
        require(first in 0..MAX_INPUT_TICK && last in first..MAX_INPUT_TICK) {
            "Interval endpoints must be inclusive in 0..4294967295 with start <= end"
        }
        InputInterval(first, last)
    }
}

private fun inputPath(value: JsonObject, moving: Boolean): InputPath {
    val allowed =
        if (moving) setOf("space", "snap", "from", "to") else setOf("space", "snap", "x", "y")
    require(value.keys.all { it in allowed }) { "Unknown pointer coordinate field" }
    val space = value.getValue("space").stringArgument()
    require(space in setOf("viewport", "world")) { "space must be viewport or world" }
    val snap = value["snap"]?.stringArgument()
    require(snap == null || (space == "world" && snap == "tile_center")) {
        "snap permits tile_center in world space only"
    }
    fun point(point: JsonObject): InputPoint {
        if (moving)
            require(point.keys == setOf("x", "y")) { "A path endpoint requires only x and y" }
        fun coordinate(name: String): Double {
            val raw = point.getValue(name).jsonPrimitive
            require(!raw.isString) { "$name must be numeric" }
            val number = requireNotNull(raw.doubleOrNull) { "$name must be numeric" }
            require(number.isFinite()) { "$name must be finite" }
            if (space == "viewport")
                require(number in 0.0..Int.MAX_VALUE.toDouble() && floor(number) == number) {
                    "Viewport coordinates must be nonnegative integer pixels"
                }
            else require(number in -1000000.0..1000000.0) { "World coordinates exceed bounds" }
            val result = if (snap != null) floor(number) + 0.5 else number
            require(space != "world" || result in -1000000.0..1000000.0) {
                "Snapped world coordinate exceeds bounds"
            }
            return result
        }
        return InputPoint(coordinate("x"), coordinate("y"))
    }
    val from = point(if (moving) value.getValue("from").jsonObject else value)
    return InputPath(
        space,
        from,
        if (moving) point(value.getValue("to").jsonObject) else from,
        snap != null,
    )
}

internal fun parseInputSequence(args: JsonObject): InputSequenceRequest {
    require(args.keys.all { it in setOf("timeline", "stop_previous") }) { "Unknown input argument" }
    val timeline = args.getValue("timeline").jsonArray
    require(timeline.size <= MAX_INPUT_ENTRIES) { "Input timeline exceeds 256 entries" }
    val entries =
        timeline.map { item ->
            val entry = item.jsonObject
            val device = entry.getValue("device").stringArgument()
            val actions =
                listOf("key", "button", "position", "motion", "wheel").filter { it in entry }
            require(actions.size == 1) { "Each timeline entry requires exactly one action" }
            val action = actions.single()
            require(
                entry.keys.all {
                    it in setOf("device", action, "tick", "start_tick", "per_point_ticks")
                }
            ) {
                "Unknown timeline entry field"
            }
            val control =
                when {
                    device == "keyboard" && action == "key" -> {
                        val key = entry.getValue("key").stringArgument()
                        require(
                            key.length in 1..64 &&
                                key.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }
                        ) {
                            "Use uppercase key names from input_bindings"
                        }
                        InputControl.Keyboard(key)
                    }
                    device == "mouse" && action == "button" -> {
                        val button = entry.getValue("button").stringArgument()
                        require(
                            button in listOf("left", "right", "middle", "button_4", "button_5")
                        ) {
                            "Unsupported mouse button"
                        }
                        InputControl.MouseButton(button)
                    }
                    device == "mouse" && action in setOf("position", "motion") ->
                        InputControl.Pointer(
                            inputPath(entry.getValue(action).jsonObject, action == "motion"),
                            action == "motion",
                        )
                    device == "mouse" && action == "wheel" -> {
                        val direction = entry.getValue("wheel").stringArgument()
                        require(direction in setOf("up", "down")) { "wheel must be up or down" }
                        InputControl.Wheel(direction)
                    }
                    else -> error("Use keyboard key or mouse button/position/motion/wheel")
                }
            fun integer(name: String, default: Long? = null): Long {
                val raw =
                    entry[name]?.jsonPrimitive
                        ?: return requireNotNull(default) { "$name is required" }
                require(!raw.isString) { "$name must be an integer" }
                return requireNotNull(raw.longOrNull) { "$name must be an integer" }
            }
            if ("per_point_ticks" in entry) {
                require(control is InputControl.Pointer && control.moving && "tick" !in entry) {
                    "per_point_ticks requires motion and cannot be combined with tick"
                }
                val dwell = integer("per_point_ticks")
                val start = integer("start_tick", 0)
                require(dwell in 1..MAX_INPUT_TICK && start in 0..MAX_INPUT_TICK) {
                    "Invalid dwell or start tick"
                }
                val duration = control.path.pointCount * dwell
                require(duration <= MAX_INPUT_TICK + 1 - start) {
                    "Motion ends beyond the maximum relative tick"
                }
                InputEntry(control, listOf(InputInterval(start, start + duration - 1)), dwell)
            } else {
                require("start_tick" !in entry) { "start_tick requires per_point_ticks" }
                InputEntry(control, parseInputIntervals(entry.getValue("tick").stringArgument()))
            }
        }
    return InputSequenceRequest(entries, args["stop_previous"]?.booleanArgument() ?: false)
}
