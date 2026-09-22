@file:OptIn(ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import factorio.bridge.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.io.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.*
import kotlin.system.exitProcess as terminateProcess

internal actual object Platform {
    actual fun standardInput(): Source = object : RawSource {
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            require(byteCount >= 0)
            if (byteCount == 0L) return 0
            val bytes = ByteArray(minOf(byteCount, 8192).toInt())
            // A FILE-based fread can wait for an entire buffer on a pipe. poll/read
            // returns each JSON-RPC frame promptly and allows signal-driven shutdown.
            while (!cancelled) {
                val ready = memScoped {
                    val descriptor = alloc<pollfd>()
                    descriptor.fd = STDIN_FILENO
                    descriptor.events = POLLIN.toShort()
                    poll(descriptor.ptr, 1u, 100)
                }
                if (ready < 0 && errno == EINTR) continue
                check(ready >= 0) { "Cannot poll stdin" }
                if (ready == 0) continue
                val count = bytes.usePinned { read(STDIN_FILENO, it.addressOf(0), bytes.size.toULong()) }
                if (count < 0 && errno == EINTR) continue
                check(count >= 0) { "Cannot read stdin" }
                if (count == 0L) return -1
                sink.write(bytes, 0, count.toInt())
                return count
            }
            return -1
        }

        override fun close() = Unit
    }.buffered()

    actual fun standardOutput(): Sink = SystemFileSystem.sink(Path("/dev/stdout")).buffered()

    actual fun findProcesses(name: String): List<Int> {
        require(name.isNotEmpty() && '/' !in name && '\u0000' !in name) { "Expected a process name" }
        return SystemFileSystem.list(Path("/proc")).mapNotNull { path ->
            val pid = path.name.toIntOrNull() ?: return@mapNotNull null
            val comm = runCatching {
                SystemFileSystem.source(Path(path, "comm")).buffered().use { it.readString().trim() }
            }.getOrNull()
            if (comm == name) pid else null
        }.sorted()
    }

    actual fun initializeCancellation() = fm_signals()

    actual val cancelled: Boolean
        get() = fm_cancelled() != 0

    actual fun writeError(message: String) {
        fputs("$message\n", stderr)
    }

    actual fun exitProcess(code: Int): Nothing = terminateProcess(code)
}

internal actual class GameProcess actual constructor(private val pid: Int) {
    private var resident: ResidentClient? = null
    private var attachPhase: Int? = null

    actual suspend fun connectResident(timeoutMillis: Int) {
        check(observeResident(timeoutMillis, false)) { "Status observer is absent; call status for this PID first" }
        val connection = resident ?: error("Call status first")
        val state = try {
            connection.state(minOf(timeoutMillis, 500))
        } catch (_: TimeoutCancellationException) {
            error("Cannot confirm the current world state; call status when the game responds")
        }
        check(state.inGame && !state.mainMenu) { "An active single-player or multiplayer world is required; call status after entering a game" }
        if (state.ready) return
        if (!state.actionsInstalled) {
            val enabled = awaitNative {
                fm_resident_enable(pid, state.descriptor, minOf(timeoutMillis, 150)).also {
                    check(it >= 0) { nativeError("Cannot enable world adapters") }
                }
            }
            if (enabled == 0) return
        }
        try {
            connection.bindWorld(state.generation, minOf(timeoutMillis, 500))
        } catch (_: TimeoutCancellationException) {
            // An admitted binding request is idempotent; status reports its current readiness.
        }
    }

    private suspend fun observeResident(timeoutMillis: Int, install: Boolean): Boolean {
        if (resident != null) return true
        val fd = awaitNative {
            memScoped {
                val phase = alloc<IntVar>()
                fm_resident_open(
                    pid,
                    residentBootstrapScript(FR_QUEUE_CAPACITY.toInt()),
                    minOf(timeoutMillis, 150),
                    phase.ptr,
                    if (install) 1 else 0
                ).also {
                    check(it != -1) { nativeError("Cannot install resident") }
                    attachPhase = if (it == -2) phase.value else null
                }
            }
        }
        if (fd < 0) return false
        resident = ResidentClient(LinuxResidentWire(fd), pid)
        return true
    }

    private suspend fun residentStatus(connection: ResidentClient, timeoutMillis: Int): String {
        return try {
            connection.status(timeoutMillis)
        } catch (_: TimeoutCancellationException) {
            residentUnavailableStatus(pid)
        }
    }

    actual suspend fun submitTask(description: String, timeoutMillis: Int): String =
        (resident ?: error("Resident is not connected")).submit(description, timeoutMillis)

    actual fun outputPath(filename: String): String {
        val directories = SystemFileSystem.list(Path("/proc/$pid/fd")).mapNotNull { fd ->
            val target = runCatching { SystemFileSystem.resolve(fd) }.getOrNull()
            if (target?.name == "factorio-current.log") target.parent else null
        }.distinct()
        check(directories.size == 1) { "Cannot determine game write-data directory from its open log file" }
        return Path(directories.single(), "script-output", filename).toString()
    }

    private var lock = fm_lock(pid).also {
        if (it == -2) throw UnrecognizedTarget(nativeError("Cannot recognize target debug information"))
        check(it >= 0) { nativeError("Cannot acquire session lock") }
    }

    actual suspend fun status(timeoutMillis: Int): String {
        check(lock >= 0) { "Game process session is closed" }
        observeResident(timeoutMillis, true)
        resident?.let { return residentStatus(it, minOf(timeoutMillis, 500)) }
        return pendingObserverStatus(pid, attachPhase == FmAttachPhase.FM_LOADING.value.toInt())
    }

    actual suspend fun evaluate(source: String, timeoutMillis: Int): String = awaitNative {
        check(lock >= 0) { "Game process session is closed" }
        fm_eval(pid, source, timeoutMillis)?.toKString() ?: error(nativeError("Native bridge failed"))
    }

    actual suspend fun diagnosticStatus(timeoutMillis: Int): String = awaitNative {
        memScoped {
            val state = alloc<FmDiagnosticStatus>()
            check(fm_status(pid, timeoutMillis, state.ptr) != null) { nativeError("Cannot read diagnostic state") }
            diagnosticStatusJson(pid, state.main_menu != 0, state.build_id!!.toKString(), "developer ELF + DWARF")
        }
    }

    actual suspend fun close() {
        resident?.close()
        resident = null
        if (lock >= 0) {
            fm_unlock(lock)
            lock = -1
        }
    }
}

private fun nativeError(fallback: String): String = fm_error()?.toKString() ?: fallback

/** Cancellation can interrupt a rendezvous; executing native game code must return normally. */
private suspend fun <T> awaitNative(call: () -> T): T = coroutineScope {
    fm_cancel_wait(0)
    val execution = async(Dispatchers.IO) { call() }
    try {
        execution.await()
    } finally {
        // Keep the session locked until all ptrace state has been restored.
        withContext(NonCancellable) {
            if (!execution.isCompleted) fm_cancel_wait(1)
            execution.join()
            fm_cancel_wait(0)
        }
    }
}
