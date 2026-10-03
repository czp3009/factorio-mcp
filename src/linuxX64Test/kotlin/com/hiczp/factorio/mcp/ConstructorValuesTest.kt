@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlinx.cinterop.toKString
import platform.posix.getenv

class ConstructorValuesTest {
    private fun savedFrame(
        middle: String = "",
        inputs: Boolean = true,
        aliases: Boolean = true,
        extent: Int = 16,
    ): ConstructorValues.Value? {
        val code =
            "55 48 89 e5 48 83 ec 40 48 89 7d f8 48 8d 45 c8 48 89 45 e8 $middle e8 00 10 00 00 48 8b 7d e8 e8 00 20 00 00 48 8b 7d f8 48 83 c4 40 5d c3"
        val instructions =
            X64Instructions(machineCode(code.trim().split(Regex("\\s+")).joinToString(" "))).all()
        val calls = instructions.filter { it.operation == Operation.CALL }
        val borrows = mapOf(calls[1].offset to listOf(ConstructorValues.Borrow(7, extent)))
        val actualInputs =
            if (inputs) mapOf(calls[0].offset to emptySet(), calls[1].offset to setOf(7))
            else emptyMap()
        return ConstructorValues(
                X64ControlFlow(instructions),
                borrows,
                callInputs = actualInputs,
                trackFrameAliases = aliases,
            )
            .register(instructions.last().offset, 7)
    }

    @Test
    fun tracksPrivateFrameAliasesWithIndependentlySelectedCallInputs() {
        assertEquals(ConstructorValues.Argument(7), savedFrame())
        assertFails { savedFrame(inputs = false) }
        assertFails { savedFrame(aliases = false) }
        assertFails { savedFrame("c6 45 e9 01") }
        assertFails { savedFrame(extent = 64) }
        assertFails { savedFrame("89 c7") }
    }

    @Test
    fun dropsOnlyFrameAliasesProvenDeadOnEveryIncomingContinuation() {
        val join = "85 f6 74 02 31 c0"
        assertEquals(ConstructorValues.Argument(7), savedFrame(join))
        assertEquals(ConstructorValues.Argument(7), savedFrame("$join 31 c0", inputs = false))
        assertFails { savedFrame("$join 48 8b 10") }
        assertFails { savedFrame("$join 48 89 03") }
        assertFails { savedFrame("$join b0 01", inputs = false) }
        assertFails { savedFrame("$join 89 c7") }
    }

    @Test
    fun acceptsCompleteGpRegisterReplacementWithoutTruncatingAFrameSource() {
        assertEquals(ConstructorValues.Argument(7), savedFrame("31 c0", inputs = false))
        assertEquals(ConstructorValues.Argument(7), savedFrame("b8 01 00 00 00", inputs = false))
        assertFails { savedFrame("b0 01", inputs = false) }
    }

    @Test
    fun exchangeAddInvalidatesItsSourceWithoutLosingUnrelatedReceiverStorage() {
        for (exchange in listOf("f0 48 0f c1 06", "f0 0f c1 06")) {
            val instructions =
                X64Instructions(
                        machineCode(
                            "55 48 89 e5 48 83 ec 20 48 89 7d f8 48 89 f8 $exchange 48 8b 7d f8 48 83 c4 20 5d c3"
                        ),
                        allowAtomicExchangeAdd = true,
                    )
                    .all()
            val flow = X64ControlFlow(instructions)
            val end = instructions.last().offset
            val values = ConstructorValues(flow, emptyMap())
            assertNull(values.register(end, 0))
            assertEquals(ConstructorValues.Argument(7), values.register(end, 7))
            assertNull(SysVArgumentFlow(flow).register(end, 0))
        }
        val instructions =
            X64Instructions(
                    machineCode(
                        "55 48 89 e5 48 83 ec 20 48 8d 45 f0 f0 48 0f c1 06 48 83 c4 20 5d c3"
                    ),
                    allowAtomicExchangeAdd = true,
                )
                .all()
        val flow = X64ControlFlow(instructions)
        assertNull(SysVLocalArgument(flow).registers(instructions.last().offset)[0])
        // The stronger constructor reader refuses publishing a private frame address through XADD.
        assertFails { ConstructorValues(flow, emptyMap()) }
    }

    @Test
    fun derivesCompilerGeneratedNullableBaseConversionsAcrossLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/aggregate_fixture_$padding").use {
            file ->
            val image = ElfImage(file.view)
            fun scalar(name: String): Long {
                val symbol = image.symbol(name)
                return image.virtualBytes(symbol.address, symbol.size).unsigned(0, 8)
            }

            val function = image.symbol("fixture_construct_enter")
            val dispatch = image.symbol("fixture_dispatch_enter")
            val flow = X64ControlFlow.resolve(image, function)
            val call =
                flow.instructions.single {
                    it.operation == Operation.CALL &&
                        (it.destination as? Immediate)?.value?.plus(function.address) ==
                            dispatch.address
                }
            val extent = scalar("fixture_event_extent").toInt()
            val proof =
                ConstructorValues(
                    flow,
                    mapOf(call.offset to listOf(ConstructorValues.Borrow(6, extent))),
                )
            val previous =
                proof.field(call.offset, extent, scalar("fixture_event_previous"))
                    as ConstructorValues.Nullable
            assertEquals(-scalar("fixture_previous_adjustment"), previous.adjustment)
            val load = previous.base as ConstructorValues.Load
            assertEquals(ConstructorValues.Argument(7), load.base)
            assertEquals(scalar("fixture_gui_previous"), load.member)
        }
    }

    private val setup = "55 48 89 e5 53 41 54 48 83 ec 50 48 89 fb"
    private val conversion = "48 8b 43 18 48 8d 50 f8 48 85 c0 48 0f 44 d0 48 89 55 e8"
    private val copy = "48 8b 45 e8 48 89 45 b8 48 8d 75 a0 48 89 df e8 00 01 00 00"
    private val cleanup = "48 83 c4 50 41 5c 5b 5d c3"

    private fun analyze(
        middle: String = "",
        source: String = conversion,
        firstBorrow: Int? = null,
    ): ConstructorValues.Value? {
        val code =
            listOf(setup, source, middle, copy, cleanup)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        val instructions = X64Instructions(machineCode(code)).all()
        val calls = instructions.filter { it.operation == Operation.CALL }
        val borrows = mutableMapOf(calls.last().offset to listOf(ConstructorValues.Borrow(6, 40)))
        if (firstBorrow != null)
            borrows[calls.first().offset] = listOf(ConstructorValues.Borrow(6, firstBorrow))
        return ConstructorValues(X64ControlFlow(instructions), borrows)
            .field(calls.last().offset, 40, 24)
    }

    @Test
    fun retainsPrivateNullablePointerAcrossCallsAndLoops() {
        val expected =
            ConstructorValues.Nullable(
                ConstructorValues.Load(ConstructorValues.Argument(7), 24, 14),
                -8,
            )
        assertEquals(expected, analyze())
        assertEquals(expected, analyze("e8 00 01 00 00"))
        assertEquals(expected, analyze("41 bc 03 00 00 00 e8 00 01 00 00 41 ff cc 75 f6"))
        assertEquals(expected, analyze("48 8d 75 a0 e8 00 01 00 00", firstBorrow = 40))
    }

    @Test
    fun rejectsUnknownBorrowsAndForgetsClobberedStorage() {
        assertFails { analyze("48 8d 75 e8 e8 00 01 00 00") }
        assertNull(analyze("48 8d 75 e8 e8 00 01 00 00", firstBorrow = 8))
        assertNull(analyze("c6 45 ef 01"))
        assertFails { analyze("48 8d 45 e8 48 89 45 e0") }
        assertFails { analyze("48 8d 45 e8 48 89 03") }
        assertFails { analyze("48 8d 74 05 e8 e8 00 01 00 00") }
        assertFails { analyze("48 8d 75 e8 48 81 c6 00 00 01 00 e8 00 01 00 00") }
        assertFails { analyze("54 5e e8 00 01 00 00") }
        assertNull(analyze(source = conversion.replace("48 0f 44", "48 0f 45")))
        assertNull(analyze(source = conversion.replace("48 85 c0", "48 85 d2")))
    }

    private fun witness(
        middle: String = "",
        value: Long? = 4,
        borrow: Int? = null,
        aliasComparison: String = "48 8b 4d f0 48 39 c1",
    ): ConstructorValues.Value? {
        // The scalar bit selects a valid original-argument path; equality follows the saved local
        // pointer.
        val code =
            listOf(
                    "55 48 89 e5 48 83 ec 40 48 89 7d e8 89 4d e4",
                    "48 8d 45 d0 48 89 45 f0 f6 45 e4 04 75 05 e8 00 01 00 00",
                    "$aliasComparison 74 05 e8 00 01 00 00",
                    middle,
                    "48 8b 7d e8 48 83 c4 40 5d c3",
                )
                .filter { it.isNotBlank() }
                .joinToString(" ")
        val instructions = X64Instructions(machineCode(code)).all()
        val calls = instructions.filter { it.operation == Operation.CALL }
        val borrows =
            if (borrow == null) emptyMap()
            else mapOf(calls.last().offset to listOf(ConstructorValues.Borrow(6, borrow)))
        val proof =
            ConstructorValues(X64ControlFlow(instructions), borrows, value?.let { mapOf(1 to it) })
        return proof.register(instructions.last().offset, 7)
    }

    @Test
    fun witnessesOriginalScalarBranchesAndExactSavedFrameEquality() {
        assertEquals(ConstructorValues.Argument(7), witness())
        assertEquals(ConstructorValues.Argument(7), witness(value = 12))
        assertFails { witness(value = null) }
        assertFails { witness(value = 0) }
        assertFails { witness(aliasComparison = "48 8b 4d f0 48 83 c1 08 48 39 c1") }
        // The frame alias can be fully replaced once its lifetime ends.
        assertEquals(ConstructorValues.Argument(7), witness("48 c7 45 f0 00 00 00 00"))
    }

    @Test
    fun rejectsPartialAliasesPublicationAndOverlappingBorrowsOnWitnessPath() {
        assertFails { witness("8b 55 f0") }
        assertFails { witness("c6 45 f7 00") }
        assertFails { witness("48 8b 55 f0 48 89 17") }
        assertFails { witness("48 8b 55 f0 e8 00 01 00 00") }
        // Clear incidental volatile aliases before the explicit borrow, isolating the saved-slot
        // check.
        val clear = "48 c7 c0 00 00 00 00 48 c7 c1 00 00 00 00"
        assertFails { witness("$clear 48 8d 75 f0 e8 00 01 00 00", borrow = 8) }
        assertEquals(
            ConstructorValues.Argument(7),
            witness("$clear 48 8d 75 c0 e8 00 01 00 00", borrow = 8),
        )
        // Borrowing the original-this spill invalidates it rather than silently preserving
        // provenance.
        assertNull(witness("$clear 48 8d 75 e8 e8 00 01 00 00", borrow = 8))
    }
}
