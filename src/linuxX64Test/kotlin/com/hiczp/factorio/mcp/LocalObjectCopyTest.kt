package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LocalObjectCopyTest {
    private fun flow(member: Int, tail: Int = member + 8, owner: Int = 6): X64ControlFlow {
        val source = if (owner == 6) "46" else "47"
        return X64ControlFlow(X64Instructions(machineCode(
            "53 48 83 ec 10 48 8b $source ${member.toString(16)} 48 89 04 24 " +
                    "48 8b $source ${tail.toString(16)} 48 89 44 24 08 48 8d 0c 24 " +
                    "e8 00 01 00 00 48 83 c4 10 5b c3"
        )).all())
    }

    @Test
    fun requiresCompleteConsistentBoundedSourceBytes() {
        for (member in listOf(40, 104)) {
            val flow = flow(member)
            val call = flow.instructions.single { it.operation == X64Instructions.Operation.CALL }.offset
            assertEquals(member.toLong(), LocalObjectCopy.analyze(flow, call, 6, 1, 256, 16))
            assertFails { LocalObjectCopy.analyze(flow, call, 6, 1, member + 15L, 16) }
            assertFails { LocalObjectCopy.analyze(flow(member, member + 16), call, 6, 1, 256, 16) }
            assertFails { LocalObjectCopy.analyze(flow(member, owner = 7), call, 6, 1, 256, 16) }
            assertFails { LocalObjectCopy.analyze(flow, call, 6, 1, 256, 24) }
        }
    }
}
