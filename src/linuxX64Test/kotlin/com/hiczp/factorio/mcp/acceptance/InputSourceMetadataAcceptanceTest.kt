@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.ElfImage
import com.hiczp.factorio.mcp.InputContextMetadata
import com.hiczp.factorio.mcp.MappedBinary
import com.hiczp.factorio.mcp.ProcessHandle
import com.hiczp.factorio.mcp.loadBias
import kotlinx.cinterop.toKString
import platform.posix._SC_PAGESIZE
import platform.posix.getenv
import platform.posix.sysconf
import kotlin.test.Test

/** Explicit selected-process evidence check. Does not install a hook or send input. */
class InputSourceMetadataAcceptanceTest {
    @Test
    fun verifiesSelectedProcessEvidence() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        val pid = checkNotNull(getenv("FACTORIO_MCP_TEST_PID")).toKString().toInt()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val metadata = InputContextMetadata.resolve(image)
            ProcessHandle(pid).use { process ->
                val bias = image.loadBias(process.executableMappings(), sysconf(_SC_PAGESIZE))
                metadata.verifyLoaded(image, process, bias)
                println("Verified input context evidence in selected process $pid")
            }
        }
    }
}
