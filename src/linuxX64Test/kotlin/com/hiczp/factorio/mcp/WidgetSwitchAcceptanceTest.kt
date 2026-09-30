@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test

/** Explicit selected-file metadata analysis; does not invoke game code or establish a runtime reader. */
class WidgetSwitchAcceptanceTest {
    @Test
    fun resolvesStateWithinEmbeddedSwitch() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use {
            val image = ElfImage(it.view)
            println("Switch state metadata: ${WidgetSwitchState.resolve(image)}")
        }
    }
}
