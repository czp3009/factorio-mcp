@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.*
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlinx.io.*
import platform.posix.*
import kotlin.concurrent.atomics.AtomicInt
import platform.posix.write as writeDescriptor

private val stopping = AtomicInt(0)

internal actual object Platform {
    actual val cancelled: Boolean
        get() = stopping.load() != 0

    actual fun initializeCancellation() {
        val handler = staticCFunction { _: Int -> stopping.store(1) }
        signal(SIGINT, handler)
        signal(SIGTERM, handler)
        signal(SIGPIPE, SIG_IGN)
    }

    actual fun writeError(message: String) {
        fputs("$message\n", stderr)
    }

    actual fun findProcesses(name: String): List<Int> = discoverProcesses(name)

    actual fun standardInput(): Source = object : RawSource {
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            require(byteCount >= 0)
            if (byteCount == 0L) return 0
            val bytes = ByteArray(minOf(byteCount, 8192).toInt())
            return memScoped {
                val descriptor = alloc<pollfd>()
                descriptor.fd = STDIN_FILENO
                descriptor.events = POLLIN.toShort()
                while (!cancelled) {
                    val available = poll(descriptor.ptr, 1u, 100)
                    if (available < 0 && errno == EINTR) continue
                    check(available >= 0) { "Cannot poll stdin (errno $errno)" }
                    if (available == 0) continue
                    val count = bytes.usePinned { read(STDIN_FILENO, it.addressOf(0), bytes.size.toULong()) }
                    if (count < 0 && errno == EINTR) continue
                    check(count >= 0) { "Cannot read stdin (errno $errno)" }
                    if (count == 0L) return@memScoped -1L
                    sink.write(bytes, 0, count.toInt())
                    return@memScoped count
                }
                -1L
            }
        }

        override fun close() = Unit
    }.buffered()

    actual fun standardOutput(): Sink = object : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            require(byteCount >= 0)
            var remaining = byteCount
            while (remaining > 0) {
                val bytes = source.readByteArray(minOf(remaining, 8192).toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val count = bytes.usePinned {
                        writeDescriptor(STDOUT_FILENO, it.addressOf(offset), (bytes.size - offset).toULong())
                    }
                    if (count < 0 && errno == EINTR) continue
                    check(count > 0) { "Cannot write stdout (errno $errno)" }
                    offset += count.toInt()
                }
                remaining -= bytes.size
            }
        }

        override fun flush() = Unit

        override fun close() = Unit
    }.buffered()
}

internal actual class GameProcess actual constructor(private val pid: Int) : GameConnection {
    private val dispatcher = newSingleThreadContext("factorio-mcp-native")
    private var client: ResidentConnection? = null

    actual override suspend fun execute(operation: Int, limit: Int, action: UiAction?): GameSnapshot =
        withContext(dispatcher) {
            val target = client ?: run {
                val directory = ProcessHandle(getpid()).use { it.executablePath().substringBeforeLast('/') }
                ResidentConnection(pid, "$directory/libfactorio_mcp_resident.so").also { client = it }
            }
            target.execute(operation, limit, action)
        }

    actual override suspend fun isAlive(): Boolean = withContext(dispatcher) { client?.alive() ?: false }

    actual override suspend fun query(query: WorldQuery): GameSnapshot =
        withContext(dispatcher) { checkNotNull(client).query(query) }

    actual override suspend fun sendChat(text: String): GameSnapshot =
        withContext(dispatcher) { checkNotNull(client).sendChat(text) }

    actual override suspend fun beginInput(request: InputSequenceRequest): GameInputTask = super.beginInput(request)

    actual override suspend fun awaitExit() = super.awaitExit()

    actual override suspend fun close() {
        withContext(dispatcher) {
            client?.close()
            client = null
        }
        dispatcher.close()
    }
}
