package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SdlButtonAdmissionTest {
    @Test
    fun requiresSdkTypeAndButtonPathsToReachTheVerifiedLookup() {
        // Public SDK layout at the input; arbitrary synthetic output values and table location.
        val code = "8b 0f 8d 91 ff fb ff ff 83 fa 02 73 18 " +
                "0f b6 47 10 fe c8 3c 02 77 0e 0f b6 c0 48 8d 15 00 01 00 00 8b 04 82 c3 b8 ff ff ff ff c3"

        fun resolve(text: String): SdlButtonAdmission.Proof {
            val flow = X64ControlFlow(X64Instructions(machineCode(text)).all())
            val table = GuardedByteTable.analyze(flow, 0) { _, size -> BinaryView(ByteArray(size.toInt())) }.single()
            return SdlButtonAdmission.analyze(flow, table)
        }

        val proof = resolve(code)
        assertEquals(SdlButtonAdmission.Button.entries.flatMap { button ->
            SdlButtonAdmission.Transition.entries.map { button to it }
        }.toSet(), proof.cases.map { it.button to it.transition }.toSet())
        assertEquals(6, proof.cases.size)
        val extendedFlow = X64ControlFlow(X64Instructions(machineCode(code.replace("3c 02", "3c 04"))).all())
        val extendedTable =
            GuardedByteTable.analyze(extendedFlow, 0) { _, size -> BinaryView(ByteArray(size.toInt())) }.single()
        assertEquals(10, SdlButtonAdmission.buttonPaths(extendedFlow, extendedTable, (1..5).toSet()).size)
        assertFails { SdlButtonAdmission.buttonPaths(extendedFlow, extendedTable, setOf(6)) }
        assertFails { resolve(code.replace("0f b6 47 10", "0f b6 47 11")) }
        assertFails { resolve(code.replace("ff fb ff ff", "fe fb ff ff")) }
        assertFails { resolve(code.replace("8b 0f", "8b 0e")) }
        assertFails { resolve(code.replace("3c 02", "3c 01")) }
        assertFails { resolve(code.replace("83 fa 02", "83 fa 01")) }
        val type = ScalarExpression.Input(
            0,
            SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, 0), 4), 4
        )
        val kind = ScalarExpression.Select(
            4, type, ScalarExpression.Literal(0x402, 4),
            ScalarExpression.Literal(3, 4), ScalarExpression.Literal(9, 4), 4
        )
        assertEquals(
            mapOf(SdlButtonAdmission.Transition.PRESS to 9L, SdlButtonAdmission.Transition.RELEASE to 3L),
            SdlButtonAdmission.kinds(proof.table, kind)
        )
        assertFails { SdlButtonAdmission.kinds(proof.table, ScalarExpression.Literal(3, 4)) }
        assertFails { SdlButtonAdmission.kinds(proof.table, kind.copy(no = kind.yes)) }
        assertFails {
            SdlButtonAdmission.kinds(
                proof.table, kind.copy(
                    left = type.copy(
                        field = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, 4), 4)
                    )
                )
            )
        }
    }
}
