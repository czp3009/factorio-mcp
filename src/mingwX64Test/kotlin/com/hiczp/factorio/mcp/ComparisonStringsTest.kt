package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ComparisonStringsTest {
    private val source = "48 83 ec 28 0f b6 51 05 83 fa 01 77 2c 4c 8d 05 ec fe ff ff " +
            "41 8b 8c 90 00 04 00 00 49 03 c8 ff e1 " +
            "48 8d 05 d8 01 00 00 48 83 c4 28 c3 48 8d 05 ec 01 00 00 48 83 c4 28 c3"
    private fun code(text: String) = BinaryView(text.split(' ').map { it.toInt(16).toByte() }.toByteArray())

    private fun read(address: Int, size: Int): ByteArray = when (address) {
        0x400, 0x404 -> {
            require(size == 4)
            val target = if (address == 0x400) 0x121 else 0x12d
            ByteArray(4) { (target ushr (8 * it)).toByte() }
        }
        0x300, 0x320 -> {
            require(size == 64)
            (if (address == 0x300) "≥" else "=").encodeToByteArray().copyOf(64)
        }
        else -> error("Read outside synthetic immutable image")
    }

    @Test
    fun readsOriginalUtf8StringsThroughBoundedNativeSwitchWithoutCalls() {
        assertEquals(mapOf(0 to "≥", 1 to "="), ComparisonStrings.analyze(code(source), 0x100, 5, 8, setOf(0, 1), ::read))
    }

    @Test
    fun rejectsWrongReceiverMemberWidthsReturnAbiAndUnboundedDomains() {
        for (changed in listOf(
            source.replace("0f b6 51 05", "0f b6 52 05"),
            source.replace("0f b6 51 05", "0f b6 51 06"),
            source.replace("48 83 c4 28", "48 83 c4 20"),
            source.replace("48 8d 05", "48 8d 1d"),
            source.replace("41 8b 8c 90", "41 8b 8c 50"),
        )) assertFails(changed) {
            ComparisonStrings.analyze(code(changed), 0x100, 5, 8, setOf(0, 1), ::read)
        }
        assertFails { ComparisonStrings.analyze(code(source), 0x100, 5, 8, setOf(2), ::read) }
        assertFails { ComparisonStrings.analyze(code(source), 0x100, 5, 8, setOf(0, 1)) { address, size ->
            if (address == 0x404) byteArrayOf(0x22, 0x01, 0, 0) else read(address, size)
        } }
        assertFails { ComparisonStrings.analyze(code(source), 0x100, 5, 8, setOf(0, 1)) { address, size ->
            read(address, size).let { if (size == 64) ByteArray(64) { 'x'.code.toByte() } else it }
        } }
    }
}
