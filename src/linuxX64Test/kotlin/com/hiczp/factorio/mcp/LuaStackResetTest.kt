@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertFails

class LuaStackResetTest {
    @Test
    fun provesCompilerGeneratedZeroIndexResetWithDifferentLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/lua_stack_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) = image.virtualBytes(image.symbol(name).address, 8).unsigned(0, 8)
            val layout = SysVLuaStack.resolve(image, "fixture_absindex")
            val bytes = image.functionBytes(image.symbol("fixture_settop"), 512)
            val capacity = constant("fixture_end_offset")
            val size = constant("fixture_state_size")
            SysVLuaStackReset.analyze(bytes, layout, capacity, size)
            assertFails { SysVLuaStackReset.analyze(bytes, layout.copy(valueSize = 64), capacity, size) }
            assertFails { SysVLuaStackReset.analyze(bytes, layout, layout.top, size) }
            assertFails { SysVLuaStackReset.analyze(bytes, layout, layout.top + 1, size) }
            assertFails { SysVLuaStackReset.analyze(bytes, layout, capacity, capacity + 7) }
        }
    }

    private val layout = LuaStackLayout(24, 40, 0, 16)
    private fun fixture() = ("55 48 89 e5 48 8b 47 28 48 8b 00 85 f6 78 2e 89 f1 " +
            "48 8b 57 38 48 83 c0 10 48 29 c2 48 c1 fa 04 48 39 ca 7c 18 " +
            "48 c1 e1 04 48 01 c8 48 8b 4f 18 48 39 c1 73 02 cc cc " +
            "48 89 47 18 5d c3 cc cc")
        .split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun rejectsWrongArgumentsMembersStrideAndStores() {
        val bytes = fixture()
        SysVLuaStackReset.analyze(BinaryView(bytes), layout, 56, 128)
        fun fails(index: Int, value: Int) = assertFails("Mutation at byte $index") {
            SysVLuaStackReset.analyze(BinaryView(bytes.copyOf().also { it[index] = value.toByte() }), layout, 56, 128)
        }
        fails(7, 32) // Current frame is read from an unrelated state member.
        fails(12, 0xd2) // Index comes from EDX instead of the second argument.
        fails(24, 32) // Frame base skips an extra value.
        fails(31, 5) // Capacity uses a different value stride.
        fails(51, 0x72) // Wrong loop guard enters the memory-writing path.
        fails(58, 40) // Reset writes the frame pointer rather than the top.
        fails(59, 0x5b) // Restores the wrong callee-saved register.
    }
}
