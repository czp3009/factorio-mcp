@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxCaptureLayout
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class GuiTargetResetTest {
    @Test
    fun derivesCompiledTargetResetAndRejectsMismatchedLayout() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/hover_gate_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun scalar(name: String): Long {
                val symbol = image.symbol(name)
                return image.virtualBytes(symbol.address, symbol.size).unsigned(0, 8)
            }

            val size = scalar("fixture_gui_size")
            val member = SysVPointerGate.resolve(image, "fixture_hover", size).member
            val layout = SysVTargeterRelease.Layout(
                scalar("fixture_target"), scalar("fixture_previous"),
                scalar("fixture_next"), scalar("fixture_head")
            )
            val base = scalar("fixture_widget_base")
            val proof = GuiTargetReset.resolve(image, "fixture_destroy", size, member, base, layout)
            assertEquals(scalar("fixture_capture_offset"), proof.targeter)
            assertEquals(
                listOf(
                    GuiTargetReset.Store(scalar("fixture_dragging"), 1, 0),
                    GuiTargetReset.Store(scalar("fixture_origin"), 8, 0x7fffffff7fffffffL)
                ), proof.stores
            )
            val binding = UiCaptureMetadata(
                size, base + layout.targetableExtent, base,
                image.symbol("fixture_destroy").address, layout, proof, emptyList()
            )
            memScoped {
                val native = alloc<FmLinuxCaptureLayout>()
                val bias = 0x100000000L
                binding.writeTo(native, bias)
                assertEquals(size.toUInt(), native.guiSize)
                assertEquals(base.toUInt(), native.widgetTargetable)
                assertEquals(proof.targeter.toUInt(), native.targeterMember)
                assertEquals((binding.release + bias).toULong(), native.targeter.release)
                assertEquals(layout.target.toUInt(), native.targeter.target)
                assertEquals(layout.previous.toUInt(), native.targeter.previous)
                assertEquals(layout.next.toUInt(), native.targeter.next)
                assertEquals(layout.head.toUInt(), native.targeter.head)
                assertEquals(proof.stores.size.toUInt(), native.resetCount)
                proof.stores.forEachIndexed { index, store ->
                    assertEquals(store.offset.toUInt(), native.resets[index].offset)
                    assertEquals(store.width.toUInt(), native.resets[index].width)
                    assertEquals(store.value.toULong(), native.resets[index].value)
                }
                assertFails { binding.writeTo(native, -1) }
                assertFails { binding.writeTo(native, Long.MAX_VALUE) }
            }
            assertFails { GuiTargetReset.resolve(image, "fixture_destroy", size, member, base + 8, layout) }
            assertFails {
                GuiTargetReset.resolve(
                    image, "fixture_destroy", size, member, base,
                    layout.copy(previous = layout.next, next = layout.previous)
                )
            }
            assertFails { GuiTargetReset.resolve(image, "fixture_destroy", member + 8, member, base, layout) }
            val function = image.symbol("fixture_destroy")
            val original = image.functionBytes(function, 8192)
            val instructions = X64Instructions(original).all(2048)
            val branch = instructions.first { it.operation == X64Instructions.Operation.JCC }
            val reversed = original.bytes(0, original.size.toInt())
            val conditionByte = branch.offset.toInt() + if (reversed[branch.offset.toInt()] == 0x0f.toByte()) 1 else 0
            reversed[conditionByte] = (reversed[conditionByte].toInt() xor 1).toByte()
            assertFails { GuiTargetReset.analyze(BinaryView(reversed), function.address, size, member, base, layout) }
            val constant = instructions.first {
                it.operation == X64Instructions.Operation.MOV && it.size >= 5 &&
                        (it.source as? X64Instructions.Immediate)?.value == 0x7fffffff7fffffffL
            }
            val dispatch = original.bytes(0, original.size.toInt())
            repeat(constant.size) { dispatch[constant.offset.toInt() + it] = 0x90.toByte() }
            dispatch[constant.offset.toInt()] = 0xe8.toByte()
            repeat(4) { dispatch[constant.offset.toInt() + 1 + it] = 0 }
            assertFails { GuiTargetReset.analyze(BinaryView(dispatch), function.address, size, member, base, layout) }
        }
    }
}
