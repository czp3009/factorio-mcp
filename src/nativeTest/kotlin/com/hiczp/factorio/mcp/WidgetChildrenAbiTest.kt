package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class WidgetChildrenAbiTest {
    /** Synthetic Microsoft ABI visitor with relocatable labels and changed vector layouts. */
    private class Visitor(private val swap: Boolean, private val stride: Int = 8) {
        val bytes = mutableListOf<Int>()
        private val labels = mutableMapOf<String, Int>()
        private val fixups = mutableListOf<Triple<Int, String, Int>>()
        private fun register(value: Int) = if (!swap) value else when (value) { 3 -> 7; 7 -> 3; else -> value }
        private fun emit(vararg values: Int) { bytes.addAll(values.toList()) }
        private fun label(name: String) { labels[name] = bytes.size }
        private fun mov(destination: Int, source: Int) = emit(0x48, 0x8b, 0xc0 + register(destination) * 8 + register(source))
        private fun memory(opcode: Int, register: Int, base: Int, offset: Int) {
            val b = this.register(base)
            emit(0x48, opcode, 0x40 + this.register(register) * 8 + b)
            if (b == 4) emit(0x24)
            emit(offset)
        }
        private fun compare(first: Int, second: Int) = emit(0x48, 0x3b, 0xc0 + register(first) * 8 + register(second))
        private fun branch(opcode: Int, name: String) {
            emit(opcode, 0)
            fixups += Triple(bytes.lastIndex, name, 1)
        }
        private fun call(name: String) {
            emit(0xe8, 0, 0, 0, 0)
            fixups += Triple(bytes.size - 4, name, 4)
        }

        fun body(first: Int = 24, second: Int = 64, trap: Boolean = false): BinaryView {
            label("self")
            memory(0x89, 3, 4, 16)
            memory(0x89, 5, 4, 24)
            memory(0x89, 6, 4, 32)
            emit(0x50 + register(7), 0x48, 0x83, 0xec, 32)
            mov(5, 1)
            mov(6, 2)
            for ((index, offset) in listOf(first, second).withIndex()) {
                memory(0x8b, 3, 5, offset)
                memory(0x8b, 7, 5, offset + 8)
                compare(3, 7)
                branch(0x74, "after-$index")
                emit(0x90, 0x90)
                label("loop-$index")
                memory(0x8b, 1, 3, 0)
                mov(2, 6)
                call("self")
                emit(0x48, 0x83, 0xc0 + register(3), stride)
                compare(3, 7)
                branch(0x75, "loop-$index")
                label("after-$index")
            }
            memory(0x8b, 1, 6, 40)
            memory(0x89, 5, 4, 48)
            emit(0x48, 0x85, 0xc9)
            branch(0x74, "failure")
            memory(0x8b, 0, 1, 0)
            memory(0x8d, 2, 4, 48)
            emit(0xff, 0x50, 16)
            memory(0x8b, 3, 4, 56)
            memory(0x8b, 5, 4, 64)
            memory(0x8b, 6, 4, 72)
            emit(0x48, 0x83, 0xc4, 32, 0x58 + register(7), 0xc3)
            label("failure")
            call("bad")
            if (trap) emit(0xcc)
            labels["bad"] = 4096
            for ((offset, target, width) in fixups) {
                val value = labels.getValue(target) - offset - width
                if (width == 1) require(value in -128..127)
                repeat(width) { bytes[offset + it] = (value ushr (8 * it)) and 255 }
            }
            return BinaryView(bytes.map(Int::toByte).toByteArray())
        }
    }

    @Test
    fun microsoftVisitorProvesOrderedRangesAcrossRegisterAndLayoutChanges() {
        for (swap in listOf(false, true)) for (trap in listOf(false, true)) for ((first, second) in listOf(24 to 64, 72 to 32)) {
            assertEquals(listOf(WidgetChildren.Range(first.toLong(), first + 8L),
                WidgetChildren.Range(second.toLong(), second + 8L)),
                WidgetChildren.analyze(Visitor(swap).body(first, second, trap), 65536, 69632, 112,
                    WidgetChildren.Abi.MICROSOFT))
        }
    }

    @Test
    fun microsoftVisitorRejectsChangedStrideReceiverAbiAndBounds() {
        assertFails { WidgetChildren.analyze(Visitor(false, 16).body(), 65536, 69632, 112, WidgetChildren.Abi.MICROSOFT) }
        assertFails { WidgetChildren.analyze(Visitor(false).body(), 65536, 69632, 32, WidgetChildren.Abi.MICROSOFT) }
        assertFails { WidgetChildren.analyze(Visitor(false).body(), 65536, 69632, 112) }
        assertFails { WidgetChildren.analyze(Visitor(false).body(), 65536, 69633, 112, WidgetChildren.Abi.MICROSOFT) }
    }
}
