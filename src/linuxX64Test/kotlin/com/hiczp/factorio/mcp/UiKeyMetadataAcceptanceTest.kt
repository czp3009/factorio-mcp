@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxPollHookConfig
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxUiKeyConfig
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix._SC_PAGESIZE
import platform.posix.getenv
import platform.posix.sysconf
import kotlin.test.Test
import kotlin.test.assertEquals

/** Explicit read-only composition against a selected live client. Does not inject or dispatch input. */
class UiKeyMetadataAcceptanceTest {
    @Test
    fun composesAndRelocatesLiveKeyboardConfiguration() {
        val pid = checkNotNull(getenv("FACTORIO_MCP_TEST_PID")) { "Select an initialized client explicitly" }
            .toKString().toInt()
        ProcessHandle(pid).use { process ->
            process.withExecutable { image ->
                val bias = image.loadBias(process.executableMappings(), sysconf(_SC_PAGESIZE))
                val metadata = UiKeyMetadata.resolve(image, process, bias)
                memScoped {
                    val event = alloc<FmLinuxUiKeyConfig>()
                    val site = alloc<FmLinuxPollHookConfig>()
                    metadata.writeTo(event, site, bias)
                    assertEquals((metadata.event.poll.poll.address + bias).toULong(), site.original)
                    assertEquals(
                        (metadata.event.poll.frame + metadata.event.poll.callerReturnFromFrame).toInt(),
                        site.eventFromFrame
                    )
                }
                println("factorio-mcp live keyboard metadata: loaded evidence and runtime configuration verified")
            }
        }
    }
}
