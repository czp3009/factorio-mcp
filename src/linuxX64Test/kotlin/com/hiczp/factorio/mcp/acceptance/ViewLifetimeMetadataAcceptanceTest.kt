@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.ElfImage
import com.hiczp.factorio.mcp.MappedBinary
import com.hiczp.factorio.mcp.ProcessHandle
import com.hiczp.factorio.mcp.SysVMemberCalls
import com.hiczp.factorio.mcp.SysVObjectSize
import com.hiczp.factorio.mcp.SysVOwnedObjectSize
import com.hiczp.factorio.mcp.ViewRetirementMetadata
import com.hiczp.factorio.mcp.loadBias
import kotlinx.cinterop.toKString
import platform.posix._SC_PAGESIZE
import platform.posix.getenv
import platform.posix.sysconf
import kotlin.test.Test

/** Explicit selected-process evidence check. Does not install a hook or retire an object. */
class ViewLifetimeMetadataAcceptanceTest {
    @Test
    fun verifiesSelectedProcessEvidence() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        val pid = checkNotNull(getenv("FACTORIO_MCP_TEST_PID")).toKString().toInt()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val size = SysVOwnedObjectSize.resolve(image,
                "_ZNSt10unique_ptrI4GameSt14default_deleteIS0_EED2Ev", "_ZN4GameD2Ev").size
            val member = SysVMemberCalls.directPrefix(image, "_ZN4GameD2Ev", "_ZN8GameView9unloadGuiEv", size)
            val metadata = ViewRetirementMetadata.resolve(image, size, SysVObjectSize.resolve(image, "8GameView"), member)
            ProcessHandle(pid).use { process ->
                val bias = image.loadBias(process.executableMappings(), sysconf(_SC_PAGESIZE))
                println("Verified live retirement entry: ${metadata.verifyLoaded(image, process, bias)}")
            }
        }
    }
}
