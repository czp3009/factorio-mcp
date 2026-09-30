@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class LuaBooleanMemberTest {
    @Test
    fun resolvesDifferentNativeObjectAndWrapperLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val fields = listOf(1, 23).map { padding ->
            MappedBinary("$directory/lua_boolean_member_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String): Long =
                    image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

                val expected =
                    LuaBooleanMember(constant("fixture_prototype_pointer"), constant("fixture_boolean_field"))
                val wrapper = constant("fixture_wrapper_extent")
                val extent = constant("fixture_prototype_extent")
                val top = constant("fixture_lua_top")
                fun resolve(wrapperSize: Long, objectSize: Long) = LuaBooleanMember.resolve(
                    image, "_ZNK7Wrapper4readEP5State",
                    "read", wrapperSize, objectSize, top, 8
                )
                assertEquals(expected, resolve(wrapper, extent))
                assertFails { resolve(expected.objectPointer + 7, extent) }
                assertFails { resolve(wrapper, expected.field) }
                expected
            }
        }
        assertNotEquals(fields[0], fields[1])
    }

    @Test
    fun rejectsChangedSourcesOutputsBoundsAndBypassedResults() {
        val code = "48 8b 47 10 0f b6 50 18 48 8b 46 20 89 10 b8 01 00 00 00 c3"
        fun analyze(text: String, scoped: Boolean = true): LuaBooleanMember {
            val flow = X64ControlFlow(X64Instructions(machineCode(text)).all())
            return LuaBooleanMember.analyze(flow, 32, 64, 32, 16, if (scoped) flow.body.keys else setOf(0))
        }
        assertEquals(LuaBooleanMember(16, 24), analyze(code))
        assertEquals(LuaBooleanMember(16, 24), analyze(code.replace("89 10", "89 50 08")))
        assertFails { analyze(code, false) }
        for (changed in listOf(
            code.replace("48 8b 47 10", "48 8b 46 10"),
            code.replace("48 8b 47 10", "48 8b 47 20"),
            code.replace("0f b6 50 18", "0f b6 50 40"),
            code.replace("0f b6 50 18", "0f b7 50 18"),
            code.replace("48 8b 46 20", "48 8b 47 20"),
            code.replace("89 10", "89 08"),
            code.replace("89 10", "31 d2 89 10"),
            code.replace("89 10", "88 10"),
            code.replace("89 10", "89 50 10"),
            code.replace("89 10", "85 c9 74 02 31 d2 89 10"),
            code.replace("89 10", "89 10 c7 00 01 00 00 00"),
            code.replace("89 10", "89 10 c7 40 02 01 00 00 00"),
            "85 c9 75 14 $code c3",
        )) assertFails { analyze(changed) }
    }
}
