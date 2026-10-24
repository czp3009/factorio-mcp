@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Memory
import com.hiczp.factorio.mcp.X64Instructions.Operation
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxModalLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv

class UiModalMetadataTest {
    @Test
    fun followsProvenWidgetAliasesAndReorderedAncestryBlocks() {
        fun r(number: Int, width: Int = 8) = X64Instructions.Register(number, width)
        fun m(base: Int, offset: Long) = Memory(base, null, 1, offset, 8)
        fun i(value: Long) = X64Instructions.Immediate(value)
        val body = mutableListOf<X64Instructions.Instruction>()
        fun add(
            operation: Operation,
            destination: X64Instructions.Operand? = null,
            source: X64Instructions.Operand? = null,
            condition: Int? = null,
        ) {
            body +=
                X64Instructions.Instruction(
                    body.size.toLong(),
                    1,
                    operation,
                    destination,
                    source,
                    condition,
                )
        }
        add(Operation.LEA, r(8), m(6, 16))
        add(Operation.MOV, r(0), m(7, 8))
        add(Operation.MOV, r(2), m(7, 16))
        add(Operation.CMP, r(2), r(0))
        add(Operation.JCC, i(19), condition = 4)
        add(Operation.SUB, r(2), i(24))
        add(Operation.MOV, r(1), m(2, 0))
        add(Operation.TEST, r(1), r(1))
        add(Operation.JCC, i(3), condition = 4)
        add(Operation.CMP, r(1), r(8))
        add(Operation.JCC, i(21), condition = 4)
        add(Operation.SUB, r(1), i(16))
        add(Operation.JMP, i(16))
        add(Operation.MOV, r(6), m(6, 8))
        add(Operation.TEST, r(6), r(6))
        add(Operation.JCC, i(19), condition = 4)
        add(Operation.CMP, r(6), r(1))
        add(Operation.JCC, i(21), condition = 4)
        add(Operation.JMP, i(13))
        add(Operation.XOR, r(0, 4), r(0, 4))
        add(Operation.RET)
        add(Operation.MOV, r(0, 4), i(1))
        add(Operation.RET)
        fun analyze(instructions: List<X64Instructions.Instruction>) =
            UiModalMetadata.analyze(
                X64ControlFlow(instructions),
                DwarfRanges.Range(1, instructions.size.toLong()),
                64,
                64,
                16,
                8,
            )
        val result = analyze(body)
        assertEquals(8, result.begin)
        assertEquals(16, result.end)
        assertEquals(24, result.stride)
        assertFails {
            analyze(
                body.mapIndexed { index, instruction ->
                    if (index == 0) instruction.copy(source = m(2, 16)) else instruction
                }
            )
        }
        assertFails {
            analyze(
                body.mapIndexed { index, instruction ->
                    if (index == 13) instruction.copy(operation = Operation.MOV, source = r(6))
                    else instruction
                }
            )
        }
    }

    @Test
    fun derivesModalStackAndAncestryAcrossCompiledLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/modal_fixture_$padding").use { file
            ->
            val image = ElfImage(file.view)
            fun scalar(name: String) =
                image.symbol("fixture_$name").let {
                    image.virtualBytes(it.address, it.size).unsigned(0, 8)
                }

            val function = image.symbol("fixture_modal")
            val flow = X64ControlFlow.resolve(image, function)
            fun analyze(selected: X64ControlFlow = flow, parent: Long = scalar("parent")) =
                UiModalMetadata.analyze(
                    selected,
                    DwarfRanges.Range(0, function.size),
                    scalar("gui_size"),
                    scalar("widget_size"),
                    scalar("targetable"),
                    parent,
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
            val parentLoad =
                flow.instructions.single { instruction ->
                    instruction.operation == Operation.MOV &&
                        (instruction.source as? Memory)?.displacement == scalar("parent") &&
                        (instruction.destination as? X64Instructions.Register)?.number ==
                            instruction.source.base
                }
            val changed =
                flow.instructions.map {
                    if (it == parentLoad)
                        it.copy(
                            source = (it.source as Memory).copy(displacement = scalar("parent") + 8)
                        )
                    else it
                }
            assertFails { analyze(X64ControlFlow(changed)) }
            val inverted =
                flow.instructions.map {
                    if (it.operation == Operation.SET)
                        it.copy(condition = checkNotNull(it.condition) xor 1)
                    else it
                }
            check(inverted != flow.instructions)
            assertFails { analyze(X64ControlFlow(inverted)) }
            // An extracted inline can return through an earlier epilogue. That edge leaves the
            // analyzed region;
            // it is not a reverse-scan loop inside the predicate.
            val prefix = X64Instructions(machineCode("eb 03 31 c0 c3")).all()
            val shifted =
                flow.instructions
                    .map { instruction ->
                        val destination =
                            if (instruction.operation in listOf(Operation.JMP, Operation.JCC))
                                X64Instructions.Immediate(
                                    (instruction.destination as X64Instructions.Immediate).value + 5
                                )
                            else instruction.destination
                        instruction.copy(offset = instruction.offset + 5, destination = destination)
                    }
                    .toMutableList()
            val returning = shifted.indexOfLast { it.operation == Operation.RET }
            shifted[returning] =
                shifted[returning].copy(
                    operation = Operation.JMP,
                    destination = X64Instructions.Immediate(4),
                )
            val backwardExit = X64ControlFlow(prefix + shifted)
            assertEquals(
                result,
                UiModalMetadata.analyze(
                    backwardExit,
                    DwarfRanges.Range(5, function.size + 5),
                    result.guiSize,
                    result.widgetSize,
                    result.widgetTargetable,
                    result.parent,
                    mapOf(4L to false, 0L to true),
                ),
            )
        }
    }
}
