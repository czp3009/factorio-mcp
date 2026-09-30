@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.*

class SysVNoReturnTest {
    @Test
    fun verifiesDwarfDeclarationsAndImportedJumpBindings() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (version in 4..5) MappedBinary("$directory/debug_fixture_$version").use { file ->
            val image = ElfImage(file.view)
            assertEquals(
                setOf("fixture_never_return"), DwarfInfo(image)
                    .noReturnFunctions(setOf("fixture_never_return", "fixture_function"))
            )
            val verifier = SysVNoReturn(image, setOf("abort"))
            assertEquals(image.symbol("fixture_abort_wrapper").address, verifier.resolve("fixture_abort_wrapper"))
            assertFails { verifier.resolve("fixture_function") }
            assertFails { SysVNoReturn(image, emptySet()).resolve("fixture_abort_wrapper") }
        }
        MappedBinary("$directory/debug_fixture_lines").use { file ->
            assertFails { SysVNoReturn(ElfImage(file.view), setOf("abort")).resolve("fixture_abort_wrapper") }
        }
    }

    @Test
    fun requiresEveryNormalExitToBeProven() {
        fun check(code: String) = SysVNoReturn.analyze(machineCode(code), 0x1000) { it == 0x1100L }
        assertTrue(check("e8 fb 00 00 00"))
        assertTrue(check("e9 fb 00 00 00"))
        assertTrue(check("eb fe")) // A closed loop does not return normally.
        assertFalse(check("c3"))
        assertFalse(check("90")) // Falling out of a function range is not a proof.
        assertFalse(check("e8 fc 00 00 00"))
        assertFalse(check("ff e0"))
        assertFalse(check("85 ff 74 05 e8 f7 00 00 00 c3"))
    }
}
