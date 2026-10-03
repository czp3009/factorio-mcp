@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlinx.io.*
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.stderr
import platform.windows.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
private val stopping = AtomicInt(0)

@OptIn(ExperimentalAtomicApi::class)
internal actual object Platform {
    actual val cancelled: Boolean
        get() = stopping.load() != 0

    actual fun initializeCancellation() {
        SetConsoleCtrlHandler(
            staticCFunction { _: UInt ->
                stopping.store(1)
                1
            },
            1,
        )
    }

    actual fun writeError(message: String) {
        fputs("$message\n", stderr)
        fflush(stderr)
    }

    actual fun findProcesses(name: String): List<Int> = discoverProcesses(name)

    actual fun standardInput(): Source =
        object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                require(byteCount >= 0)
                if (byteCount == 0L) return 0
                val bytes = ByteArray(minOf(byteCount, 8192).toInt())
                val input = GetStdHandle(STD_INPUT_HANDLE)
                if (GetFileType(input) == FILE_TYPE_PIPE.toUInt()) {
                    while (!cancelled) {
                        val available = memScoped {
                            val count = alloc<UIntVar>()
                            if (PeekNamedPipe(input, null, 0u, null, count.ptr, null) == 0) -1L
                            else count.value.toLong()
                        }
                        if (available < 0) return -1
                        if (available > 0) break
                        Sleep(10u)
                    }
                    if (cancelled) return -1
                }
                val count = memScoped {
                    val read = alloc<UIntVar>()
                    val success =
                        bytes.usePinned {
                            ReadFile(
                                input,
                                it.addressOf(0),
                                bytes.size.toUInt(),
                                read.ptr,
                                null,
                            )
                        }
                    if (success == 0) {
                        if (GetLastError() == ERROR_BROKEN_PIPE.toUInt()) 0
                        else error("Cannot read stdin")
                    } else read.value.toInt()
                }
                if (count == 0) return -1
                sink.write(bytes, 0, count)
                return count.toLong()
            }

            override fun close() = Unit
        }
            .buffered()

    actual fun standardOutput(): Sink =
        object : RawSink {
            override fun write(source: Buffer, byteCount: Long) {
                var remaining = byteCount
                while (remaining > 0) {
                    val bytes = source.readByteArray(minOf(remaining, 8192).toInt())
                    var offset = 0
                    while (offset < bytes.size) memScoped {
                        val written = alloc<UIntVar>()
                        check(
                            bytes.usePinned {
                                WriteFile(
                                    GetStdHandle(STD_OUTPUT_HANDLE),
                                    it.addressOf(offset),
                                    (bytes.size - offset).toUInt(),
                                    written.ptr,
                                    null,
                                )
                            } != 0 && written.value > 0u
                        ) {
                            "Cannot write stdout"
                        }
                        offset += written.value.toInt()
                    }
                    remaining -= bytes.size
                }
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
            .buffered()
}

internal actual class GameProcess actual constructor(private val pid: Int) : GameConnection {
    // Windows mutex ownership is thread-affine, even across coroutine suspensions.
    private val dispatcher = newSingleThreadContext("factorio-mcp-native")
    private var client: ResidentConnection? = null

    actual override suspend fun execute(
        operation: Int,
        limit: Int,
        action: UiAction?,
    ): GameSnapshot =
        withContext(dispatcher) {
            val target = client ?: ResidentConnection(pid.toUInt()).also { client = it }
            target.execute(operation, limit, action)
        }

    actual override suspend fun close() {
        withContext(dispatcher) {
            client?.close()
            client = null
        }
        dispatcher.close()
    }

    actual override suspend fun isAlive(): Boolean =
        withContext(dispatcher) { client?.alive() ?: false }

    actual override suspend fun query(query: WorldQuery): GameSnapshot =
        withContext(dispatcher) { checkNotNull(client).execute(8, 4096, null, query) }

    actual override suspend fun sendChat(text: String): GameSnapshot =
        withContext(dispatcher) { checkNotNull(client).execute(11, 4096, null, chatText = validateChatMessage(text)) }

    actual override suspend fun beginInput(request: InputSequenceRequest): GameInputTask {
        var admitted: GameInputTask? = null
        try {
            return withContext(dispatcher) {
                val task = checkNotNull(client).beginInput(request)
                admitted = task
                object : GameInputTask {
                    override suspend fun awaitResult() =
                        withContext(dispatcher) { task.awaitResult() }

                    override suspend fun close() = withContext(dispatcher) { task.close() }
                }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + dispatcher) {
                try {
                    admitted?.close()
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }

    actual override suspend fun awaitExit() {
        withContext(dispatcher) { checkNotNull(client).awaitExit() }
    }
}
