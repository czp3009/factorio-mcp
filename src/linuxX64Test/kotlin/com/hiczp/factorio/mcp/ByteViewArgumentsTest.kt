@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class ByteViewArgumentsTest {
    @Test
    fun derivesAggregateOrderAndPreservesItAcrossCallsAndTailForwarding() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val layouts = listOf(1, 23).map { padding ->
            MappedBinary("$directory/byte_view_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String) =
                    image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8).toInt() }

                val lookup = ByteViewArguments.resolve(image, "fixture_lookup")
                assertEquals(
                    ByteViewArguments(constant("fixture_lookup_data"), constant("fixture_lookup_length")),
                    lookup
                )
                val provider = lookup.forwarded(image, "fixture_provider", "fixture_lookup")
                assertEquals(
                    ByteViewArguments(constant("fixture_provider_data"), constant("fixture_provider_length")),
                    provider
                )
                val optional = provider.forwarded(
                    image, "fixture_optional", "fixture_provider", tail = true,
                    unchanged = mapOf(7 to 7), zero = setOf(8, 9)
                )
                assertEquals(provider, optional)
                assertFails {
                    provider.forwarded(
                        image, "fixture_optional", "fixture_provider", tail = true,
                        unchanged = mapOf(7 to 6)
                    )
                }
                assertFails { lookup.forwarded(image, "fixture_provider", "fixture_optional") }

                val entry = image.symbol("fixture_optional")
                val bytes = image.functionBytes(entry, 4096)
                val zero = X64Instructions(bytes).all().single {
                    it.operation == Operation.XOR && it.destination == Register(8, 4) && it.source == Register(8, 4)
                }
                val changed = bytes.bytes(0, bytes.size.toInt())
                repeat(zero.size) { changed[zero.offset.toInt() + it] = 0x90.toByte() }
                assertFails {
                    provider.forwarded(
                        X64ControlFlow(X64Instructions(BinaryView(changed)).all()),
                        image.symbol("fixture_provider").address - entry.address, tail = true,
                        unchanged = mapOf(7 to 7), zero = setOf(8, 9)
                    )
                }
                lookup
            }
        }
        assertNotEquals(layouts[0], layouts[1])
    }

    @Test
    fun rejectsAmbiguousChangedAndTruncatedComparisonWords() {
        val code = "53 4c 89 c7 4c 89 ca 48 8b 31 e8 f1 0f 00 00 5b c3"
        fun verify(text: String): ByteViewArguments {
            val flow = X64ControlFlow(X64Instructions(machineCode(text)).all())
            val call =
                flow.instructions.single { it.operation == Operation.CALL && it.destination == Immediate(0x1000) }
            return ByteViewArguments.compared(flow, setOf(call.offset))
        }
        assertEquals(ByteViewArguments(8, 9), verify(code))
        for (changed in listOf(
            code.replace("48 8b 31", "48 89 ce"),
            code.replace("4c 89 ca", "4c 89 c2"),
            code.replace("4c 89 ca", "44 89 ca"),
            code.replace("4c 89 c7", "44 89 c7"),
            code.replace("4c 89 c7", "48 89 df"),
        )) assertFails { verify(changed) }
    }

    @Test
    fun rejectsDifferentOriginalWordsAtRepeatedDispatches() {
        val code = "53 41 54 48 83 ec 08 4c 89 c3 4d 89 cc 48 89 df 4c 89 e6 e8 e8 0f 00 00 " +
                "48 89 df 4c 89 e6 e8 dd 0f 00 00 48 83 c4 08 41 5c 5b c3"

        fun verify(text: String) = ByteViewArguments(7, 6).forwarded(
            X64ControlFlow(X64Instructions(machineCode(text)).all()), 0x1000
        )
        assertEquals(ByteViewArguments(8, 9), verify(code))
        val swapped = code.replace("48 89 df 4c 89 e6 e8 dd", "4c 89 e7 48 89 de e8 dd")
        assertFails { verify(swapped) }
    }

    @Test
    fun rejectsExtraTailExitsAndCallsThatExposeThePrivateFrame() {
        val code = "48 83 ec 08 48 89 0c 24 48 8d 3c 24 e8 ef 0f 00 00 48 8b 0c 24 48 83 c4 08 e9 e2 1f 00 00"
        val flow = X64ControlFlow(X64Instructions(machineCode(code)).all())
        assertFails { ByteViewArguments(1, 2).forwarded(flow, 0x2000, tail = true) }
        val extraExit = X64ControlFlow(X64Instructions(machineCode("85 c0 74 05 e9 f7 0f 00 00 c3")).all())
        assertFails { ByteViewArguments(1, 2).forwarded(extraExit, 0x1000, tail = true) }
    }
}
