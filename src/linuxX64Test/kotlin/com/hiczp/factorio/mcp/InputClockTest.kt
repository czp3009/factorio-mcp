@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class InputClockTest {
    @Test
    fun verifiesBothInitializationStatesAndTheNativeFloatingReturn() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (divisor in listOf(1000, 2000)) MappedBinary("$directory/input_clock_fixture_$divisor").use { file ->
            val image = ElfImage(file.view)
            val proof = InputClock.resolve(image, "fixture_input_time", "fixture_ticks_started", "fixture_origin")
            assertEquals(image.symbol("fixture_ticks_started").address, proof.layout.initialized)
            assertEquals(image.symbol("fixture_origin").address, proof.layout.origin)
            assertEquals(listOf(divisor.toDouble()), proof.literals.map { Double.fromBits(it.bits) })
            val function = image.symbol("fixture_input_time")
            val code = image.functionBytes(function, 2048)
            fun analyze(
                bytes: BinaryView, layout: InputClock.Layout = proof.layout,
                timeCall: (Long) -> Boolean = { image.importedFunction(it) == "gettimeofday" },
                literal: (Long) -> Long = { image.virtualBytes(it, 8).unsigned(0, 8) }
            ) =
                InputClock.analyze(bytes, function.address, layout, timeCall, literal)
            assertFails { analyze(code, proof.layout.copy(timeExtent = proof.layout.timeExtent - 1)) }
            assertFails { analyze(code, proof.layout.copy(timeExtent = proof.layout.timeExtent + 1)) }
            assertFails { analyze(code, proof.layout.copy(initialized = proof.layout.initialized + 1)) }
            assertFails { analyze(code, timeCall = { false }) }
            assertFails { analyze(code, literal = { 0L }) }
            assertFails { analyze(code, literal = { Double.NaN.toBits() }) }
            var reads = 0
            assertFails { analyze(code, literal = { (++reads).toDouble().toBits() }) }
            val original = code.bytes(0, code.size.toInt())
            assertFails { analyze(BinaryView(machineCode("48 89 07").bytes(0, 3) + original)) }
            val instructions = X64Instructions(code, allowWideMultiply = true).all()
            val returnSite = instructions.last { it.operation == X64Instructions.Operation.RET }
            val altered = original.copyOfRange(0, returnSite.offset.toInt()) +
                    machineCode("66 0f 57 c0 c3").bytes(0, 5)
            assertFails { analyze(BinaryView(altered)) }
        }
    }
}
