@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FM_MEMFD_FLAGS
import com.hiczp.factorio.mcp.linuxbridge.FM_MEMFD_SYSCALL
import com.hiczp.factorio.mcp.linuxbridge.fm_ipc_load
import com.hiczp.factorio.mcp.linuxbridge.fm_ipc_store
import com.hiczp.factorio.mcp.testbridge.FmIpcFixture
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.test.*

class SharedMappingTest {
    private val size = sizeOf<FmIpcFixture>()

    private fun state(mapping: SharedMapping): FmIpcFixture = mapping.memory.reinterpret<FmIpcFixture>().pointed

    private fun ipcPath(fixture: TraceFixture): String {
        fixture.command('d')
        val descriptor = fixture.receiveLine().toInt()
        require(descriptor >= 0)
        return "/proc/${fixture.pid}/fd/$descriptor"
    }

    @Test
    fun sharedViewsUseIndependentLeasesAndKeepPayloadOnReacquisition() {
        SharedMapping.create(size).use { owner ->
            val path = "/proc/self/fd/${owner.descriptorNumber}"
            SharedMapping.open(path, size).use { peer ->
                assertTrue(owner.tryAcquireLease())
                assertFalse(peer.tryAcquireLease())
                val data = state(owner)
                data.request = 123u
                fm_ipc_store(data.ptr.reinterpret(), 2u)
                owner.releaseLease()
                assertTrue(peer.tryAcquireLease())
                assertEquals(2u, fm_ipc_load(state(peer).ptr.reinterpret()))
                assertEquals(123uL, state(peer).request)
                peer.close()
                assertTrue(owner.tryAcquireLease())
            }
        }
    }

    @Test
    fun publishesTypedPayloadToANativeProcessAndDoesNotReplayCompletedWork() {
        TraceFixture("ipc_fixture").use { fixture ->
            val path = ipcPath(fixture)
            SharedMapping.open(path, size).use { first ->
                assertTrue(first.tryAcquireLease())
                state(first).request = 41u
                fm_ipc_store(state(first).ptr.reinterpret(), 1u)
            }
            // The native process keeps the mapping after this simulated MCP owner disappears.
            fixture.command('e')
            assertEquals('e', fixture.receive())
            SharedMapping.open(path, size).use { next ->
                assertTrue(next.tryAcquireLease())
                assertEquals(3u, fm_ipc_load(state(next).ptr.reinterpret()))
                assertEquals(298uL, state(next).result)
                assertEquals(1u, state(next).executions)
                fixture.command('e')
                assertEquals('n', fixture.receive())
                assertEquals(1u, state(next).executions)
            }
            fixture.finish()
        }
    }

    @Test
    fun processExitReleasesLeaseWithoutResettingOutstandingState() {
        TraceFixture("ipc_fixture").use { fixture ->
            SharedMapping.open(ipcPath(fixture), size).use { mapping ->
                assertTrue(mapping.tryAcquireLease())
                state(mapping).request = 987u
                fm_ipc_store(state(mapping).ptr.reinterpret(), 2u)
                mapping.releaseLease()
                fixture.command('l')
                assertEquals('l', fixture.receive())
                assertFalse(mapping.tryAcquireLease())
                fixture.finish()
                assertTrue(mapping.tryAcquireLease())
                assertEquals(2u, fm_ipc_load(state(mapping).ptr.reinterpret()))
                assertEquals(987uL, state(mapping).request)
            }
        }
    }

    @Test
    fun nativeLeaseContendsWithKotlinLease() {
        TraceFixture("ipc_fixture").use { fixture ->
            SharedMapping.open(ipcPath(fixture), size).use { mapping ->
                assertTrue(mapping.tryAcquireLease())
                fixture.command('l')
                assertEquals('b', fixture.receive())
                mapping.releaseLease()
                fixture.command('l')
                assertEquals('l', fixture.receive())
                assertFalse(mapping.tryAcquireLease())
                fixture.command('u')
                assertEquals('u', fixture.receive())
                assertTrue(mapping.tryAcquireLease())
            }
            fixture.finish()
        }
    }

    @Test
    fun sealingRejectsResizeAndUnexpectedStorageSizes() {
        SharedMapping.create(size).use { mapping ->
            assertEquals(-1, ftruncate(mapping.descriptorNumber, size / 2))
            assertEquals(EPERM, errno)
            assertEquals(-1, ftruncate(mapping.descriptorNumber, size * 2))
            assertEquals(EPERM, errno)
            assertFailsWith<IllegalArgumentException> {
                SharedMapping.open("/proc/self/fd/${mapping.descriptorNumber}", size + 1)
            }
            state(mapping).request = 19u
            assertEquals(19uL, state(mapping).request)
        }
        assertFailsWith<IllegalArgumentException> { SharedMapping.create(0) }
        assertFailsWith<IllegalArgumentException> { SharedMapping.create(Long.MAX_VALUE) }
    }

    @Test
    fun rejectsUnsealedStorageAndClosedMappings() = memScoped {
        val descriptor = syscall(
            FM_MEMFD_SYSCALL.toLong(),
            "factorio-mcp-unsealed-test".cstr.getPointer(this),
            FM_MEMFD_FLAGS
        ).toInt()
        check(descriptor >= 0)
        try {
            check(ftruncate(descriptor, size) == 0)
            assertFailsWith<IllegalArgumentException> { SharedMapping.open("/proc/self/fd/$descriptor", size) }
        } finally {
            close(descriptor)
        }
        val mapping = SharedMapping.create(size)
        mapping.close()
        mapping.close()
        assertFailsWith<IllegalStateException> { mapping.memory }
        assertFailsWith<IllegalStateException> { mapping.descriptorNumber }
        assertFailsWith<IllegalStateException> { mapping.tryAcquireLease() }
        Unit
    }
}
