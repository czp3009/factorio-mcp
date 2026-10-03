package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class IconSpriteFieldsTest {
    private fun analyze(code: String, constructor: String = "48 89 77 18 c3", shift: Int = 0): IconSpriteFields {
        val bytes = machineCode(code)
        return IconSpriteFields.analyze(
            X64ControlFlow(X64Instructions(machineCode(constructor)).all()),
            X64ControlFlow(X64Instructions(bytes).all()), bytes, 0x1000, 96, 7,
            mapOf("getHeight" to listOf(DwarfRanges.Range(39L + shift, 43L + shift)),
                "getWidth" to listOf(DwarfRanges.Range(43L + shift, 47L + shift))),
        )
    }

    private val getter = "53 48 89 fb 48 8b 07 ff 50 38 84 c0 74 0c 80 7b 40 00 74 0f " +
            "48 8b 43 20 eb 0d 48 8b 43 28 48 85 c0 75 04 48 8b 43 18 " +
            "0f bf 48 06 0f bf 40 04 5b c3"

    @Test
    fun connectsConstructorEnabledBranchesAndNamedDimensionLoads() {
        val expected = IconSpriteFields(24, 32, 40, 4, 6)
        assertEquals(expected, analyze(getter))
        // Original argument preservation and store order do not require one observed constructor prefix.
        assertEquals(expected, analyze(getter, "53 48 89 fb 90 48 89 73 18 5b c3"))
        // An unrelated register setup and a byte-return copy do not change enabled-state provenance.
        assertEquals(expected, analyze(getter.replace("84 c0", "90 48 8d 4b 10 88 c2 84 d2"), shift = 7))
        // Loading a selected virtual function into a register is equivalent to a memory-indirect call.
        assertEquals(expected, analyze(getter.replace("ff 50 38", "4c 8b 58 38 90 41 ff d3"), shift = 5))
        // Unrelated floating-point work cannot invalidate the original receiver or the virtual entry.
        assertEquals(expected, analyze(getter.replace("48 8b 07", "f2 0f 5e c1 48 8b 07"), shift = 4))
        assertEquals(expected, analyze(getter.replace("48 8b 07", "f2 0f 59 c1 48 8b 07"), shift = 4))
    }

    @Test
    fun rejectsUnprovenRolesReceiversAndAmbiguousNativeStores() {
        for (code in listOf(
            getter.replace("ff 50 38", "ff 50 30"),
            getter.replace("48 8b 07", "48 8b 06"),
            getter.replace("84 c0", "31 c0"),
            getter.replace("43 28", "43 20"),
            getter.replace("0f bf 48 06", "0f bf 4b 06"),
        )) assertFails { analyze(code) }
        assertFails { analyze(getter, "48 89 77 18 48 89 77 20 c3") }
        assertFails { analyze(getter, "48 89 57 18 c3") }
    }
}
