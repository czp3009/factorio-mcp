@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.*

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test

/** Explicit selected-ELF analysis; this test never reads or calls a live game object. */
class WidgetCheckAcceptanceTest {
    @Test
    fun crossChecksNamedNativePredicates() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use {
            val image = ElfImage(it.view)
            val predicate = WidgetCheckPredicate.resolve(image)
            println("Native check predicate: $predicate")
            for (type in ItaniumClass.descendants(image, "N4agui12ToggleButtonE")) {
                println("Concrete check layout $type: ${WidgetCheckLayout.resolve(image, type, predicate)}")
            }
        }
    }
}
