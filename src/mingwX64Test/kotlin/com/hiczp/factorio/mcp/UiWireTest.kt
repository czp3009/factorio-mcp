@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.FmAction
import com.hiczp.factorio.mcp.nativebridge.FmRequestBuffer
import kotlinx.cinterop.*
import platform.posix.memset
import kotlin.test.*

class UiWireTest {
    private val step = UiStep(false, 0, null, null, null)

    @Test
    fun largeSelectorsBindOwnedBytesAndClearTheNextRequest() = memScoped {
        val target = alloc<FmAction>()
        val root = alloc<FmRequestBuffer>()
        val path = List(512) { step.copy(type = "x".repeat(320), text = "界".repeat(1024)) }
        val payload = RequestPayload(selectorPayload(path))
        root.address = 4096uL
        root.size = payload.bytes.size.toULong()
        writeAction(target, UiAction(path, text = List(16384) { 0x1f680 }), List(8) { it.toUInt() }, payload, root)
        assertEquals(512, target.count)
        assertEquals(root.address, target.path.address)
        assertEquals(payload.bytes.size.toULong(), target.path.size)
        assertEquals(16384u, target.textCount)
        assertEquals(0x1f680u, target.text[16383])
        assertEquals(7u, target.keys[7])
        assertEquals(defaultUiFields.uiFieldMask(), target.fields)
        writeAction(target, UiAction(emptyList(), fields = emptySet()))
        assertEquals(0u, target.fields)
        writeAction(target, null)
        assertTrue(target.ptr.reinterpret<ByteVar>().readBytes(sizeOf<FmAction>().toInt()).all { it == 0.toByte() })
    }

    @Test
    fun dispatchBudgetsRejectBeforeAnyNativeWrite() = memScoped {
        val size = sizeOf<FmAction>().toInt() + 32
        val buffer = allocArray<ByteVar>(size)
        memset(buffer, 0x5a, size.toULong())
        val before = buffer.readBytes(size)
        val target = buffer.reinterpret<FmAction>().pointed
        assertFailsWith<IllegalArgumentException> { writeAction(target, UiAction(emptyList(), text = List(16385) { 65 })) }
        assertFailsWith<IllegalArgumentException> { writeAction(target, UiAction(emptyList()), List(9) { 1u }) }
        assertFailsWith<IllegalArgumentException> { writeAction(target, null, listOf(1u)) }
        assertContentEquals(before, buffer.readBytes(size))
    }

    @Test
    fun requestSlicesRejectUnownedRangesAndIntegerOverflow() = memScoped<Unit> {
        val root = alloc<FmRequestBuffer>()
        val target = alloc<FmRequestBuffer>()
        root.address = 4096uL
        root.size = 32uL
        target.bindRequest(root, RequestSlice(7, 11))
        assertEquals(4103uL, target.address)
        assertEquals(11uL, target.size)
        for (slice in listOf(RequestSlice(-1, 1), RequestSlice(0, -1), RequestSlice(30, 3)))
            assertFailsWith<IllegalArgumentException> { target.bindRequest(root, slice) }
        root.address = ULong.MAX_VALUE
        assertFailsWith<IllegalArgumentException> { target.bindRequest(root, RequestSlice(0, 1)) }
    }
}
