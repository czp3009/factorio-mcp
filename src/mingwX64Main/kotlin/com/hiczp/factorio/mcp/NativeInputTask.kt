@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.*
import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import platform.windows.*
import kotlin.uuid.Uuid

/** The resident retains its own mapping handle until the admitted sequence releases its inputs. */
internal class NativeInputTask(
    pid: UInt,
    request: InputSequenceRequest,
    keys: (List<String>) -> List<UInt>,
    private val alive: () -> Boolean,
    private val unmap: (COpaquePointer) -> Boolean = { UnmapViewOfFile(it) != 0 },
    private val closeMapping: (HANDLE) -> Boolean = { CloseHandle(it) != 0 },
) : GameInputTask {
    val name = "Local\\factorio-mcp-input-$pid-${Uuid.random()}"
    private var mapping: HANDLE? = null
    private var view: CPointer<FmInputTask>? = null
    private var result: InputSequenceResult? = null

    init {
        try {
            mapping =
                checkNotNull(
                    CreateFileMappingW(
                        INVALID_HANDLE_VALUE,
                        null,
                        PAGE_READWRITE.toUInt(),
                        0u,
                        sizeOf<FmInputTask>().toUInt(),
                        name,
                    )
                ) {
                    "Cannot create input task mapping"
                }
            check(GetLastError() != ERROR_ALREADY_EXISTS.toUInt()) {
                "Input task identity collision"
            }
            val task =
                checkNotNull(
                    MapViewOfFile(
                        mapping,
                        FILE_MAP_ALL_ACCESS.toUInt(),
                        0u,
                        0u,
                        sizeOf<FmInputTask>().toULong(),
                    )
                        ?.reinterpret<FmInputTask>()
                ) {
                    "Cannot map input task"
                }
            view = task
            task.pointed.ownerPid = GetCurrentProcessId()
            task.pointed.stopPrevious = if (request.stopPrevious) 1u else 0u
            require(request.operations.size <= FM_MAX_INPUT_STEPS)
            task.pointed.count = request.operations.size.toUInt()
            request.operations.forEachIndexed { index, operation ->
                val row = task.pointed.operations[index]
                require(operation.ticks in 1..4294967295L)
                row.ticks = operation.ticks.toUInt()
                val resolved =
                    keys(
                        operation.controls.filterIsInstance<InputControl.Keyboard>().map {
                            it.key
                        }
                    )
                        .iterator()
                var count = 0
                operation.controls.forEach { control ->
                    val button =
                        when (control) {
                            is InputControl.Keyboard -> 0u to resolved.next()
                            is InputControl.Mouse -> {
                                if (control.motion.isNotEmpty()) {
                                    require(row.motionCount == 0u) { "Duplicate mouse motion path" }
                                    validateInputMotion(control.motion, operation.ticks)
                                    require(control.motion.size <= FM_MAX_INPUT_MOTION)
                                    row.motionCount = control.motion.size.toUInt()
                                    control.motion.forEachIndexed { pointIndex, point ->
                                        row.motion[pointIndex].tick = point.tick.toUInt()
                                        row.motion[pointIndex].x = point.position.x
                                        row.motion[pointIndex].y = point.position.y
                                    }
                                }
                                control.wheel?.let {
                                    require(row.wheel == 0) { "Duplicate wheel event" }
                                    row.wheel =
                                        when (it) {
                                            "up" -> 1
                                            "down" -> -1
                                            else -> error("Unsupported wheel direction")
                                        }
                                }
                                control.position?.let {
                                    row.hasPosition = 1u
                                    row.x = it.x
                                    row.y = it.y
                                }
                                control.button?.let { button ->
                                    val code =
                                        listOf("left", "right", "middle", "button_4", "button_5")
                                            .indexOf(button)
                                    require(code >= 0) { "Unsupported mouse button" }
                                    1u to (code + 1).toUInt()
                                }
                            }
                        }
                    if (button != null) {
                        require(count < FM_MAX_INPUT_BUTTONS)
                        row.buttons[count].device = button.first
                        row.buttons[count++].code = button.second
                    }
                }
                row.count = count.toUInt()
            }
        } catch (failure: Throwable) {
            try {
                dispose()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun terminal(task: CPointer<FmInputTask>): InputSequenceResult? {
        val state = fm_input_state(task)
        check(state in 0..3) { "Invalid resident input task state" }
        if (state < 2) return null
        return InputSequenceResult(
            state == 2,
            fm_input_completed(task),
            fm_input_ticks(task),
            task.pointed.reason.toKString().takeIf { it.isNotEmpty() },
        )
    }

    override suspend fun awaitResult(): InputSequenceResult {
        result?.let {
            return it
        }
        val task = checkNotNull(view) { "Input task is closed" }
        while (true) {
            terminal(task)?.let {
                result = it
                return it
            }
            if (!alive()) {
                return InputSequenceResult(
                    false,
                    fm_input_completed(task),
                    fm_input_ticks(task),
                    "Factorio process exited; progress is the last published observation",
                )
                    .also { result = it }
            }
            delay(10)
        }
    }

    override suspend fun close(): InputSequenceResult {
        val task = view
        if (result == null && task != null) {
            // A zero state after admission has settled means the resident rejected the request.
            if (fm_input_state(task) == 0)
                result = InputSequenceResult(false, 0, 0, "Input was not admitted")
            else {
                fm_input_cancel(task)
                awaitResult()
            }
        }
        val completion = checkNotNull(result)
        dispose()
        return completion
    }

    private fun dispose() {
        view?.let {
            check(unmap(it)) { "Cannot unmap input task: ${GetLastError()}" }
            view = null
        }
        mapping?.let {
            check(closeMapping(it)) { "Cannot close input task mapping: ${GetLastError()}" }
            mapping = null
        }
    }
}
