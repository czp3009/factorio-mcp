@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.windows.*
import kotlin.test.*

class RemoteBootstrapTest {
    @Test
    fun failedOrPendingWaitPreservesArgumentAndAllowsCleanupRetry() = runBlocking {
        withTimeout(10_000) {
            val process = checkNotNull(GetCurrentProcess())
            val event = checkNotNull(CreateEventW(null, 1, 0, null))
            val argument =
                checkNotNull(
                    VirtualAllocEx(
                        process,
                        null,
                        4096u,
                        (MEM_RESERVE or MEM_COMMIT).toUInt(),
                        PAGE_READWRITE.toUInt(),
                    )
                )
            argument.reinterpret<COpaquePointerVar>().pointed.value = event
            val thread =
                checkNotNull(
                    CreateThread(
                        null,
                        0u,
                        staticCFunction { parameter: COpaquePointer? ->
                            WaitForSingleObject(
                                parameter!!.reinterpret<COpaquePointerVar>().pointed.value,
                                INFINITE,
                            )
                            // A signaled thread may legitimately return the numeric value of
                            // STILL_ACTIVE.
                            259u
                        },
                        argument,
                        0u,
                        null,
                    )
                )
            var failWait = true
            val call =
                RemoteBootstrap(process, thread, argument) {
                    if (failWait) {
                        SetLastError(ERROR_INVALID_HANDLE.toUInt())
                        WAIT_FAILED
                    } else WaitForSingleObject(it, 0u)
                }

            fun assertArgumentAllocated() = memScoped {
                val region = alloc<MEMORY_BASIC_INFORMATION>()
                check(
                    VirtualQuery(
                        argument,
                        region.ptr,
                        sizeOf<MEMORY_BASIC_INFORMATION>().toULong(),
                    ) != 0uL
                )
                assertEquals(MEM_COMMIT.toUInt(), region.State)
            }

            var closed = false
            try {
                assertFailsWith<IllegalStateException> { call.await() }
                assertFailsWith<IllegalStateException> { call.close() }
                assertArgumentAllocated()
                failWait = false
                assertFailsWith<IllegalStateException> { call.close() }
                assertArgumentAllocated()
                val completion = async(start = CoroutineStart.UNDISPATCHED) { call.await() }
                assertFalse(completion.isCompleted)
                check(SetEvent(event) != 0)
                assertEquals(259u, completion.await())
                call.close()
                closed = true
                call.close()
                memScoped {
                    val region = alloc<MEMORY_BASIC_INFORMATION>()
                    check(
                        VirtualQuery(
                            argument,
                            region.ptr,
                            sizeOf<MEMORY_BASIC_INFORMATION>().toULong(),
                        ) != 0uL
                    )
                    assertEquals(MEM_FREE.toUInt(), region.State)
                }
            } finally {
                failWait = false
                SetEvent(event)
                if (!closed) {
                    // Only this fixture's own thread can be active here.
                    WaitForSingleObject(thread, 1000u)
                    call.close()
                }
                CloseHandle(event)
            }
        }
    }

    @Test
    fun processAndModuleEnumerationDistinguishMissingNames() {
        val pid = GetCurrentProcessId()
        val image = checkNotNull(processModule(pid))
        assertTrue(pid.toInt() in discoverProcesses(image.path.substringAfterLast('\\')))
        assertEquals(image, processModule(pid, image.path.substringAfterLast('\\')))
        assertNull(processModule(pid, "factorio-mcp-absent-module.dll"))
        assertTrue(discoverProcesses("factorio-mcp-absent-process.exe").isEmpty())
        assertFailsWith<IllegalStateException> { processModule(UInt.MAX_VALUE) }
    }
}
