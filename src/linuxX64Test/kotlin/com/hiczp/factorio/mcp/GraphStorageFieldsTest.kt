@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlin.test.*
import kotlinx.cinterop.toKString
import platform.posix.getenv

class GraphStorageFieldsTest {
    @Test
    fun matchesIndependentlyCompiledGraphStorageWithDifferentLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val offsets = listOf(1, 23).map { padding ->
            MappedBinary("$directory/graph_storage_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                val exported = image.symbol("fixture_graph_fields")
                val data = image.virtualBytes(exported.address, exported.size)
                val fields = List(9) { data.unsigned(it * 8L, 8) }
                // This fixture's libstdc++ storage is synthetic evidence only, never a game layout fallback.
                val string = NativeStringLayout(0, 8, 16, fields[8], image.symbol("_ZdlPvm"))
                val result = GraphStorageFields.resolve(image, fields[0], string)
                assertEquals(fields[1], result.sampleCount)
                assertEquals(fields[2], result.minimum)
                assertEquals(fields[3], result.maximum)
                assertEquals(fields[4], result.series.first)
                assertEquals(fields[5], result.series.stride)
                assertEquals(fields[6], result.values.first)
                assertEquals(fields[7], result.label)
                result.series.first
            }
        }
        assertNotEquals(offsets[0], offsets[1])
    }

    @Test
    fun sampleCountRequiresUnchangedOriginalScalarAndBoundedReceiver() {
        fun flow(code: String) = X64ControlFlow(X64Instructions(machineCode(code)).all())
        assertEquals(32L, GraphStorageFields.sampleCount(flow("89 77 20 c3"), 64))
        assertEquals(32L, GraphStorageFields.sampleCount(flow("41 89 f0 90 44 89 47 20 c3"), 64))
        assertFails { GraphStorageFields.sampleCount(flow("83 c6 01 89 77 20 c3"), 64) }
        assertFails { GraphStorageFields.sampleCount(flow("89 76 20 c3"), 64) }
        assertFails { GraphStorageFields.sampleCount(flow("89 77 20 c3"), 32) }
    }

    @Test
    fun achievementStateRequiresTheSameStoredPairInComparisonAndCopy() {
        fun flow(code: String) = X64ControlFlow(X64Instructions(machineCode(code)).all())
        val code = "8b 47 20 3b 47 24 74 03 89 47 24 c3"
        assertEquals(32L to 36L, StoredWidgetLayouts.stateCopy(flow(code), 64, 16))
        assertFails { StoredWidgetLayouts.stateCopy(flow(code.replace("89 47 24", "89 46 24")), 64, 16) }
        assertFails { StoredWidgetLayouts.stateCopy(flow(code.replace("3b 47 24", "3b 47 28")), 64, 16) }
        assertFails { StoredWidgetLayouts.stateCopy(flow(code), 36, 16) }
    }
}
