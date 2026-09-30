@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test

/** Explicit selected-file metadata validation; not included in default Gradle tests. */
class WidgetDropdownAcceptanceTest {
    @Test
    fun resolvesSelectionAndOptionStorage() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use {
            val image = ElfImage(it.view)
            val fields = WidgetDropdownFields.resolve(image)
            println("Dropdown fields: $fields")
            for (type in listOf("N4agui8DropDownE") + ItaniumClass.descendants(image, "N4agui8DropDownE")) {
                println("Dropdown concrete type $type: ${WidgetDropdownLayout.resolve(image, type, fields)}")
            }
        }
    }
}
