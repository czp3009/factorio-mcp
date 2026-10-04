package com.hiczp.factorio.mcp

import kotlin.math.floor

/** Immutable timeline adapter values; these are not game object layouts. */
internal data class InputTaskEntry(
    val kind: UInt,
    val code: UInt,
    val intervals: List<InputInterval>,
    val path: InputPath? = null,
    val perPointTicks: UInt = 0u,
)

/** Resolve the complete candidate before native admission can replace another task. */
internal fun resolveInputTaskEntries(
    request: InputSequenceRequest,
    keys: (List<String>) -> List<UInt>,
): List<InputTaskEntry> {
    require(request.timeline.size <= MAX_INPUT_ENTRIES)
    val names = request.timeline.mapNotNull { (it.control as? InputControl.Keyboard)?.key }
    val resolved = keys(names)
    require(resolved.size == names.size && resolved.all { it != 0u }) {
        "Invalid keyboard resolution"
    }
    val codes = resolved.iterator()
    return request.timeline.map { entry ->
        require(entry.intervals.size in 1..MAX_INPUT_INTERVALS)
        entry.intervals.forEach {
            require(it.first in 0..MAX_INPUT_TICK && it.last in it.first..MAX_INPUT_TICK)
        }
        require(entry.perPointTicks in 0..MAX_INPUT_TICK)
        if (entry.perPointTicks != 0L) {
            val pointer = entry.control as? InputControl.Pointer
            require(pointer != null && pointer.moving && entry.intervals.size == 1)
            val interval = entry.intervals.single()
            require(
                interval.last - interval.first + 1 == pointer.path.pointCount * entry.perPointTicks
            )
        }
        when (val control = entry.control) {
            is InputControl.Keyboard -> InputTaskEntry(0u, codes.next(), entry.intervals)
            is InputControl.MouseButton -> {
                val code =
                    listOf("left", "right", "middle", "button_4", "button_5")
                        .indexOf(control.button) + 1
                require(code in 1..5)
                InputTaskEntry(1u, code.toUInt(), entry.intervals)
            }
            is InputControl.Pointer -> {
                val path = control.path
                require(
                    path.space in setOf("world", "viewport") &&
                        (!path.tileCenters || path.space == "world")
                )
                require(control.moving || path.from == path.to)
                for (point in listOf(path.from, path.to)) {
                    for (coordinate in listOf(point.x, point.y)) {
                        require(coordinate.isFinite())
                        if (path.space == "world") {
                            require(coordinate in -1000000.0..1000000.0)
                            require(!path.tileCenters || coordinate == floor(coordinate) + 0.5)
                        } else {
                            require(
                                coordinate in 0.0..Int.MAX_VALUE.toDouble() &&
                                    floor(coordinate) == coordinate
                            )
                        }
                    }
                }
                InputTaskEntry(
                    if (control.moving) 3u else 2u,
                    0u,
                    entry.intervals,
                    path,
                    entry.perPointTicks.toUInt(),
                )
            }
            is InputControl.Wheel -> {
                require(control.direction in setOf("up", "down"))
                InputTaskEntry(4u, if (control.direction == "up") 1u else 2u, entry.intervals)
            }
        }
    }
}
