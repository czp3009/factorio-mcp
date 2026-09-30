@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class WidgetEventDispatchTest {
    @Test
    fun derivesTheEventMaskThatDominatesNativeDispatch() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/mouse_event_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun scalar(name: String): Long {
                val symbol = image.symbol(name)
                return image.virtualBytes(symbol.address, symbol.size).unsigned(0, 8)
            }

            val function = image.symbol("_ZN6Widget8dispatchERK10MouseEvent")
            val method = ItaniumVtable.resolve(image, "_ZTV6Widget").method(image, "_ZN6Widget7onEventERK10MouseEvent")
            val size = scalar("fixture_widget_size")
            val eventSize = scalar("fixture_event_size")
            val proof = WidgetEventDispatch.mask(image, function, size, eventSize, method)
            assertEquals(WidgetEventDispatch.Mask(scalar("fixture_mask"), scalar("fixture_accepted"), 2), proof)
            assertFails { WidgetEventDispatch.mask(image, function, size, proof.event + proof.width - 1, method) }
            val bytes = image.functionBytes(function, 8192)
            val instructions = X64Instructions(bytes).all(2048)
            val test = instructions.first {
                it.operation == X64Instructions.Operation.TEST &&
                        listOf(
                            it.destination,
                            it.source
                        ).any { operand -> operand is X64Instructions.Memory && operand.width == proof.width }
            }
            val branch = instructions.first { it.offset == test.offset + test.size }
            val changed = bytes.bytes(0, bytes.size.toInt())
            val opcode = branch.offset.toInt() + if (changed[branch.offset.toInt()] == 0x0f.toByte()) 1 else 0
            changed[opcode] = (changed[opcode].toInt() xor 1).toByte()
            assertFails {
                WidgetEventDispatch.mask(
                    BinaryView(changed),
                    function.address,
                    size,
                    eventSize,
                    method.slot
                )
            }
        }
    }

    @Test
    fun verifiesPreservedArgumentsAcrossBorrowedLocalStorage() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/mouse_event_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            val sizeSymbol = image.symbol("fixture_widget_size")
            val size = image.virtualBytes(sizeSymbol.address, sizeSymbol.size).unsigned(0, 8)
            val function = image.symbol("_ZN6Widget8dispatchERK10MouseEvent")
            val method = ItaniumVtable.resolve(image, "_ZTV6Widget").method(image, "_ZN6Widget7onEventERK10MouseEvent")
            WidgetEventDispatch.verify(image, function, size, method)
            val code = image.functionBytes(function, 8192)
            val strict = SysVReceiverFlow(code, function.address, size)
            val call = strict.instructions.first {
                it.operation == X64Instructions.Operation.CALL &&
                        (it.destination as? X64Instructions.Memory)?.displacement == method.slot * 8L
            }
            assertFails { strict.call(call.offset) }
            for (prefix in listOf("48 89 d6", "48 89 f7")) {
                val first = machineCode(prefix)
                assertFails {
                    WidgetEventDispatch.verify(
                        BinaryView(
                            first.bytes(0, first.size.toInt()) +
                                    code.bytes(0, code.size.toInt())
                        ), function.address, size, method.slot
                    )
                }
            }
        }
    }

    @Test
    fun neverTrustsAnArgumentReloadedFromBorrowedStorage() {
        // Save RSI locally, lend the frame to a callee, then reload and dispatch through the original widget.
        val bytes = machineCode(
            "53 48 83 ec 10 48 89 fb 48 89 34 24 48 89 e7 e8 00 00 00 00 " +
                    "48 8b 34 24 48 89 df 48 8b 03 ff 50 10 48 83 c4 10 5b c3"
        )
        val failure = assertFails { WidgetEventDispatch.verify(bytes, 0, 64, 2) }
        assertTrue(failure.message.orEmpty().contains("does not preserve its original receiver and event"))
    }

    @Test
    fun invalidatesNewSpillsWhenAnEscapedLinkCanAliasTheFrame() {
        val bytes = machineCode(
            "53 41 54 48 83 ec 18 48 89 fb 49 89 f4 48 89 e0 48 89 43 08 " +
                    "48 89 e7 e8 00 00 00 00 4c 89 24 24 48 8b 43 08 48 c7 00 00 00 00 00 " +
                    "48 8b 34 24 48 89 df 48 8b 03 ff 50 10 48 83 c4 18 41 5c 5b c3"
        )
        val failure = assertFails { WidgetEventDispatch.verify(bytes, 0, 64, 2) }
        assertTrue(failure.message.orEmpty().contains("does not preserve its original receiver and event"))
    }
}
