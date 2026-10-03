package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlin.test.Test
import kotlin.test.assertFails

class ManualRecordAbiTest {
    private val prefix = "55 48 89 e5 53 41 54 41 55 41 56 48 83 ec 40 " +
            "4d 89 c6 49 89 fc 49 89 cd 48 89 75 b0 48 89 55 b8 49 8d 48 40 48 89 4d c0 "
    private val copy = "48 8d 7d d0 4c 89 f6 ba 10 00 00 00 e8 00 10 00 00 48 8b 4d c0 "
    private val suffix = "48 89 cf 48 8b 75 b0 e8 00 20 00 00 " +
            "4d 89 66 70 48 8b 45 b8 49 89 46 78 4d 89 ae 80 00 00 00 " +
            "48 83 c4 40 41 5e 41 5d 41 5c 5b 5d c3"

    private fun analyze(code: String) {
        val flow = X64ControlFlow(X64Instructions(machineCode(code)).all())
        val calls = flow.instructions.filter { it.operation == Operation.CALL }
        ManualRecordAbi.analyze(flow, (calls.last().destination as Immediate).value,
            (calls.first().destination as Immediate).value, 32)
    }

    @Test
    fun acceptsProtectedSpillsAndAgreeingBranchDefinitions() {
        analyze(prefix + copy + suffix)
        // One path keeps the external explorer string in RCX; the other privately restores that same address.
        analyze(prefix + "4d 85 d2 74 15 " + copy + suffix)
        analyze(prefix + copy.replace("4c 89 f6", "90 4c 89 f6 90") + suffix)
    }

    @Test
    fun rejectsAliasWritesWrongArgumentsAndPartialSpills() {
        assertFails { analyze(prefix + copy.replace("48 8d 7d d0", "48 8d 7d b0") + suffix) }
        assertFails { analyze(prefix.replace("49 89 fc", "49 89 f4") + copy + suffix) }
        assertFails { analyze(prefix.replace("4d 89 c6", "49 89 fe") + copy + suffix) }
        assertFails { analyze(prefix.replace("48 89 55 b8", "89 55 b8") + copy + suffix) }
        assertFails { analyze(prefix + copy + suffix.replace("48 8b 75 b0", "48 8b 75 b8")) }
        assertFails { analyze(prefix + "48 8d 45 c8 49 89 06 " + copy + suffix) }
        assertFails { analyze(prefix + "48 8d 45 c8 48 89 45 c8 4c 8b 5d c8 " + copy + suffix) }
    }
}
