@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.*
import kotlinx.cinterop.*
import platform.posix.getpid

/** The resident owns an independent mapping until the admitted sequence has released its inputs. */
internal class NativeInputTask(
    pid: Int,
    request: InputSequenceRequest,
    keys: (List<String>) -> List<UInt>,
    alive: () -> Boolean,
) : GameInputTask {
    private var mapping: SharedMapping? = null
    val descriptorNumber: Int
        get() = checkNotNull(mapping) { "Input task is closed" }.descriptorNumber

    private fun task(): CPointer<FmLinuxInputTask> =
        checkNotNull(mapping) { "Input task is closed" }.memory.reinterpret()

    private val lifecycle = InputTaskLifecycle(
        progress = {
            val task = task()
            val state = fm_linux_input_state(task).toInt()
            val reason = if (state >= 2) {
                val bytes = task.pointed.reason.readBytes(512)
                val length = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
                bytes.decodeToString(endIndex = length).takeIf { it.isNotEmpty() }
            } else null
            InputTaskProgress(state, fm_linux_input_completed(task).toInt(),
                fm_linux_input_ticks(task).toLong(), reason)
        },
        alive = alive,
        cancel = { fm_linux_input_cancel(task()) },
        release = { dispose() },
    )

    init {
        require(pid > 0)
        val operations = resolveInputTaskOperations(request, keys)
        require(operations.size <= FM_LINUX_INPUT_STEPS)
        try {
            mapping = SharedMapping.create(sizeOf<FmLinuxInputTask>())
            val wire = task().pointed
            wire.ownerPid = getpid().toUInt()
            wire.targetPid = pid.toUInt()
            wire.stopPrevious = if (request.stopPrevious) 1u else 0u
            wire.count = operations.size.toUInt()
            operations.forEachIndexed { index, operation ->
                val row = wire.operations[index]
                row.ticks = operation.ticks
                row.wheel = operation.wheel
                operation.position?.let {
                    row.hasPosition = 1u
                    row.x = it.x
                    row.y = it.y
                }
                require(operation.motion.size <= FM_LINUX_INPUT_MOTION)
                row.motionCount = operation.motion.size.toUInt()
                operation.motion.forEachIndexed { pointIndex, point ->
                    row.motion[pointIndex].tick = point.tick.toUInt()
                    row.motion[pointIndex].x = point.position.x
                    row.motion[pointIndex].y = point.position.y
                }
                require(operation.buttons.size <= FM_LINUX_INPUT_BUTTONS)
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
        mapping?.close()
        mapping = null
    }
}
