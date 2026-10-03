@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.ElfImage
import com.hiczp.factorio.mcp.MappedBinary
import com.hiczp.factorio.mcp.WidgetIconLayout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.cinterop.toKString
import platform.posix.getenv

/** Explicit installed-file check, without injecting or requiring a running game. */
class WidgetIconMetadataTest {
    @Test
    fun resolvesOriginalWidgetReferencesWithoutImageLayouts() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val layout = WidgetIconLayout.resolve(ElfImage(file.view))
            assertTrue(layout.widgetType != layout.type && layout.evidence.functions.isNotEmpty())
            assertTrue(
                listOf(layout.fields.normal, layout.fields.hovered, layout.fields.disabled)
                    .distinct()
                    .size == 3
            )
            println(
                "Icon reference fields=${layout.fields}; verified functions=${layout.evidence.functions.size}"
            )
        }
    }
}
