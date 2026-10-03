@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlin.test.*
import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.*

class ProcessHandleTest {
    @Test
    fun rejectsCachedMetadataAfterTheSameProcessExecutesAnotherImage(): Unit = memScoped {
        val descriptors = allocArray<IntVar>(2)
        val byte = alloc<ByteVar>()
        val status = alloc<IntVar>()
        val arguments = allocArray<CPointerVar<ByteVar>>(3)
        arguments[0] = "sleep".cstr.getPointer(this)
        arguments[1] = "30".cstr.getPointer(this)
        arguments[2] = null
        check(pipe(descriptors) == 0)
        val child = fork()
        if (child == 0) {
            close(descriptors[1])
            read(descriptors[0], byte.ptr, 1u)
            close(descriptors[0])
            execv("/bin/sleep", arguments)
            _exit(127)
        }
        close(descriptors[0])
        check(child > 0)
        try {
            ProcessHandle(child).use { process ->
                val originalPath = process.executablePath()
                process.withExecutable { it.segments }
                byte.value = 1
                check(write(descriptors[1], byte.ptr, 1u) == 1L)
                runBlocking {
                    withTimeout(5_000) { while (process.executablePath() == originalPath) delay(1) }
                }
                assertTrue(process.alive())
                assertFailsWith<IllegalStateException> { process.withExecutable { it } }
            }
            ProcessHandle(child).use { replacement ->
                replacement.withExecutable { assertTrue(it.segments.isNotEmpty()) }
            }
        } finally {
            close(descriptors[1])
            kill(child, SIGTERM)
            check(waitpid(child, status.ptr, 0) == child)
        }
    }

    @Test
    fun executableIndexesAreOwnedByOneHandleAndReleasedOnClose() {
        val process = ProcessHandle(getpid())
        val first = process.withExecutable { it }
        try {
            assertSame(first, process.withExecutable { it })
            assertFailsWith<IllegalArgumentException> {
                process.withExecutable { throw IllegalArgumentException("fixture callback") }
            }
            assertSame(first, process.withExecutable { it })
            ProcessHandle(getpid()).use { other ->
                assertNotSame(first, other.withExecutable { it })
            }
        } finally {
            process.close()
        }
        process.close()
        assertFailsWith<IllegalStateException> { process.withExecutable { it } }
        val segment = first.segments.first { it.type == 1L && it.fileSize >= 64 }
        assertFailsWith<IllegalStateException> {
            first.virtualBytes(segment.address, 64).bytes(0, 64)
        }
    }

    @Test
    fun discoversSelfAndReadsOnlyTheSelectedImageAndMemory() {
        ProcessHandle(getpid()).use { process ->
            assertTrue(process.alive())
            val name = process.executablePath().substringAfterLast('/')
            assertTrue(getpid() in discoverProcesses(name))
            val mappings = process.executableMappings()
            assertTrue(mappings.any(ProcMapping::executable))
            process.withExecutable { image ->
                val bias = image.loadBias(mappings, sysconf(_SC_PAGESIZE))
                val first = image.segments.first { it.type == 1L && it.fileSize >= 64 }
                assertContentEquals(
                    image.virtualBytes(first.address, 64).bytes(0, 64),
                    process.readMemory(bias + first.address, 64),
                )
            }
            val expected = byteArrayOf(1, 19, 33, 47)
            expected.usePinned {
                assertContentEquals(
                    expected,
                    process.readMemory(it.addressOf(0).rawValue.toLong(), expected.size),
                )
            }
        }
    }

    @Test
    fun retainedHandleObservesExitWithoutRetargetingPid(): Unit = memScoped {
        val descriptors = allocArray<IntVar>(2)
        val byte = alloc<ByteVar>()
        val status = alloc<IntVar>()
        check(pipe(descriptors) == 0)
        val child = fork()
        if (child == 0) {
            close(descriptors[1])
            read(descriptors[0], byte.ptr, 1u)
            _exit(0)
        }
        close(descriptors[0])
        check(child > 0)
        var reaped = false
        try {
            ProcessHandle(child).use { process ->
                assertTrue(process.alive())
                byte.value = 1
                check(write(descriptors[1], byte.ptr, 1u) == 1L)
                runBlocking { withTimeout(5_000) { while (process.alive()) delay(1) } }
                assertEquals('Z', process.state())
                assertFalse(process.alive())
                check(waitpid(child, status.ptr, 0) == child)
                reaped = true
                assertFalse(process.alive())
                assertFailsWith<IllegalStateException> { process.executablePath() }
            }
        } finally {
            close(descriptors[1])
            if (!reaped) waitpid(child, status.ptr, 0)
        }
    }

    @Test
    fun rejectsClosedHandlesAndInvalidSelectors() {
        val process = ProcessHandle(getpid())
        process.close()
        process.close()
        assertFailsWith<IllegalStateException> { process.alive() }
        assertFailsWith<IllegalArgumentException> { ProcessHandle(0) }
        assertFailsWith<IllegalArgumentException> { discoverProcesses("a/b") }
    }

    @Test
    fun parsesMappedPathsWithSpacesAndRejectsOverlaps() {
        val maps =
            ProcMapping.parse(
                "1000-2000 r--p 00000000 08:01 17     /tmp/game with spaces\n" +
                    "2000-4000 r-xp 00001000 08:01 17     /tmp/game with spaces\n" +
                    "ffffffffff600000-ffffffffff601000 --xp 00000000 00:00 0 [vsyscall]\n"
            )
        assertEquals(2, maps.size)
        assertEquals("/tmp/game with spaces", maps.first().path)
        assertTrue(maps[1].executable)
        assertEquals(17, maps[1].inode)
        assertFailsWith<IllegalArgumentException> {
            ProcMapping.parse("1000-3000 r--p 0 00:00 0\n2000-4000 r-xp 0 00:00 0\n")
        }
        assertFailsWith<IllegalStateException> { ProcMapping.parse("not a mapping") }
    }
}
