@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix._SC_PAGESIZE
import platform.posix.getenv
import platform.posix.sysconf
import kotlin.test.Test

/** Explicit loaded-code comparison only: no injection, hook installation or native game invocation. */
class GraphicsLoadedAcceptanceTest {
    @Test
    fun matchesSelectedProcessGraphicsEvidence() {
        val pid = checkNotNull(getenv("FACTORIO_MCP_TEST_PID")).toKString().toInt()
        require(pid > 0)
        ProcessHandle(pid).use { process ->
            process.withExecutable { image ->
                val bias = image.loadBias(process.executableMappings(), sysconf(_SC_PAGESIZE))
                val metadata = FrameContextMetadata.resolve(image)
                metadata.verifyLoaded(image, process, bias)
                val api = FrameApiBinding.bind(metadata.apiSlots, bias, process.mappings(), process::readMemory)
                val hook = FrameHookBinding.bind(
                    metadata.swap, metadata.backends, bias, sysconf(_SC_PAGESIZE),
                    process.mappings(), process::readMemory
                )
                println(
                    "Verified loaded graphics evidence for PID $pid: " +
                            "${metadata.evidence.functions.size} functions, ${metadata.evidence.pointers.size} pointer words, " +
                            "${metadata.backends.size} backend allocations, ${api.size} executable GL entries, " +
                            "current SDL swap device ${hook.device}"
                )
            }
        }
    }
}
