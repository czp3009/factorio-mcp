@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class
)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FM_DEBUG_REGISTERS_OFFSET
import com.hiczp.factorio.mcp.linuxbridge.FM_DEBUG_REGISTER_WIDTH
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.linux.user_regs_struct
import platform.posix.*
import kotlin.test.*

class ThreadTraceTest {
    private fun fixtureTest(test: suspend (TraceFixture, ThreadTrace) -> Unit) = runBlocking {
        newSingleThreadContext("factorio-mcp-trace-test").use { dispatcher ->
            withContext(dispatcher) {
                TraceFixture().use { fixture ->
                    val trace = ThreadTrace(fixture.pid)
                    try {
                        withTimeout(10000) { test(fixture, trace) }
                    } finally {
                        trace.close()
                    }
                }
            }
        }
    }

    private suspend fun awaitStop(trace: ThreadTrace): ThreadTrace.Stop {
        while (true) {
            trace.pollStop()?.let { return it }
            check(!trace.hasExited) { "Trace fixture exited while waiting for a stop" }
            delay(1)
        }
    }

    private fun CValue<user_regs_struct>.bytes() = memScoped {
        getPointer(this).reinterpret<ByteVar>().readBytes(sizeOf<user_regs_struct>().toInt())
    }

    @Test
    fun preservesIntegerAndExtendedRegistersThroughInterruptAndDetach() = fixtureTest { fixture, trace ->
        trace.seize()
        assertEquals(ThreadTrace.StopKind.INTERRUPT, trace.interrupt()?.kind)
        val original = trace.registers()
        assertTrue(original.general.useContents { rip > 0u && rsp > 0u })
        try {
            val modified = original.copy(general = original.general.copy { rax = rax xor 0x12345678uL })
            trace.restore(modified)
            assertContentEquals(modified.general.bytes(), trace.registers().general.bytes())
        } finally {
            trace.restore(original)
        }
        val restored = trace.registers()
        assertContentEquals(original.general.bytes(), restored.general.bytes())
        assertContentEquals(original.extended, restored.extended)
        trace.close()
        trace.close()
        assertFalse(trace.isAttached)
        fixture.command('p')
        assertEquals('p', fixture.receive())
        fixture.finish()
    }

    @Test
    fun forwardsPendingApplicationSignalWhenDetaching() = fixtureTest { fixture, trace ->
        trace.seize()
        fixture.command('u')
        val stop = awaitStop(trace)
        assertEquals(ThreadTrace.StopKind.SIGNAL, stop.kind)
        assertEquals(SIGUSR1, stop.signal)
        trace.close()
        assertEquals('s', fixture.receive())
        fixture.finish()
    }

    @Test
    fun genuineSigtrapIsDeliveredInsteadOfTreatedAsAnInterrupt() = fixtureTest { fixture, trace ->
        trace.seize()
        fixture.command('t')
        val stop = awaitStop(trace)
        assertEquals(ThreadTrace.StopKind.SIGNAL, stop.kind)
        assertEquals(SIGTRAP, stop.signal)
        trace.resume()
        assertEquals('t', fixture.receive())
        trace.close()
        fixture.finish()
    }

    @Test
    fun observesExitWithoutWaitingForADeadThread() = fixtureTest { fixture, trace ->
        trace.seize()
        fixture.command('x')
        while (!trace.hasExited) {
            trace.pollStop()
            delay(1)
        }
        fixture.markReaped()
        assertFalse(trace.isAttached)
        assertNull(trace.interrupt())
        trace.close()
    }

    @Test
    fun cancelledCallerStillDetachesItsTrace() = fixtureTest { fixture, trace ->
        trace.seize()
        var cleaned = false
        val caller = CoroutineScope(currentCoroutineContext()).launch {
            currentCoroutineContext().cancel()
            trace.close()
            cleaned = true
        }
        caller.join()
        assertTrue(cleaned)
        assertFalse(trace.isAttached)
        fixture.command('p')
        assertEquals('p', fixture.receive())
        fixture.finish()
    }

    @Test
    fun rejectsCrossThreadOperationsWithoutLosingOwnership() = fixtureTest { fixture, trace ->
        trace.seize()
        newSingleThreadContext("factorio-mcp-wrong-tracer").use { other ->
            withContext(other) {
                assertFailsWith<IllegalStateException> { trace.pollStop() }
            }
        }
        trace.close()
        fixture.finish()
    }

    @Test
    fun detachPreservesAnExternalJobControlStop() = fixtureTest { fixture, trace ->
        trace.seize()
        check(kill(fixture.pid, SIGSTOP) == 0)
        assertEquals(SIGSTOP, awaitStop(trace).signal)
        trace.resume()
        assertEquals(ThreadTrace.StopKind.GROUP, awaitStop(trace).kind)
        trace.close()
        ProcessHandle(fixture.pid).use { process ->
            while (process.state() != 'T') delay(1)
        }
        check(kill(fixture.pid, SIGCONT) == 0)
        fixture.command('p')
        assertEquals('p', fixture.receive())
        fixture.finish()
    }

    @Test
    fun capturesFunctionEntryWithoutPatchingCodeAndRestoresDebugState() = fixtureTest { fixture, trace ->
        trace.seize()
        trace.interrupt()
        ProcessHandle(fixture.pid).use { process ->
            var address = 0L
            process.withExecutable { image ->
                address = image.loadBias(process.executableMappings(), sysconf(_SC_PAGESIZE)) +
                        image.symbol("fm_fixture_safe_point").address
            }
            val originalCode = process.readMemory(address, 16)
            val statusOffset = (FM_DEBUG_REGISTERS_OFFSET.toLong() + 6 * FM_DEBUG_REGISTER_WIDTH.toLong())
                .toCPointer<ByteVar>()
            val oldStatus = ptrace(PTRACE_PEEKUSER, fixture.pid, statusOffset, null)
            val staleStatus = oldStatus or 15
            check(ptrace(PTRACE_POKEUSER, fixture.pid, statusOffset, staleStatus.toCPointer<ByteVar>()) == 0L)
            trace.breakAt(address)
            trace.resume()
            fixture.command('b')
            assertEquals(ThreadTrace.StopKind.BREAKPOINT, awaitStop(trace).kind)
            assertEquals(address.toULong(), trace.registers().general.useContents { rip })
            assertContentEquals(originalCode, process.readMemory(address, 16))
            trace.removeBreakpoint()
            assertEquals(staleStatus, ptrace(PTRACE_PEEKUSER, fixture.pid, statusOffset, null))
            check(ptrace(PTRACE_POKEUSER, fixture.pid, statusOffset, oldStatus.toCPointer<ByteVar>()) == 0L)
            trace.close()
            assertEquals('b', fixture.receive())
            // A second execution must not trigger a lingering debug-register trap.
            fixture.command('b')
            assertEquals('b', fixture.receive())
            fixture.finish()
        }
    }

    @Test
    fun invalidBreakpointDoesNotModifyTheTarget() = fixtureTest { fixture, trace ->
        trace.seize()
        trace.interrupt()
        assertFailsWith<IllegalArgumentException> { trace.breakAt(1) }
        trace.close()
        fixture.command('p')
        assertEquals('p', fixture.receive())
        fixture.finish()
    }

    @Test
    fun failedDebugRegisterCleanupRetainsOwnershipForRetry() = fixtureTest { fixture, trace ->
        trace.seize()
        trace.interrupt()
        ProcessHandle(fixture.pid).use { process ->
            process.withExecutable { image ->
                val address = image.loadBias(process.executableMappings(), sysconf(_SC_PAGESIZE)) +
                        image.symbol("fm_fixture_safe_point").address
                trace.breakAt(address)
            }
        }
        val controlOffset = (FM_DEBUG_REGISTERS_OFFSET.toLong() + 7 * FM_DEBUG_REGISTER_WIDTH.toLong())
            .toCPointer<ByteVar>()
        val installed = ptrace(PTRACE_PEEKUSER, fixture.pid, controlOffset, null)
        check(installed >= 0)
        check(ptrace(PTRACE_POKEUSER, fixture.pid, controlOffset, null) == 0L)
        assertFailsWith<IllegalStateException> { trace.close() }
        assertTrue(trace.isAttached)
        assertNotNull(trace.stop)
        check(ptrace(PTRACE_POKEUSER, fixture.pid, controlOffset, installed.toCPointer<ByteVar>()) == 0L)
        trace.close()
        assertFalse(trace.isAttached)
        fixture.command('p')
        assertEquals('p', fixture.receive())
        fixture.finish()
    }

    @Test
    fun exitWhileStoppedDiscardsBreakpointOwnershipAndReapsTheTrace() = fixtureTest { fixture, trace ->
        trace.seize()
        trace.interrupt()
        ProcessHandle(fixture.pid).use { process ->
            process.withExecutable { image ->
                trace.breakAt(
                    image.loadBias(process.executableMappings(), sysconf(_SC_PAGESIZE)) +
                            image.symbol("fm_fixture_safe_point").address
                )
            }
            check(kill(fixture.pid, SIGKILL) == 0)
            while (process.alive()) delay(1)
        }
        trace.close()
        fixture.markReaped()
        assertTrue(trace.hasExited)
        assertFalse(trace.isAttached)
    }
}
