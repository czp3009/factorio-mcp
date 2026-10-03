@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One leased resident command. An abandoned command is reconciled, never overwritten or replayed. */
internal class ResidentChannel(
    private val process: ProcessHandle,
    mapping: SharedMapping,
) {
    private val shared = mapping.memory.reinterpret<FmLinuxShared>()
    private val mutex = Mutex()

    data class Result(val code: Int, val frame: ULong, val state: String = "unknown", val paused: Boolean? = null)

    init {
        check(mapping.tryAcquireLease()) { "Another factorio-mcp attachment owns the resident" }
    }

    val attached: Boolean
        get() = fm_ipc_load(fm_linux_attached_word(shared)) != 0u

    val actionOwned: Boolean
        get() = fm_ipc_load(fm_linux_action_word(shared)) != 0u

    private fun state(): UInt = fm_ipc_load(fm_linux_command_word(shared)).also {
        check(it in FM_LINUX_IDLE..FM_LINUX_COMPLETE) { "Invalid resident command state" }
    }

    /** Must run before supplying a new payload, including after obtaining a lease from a dead MCP. */
    suspend fun reconcile(): Result? = mutex.withLock {
        if (state() == FM_LINUX_IDLE) return@withLock null
        withContext(NonCancellable) {
            fm_ipc_store(fm_linux_cancel_word(shared), 1u)
            awaitResult()
        }
    }

    suspend fun execute(operation: UInt): Result = execute(operation, {}, { _, result -> result })

    /** Keep payload preparation and result copying inside the same command ownership interval. */
    suspend fun <T> execute(
        operation: UInt,
        prepare: (FmLinuxShared) -> Unit,
        read: (FmLinuxShared, Result) -> T,
    ): T = mutex.withLock {
        currentCoroutineContext().ensureActive()
        check(process.alive()) { "Factorio exited" }
        check(attached) { "Resident has no active frontend hook" }
        check(state() == FM_LINUX_IDLE) { "Reconcile the previous resident command before submitting another" }
        check(!actionOwned || operation == FM_LINUX_CLEANUP || operation == FM_LINUX_DETACH) {
            "Previous action cleanup is incomplete; retry detach"
        }
        prepare(shared.pointed)
        shared.pointed.operation = operation
        fm_ipc_store(fm_linux_cancel_word(shared), 0u)
        check(fm_ipc_exchange_if(fm_linux_command_word(shared), FM_LINUX_IDLE, FM_LINUX_PENDING) != 0) {
            "Resident command admission lost ownership"
        }
        val result = try {
            awaitResult()
        } catch (canceled: CancellationException) {
            // Preserve the terminal native result while the owning tool reports its cancellation/cleanup outcome.
            withContext(NonCancellable) {
                fm_ipc_store(fm_linux_cancel_word(shared), 1u)
                awaitResult()
            }
        }
        read(shared.pointed, result)
    }

    private suspend fun awaitResult(): Result {
        while (true) {
            check(process.alive()) { "Factorio exited during an admitted resident command" }
            when (state()) {
                FM_LINUX_COMPLETE -> {
                    val observation = shared.pointed.gameState
                    val result = Result(shared.pointed.result, shared.pointed.resultFrame,
                        when (observation.state) {
                            1u -> "main_menu"
                            2u -> "in_game"
                            3u -> "loading"
                            4u -> "paused"
                            else -> "unknown"
                        }, if (observation.paused < 0) null else observation.paused != 0)
                    fm_ipc_store(fm_linux_command_word(shared), FM_LINUX_IDLE)
                    return result
                }

                FM_LINUX_PENDING, FM_LINUX_RUNNING -> delay(1)
                else -> error("Resident lost the admitted command")
            }
        }
    }
}
