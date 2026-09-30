@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class ConstructorValuesTest {
    @Test
    fun derivesCompilerGeneratedNullableBaseConversionsAcrossLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/aggregate_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun scalar(name: String): Long {
                val symbol = image.symbol(name)
                return image.virtualBytes(symbol.address, symbol.size).unsigned(0, 8)
            }

            val function = image.symbol("fixture_construct_enter")
            val dispatch = image.symbol("fixture_dispatch_enter")
            val flow = X64ControlFlow.resolve(image, function)
            val call = flow.instructions.single {
                it.operation == Operation.CALL &&
                        (it.destination as? Immediate)?.value?.plus(function.address) == dispatch.address
            }
            val extent = scalar("fixture_event_extent").toInt()
            val proof = ConstructorValues(flow, mapOf(call.offset to listOf(ConstructorValues.Borrow(6, extent))))
            val previous =
                proof.field(call.offset, extent, scalar("fixture_event_previous")) as ConstructorValues.Nullable
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
        middle: String = "", source: String = conversion,
        firstBorrow: Int? = null
    ): ConstructorValues.Value? {
        val code = listOf(setup, source, middle, copy, cleanup).filter { it.isNotBlank() }.joinToString(" ")
        val instructions = X64Instructions(machineCode(code)).all()
        val calls = instructions.filter { it.operation == Operation.CALL }
        val borrows = mutableMapOf(calls.last().offset to listOf(ConstructorValues.Borrow(6, 40)))
        if (firstBorrow != null) borrows[calls.first().offset] = listOf(ConstructorValues.Borrow(6, firstBorrow))
        return ConstructorValues(X64ControlFlow(instructions), borrows).field(calls.last().offset, 40, 24)
    }

    @Test
    fun retainsPrivateNullablePointerAcrossCallsAndLoops() {
        val expected = ConstructorValues.Nullable(ConstructorValues.Load(ConstructorValues.Argument(7), 24, 14), -8)
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
}
