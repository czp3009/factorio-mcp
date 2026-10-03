@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.*
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxPointerEventLayout
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxPointerStateLayout
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxInputDispatchConfig
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.PROT_READ
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** Explicit selected-file discovery and evidence validation, never attaches to or starts a game. */
class PointerMetadataAcceptanceTest {
    @Test
    fun resolvesAllPointerVariantsAndCursorWithLoadedEvidence() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val keyboard = KeyboardEventMetadata.resolve(image)
            val mouse = MouseStateMetadata.resolve(image)
            val keys = KeyboardStateMetadata.resolve(image, mouse)
            val dispatch = InputDispatchMetadata.resolve(image, keyboard, keys)
            val metadata = dispatch.pointer
            println("Native pump scalar argument/status=${dispatch.call}, entry capture=${dispatch.capture}")
            println("Native pointer variants: ${metadata.payload.cases}")
            println("Native pointer codes=${metadata.payload.codes}, masks=${metadata.masks}, cursor=${metadata.cursor}")
            println("Native pointer state reads: ${metadata.stateUpdates}")
            println("Native pointer post reads: ${metadata.postUpdates}")
            val bias = 0x100000000L
            val ranges = mutableSetOf<Pair<Long, Int>>()
            dispatch.verify(image, bias) { address, size ->
                ranges += address to size
                image.virtualBytes(address - bias, size.toLong()).bytes(0, size)
            }
            check(ranges.isNotEmpty())
            for (selected in ranges) assertFails {
                dispatch.verify(image, bias) { address, size ->
                    image.virtualBytes(address - bias, size.toLong()).bytes(0, size).also {
                        if (address to size == selected) it[0] = (it[0].toInt() xor 1).toByte()
                    }
                }
            }
            memScoped {
                val event = alloc<FmLinuxPointerEventLayout>()
                val state = alloc<FmLinuxPointerStateLayout>()
                val config = alloc<FmLinuxInputDispatchConfig>()
                dispatch.writeTo(config, PROT_READ, bias)
                assertEquals((keyboard.poll.pump.address + bias).toULong(), config.pump)
                assertEquals(0u, config.pumpArgument)
                assertEquals((mouse.state.global + bias).toULong(), config.owner.global)
                assertEquals(keys.update.map.toUInt(), config.keys.map)
                assertEquals((keyboard.poll.caller.address + keyboard.poll.returnOffset + bias).toULong(), config.site.caller)
                assertEquals((keyboard.poll.pump.address + keyboard.poll.pumpReturnOffset + bias).toULong(), config.site.pumpCaller)
                assertEquals(metadata.payload.header.extent.toUInt(), config.site.eventExtent)
                assertFails { dispatch.writeTo(config, 0, bias) }
                assertFails { dispatch.writeTo(config, PROT_READ, -1) }
                metadata.writeTo(event)
                metadata.writeTo(state)
                assertEquals(metadata.cursor.position.toUInt(), state.position)
                assertEquals(metadata.cursor.inWindow.toUInt(), state.inWindow)
                metadata.payload.cases.forEachIndexed { index, case ->
                    assertEquals(case.kind.toUInt(), event.cases[index].kind)
                    for (byte in 0 until 256)
                        assertEquals(if (byte.toLong() in case.defaults) 1.toUByte() else 0.toUByte(),
                            event.cases[index].initialized[byte])
                }
                for (index in 0 until 5) {
                    assertEquals(metadata.payload.codes[index].toUInt(), event.codes[index])
                    assertEquals(metadata.masks[index].toUInt(), state.masks[index])
                }
            }
            println("Verified keyboard/pointer/pump code/data evidence: ${ranges.size} ranges, corruption rejected individually")
        }
    }
}
