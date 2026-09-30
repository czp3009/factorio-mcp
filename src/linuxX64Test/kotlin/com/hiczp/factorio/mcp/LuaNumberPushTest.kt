@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertFails

class LuaNumberPushTest {
    @Test
    fun verifiesCompilerGeneratedDoubleArgumentStores() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/lua_stack_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String): Long =
                image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

            val stack = SysVLuaStack.analyze(image.functionBytes(image.symbol("fixture_absindex"), 512))
            val function = image.symbol("fixture_pushnumber")
            val code = image.functionBytes(function, 1024)
            SysVLuaNumberPush.analyze(code, function.address, stack, constant("fixture_state_size"))
            assertFails {
                SysVLuaNumberPush.analyze(
                    code,
                    function.address,
                    stack.copy(top = stack.top + 8),
                    constant("fixture_state_size")
                )
            }
        }
    }

    @Test
    fun rejectsWrongDoubleRegisterDestinationAndBypasses() {
        val stack = LuaStackLayout(16, 24, 0, 16)
        val valid = "48 8b 4f 10 48 8b 57 20 48 29 ca 48 83 fa 10 7e 05 f2 0f 11 01 c3 c3"
        fun verify(code: String) = SysVLuaNumberPush.analyze(machineCode(code), 0x1000, stack, 64)
        verify(valid)
        for (code in listOf(
            valid.replace("f2 0f 11 01", "f2 0f 11 09"),
            valid.replace("f2 0f 11 01", "f2 0f 11 07"),
            valid.replace("f2 0f 11 01", "f2 0f 11 02"),
            valid.replace("7e 05", "7f 05"),
            valid.replace("7e 05", "7e 7f"),
        )) assertFails { verify(code) }
        assertFails { verify(valid.replace("f2 0f 11 01 c3 c3", "85 f6 74 04 f2 0f 11 01 c3 c3")) }
    }
}
