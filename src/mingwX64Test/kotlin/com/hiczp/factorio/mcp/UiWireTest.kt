@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.FmAction
import kotlinx.cinterop.*
import platform.posix.memset
import kotlin.test.*

class UiWireTest {
    private val step = UiStep(false, 0, null, null, null)

    @Test
    fun visibilityPredicateDoesNotLeakBetweenRequests() = memScoped {
        val target = alloc<FmAction>()
        for (visible in listOf(true, false, null)) {
            writeAction(target, UiAction(listOf(step.copy(visible = visible))))
            assertEquals(if (visible == null) 0 else 1, target.path[0].hasVisible)
            assertEquals(if (visible == true) 1 else 0, target.path[0].visible)
        }
    }

    @Test
    fun oversizedTypedRequestsAreRejectedBeforeAnyNativeWrite() {
        val size = sizeOf<FmAction>().toInt() + 32
        val buffer = nativeHeap.allocArray<ByteVar>(size)
        try {
            memset(buffer, 0x5a, size.toULong())
            val before = buffer.readBytes(size)
            val target = buffer.reinterpret<FmAction>().pointed
            val invalid =
                listOf(
                    UiAction(List(17) { step }),
                    UiAction(listOf(step), text = List(1025) { 65 }),
                    UiAction(listOf(step.copy(type = "x".repeat(160)))),
                    UiAction(listOf(step.copy(prototype = UiPrototypeMatch("x".repeat(256))))),
                    UiAction(listOf(step.copy(prototype = UiPrototypeMatch("x", "x".repeat(160))))),
                    UiAction(listOf(step.copy(text = "x".repeat(1024)))),
                    UiAction(listOf(step.copy(text = "界".repeat(342)))),
                    UiAction(listOf(step.copy(text = "a\u0000b"))),
                )
            invalid.forEach {
                assertFailsWith<IllegalArgumentException> { writeAction(target, it) }
                assertContentEquals(before, buffer.readBytes(size))
            }
            assertFailsWith<IllegalArgumentException> {
                writeAction(target, UiAction(emptyList()), List(9) { 1u })
            }
            assertContentEquals(before, buffer.readBytes(size))
            assertFailsWith<IllegalArgumentException> { writeAction(target, null, listOf(1u)) }
            assertContentEquals(before, buffer.readBytes(size))
        } finally {
            nativeHeap.free(buffer)
        }
    }

    @Test
    fun maximumPayloadKeepsTerminatorsAndNextRequestClearsOldFields() {
        val target = nativeHeap.alloc<FmAction>()
        try {
            val path = List(16) { step.copy(type = "x".repeat(159), text = "界".repeat(341)) }
            writeAction(
                target,
                UiAction(path, text = List(1024) { 0x1f680 }),
                List(8) { it.toUInt() },
            )
            assertEquals(16, target.count)
            assertEquals(1024u, target.textCount)
            assertEquals(0x1f680u, target.text[1023])
            assertEquals(7u, target.keys[7])
            assertEquals(path.last().text, target.path[15].text.toKString())
            assertEquals(0.toByte(), target.path[15].type[159])
            assertEquals(0.toByte(), target.path[15].text[1023])
            writeAction(target, null)
            assertTrue(
                target.ptr.reinterpret<ByteVar>().readBytes(sizeOf<FmAction>().toInt()).all {
                    it == 0.toByte()
                }
            )
        } finally {
            nativeHeap.free(target)
        }
    }
}
