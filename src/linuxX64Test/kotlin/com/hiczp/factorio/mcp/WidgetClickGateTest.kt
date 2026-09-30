@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class WidgetClickGateTest {
    @Test
    fun derivesTheFlagControllingNestedClickWithoutAssumingItsBitOrOffset() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/mouse_event_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun scalar(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            val size = scalar("fixture_widget_size")
            val down = image.symbol("_ZN6Widget12dispatchDownERK10MouseEvent")
            val click = image.symbol("_ZN6Widget8dispatchERK10MouseEvent")
            val bytes = image.functionBytes(down, 8192)
            val proof = WidgetClickGate.analyze(bytes, down.address, size, click.address)
            assertEquals(scalar("fixture_flags") * 8 + scalar("fixture_click_bit"), proof.offset * 8 + proof.shift)
            assertEquals(1UL, proof.maximum)
            assertFails { WidgetClickGate.analyze(bytes, down.address, proof.offset, click.address) }
            assertFails { WidgetClickGate.analyze(bytes, down.address, size, click.address + 1) }
            val body = X64Instructions(bytes).all()
            val branch = body.single { it.operation == Operation.JCC }
            val changed = bytes.bytes(0, bytes.size.toInt())
            val opcode = branch.offset.toInt() + if (changed[branch.offset.toInt()] == 0x0f.toByte()) 1 else 0
            changed[opcode] = (changed[opcode].toInt() xor 1).toByte()
            assertFails { WidgetClickGate.analyze(BinaryView(changed), down.address, size, click.address) }
            val prefix = machineCode("48 89 d6")
            assertFails {
                WidgetClickGate.analyze(
                    BinaryView(
                        prefix.bytes(0, prefix.size.toInt()) +
                                bytes.bytes(0, bytes.size.toInt())
                    ), down.address, size, click.address + prefix.size
                )
            }
        }
    }
}
