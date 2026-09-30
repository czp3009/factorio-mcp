@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LuaStackLayoutTest {
    @Test
    fun derivesCompilerGeneratedStackLayoutsWithDifferentPaddingAndValueSizes() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/lua_stack_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String): Long =
                image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

            val layout = SysVLuaStack.resolve(image, "fixture_absindex")
                .withinObjects(constant("fixture_state_size"), constant("fixture_frame_size"))
            assertEquals(constant("fixture_top_offset"), layout.top)
            assertEquals(constant("fixture_frame_offset"), layout.callInfo)
            assertEquals(constant("fixture_function_offset"), layout.function)
            assertEquals(constant("fixture_value_size"), layout.valueSize)
            assertEquals(0, layout.count(4096u + layout.valueSize.toULong(), 4096u))
            assertEquals(4, layout.count(4096u + 5u * layout.valueSize.toULong(), 4096u))
            val body = image.functionBytes(image.symbol("fixture_absindex"), 256)
            val branch = X64Instructions(body).all().first { it.operation == X64Instructions.Operation.JCC }
            val reversed = body.bytes(0, body.size.toInt())
            val opcode = branch.offset.toInt() + if (branch.size == 6) 1 else 0
            reversed[opcode] = (reversed[opcode].toInt() xor 1).toByte()
            assertFails { SysVLuaStack.analyze(BinaryView(reversed)) }
            assertFails { SysVLuaStack.resolve(image, "fixture_mutating_absindex") }
            assertFails { layout.withinObjects(layout.top + 7, constant("fixture_frame_size")) }
            assertFails { layout.withinObjects(constant("fixture_state_size"), layout.function + 7) }
        }
    }

    @Test
    fun refusesInvalidLiveStackArithmetic() {
        val layout = LuaStackLayout(8, 24, 0, 16).withinObjects(32, 8)
        assertFails { layout.count(4096u, 4096u) }
        assertFails { layout.count(4095u, 4096u) }
        assertFails { layout.count(4113u, 4096u) }
        assertFails { layout.count(ULong.MAX_VALUE, 0u) }
        assertFails { LuaStackLayout(8, 12, 0, 16).withinObjects(32, 8) }
        assertFails { LuaStackLayout(8, 24, 0, 0) }
    }

    @Test
    fun rejectsUnknownArgumentsStoresWrongCountsAndUnrestoredRegisters() {
        // Synthetic layout and code, deliberately different from the installed game's offsets.
        val body = byteArrayOf(
            0x55, 0x48, 0x89.toByte(), 0xe5.toByte(),
            0x48, 0x8b.toByte(), 0x4f, 0x48, 0x48, 0x8b.toByte(), 0x57, 0x58,
            0x48, 0x2b, 0x4a, 0x10, 0x48, 0xc1.toByte(), 0xe9.toByte(), 0x05,
            0x89.toByte(), 0xf0.toByte(), 0x01, 0xc1.toByte(), 0x89.toByte(), 0xc8.toByte(), 0x5d, 0xc3.toByte()
        )
        assertEquals(LuaStackLayout(72, 88, 16, 32), SysVLuaStack.analyze(BinaryView(body)))
        fun changed(index: Int, value: Int) = BinaryView(body.copyOf().also { it[index] = value.toByte() })
        assertFails { SysVLuaStack.analyze(changed(21, 0xd0)) } // EDX is a pointer, not the fixed index.
        assertFails { SysVLuaStack.analyze(changed(26, 0x5b)) } // Restore RBX instead of RBP.
        assertFails { SysVLuaStack.analyze(changed(5, 0x89)) } // Write a state member.
        assertFails { SysVLuaStack.analyze(changed(19, 2)) } // Unsupported value width.
        assertFails { SysVLuaStack.analyze(BinaryView(body.copyOf(body.size - 1))) }
    }
}
