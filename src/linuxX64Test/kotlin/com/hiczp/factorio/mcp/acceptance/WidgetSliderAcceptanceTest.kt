@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.*

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test

/** Explicit installed-file analysis, separate from game-independent checks and live acceptance. */
class WidgetSliderAcceptanceTest {
    @Test
    fun resolvesSliderValueRangeAndStep() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use {
            val image = ElfImage(it.view)
            val fields = WidgetSliderFields.resolve(image)
            println("Slider fields: $fields")
            for (type in listOf("N4agui6SliderE") + ItaniumClass.descendants(image, "N4agui6SliderE")) {
                println("Slider concrete type $type: ${WidgetSliderLayout.resolve(image, type, fields)}")
            }
        }
    }
}
