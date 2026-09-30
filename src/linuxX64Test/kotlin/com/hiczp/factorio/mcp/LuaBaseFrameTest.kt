@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LuaBaseFrameTest {
    @Test
    fun derivesEmbeddedFramesFromPaddedInitializers() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/lua_base_frame_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) =
                image.symbol("fixture_$name").let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

            val allocation = LuaAllocation(
                constant("allocation_size"), constant("global_member"), constant("global_offset"),
                constant("allocator"), constant("userdata"), 0
            )
            val stack = LuaStackLayout(constant("top"), constant("frame"), constant("function"), 16)
            val entry = image.symbol("fixture_open")
            fun analyze(
                identity: LuaAllocation = allocation,
                layout: LuaStackLayout = stack,
                limit: Long = constant("limit")
            ) =
                SysVLuaBaseFrame.analyze(
                    image.functionBytes(entry, 8192), entry.address, image.symbol("fixture_next").address,
                    identity, layout, limit
                )
            assertEquals(constant("embedded"), analyze())
            assertFails { analyze(identity = allocation.copy(global = allocation.global + 1)) }
            assertFails { analyze(identity = allocation.copy(allocator = allocation.allocator + 1)) }
            assertFails { analyze(identity = allocation.copy(userdata = allocation.userdata + 1)) }
            assertFails { analyze(identity = allocation.copy(globalOffset = constant("embedded") + constant("limit") + 7)) }
            assertFails { analyze(layout = stack.copy(callInfo = stack.callInfo + 1)) }
            assertFails { analyze(limit = 4096) }
            assertFails { analyze(limit = stack.function) }
        }
    }
}
