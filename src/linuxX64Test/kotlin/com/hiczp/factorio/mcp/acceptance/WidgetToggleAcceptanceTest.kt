@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.ElfImage
import com.hiczp.factorio.mcp.MappedBinary
import com.hiczp.factorio.mcp.WidgetToggleLayout
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test

/** Explicit installed-file check; no game process or injection is used. */
class WidgetToggleAcceptanceTest {
    @Test
    fun resolvesBoundedToggleGetter() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use {
            println("Button toggle metadata: ${WidgetToggleLayout.resolve(ElfImage(it.view))}")
        }
    }
}
