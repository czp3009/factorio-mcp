@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.*
import kotlin.uuid.Uuid
import kotlinx.cinterop.*
import platform.windows.*

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
    private val lifecycle =
        InputTaskLifecycle(
            progress = {
                val task = checkNotNull(view) { "Input task is closed" }
                val state = fm_input_state(task)
                InputTaskProgress(
                    state,
                    fm_input_completed(task),
                    fm_input_ticks(task),
                    if (state >= 2) task.pointed.reason.toKString().takeIf { it.isNotEmpty() }
                    else null,
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
            val entries = resolveInputTaskEntries(request, keys)
            require(entries.size <= MAX_INPUT_ENTRIES)
            task.pointed.count = entries.size.toUInt()
            entries.forEachIndexed { index, entry ->
                val row = task.pointed.entries[index]
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
