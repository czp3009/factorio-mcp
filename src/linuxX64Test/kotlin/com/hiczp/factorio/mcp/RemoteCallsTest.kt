@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class
)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FM_DEBUG_REGISTERS_OFFSET
import com.hiczp.factorio.mcp.linuxbridge.FM_DEBUG_REGISTER_WIDTH
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxShared
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.linux.user_regs_struct
import platform.posix.*
import kotlin.test.*

class RemoteCallsTest {
    private fun fixtureTest(test: suspend CoroutineScope.(TraceFixture, ThreadTrace, ProcessHandle, Map<String, Long>) -> Unit) =
        runBlocking {
            newSingleThreadContext("factorio-mcp-remote-test").use { owner ->
                withContext(owner) {
                    TraceFixture().use { fixture ->
                        ProcessHandle(fixture.pid).use { process ->
                            var functions = emptyMap<String, Long>()
                            process.withExecutable { image ->
                                val bias = image.loadBias(process.executableMappings(), sysconf(_SC_PAGESIZE))
                                functions = listOf(
                                    "fm_fixture_safe_point",
                                    "fm_fixture_remote",
                                    "fm_fixture_remote_wait"
                                ).associateWith {
                                    bias + image.symbol(it).address
                                }
                            }
                            val trace = ThreadTrace(fixture.pid)
                            try {
                                withTimeout(10000) {
                                    trace.seize()
                                    trace.interrupt()
                                    trace.breakAt(functions.getValue("fm_fixture_safe_point"))
                                    fixture.command('b')
                                    trace.resume()
                                    while (trace.pollStop()?.kind != ThreadTrace.StopKind.BREAKPOINT) delay(1)
                                    trace.removeBreakpoint()
                                    test(fixture, trace, process, functions)
                                }
                            } finally {
                                // The test watchdog may kill only this fixture if a broken borrowed call cannot return.
                                if (trace.hasPendingCall) kill(fixture.pid, SIGKILL)
                                trace.close()
                            }
                        }
                    }
                }
            }
        }

    private fun CValue<user_regs_struct>.bytes() = memScoped {
        getPointer(this).reinterpret<ByteVar>().readBytes(sizeOf<user_regs_struct>().toInt())
    }

    @Test
    fun callsAllIntegerArgumentsAndRestoresRegistersRedZoneAndReturnWord() =
        fixtureTest { fixture, trace, process, functions ->
            val before = trace.registers()
            val stack = before.general.useContents { rsp.toLong() }
            val region = process.readMemory(stack - 160, 168)
            assertEquals(183uL, trace.call(functions.getValue("fm_fixture_remote"), listOf(1u, 2u, 3u, 4u, 5u, 6u)))
            val after = trace.registers()
            assertContentEquals(before.general.bytes(), after.general.bytes())
            assertContentEquals(before.extended, after.extended)
            // Bytes below the System V red zone are unused scratch; the borrowed return word itself is restored.
            assertContentEquals(region.copyOfRange(32, 168), process.readMemory(stack - 128, 136))
            val borrowed = ((stack - 128) and -16L) - 8
            val index = (borrowed - (stack - 160)).toInt()
            assertContentEquals(region.copyOfRange(index, index + 8), process.readMemory(borrowed, 8))
            assertFalse(trace.hasPendingCall)
            assertFalse(trace.hasBreakpoint)
            trace.close()
            assertEquals('b', fixture.receive())
            fixture.finish()
        }

    @Test
    fun cancellationWaitsForNativeReturnBeforeRestoringTheCaller() = fixtureTest { fixture, trace, _, functions ->
        val before = trace.registers()
        var terminalResult: ULong? = null
        val operation = async { terminalResult = trace.call(functions.getValue("fm_fixture_remote_wait")) }
        yield()
        assertEquals('w', fixture.receive())
        operation.cancel()
        delay(20)
        assertFalse(operation.isCompleted)
        assertTrue(trace.hasPendingCall)
        fixture.command('g')
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(77uL, terminalResult)
        assertFalse(trace.hasPendingCall)
        assertContentEquals(before.general.bytes(), trace.registers().general.bytes())
        trace.close()
        assertEquals('b', fixture.receive())
        fixture.finish()
    }

    @Test
    fun detachAlsoWaitsForAnAdmittedNativeCall() = fixtureTest { fixture, trace, _, functions ->
        val operation = async { trace.call(functions.getValue("fm_fixture_remote_wait")) }
        yield()
        assertEquals('w', fixture.receive())
        val closing = async { trace.close() }
        delay(20)
        assertFalse(closing.isCompleted)
        fixture.command('g')
        assertEquals(77uL, operation.await())
        closing.await()
        assertFalse(trace.isAttached)
        assertEquals('b', fixture.receive())
        fixture.finish()
    }

    @Test
    fun preservesSignalsAndJobControlDuringAnAdmittedCall() = fixtureTest { fixture, trace, process, functions ->
        val operation = async { trace.call(functions.getValue("fm_fixture_remote_wait")) }
        yield()
        assertEquals('w', fixture.receive())
        check(kill(fixture.pid, SIGUSR1) == 0)
        delay(20)
        assertEquals('s', fixture.receive())
        check(kill(fixture.pid, SIGTRAP) == 0)
        delay(20)
        assertEquals('t', fixture.receive())
        check(kill(fixture.pid, SIGSTOP) == 0)
        delay(20)
        assertFalse(operation.isCompleted)
        assertTrue(process.state() in setOf('T', 't'))
        fixture.command('g')
        delay(20)
        assertFalse(operation.isCompleted)
        check(kill(fixture.pid, SIGCONT) == 0)
        assertEquals(77uL, operation.await())
        trace.close()
        assertEquals('b', fixture.receive())
        fixture.finish()
    }

    @Test
    fun rejectsInvalidAdmissionWithoutChangingTheStoppedThread() = fixtureTest { fixture, trace, process, functions ->
        val before = trace.registers()
        assertFails { trace.call(1) }
        assertFails { trace.call(functions.getValue("fm_fixture_remote"), List(7) { 0u }) }
        assertFails { process.writeData(functions.getValue("fm_fixture_remote"), byteArrayOf(0)) }
        assertFalse(trace.hasPendingCall)
        assertContentEquals(before.general.bytes(), trace.registers().general.bytes())
        trace.close()
        assertEquals('b', fixture.receive())
        fixture.finish()
    }

    @Test
    fun cleanupFailureRetainsTheCompletedResultWithoutReplayingNativeCode() =
        fixtureTest { fixture, trace, _, functions ->
            val before = trace.registers()
            val operation = async { runCatching { trace.call(functions.getValue("fm_fixture_remote_wait")) } }
            yield()
            assertEquals('w', fixture.receive())
            fixture.command('g')
            // Keep this test coroutine on the owner thread until the kernel reports the return, before the waiter cleans up.
            while (trace.pollStop()?.kind != ThreadTrace.StopKind.BREAKPOINT) {
                check(!trace.hasExited)
                usleep(1000u)
            }
            val controlOffset = (FM_DEBUG_REGISTERS_OFFSET.toLong() + 7 * FM_DEBUG_REGISTER_WIDTH.toLong())
                .toCPointer<ByteVar>()
            val installed = ptrace(PTRACE_PEEKUSER, fixture.pid, controlOffset, null)
            check(installed >= 0)
            check(ptrace(PTRACE_POKEUSER, fixture.pid, controlOffset, null) == 0L)
            assertTrue(operation.await().isFailure)
            assertTrue(trace.hasPendingCall)
            assertTrue(trace.isAttached)
            check(ptrace(PTRACE_POKEUSER, fixture.pid, controlOffset, installed.toCPointer<ByteVar>()) == 0L)
            assertEquals(77uL, trace.completePendingCall())
            assertFalse(trace.hasPendingCall)
            assertContentEquals(before.general.bytes(), trace.registers().general.bytes())
            trace.close()
            assertEquals('b', fixture.receive())
            fixture.finish()
        }

    @Test
    fun loadsSharedLibraryWithoutPatchingTargetCodeAndRestoresErrno() = fixtureTest { fixture, trace, process, _ ->
        val path = "${checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()}/libloader_fixture.so"
        MappedBinary(path).use { assertEquals("libloader_fixture.so", ElfImage(it.view).sharedObjectName()) }
        assertFails {
            ProcessModules(process).functions(setOf("__errno_location"), sharedObjects = setOf("absent-library.so"))
        }
        val errnoFunction =
            ProcessModules(process).functions(setOf("__errno_location"), sharedObjects = setOf("libc.so.6"))
                .getValue("__errno_location")
        val errnoPointer = trace.call(errnoFunction.address).toLong()
        val originalErrno = process.readMemory(errnoPointer, 4)
        val before = trace.registers()
        val loader = LibraryLoader(trace, process)
        try {
            val loaded = loader.load(path)
            assertEquals(path, loaded.path)
            assertTrue(loaded.handle != 0uL)
            assertFalse(loader.hasScratchResources)
            assertContentEquals(originalErrno, process.readMemory(errnoPointer, 4))
            assertContentEquals(before.general.bytes(), trace.registers().general.bytes())
            val function = ProcessModules(process).functions(setOf("fm_loader_value"), path).getValue("fm_loader_value")
            assertEquals(128uL, trace.call(function.address, listOf(7u)))
            val duplicate = LibraryLoader(trace, process)
            assertFails { duplicate.load(path) }
            assertFalse(duplicate.hasScratchResources)
        } finally {
            loader.cleanup()
        }
        trace.close()
        assertEquals('b', fixture.receive())
        fixture.finish()
    }

    @Test
    fun initializesResidentOnceAndValidatesRetainedIpcBeforeReuse() = fixtureTest { fixture, trace, process, _ ->
        val path =
            "${checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()}/resident/libfactorio_mcp_resident.so"
        val loader = LibraryLoader(trace, process)
        try {
            loader.load(path)
            assertFails { ResidentMapping.open(process, path) }
            val initialize =
                ProcessModules(process).functions(setOf("fm_linux_initialize"), path).getValue("fm_linux_initialize")
            val descriptor = trace.call(initialize.address)
            assertTrue(descriptor < Int.MAX_VALUE.toULong())
            ResidentMapping.open(process, path).use { mapping ->
                assertTrue(mapping.tryAcquireLease())
                val state = mapping.memory.reinterpret<FmLinuxShared>().pointed
                assertEquals(fixture.pid.toUInt(), state.process)
                assertEquals(0u, state.attached)
                assertEquals(0u, state.command)
                state.command = 2u
                assertEquals(descriptor, trace.call(initialize.address))
                assertEquals(2u, state.command)
            }
            ResidentMapping.open(process, path).use { mapping ->
                assertTrue(mapping.tryAcquireLease())
                assertEquals(2u, mapping.memory.reinterpret<FmLinuxShared>().pointed.command)
            }
        } finally {
            loader.cleanup()
        }
        trace.close()
        assertEquals('b', fixture.receive())
        fixture.finish()
    }

    @Test
    fun rejectedDlopenStillReleasesScratchAndRestoresTheThread() = fixtureTest { fixture, trace, process, _ ->
        val path = "${checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()}/trace_fixture"
        // A PIE executable is a valid ELF but cannot be loaded as a shared library.
        // Use another fixture's inode so the existing-module rejection does not short-circuit dlopen.
        val unloadedExecutable = path.substringBeforeLast('/') + "/accessor_fixture_1"
        val before = trace.registers()
        val loader = LibraryLoader(trace, process)
        try {
            assertFails { loader.load(unloadedExecutable) }
            assertNull(loader.library)
            assertFalse(loader.hasScratchResources)
            assertFalse(trace.hasPendingCall)
            assertContentEquals(before.general.bytes(), trace.registers().general.bytes())
        } finally {
            loader.cleanup()
        }
        trace.close()
        assertEquals('b', fixture.receive())
        fixture.finish()
    }

    @Test
    fun processExitDiscardsOnlyDeadCallResources() = fixtureTest { fixture, trace, _, functions ->
        val operation = async { runCatching { trace.call(functions.getValue("fm_fixture_remote_wait")) } }
        yield()
        assertEquals('w', fixture.receive())
        check(kill(fixture.pid, SIGKILL) == 0)
        assertTrue(operation.await().isFailure)
        assertTrue(trace.hasExited)
        assertFalse(trace.hasPendingCall)
        trace.close()
        fixture.markReaped()
    }
}
