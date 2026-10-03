@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** Explicit selected-file proof for the common provider; no injection or native game call. */
class WidgetIdentityAcceptanceTest {
    @Test
    fun resolvesRawQualityConditionFieldsAndNativeComparisonStrings() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val fields = QualityConditionFields.resolve(ElfImage(file.view))
            assertTrue(fields.quality != fields.comparison && fields.comparisons.isNotEmpty())
            assertTrue(fields.registryBegin != fields.registryEnd)
            println("Raw condition: quality=${fields.quality}, comparison=${fields.comparison}, values=${fields.comparisons}")
            println("Quality registry: begin=${fields.registryBegin}, end=${fields.registryEnd}")
            val returned = QualityConditionReturn.resolve(ElfImage(file.view), fields)
            assertTrue(returned.quality != returned.comparison)
            println("Returned condition: width=${returned.width}, quality=${returned.quality}, comparison=${returned.comparison}")
        }
    }

    @Test
    fun resolvesExistingItemFieldsThroughTypedLuaReaders() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val layout = WidgetItemLayout.resolve(ElfImage(file.view))
            assertTrue(layout.stackItem + 8 <= layout.stackExtent)
            assertTrue(layout.health + 4 <= layout.itemSize)
            assertTrue(layout.durability + 8 <= layout.toolSize)
            assertTrue(layout.magazine + 4 <= layout.ammoSize)
            println("Existing items: stack item=${layout.stackItem}, count=${layout.count}, health=${layout.health}, durability=${layout.durability}, magazine=${layout.magazine}")
        }
    }

    @Test
    fun resolvesBothElementProviderSlotsInsideIndependentPrefixes() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val layout = WidgetElementInterfaces.resolve(ElfImage(file.view))
            assertTrue(layout.stackProvider != layout.itemProvider)
            println("Element providers: stack slot=${layout.stackGetter}, item slot=${layout.itemGetter}")
        }
    }

    @Test
    fun resolvesRawPrototypeNameInsideIndependentPrefix() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val layout = PrototypeNameLayout.resolve(image)
            assertTrue(layout.offset + layout.string.size <= layout.minimumExtent)
            println("Prototype name=${layout.offset}, minimum prefix=${layout.minimumExtent}, quality base=${layout.qualityBase}")
        }
    }

    @Test
    fun resolvesCommonProviderThroughConcreteSecondaryRttiAndChecksLoadedEvidence() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val layout = WidgetIdentityInterfaces.resolve(image)
            assertTrue(layout.prototype != layout.quality)
            println("Prototype provider: prototype slot=${layout.prototype}, quality slot=${layout.quality}")
            for (bias in listOf(0L, 0x100000000L)) {
                fun loaded(address: Long, size: Int): ByteArray {
                    val bytes = image.virtualBytes(address - bias, size.toLong()).bytes(0, size)
                    for ((slot, target) in layout.evidence.pointers) for (index in 0..7) {
                        val offset = slot + bias + index - address
                        if (offset in 0 until size.toLong()) bytes[offset.toInt()] =
                            ((if (target == 0L) 0 else target + bias) ushr (index * 8)).toByte()
                    }
                    return bytes
                }
                layout.evidence.verify(image, bias, ::loaded)
                val evidence = layout.evidence
                val cases = buildList {
                    for (function in evidence.functions) add(function.address to
                            ElfEvidence(listOf(function), emptyList(), emptyMap(), emptyMap()))
                    for (range in evidence.readonly) add(range.address to
                            ElfEvidence(emptyList(), listOf(range), evidence.pointers, emptyMap()))
                    for ((address, target) in evidence.pointers) add(address to
                            ElfEvidence(emptyList(), emptyList(), mapOf(address to target), emptyMap()))
                    for ((address, value) in evidence.scalars) add(address to
                            ElfEvidence(emptyList(), emptyList(), emptyMap(), mapOf(address to value)))
                }
                assertTrue(cases.isNotEmpty())
                for ((selected, isolated) in cases) assertFails {
                    isolated.verify(image, bias) { address, size ->
                        loaded(address, size).also {
                            if (address == selected + bias) it[0] = (it[0].toInt() xor 1).toByte()
                        }
                    }
                }
                println("Checked provider evidence at bias $bias: ${cases.size} ranges")
            }
        }
    }
}
