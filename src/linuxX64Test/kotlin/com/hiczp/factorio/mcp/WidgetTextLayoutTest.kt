@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetTextLayoutTest {
    @Test
    fun derivesDistinctVirtualTextMembersFromCompilerEvidence() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) {
            MappedBinary("$directory/accessor_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                val layout = WidgetTextLayout.resolve(
                    image, "7fixture", "7fixture4Text",
                    "fixture_string_data", "fixture_string_size"
                )

                fun value(name: String): Long = image.symbol(name).let {
                    image.virtualBytes(it.address, 8).unsigned(0, 8)
                }
                assertEquals((value("fixture_text_member") - 1) / 8, layout.slot.toLong())
                val base = layout.getters.single { it.function.name == "_ZNK7fixture4Text7getTextB5cxx11Ev" }
                val label = layout.getters.single { it.function.name == "_ZNK7fixture5Label7getTextB5cxx11Ev" }
                assertEquals(value("fixture_text_size"), base.objectSize)
                assertEquals(value("fixture_text_offset"), base.offset)
                assertEquals(value("fixture_label_size"), label.objectSize)
                assertEquals(value("fixture_label_offset"), label.offset)
            }
        }
    }
}
