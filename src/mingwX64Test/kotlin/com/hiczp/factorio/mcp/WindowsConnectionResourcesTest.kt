@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.Shared
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import platform.windows.*
import kotlin.test.*

class WindowsConnectionResourcesTest {
    @Test
    fun cleanupRetriesEveryReleaseWithoutClosingDependenciesOrReusingClosedHandles() {
        val process = checkNotNull(OpenProcess(SYNCHRONIZE.toUInt(), 0, GetCurrentProcessId()))
        var failUnmap = true
        var failHandle: HANDLE? = null
        var unmaps = 0
        val closed = mutableListOf<HANDLE>()
        val resources =
            WindowsConnectionResources(
                process,
                unmap = {
                    unmaps++
                    if (failUnmap) false else UnmapViewOfFile(it) != 0
                },
                closeHandle = {
                    if (it == failHandle) false
                    else {
                        assertFalse(it in closed, "A released handle must not be closed again")
                        (CloseHandle(it) != 0).also { success -> if (success) closed += it }
                    }
                },
            )
        try {
            resources.gate = checkNotNull(CreateMutexW(null, 0, null))
            resources.mapping =
                checkNotNull(
                    CreateFileMappingW(
                        INVALID_HANDLE_VALUE,
                        null,
                        PAGE_READWRITE.toUInt(),
                        0u,
                        sizeOf<Shared>().toUInt(),
                        null,
                    )
                )
            resources.shared =
                checkNotNull(
                    MapViewOfFile(
                        resources.mapping,
                        FILE_MAP_ALL_ACCESS.toUInt(),
                        0u,
                        0u,
                        sizeOf<Shared>().toULong(),
                    )
                )
                    .reinterpret()
            assertFailsWith<IllegalStateException> { resources.close() }
            assertNotNull(resources.shared)
            assertTrue(closed.isEmpty())
            failUnmap = false
            val handles = listOf(resources.mapping!!, resources.gate!!, process)
            for ((index, handle) in handles.withIndex()) {
                failHandle = handle
                assertFailsWith<IllegalStateException> { resources.close() }
                assertNull(resources.shared)
                assertEquals(index, closed.size)
                assertNotNull(resources.process)
            }
            failHandle = null
            resources.close()
            resources.close()
            assertEquals(handles, closed)
            assertEquals(2, unmaps)
            assertNull(resources.process)
            assertNull(resources.mapping)
            assertNull(resources.gate)
        } finally {
            failUnmap = false
            failHandle = null
            resources.close()
        }
    }
}
