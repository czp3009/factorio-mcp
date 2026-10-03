@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.ElfImage
import com.hiczp.factorio.mcp.MappedBinary
import com.hiczp.factorio.mcp.WorkerListenerMetadata
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class WorkerListenerMetadataAcceptanceTest {
    @Test
    fun derivesTypedWorkerCompletionAndRejectsChangedCode() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val metadata = WorkerListenerMetadata.resolve(image)
            var ranges = 0
            metadata.evidence.verify(image, 0) { address, size ->
                ++ranges
                image.virtualBytes(address, size.toLong()).bytes(0, size)
            }
            assertTrue(ranges > 0)
            assertFails {
                metadata.evidence.verify(image, 0) { address, size ->
                    image.virtualBytes(address, size.toLong()).bytes(0, size).also {
                        if (address == metadata.completion.function.address) it[0] = (it[0].toInt() xor 1).toByte()
                    }
                }
            }
            println("Worker completion: size=${metadata.workerSize}, listener=${metadata.listenerMember}, " +
                    "slot=${metadata.completion.slot}, caller=${metadata.caller}, verified=$ranges ranges")
        }
    }
}
