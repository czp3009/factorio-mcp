@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class ScalarDecisionTraceTest {
    @Test
    fun comparesByteExpressionsAcrossRegisterRenamingWithoutInventingUpperBytes() {
        val prefix = "53 48 89 fb be 17 00 00 00 e8 f2 00 00 00"
        fun trace(body: String, result: Register = Register(0, 1)): Set<ScalarDecisionTrace.Path> {
            val flow = X64ControlFlow(X64Instructions(machineCode("$prefix $body 5b c3")).all())
            return ScalarDecisionTrace(flow, 0, mapOf(256L to listOf(Register(7, 8), Register(6, 4))))
                .paths(result = result)
        }

        val expected = trace("0f b6 40 03 34 01")
        assertEquals(expected, trace("0f b6 48 03 80 f1 01 88 c8"))
        assertNotEquals(expected, trace("0f b6 40 03 34 02"))
        assertFails { trace("0f b6 40 03 34 01", Register(0, 4)) }
        assertFails { trace("66 0f b6 40 03", Register(0, 4)) }
        assertFails { trace("0f b6 40 03 e8 e9 00 00 00") }
    }

    @Test
    fun comparesCompiledInlineDecisionsWithDifferentLayoutsAndKeys() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/decision_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            val calls = listOf("fixture_decision_lookup", "fixture_decision_refresh").associate {
                image.symbol(it).address to listOf(Register(7, 8), Register(6, 4))
            }
            val getter = image.symbol("fixture_decision_getter")
            val expected = ScalarDecisionTrace(X64ControlFlow.resolve(image, getter), getter.address, calls).paths()
            val caller = image.symbol("fixture_decision_inline")
            val flow = X64ControlFlow.resolve(image, caller)
            val emit = image.symbol("fixture_decision_emit").address
            val sink = flow.instructions.single {
                it.operation == Operation.CALL &&
                        (it.destination as? Immediate)?.value?.plus(caller.address) == emit
            }.offset
            assertEquals(
                expected,
                ScalarDecisionTrace(flow, caller.address, calls).paths(end = sink, result = Register(7, 1))
            )
        }
    }

    @Test
    fun preservesCallReadOrderConditionsAndRejectsUnknownInputs() {
        val code = "53 48 89 fb be 17 00 00 00 e8 f2 00 00 00 80 78 03 01 75 09 b0 01 5b c3 90 90 90 90 90 31 c0 5b c3"
        fun trace(bytes: String): Set<ScalarDecisionTrace.Path> {
            val flow = X64ControlFlow(X64Instructions(machineCode(bytes)).all())
            return ScalarDecisionTrace(flow, 0, mapOf(256L to listOf(Register(7, 8), Register(6, 4)))).paths()
        }

        val expected = trace(code)
        assertEquals(2, expected.size)
        assertNotEquals(expected, trace(code.replace("be 17", "be 18")))
        assertNotEquals(expected, trace(code.replace("78 03", "78 04")))
        assertNotEquals(expected, trace(code.replace("75 09", "74 09")))
        assertFails { trace(code.replace("e8 f2", "e8 f3")) }
        assertFails { trace(code.replace("be 17 00 00 00", "90 90 90 90 90")) }
        assertFails { trace(code.replace("80 78", "80 7b")) }
        assertFails { trace(code.replace("31 c0", "eb fe")) }
        assertFails { trace(code.replace("b0 01", "88 07")) }
    }
}
