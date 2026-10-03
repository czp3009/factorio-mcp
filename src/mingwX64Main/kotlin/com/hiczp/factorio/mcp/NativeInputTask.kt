@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.*
import kotlinx.cinterop.*
import platform.windows.*
import kotlin.uuid.Uuid

/** The resident retains its own mapping handle until the admitted sequence releases its inputs. */
internal class NativeInputTask(
    pid: UInt,
    request: InputSequenceRequest,
    keys: (List<String>) -> List<UInt>,
    alive: () -> Boolean,
    private val unmap: (COpaquePointer) -> Boolean = { UnmapViewOfFile(it) != 0 },
    private val closeMapping: (HANDLE) -> Boolean = { CloseHandle(it) != 0 },
) : GameInputTask {
    val name = "Local\\factorio-mcp-input-$pid-${Uuid.random()}"
    private var mapping: HANDLE? = null
    private var view: CPointer<FmInputTask>? = null
    private val lifecycle = InputTaskLifecycle(
        progress = {
            val task = checkNotNull(view) { "Input task is closed" }
            val state = fm_input_state(task)
            InputTaskProgress(
                state, fm_input_completed(task), fm_input_ticks(task),
                if (state >= 2) task.pointed.reason.toKString().takeIf { it.isNotEmpty() } else null,
            )
        },
        alive = alive,
        cancel = { fm_input_cancel(checkNotNull(view)) },
        release = { dispose() },
    )

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
            val operations = resolveInputTaskOperations(request, keys)
            require(operations.size <= FM_MAX_INPUT_STEPS)
            task.pointed.count = operations.size.toUInt()
            operations.forEachIndexed { index, operation ->
                val row = task.pointed.operations[index]
                row.ticks = operation.ticks
                row.wheel = operation.wheel
                operation.position?.let {
                    row.hasPosition = 1u
                    row.x = it.x
                    row.y = it.y
                }
                require(operation.motion.size <= FM_MAX_INPUT_MOTION)
                row.motionCount = operation.motion.size.toUInt()
                operation.motion.forEachIndexed { pointIndex, point ->
                    row.motion[pointIndex].tick = point.tick.toUInt()
                    row.motion[pointIndex].x = point.position.x
                    row.motion[pointIndex].y = point.position.y
                }
                require(operation.buttons.size <= FM_MAX_INPUT_BUTTONS)
                row.count = operation.buttons.size.toUInt()
                operation.buttons.forEachIndexed { buttonIndex, button ->
                    row.buttons[buttonIndex].device = button.device
                    row.buttons[buttonIndex].code = button.code
                }
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

    override suspend fun awaitResult() = lifecycle.awaitResult()

    override suspend fun close() = lifecycle.close()

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
