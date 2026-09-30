@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class ControlUsageTest {
    @Test
    fun derivesUsageAndEnumForwardingThroughPrivateScalars() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val fields = listOf(1, 23).map { padding ->
            MappedBinary("$directory/control_usage_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String, width: Int = 8) = image.symbol(name).let {
                    image.virtualBytes(it.address, width.toLong()).unsigned(0, width)
                }

                val expected = ControlUsage(
                    constant("fixture_usage_field"), constant("fixture_usage_comparison", 4),
                    constant("fixture_usage_equal", 4), constant("fixture_usage_different", 4)
                )

                fun resolve(extent: Long) = ControlUsage.resolve(
                    image, extent, "_ZNK7Control6activeEv",
                    "_ZNK5Value6activeEb10Continuous", "_ZNK5Value9modifiersE10Continuous", "fixture_required"
                )
                assertEquals(expected, resolve(constant("fixture_control_size")))
                assertFails { resolve(expected.field + 3) }
                expected
            }
        }
        assertNotEquals(fields[0].field, fields[1].field)
        assertNotEquals(fields[0].comparison, fields[1].comparison)
        assertNotEquals(fields[0].whenEqual, fields[1].whenEqual)
    }

    @Test
    fun requiresOnlyOneBoundedEqualityToDetermineTheConversion() {
        val input = ScalarExpression.Input(0, SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, 24), 4), 4)
        val equal = ScalarExpression.Select(
            4, input, ScalarExpression.Literal(17, 4),
            ScalarExpression.Literal(3, 4), ScalarExpression.Literal(8, 4), 4
        )
        assertEquals(ControlUsage(24, 17, 3, 8), ControlUsage.conversion(equal, 64))
        assertEquals(ControlUsage(24, 17, 8, 3), ControlUsage.conversion(equal.copy(condition = 5), 64))
        assertFails { ControlUsage.conversion(equal, 27) }
        for (changed in listOf(
            equal.copy(condition = 2),
            equal.copy(yes = equal.no),
            equal.copy(yes = input),
            ScalarExpression.Binary(X64Instructions.Operation.ADD, equal, input, 4),
            equal.copy(left = input.copy(field = input.field.copy(reference = SysVArgumentFlow.Reference(6, 24)))),
        )) assertFails { ControlUsage.conversion(changed, 64) }
    }
}
