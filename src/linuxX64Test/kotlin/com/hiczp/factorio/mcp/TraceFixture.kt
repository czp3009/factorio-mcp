@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.fm_wait_exit_code
import com.hiczp.factorio.mcp.linuxbridge.fm_wait_exited
import com.hiczp.factorio.mcp.testbridge.fm_fixture_spawn
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.test.assertEquals

/** Owns only its generated native child, never a game or an external MCP endpoint. */
internal class TraceFixture(name: String = "trace_fixture") : AutoCloseable {
    val pid: Int
    private val input: Int
    private val output: Int
    private var reaped = false

    init {
        memScoped {
            require(name == "trace_fixture" || name == "ipc_fixture")
            val path = "${checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()}/$name".cstr.getPointer(this)
            val incoming = allocArray<IntVar>(2)
            val outgoing = allocArray<IntVar>(2)
            check(pipe(incoming) == 0)
            if (pipe(outgoing) != 0) {
                close(incoming[0])
                close(incoming[1])
                error("Cannot create native test pipes")
            }
            pid = fm_fixture_spawn(path, incoming[0], incoming[1], outgoing[0], outgoing[1])
            close(incoming[0])
            close(outgoing[1])
            input = incoming[1]
            output = outgoing[0]
            if (pid < 0) {
                close(input)
                close(output)
                error("Cannot launch native trace fixture: POSIX error ${-pid}")
            }
        }
        try {
            assertEquals('r', receive())
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun command(command: Char) = memScoped {
        val byte = alloc<ByteVar>()
        byte.value = command.code.toByte()
        check(write(input, byte.ptr, 1u) == 1L) { "Cannot write native fixture command" }
    }

    fun receive(): Char = memScoped {
        val descriptor = alloc<pollfd>()
        descriptor.fd = output
        descriptor.events = POLLIN.toShort()
        descriptor.revents = 0
        var result: Int
        do {
            result = poll(descriptor.ptr, 1u, 5000)
        } while (result < 0 && errno == EINTR)
        check(result == 1 && descriptor.revents.toInt() and POLLIN != 0) { "Native fixture response watchdog expired" }
        val byte = alloc<ByteVar>()
        check(read(output, byte.ptr, 1u) == 1L)
        byte.value.toInt().toChar()
    }

    fun receiveLine(): String = buildString {
        repeat(256) {
            val next = receive()
            if (next == '\n') return@buildString
            append(next)
        }
        error("Native fixture line exceeds bound")
    }

    fun finish() = memScoped {
        command('x')
        val status = alloc<IntVar>()
        var result: Int
        do {
            result = waitpid(pid, status.ptr, 0)
        } while (result < 0 && errno == EINTR)
        check(result == pid)
        reaped = true
        check(fm_wait_exited(status.value) != 0)
        assertEquals(0, fm_wait_exit_code(status.value))
    }

    fun markReaped() {
        reaped = true
    }

    override fun close() {
        if (!reaped) {
            kill(pid, SIGKILL)
            memScoped {
                val status = alloc<IntVar>()
                while (waitpid(pid, status.ptr, 0) < 0 && errno == EINTR) {
                    // Retry only the wait for this owned test child.
                }
            }
            reaped = true
        }
        close(input)
        close(output)
    }
}
