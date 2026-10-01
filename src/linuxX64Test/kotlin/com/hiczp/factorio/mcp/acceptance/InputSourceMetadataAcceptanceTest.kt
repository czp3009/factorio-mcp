@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.*

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxInputContextConfig
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix._SC_PAGESIZE
import platform.posix.getenv
import platform.posix.sysconf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** Explicit installed-file analysis and optional selected-process evidence check. Does not install a hook or send input. */
class InputSourceMetadataAcceptanceTest {
    @Test
    fun resolvesCurrentPlayerInputOwnerAndEvaluationMethod() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select an installed executable" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val metadata = InputContextMetadata.resolve(image)
            println("Verified local input ownership and evaluation: ${metadata.source}")
            println("Verified native sender Game source member: ${metadata.gameSource}")
            println("Verified native evaluation return site: ${metadata.evaluationCaller}")
            println("Verified Map stop byte: ${metadata.stop}")
            println("Tick candidate requiring live Map equality: ${metadata.tick}")
            println("Pause candidate requiring live Map/source equality: ${metadata.pause}")
            println("Typed handler candidates: ${metadata.handlers}")
            var reads = 0
            metadata.verify(image, 0) { address, size ->
                ++reads
                image.virtualBytes(address, size.toLong()).bytes(0, size)
            }
            assertTrue(reads > 0)
            assertFails {
                metadata.verify(image, 0) { address, size ->
                    image.virtualBytes(address, size.toLong()).bytes(0, size).also {
                        it[0] = (it[0].toInt() xor 1).toByte()
                    }
                }
            }
            println("Checked $reads code/data ranges and rejected changed evidence")
            getenv("FACTORIO_MCP_TEST_PID")?.toKString()?.toInt()?.let { pid ->
                ProcessHandle(pid).use { process ->
                    val bias = image.loadBias(process.executableMappings(), sysconf(_SC_PAGESIZE))
                    metadata.verifyLoaded(image, process, bias)
                    println("Verified input context evidence in selected process $pid")
                }
            }
            memScoped {
                val config = alloc<FmLinuxInputContextConfig>()
                metadata.writeTo(config, 0)
                assertEquals(metadata.handlers.size.toUInt(), config.input.handlerCount)
                assertEquals(metadata.source.gameSize.toUInt(), config.world.gameSize)
                assertEquals(metadata.gameSource.toUInt(), config.input.gameSource)
                assertEquals(metadata.tick.mapTick.toUInt(), config.input.mapTick)
            }
        }
    }
}
