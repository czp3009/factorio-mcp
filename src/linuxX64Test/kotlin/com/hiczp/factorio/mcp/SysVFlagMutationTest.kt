@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class SysVFlagMutationTest {
    @Test
    fun derivesIndependentlyCompiledFlagOffsetsAndMasks() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val results = mutableListOf<NativeAccessor>()
        for (padding in listOf(1, 23)) MappedBinary("$directory/accessor_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun value(name: String, width: Int = 8): Long {
                val symbol = image.symbol(name)
                return image.virtualBytes(symbol.address, symbol.size).unsigned(0, width)
            }

            val size = value("fixture_accessor_size")
            val set = SysVFlagMutation.resolveConstant(image, "fixture_flag_set", size, true)
            assertEquals(set, SysVFlagMutation.resolveConstant(image, "fixture_flag_clear", size, false))
            assertEquals(set, SysVFlagMutation.resolveBoolean(image, "fixture_flag_boolean", size))
            val bit = value("fixture_mutation_mask", 4).toULong().countTrailingZeroBits()
            assertEquals(value("fixture_count_offset") + bit / 8, set.offset)
            assertEquals(1uL shl (bit % 8), set.mask)
            results += set
        }
        assertNotEquals(results[0].offset, results[1].offset)
        assertNotEquals(results[0].mask, results[1].mask)
    }

    @Test
    fun tracksArgumentBranchesAndRejectsUnrelatedWrites() {
        val code = "8b 47 20 40 84 f6 74 07 83 c8 10 89 47 20 c3 83 e0 ef 89 47 20 c3"
        val expected = NativeAccessor(32, 1, 16u, 4)
        for (set in listOf(false, true)) {
            assertEquals(expected, SysVFlagMutation.analyze(machineCode(code), 64, set, set))
            assertFails { SysVFlagMutation.analyze(machineCode(code), 64, !set, set) }
            assertFails { SysVFlagMutation.analyze(machineCode(code), 35, set, set) }
        }
        for (changed in listOf(
            code.replace("8b 47 20", "8b 46 20"),
            code.replace("89 47 20", "89 47 24"),
            code.replace("83 c8 10", "83 c8 30"),
            code.replace("83 c8 10", "83 f0 10"),
            code.replace("40 84 f6", "40 84 d2"),
            "e8 00 00 00 00 $code",
            "eb fe $code",
        )) assertFails(changed) { SysVFlagMutation.analyze(machineCode(changed), 64, true, true) }
    }

    @Test
    fun acceptsGuardedNoChangePathsWithoutInventingFieldValues() {
        val code = "55 48 89 e5 8b 47 20 a9 10 00 00 00 75 02 5d c3 83 e0 ef 89 47 20 5d c3"
        assertEquals(NativeAccessor(32, 1, 16u, 4), SysVFlagMutation.analyze(machineCode(code), 64, false))
        assertFails { SysVFlagMutation.analyze(machineCode("c3"), 64, false) }
        assertFails { SysVFlagMutation.analyze(machineCode("c7 47 20 10 00 00 00 c3"), 64, true) }
    }
}
