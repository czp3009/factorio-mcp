@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.memset
import platform.windows.*
import kotlin.test.*

/** Native wait lifetime tests use disposable child processes, never an existing game. */
class ProcessExitWaitTest {
    @Test
    fun failedProcessWaitIsNotReportedAsExit() {
        assertFailsWith<IllegalStateException> { processIsAlive(null) }
    }

    private fun startSuspendedChild(): HANDLE = memScoped {
        val directory = allocArray<UShortVar>(32768)
        check(GetSystemDirectoryW(directory, 32768u) in 1u..32767u)
        val executable = "${directory.toKString()}\\cmd.exe"
        val startup = alloc<STARTUPINFOW>()
        memset(startup.ptr, 0, sizeOf<STARTUPINFOW>().toULong())
        startup.cb = sizeOf<STARTUPINFOW>().toUInt()
        val child = alloc<PROCESS_INFORMATION>()
        check(
            CreateProcessW(
                executable,
                "\"$executable\" /D /Q /C exit 17".wcstr.ptr,
                null,
                null,
                0,
                (CREATE_NO_WINDOW or CREATE_SUSPENDED).toUInt(),
                null,
                null,
                startup.ptr,
                child.ptr,
            ) != 0
        )
        // These forced-exit fixtures only need the process handle.
        CloseHandle(child.hThread)
        checkNotNull(child.hProcess)
    }

    @Test
    fun unregisterRacingWithExitKeepsCallbackContextAlive() = runBlocking {
        withTimeout(10_000) {
            repeat(20) {
                val child = startSuspendedChild()
                try {
                    val observer = ProcessExitWait(child)
                    try {
                        check(TerminateProcess(child, 23u) != 0)
                    } finally {
                        // The callback may be queued or executing when unregister begins.
                        observer.close()
                    }
                } finally {
                    if (WaitForSingleObject(child, 0u) == WAIT_TIMEOUT.toUInt()) {
                        TerminateProcess(child, 1u)
                    }
                    CloseHandle(child)
                }
            }
        }
    }

    @Test
    fun oneShotNotificationAndLateRegistrationOnTerminatedProcess() = runBlocking {
        withTimeout(10_000) {
            val child = startSuspendedChild()
            try {
                assertTrue(processIsAlive(child))
                val first = ProcessExitWait(child)
                try {
                    check(TerminateProcess(child, 23u) != 0)
                    first.await()
                    assertEquals(WAIT_OBJECT_0, WaitForSingleObject(child, 0u))
                    assertFalse(processIsAlive(child))
                    memScoped {
                        val code = alloc<UIntVar>()
                        check(GetExitCodeProcess(child, code.ptr) != 0)
                        assertEquals(23u, code.value)
                    }
                } finally {
                    first.close()
                }
                val late = ProcessExitWait(child)
                try {
                    late.await()
                } finally {
                    late.close()
                    late.close()
                }
            } finally {
                if (WaitForSingleObject(child, 0u) == WAIT_TIMEOUT.toUInt())
                    TerminateProcess(child, 1u)
                CloseHandle(child)
            }
        }
    }

    @Test
    fun unregisterWhileAliveCancelsAwaitersAndPermitsAnotherRegistration() = runBlocking {
        withTimeout(10_000) {
            val child = startSuspendedChild()
            try {
                val cancelled = ProcessExitWait(child)
                val pending =
                    async(start = CoroutineStart.UNDISPATCHED) { runCatching { cancelled.await() } }
                cancelled.close()
                assertIs<CancellationException>(pending.await().exceptionOrNull())
                val active = ProcessExitWait(child)
                try {
                    check(TerminateProcess(child, 0u) != 0)
                    active.await()
                } finally {
                    active.close()
                }
            } finally {
                if (WaitForSingleObject(child, 0u) == WAIT_TIMEOUT.toUInt())
                    TerminateProcess(child, 1u)
                CloseHandle(child)
            }
        }
    }
}
