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

    private val lifecycle =
        InputTaskLifecycle(
            progress = {
                val task = task()
                val state = fm_linux_input_state(task).toInt()
                val reason =
                    if (state >= 2) {
                        val bytes = task.pointed.reason.readBytes(512)
                        val length = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
                        bytes.decodeToString(endIndex = length).takeIf { it.isNotEmpty() }
                    } else null
                InputTaskProgress(
                    state,
                    fm_linux_input_completed(task).toInt(),
                    fm_linux_input_ticks(task).toLong(),
                    reason,
                )
            },
            alive = alive,
            cancel = { fm_linux_input_cancel(task()) },
            release = { dispose() },
        )

    init {
        require(pid > 0)
        val entries = resolveInputTaskEntries(request, keys)
        require(entries.size <= MAX_INPUT_ENTRIES)
        try {
            mapping = SharedMapping.create(sizeOf<FmLinuxInputTask>())
            val wire = task().pointed
            wire.ownerPid = getpid().toUInt()
            wire.targetPid = pid.toUInt()
            wire.stopPrevious = if (request.stopPrevious) 1u else 0u
            wire.count = entries.size.toUInt()
            entries.forEachIndexed { index, entry ->
                val row = wire.entries[index]
                row.kind = entry.kind
                row.code = entry.code
                row.perPointTicks = entry.perPointTicks
                row.intervalCount = entry.intervals.size.toUInt()
                entry.intervals.forEachIndexed { intervalIndex, interval ->
                    row.intervals[intervalIndex].first = interval.first.toUInt()
                    row.intervals[intervalIndex].last = interval.last.toUInt()
                }
                entry.path?.let {
                    row.space = if (it.space == "world") 1u else 0u
                    row.tileCenters = if (it.tileCenters) 1u else 0u
                    row.fromX = it.from.x
                    row.fromY = it.from.y
                    row.toX = it.to.x
                    row.toY = it.to.y
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
