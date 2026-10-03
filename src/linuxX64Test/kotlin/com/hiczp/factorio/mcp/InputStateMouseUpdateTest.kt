@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SdlButtonAdmission.Button
import com.hiczp.factorio.mcp.SdlButtonAdmission.Transition
import com.hiczp.factorio.mcp.X64Instructions.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class InputStateMouseUpdateTest {
    @Test
    fun bindsTypedEventCasesToMatchingNativeSetAndClearMasks() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/input_state_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            val function = image.symbol("fixture_state_update")
            val tables = X64JumpTables.resolve(image, function)
            val instructions = X64Instructions(image.functionBytes(function, 32768)).all()
            val flow = X64ControlFlow(instructions, tables)
            val header = EventHeader(
                constant("fixture_event_extent").toInt(), constant("fixture_event_type"),
                constant("fixture_event_time")
            )
            val code = constant("fixture_event_code")
            val size = constant("fixture_state_size")
            val kinds = mapOf(Transition.PRESS to 3L, Transition.RELEASE to 5L)
            val codes = mapOf(Button.LEFT to 1L, Button.MIDDLE to 3L, Button.RIGHT to 2L, Button.X1 to 4L, Button.X2 to 5L)
            fun resolve(
                selectedFlow: X64ControlFlow = flow, selectedSize: Long = size,
                selectedCode: Long = code, selectedKinds: Map<Transition, Long> = kinds,
                selectedCodes: Map<Button, Long> = codes
            ) =
                InputStateMouseUpdate.analyze(
                    selectedFlow, tables.single(), selectedSize, header, selectedCode,
                    selectedKinds, selectedCodes
                )

            val proof = resolve()
            assertEquals(constant("fixture_state_mask_offset"), proof.member)
            assertEquals(mapOf(Button.LEFT to 1L, Button.MIDDLE to 4L, Button.RIGHT to 2L, Button.X1 to 8L, Button.X2 to 16L), proof.masks)
            assertFails { resolve(selectedSize = proof.member) }
            assertFails { resolve(selectedCode = code - 1) }
            assertFails { resolve(selectedKinds = kinds.mapValues { if (it.key == Transition.PRESS) 5 else 3 }) }
            assertFails { resolve(selectedCodes = codes.mapValues { 1L }) }
            val release = flow.body.getValue(proof.stores.getValue(Transition.RELEASE))
            for (changed in listOf(
                release.copy(operation = Operation.OR),
                release.copy(destination = (release.destination as Memory).copy(displacement = proof.member - 4))
            )) {
                val modified = X64ControlFlow(instructions.map { if (it == release) changed else it }, tables)
                assertFails { resolve(selectedFlow = modified) }
            }
            val arguments = SysVArgumentFlow(flow)
            val typeRead = instructions.single {
                arguments.source(it.offset) ==
                        SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, header.type), 4)
            }
            val press = flow.body.getValue(proof.stores.getValue(Transition.PRESS))
            val rotate = instructions.single { it.operation == Operation.ROL }
            for ((original, changed) in listOf(
                typeRead to typeRead.copy(source = (typeRead.source as Memory).copy(base = 7)),
                press to press.copy(destination = (press.destination as Memory).copy(base = 6)),
                rotate to rotate.copy(source = Immediate(0)),
            )) {
                val modified = X64ControlFlow(instructions.map { if (it == original) changed else it }, tables)
                assertFails { resolve(selectedFlow = modified) }
            }
        }
    }
}
