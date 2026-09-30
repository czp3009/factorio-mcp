@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class PointerMemberArgumentTest {
    @Test
    fun connectsEmbeddedMembersAcrossLuaAndHiddenReturnReceivers() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val members = listOf(1, 23).map { padding ->
            MappedBinary("$directory/pointer_member_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
                val extent = constant("fixture_prototype_size")
                val lua = PointerMemberArgument.resolve(
                    image, "_ZNK7Wrapper4readEP5State", "fixture_push", 6,
                    constant("fixture_wrapper_size"), extent, mapOf(7 to 6)
                )
                val control = PointerMemberArgument.resolve(
                    image, "_ZNK7Control4nameEv", "_ZNK4Text6updateEv", 7,
                    constant("fixture_control_size"), extent
                )
                assertEquals(7, lua.input)
                assertEquals(6, control.input)
                assertEquals(constant("fixture_wrapper_pointer"), lua.pointer)
                assertEquals(constant("fixture_control_pointer"), control.pointer)
                assertEquals(constant("fixture_name_member"), lua.member)
                assertEquals(lua.member, control.member)
                val description = PointerMemberArgument.resolve(
                    image, "_ZNK7Wrapper11descriptionEP5State", "fixture_push", 6,
                    constant("fixture_wrapper_size"), extent, mapOf(7 to 6)
                )
                assertEquals(lua.input, description.input)
                assertEquals(lua.pointer, description.pointer)
                assertEquals(constant("fixture_description_member"), description.member)
                assertNotEquals(lua.member, description.member)
                lua.member
            }
        }
        assertNotEquals(members[0], members[1])
    }

    @Test
    fun rejectsWrongPointerBoundsArgumentsGuardsAndCalls() {
        val code = "48 8b 46 18 48 85 c0 74 0b 48 8d 78 20 e8 ee 0f 00 00 90 c3 90"
        fun analyze(text: String, owner: Long = 64, extent: Long = 96, callee: Long = 0x11000) =
            PointerMemberArgument.analyze(machineCode(text), 0x10000, callee, 7, owner, extent)

        val proof = analyze(code)
        assertEquals(6, proof.input)
        assertEquals(24L, proof.pointer)
        assertEquals(32L, proof.member)
        assertFails { analyze(code, owner = 31) }
        assertFails { analyze(code, extent = 32) }
        assertFails { analyze(code, callee = 0x12000) }
        for (changed in listOf(
            code.replace("48 8b 46 18", "48 8b 42 18"),
            code.replace("48 8b 46 18", "48 8b 46 19"),
            code.replace("48 85 c0", "48 85 ff"),
            code.replace("74 0b", "75 0b"),
            code.replace("74 0b", "74 04"),
            code.replace("48 8d 78 20", "48 8d 7e 20"),
            code.replace("48 8d 78 20", "48 8d 70 20"),
        )) assertFails { analyze(changed) }
    }
}
