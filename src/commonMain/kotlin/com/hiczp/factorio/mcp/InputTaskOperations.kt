package com.hiczp.factorio.mcp

/** Adapter values, not game object layouts. Mouse codes identify the shared logical button order. */
internal data class InputTaskButton(val device: UInt, val code: UInt)

internal data class InputTaskOperation(
    val ticks: UInt,
    val buttons: List<InputTaskButton>,
    val position: InputPosition?,
    val wheel: Int,
    val motion: List<InputMotion>,
)

/** Resolve a complete candidate before native admission can replace another task. */
internal fun resolveInputTaskOperations(
    request: InputSequenceRequest,
    keys: (List<String>) -> List<UInt>,
): List<InputTaskOperation> {
    require(request.operations.size <= 256)
    return request.operations.map { operation ->
        require(operation.ticks in 1..4294967295L)
        require(operation.controls.size <= 8) { "A combination permits at most eight controls" }
        val keyboard = operation.controls.filterIsInstance<InputControl.Keyboard>().map { it.key }
        val resolved = keys(keyboard)
        require(resolved.size == keyboard.size) { "Keyboard resolver returned a different number of keys" }
        val codes = resolved.iterator()
        val buttons = mutableListOf<InputTaskButton>()
        var position: InputPosition? = null
        var wheel = 0
        var motion = emptyList<InputMotion>()
        operation.controls.forEach { control ->
            val button = when (control) {
                is InputControl.Keyboard -> InputTaskButton(0u, codes.next())
                is InputControl.Mouse -> {
                    require(control.button != null || control.position != null ||
                            control.wheel != null || control.motion.isNotEmpty()) {
                        "Mouse control requires a button, position, wheel or motion"
                    }
                    if (control.motion.isNotEmpty()) {
                        require(motion.isEmpty()) { "Duplicate mouse motion path" }
                        validateInputMotion(control.motion, operation.ticks)
                        motion = control.motion
                    }
                    control.wheel?.let {
                        require(wheel == 0) { "Duplicate wheel event" }
                        wheel = when (it) {
                            "up" -> 1
                            "down" -> -1
                            else -> error("Unsupported wheel direction")
                        }
                    }
                    control.position?.let {
                        require(it.x >= 0 && it.y >= 0) { "Viewport coordinates must be nonnegative" }
                        require(position == null || position == it) { "Conflicting mouse positions" }
                        position = it
                    }
                    control.button?.let {
                        val code = listOf("left", "right", "middle", "button_4", "button_5").indexOf(it)
                        require(code >= 0) { "Unsupported mouse button" }
                        InputTaskButton(1u, (code + 1).toUInt())
                    }
                }
            }
            if (button != null) {
                require(buttons.size < 8)
                require(button.code != 0u) { "Invalid native input button" }
                require(button !in buttons) { "Duplicate native input button" }
                buttons += button
            }
        }
        InputTaskOperation(operation.ticks.toUInt(), buttons, position, wheel, motion)
    }
}
