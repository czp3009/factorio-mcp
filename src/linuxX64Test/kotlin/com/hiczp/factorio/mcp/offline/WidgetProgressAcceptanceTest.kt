@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.*

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test

/** Explicit selected-file analysis, never part of game-independent Gradle checks. */
class WidgetProgressAcceptanceTest {
    @Test
    fun resolvesProgressDirection() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use {
            val image = ElfImage(it.view)
            val fields = WidgetProgressFields.resolve(image)
            println("Progress fields: $fields")
            for (type in listOf("N4agui11ProgressBarE") + ItaniumClass.descendants(image, "N4agui11ProgressBarE")) {
                println("Progress concrete type $type: ${WidgetProgressLayout.resolve(image, type, fields)}")
            }
        }
    }
}
