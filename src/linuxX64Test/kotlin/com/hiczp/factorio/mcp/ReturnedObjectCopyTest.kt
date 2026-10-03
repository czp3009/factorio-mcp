package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ReturnedObjectCopyTest {
    private fun savedReceiver(slot: Int): X64ControlFlow {
        val code = byteArrayOf(0x53, 0x48, 0x83.toByte(), 0xec.toByte(), 32,
            0x48, 0x89.toByte(), 0x7c, 0x24, slot.toByte(),
            0x48, 0x89.toByte(), 0xfe.toByte(), 0x48, 0x8d.toByte(), 0x3c, 0x24, 0xe8.toByte()) +
                ByteArray(4) { (1978 ushr (it * 8)).toByte() } +
                byteArrayOf(0x48, 0x8b.toByte(), 0x5c, 0x24, slot.toByte(),
                    0x48, 0x8b.toByte(), 0x04, 0x24, 0x48, 0x89.toByte(), 0x43, 40,
                    0x48, 0x8b.toByte(), 0x44, 0x24, 8, 0x48, 0x89.toByte(), 0x43, 48,
                    0x48, 0x83.toByte(), 0xc4.toByte(), 32, 0x5b, 0xc3.toByte())
        return X64ControlFlow(X64Instructions(BinaryView(code)).all())
    }

    @Test
    fun savedReceiverMustRemainOutsideTheNativeResultBorrow() {
        assertEquals(40L, ReturnedObjectCopy.analyze(savedReceiver(24), 2000, 256, 16))
        assertFails { ReturnedObjectCopy.analyze(savedReceiver(8), 2000, 256, 16) }
    }

    private fun caller(member: Int, last: Int = member + 8): X64ControlFlow {
        val code = byteArrayOf(
            0x53, 0x48, 0x89.toByte(), 0xfb.toByte(),
            0x48, 0x83.toByte(), 0xec.toByte(), 16,
            0x48, 0x8d.toByte(), 0x3c, 0x24, 0x48, 0x89.toByte(), 0xde.toByte(),
            0xe8.toByte(),
        ) + ByteArray(4) { (1980 ushr (it * 8)).toByte() } + byteArrayOf(
            0x48, 0x8b.toByte(), 0x04, 0x24, 0x48, 0x89.toByte(), 0x43, member.toByte(),
            0x48, 0x8b.toByte(), 0x44, 0x24, 8, 0x48, 0x89.toByte(), 0x43, last.toByte(),
            0x48, 0x83.toByte(), 0xc4.toByte(), 16, 0x5b, 0xc3.toByte(),
        )
        return X64ControlFlow(X64Instructions(BinaryView(code)).all())
    }

    @Test
    fun requiresTheCompleteResultAndOneBoundedDestination() {
        for (member in listOf(40, 104)) {
            assertEquals(member.toLong(), ReturnedObjectCopy.analyze(caller(member), 2000, 256, 16))
            assertFails { ReturnedObjectCopy.analyze(caller(member), 2000, member.toLong() + 15, 16) }
            assertFails { ReturnedObjectCopy.analyze(caller(member, member + 16), 2000, 256, 16) }
            assertFails { ReturnedObjectCopy.analyze(caller(member), 2000, 256, 24) }
            assertFails { ReturnedObjectCopy.analyze(caller(member), 2001, 256, 16) }
        }
    }
}
