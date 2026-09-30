@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Memory
import com.hiczp.factorio.mcp.X64Instructions.Operation
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxModalLayout
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class UiModalMetadataTest {
    @Test
    fun derivesModalStackAndAncestryAcrossCompiledLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/modal_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun scalar(name: String) = image.symbol("fixture_$name").let {
                image.virtualBytes(it.address, it.size).unsigned(0, 8)
            }

            val function = image.symbol("fixture_modal")
            val flow = X64ControlFlow.resolve(image, function)
            fun analyze(selected: X64ControlFlow = flow, parent: Long = scalar("parent")) =
                UiModalMetadata.analyze(
                    selected, DwarfRanges.Range(0, function.size), scalar("gui_size"),
                    scalar("widget_size"), scalar("targetable"), parent
                )

            val result = analyze()
            assertEquals(scalar("begin"), result.begin)
            assertEquals(scalar("end"), result.end)
            assertEquals(scalar("stride"), result.stride)
            assertEquals(scalar("target"), result.target)
            memScoped {
                val native = alloc<FmLinuxModalLayout>()
                result.writeTo(native)
                assertEquals(result.guiSize.toUInt(), native.guiSize)
                assertEquals(result.widgetSize.toUInt(), native.widgetSize)
                assertEquals(result.begin.toUInt(), native.begin)
                assertEquals(result.end.toUInt(), native.end)
                assertEquals(result.stride.toUInt(), native.stride)
                assertEquals(result.target.toUInt(), native.target)
                assertEquals(result.widgetTargetable.toUInt(), native.widgetTargetable)
                assertEquals(result.parent.toUInt(), native.parent)
            }
            assertFails { analyze(parent = scalar("parent") + 8) }
            val parentLoad = flow.instructions.single { instruction ->
                instruction.operation == Operation.MOV && (instruction.source as? Memory)?.displacement == scalar("parent") &&
                        (instruction.destination as? X64Instructions.Register)?.number == instruction.source.base
            }
            val changed = flow.instructions.map {
                if (it == parentLoad) it.copy(source = (it.source as Memory).copy(displacement = scalar("parent") + 8)) else it
            }
            assertFails { analyze(X64ControlFlow(changed)) }
            val inverted = flow.instructions.map {
                if (it.operation == Operation.SET) it.copy(condition = checkNotNull(it.condition) xor 1) else it
            }
            check(inverted != flow.instructions)
            assertFails { analyze(X64ControlFlow(inverted)) }
            // An extracted inline can return through an earlier epilogue. That edge leaves the analyzed region;
            // it is not a reverse-scan loop inside the predicate.
            val prefix = X64Instructions(machineCode("eb 03 31 c0 c3")).all()
            val shifted = flow.instructions.map { instruction ->
                val destination = if (instruction.operation in listOf(Operation.JMP, Operation.JCC))
                    X64Instructions.Immediate((instruction.destination as X64Instructions.Immediate).value + 5)
                else instruction.destination
                instruction.copy(offset = instruction.offset + 5, destination = destination)
            }.toMutableList()
            val returning = shifted.indexOfLast { it.operation == Operation.RET }
            shifted[returning] = shifted[returning].copy(
                operation = Operation.JMP,
                destination = X64Instructions.Immediate(4)
            )
            val backwardExit = X64ControlFlow(prefix + shifted)
            assertEquals(
                result, UiModalMetadata.analyze(
                    backwardExit,
                    DwarfRanges.Range(5, function.size + 5),
                    result.guiSize,
                    result.widgetSize,
                    result.widgetTargetable,
                    result.parent,
                    mapOf(4L to false, 0L to true)
                )
            )
        }
    }
}
