@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertFails

/** Explicit selected-file evidence; no game process or privileged debugger is required. */
class WidgetNumberAcceptanceTest {
    @Test
    fun resolvesInterfaceAndRejectsChangedLoadedEvidence() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val layout = WidgetNumberLayout.resolve(image)
            println("Number interface slots: count=${layout.count}, draw=${layout.draw}, zero=${layout.zero}, " +
                    "unknown=${layout.unknown}, infinite=${layout.infinite}")
            for (bias in listOf(0L, 0x100000000L)) {
                val evidence = linkedSetOf<Pair<Long, Int>>()
                fun loaded(address: Long, size: Int): ByteArray {
                    val bytes = image.virtualBytes(address - bias, size.toLong()).bytes(0, size)
                    for ((slot, target) in layout.pointers) for (index in 0..7) {
                        val offset = slot + bias + index - address
                        if (offset in 0 until size.toLong()) bytes[offset.toInt()] =
                            ((if (target == 0L) 0L else target + bias) ushr (index * 8)).toByte()
                    }
                    return bytes
                }
                layout.verify(image, bias) { address, size ->
                    evidence += address to size
                    loaded(address, size)
                }
                check(evidence.isNotEmpty())
                for (selected in evidence) assertFails {
                    layout.verify(image, bias) { address, size ->
                        loaded(address, size).also {
                            if (address to size == selected) it[0] = (it[0].toInt() xor 1).toByte()
                        }
                    }
                }
                println("Verified number evidence at bias $bias: ${evidence.size} ranges; changes rejected")
            }
        }
    }
}
