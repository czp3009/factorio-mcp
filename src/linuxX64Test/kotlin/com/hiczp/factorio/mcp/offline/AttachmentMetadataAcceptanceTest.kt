@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.ElfImage
import com.hiczp.factorio.mcp.MappedBinary
import com.hiczp.factorio.mcp.WidgetLayout
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Explicit selected-file measurement; real attachment timing and loaded-code checks remain separate. */
class AttachmentMetadataAcceptanceTest {
    @Test
    fun resolvesTheCompleteUiLayoutFromTheSelectedExecutable() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select an installed ELF" }.toKString()
        MappedBinary(path).use { mapped ->
            val start = TimeSource.Monotonic.markNow()
            val image = ElfImage(mapped.view)
            val layout = WidgetLayout.resolve(image)
            assertTrue(layout.guiSize > 0 && layout.widgetSize > 0)
            println("factorio-mcp attachment UI metadata resolved in ${start.elapsedNow()}")
        }
    }
}
