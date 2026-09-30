package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class InlineDestructorRegistersTest {
    // Save R14/RBX, preserve the receiver in RBX, use R14 for a member, then restore all saved registers.
    private val code =
        "55 48 89 e5 41 56 53 48 89 fb 4c 8d 73 18 48 89 03 be 40 00 00 00 48 89 df 5b 41 5e 5d e9 de 00 00 00"

    @Test
    fun acceptsRestoredTemporaryAndKeepsOriginalDeletionReceiver() {
        assertEquals(64, SysVObjectSize.analyzeInlined(machineCode(code), 0, 256))
    }

    @Test
    fun acceptsInternalBackEdgesButRejectsEscapesAndMidInstructionTargets() {
        val loop =
            "55 48 89 e5 41 56 53 48 89 fb 4c 8d 73 18 49 ff ce 75 fb 48 89 03 be 40 00 00 00 48 89 df 5b 41 5e 5d e9 d9 00 00 00"
        assertEquals(64, SysVObjectSize.analyzeInlined(machineCode(loop), 0, 256))
        for (changed in listOf(
            loop.replace("75 fb", "75 ee"), loop.replace("75 fb", "75 fc"),
            loop.replace("49 ff ce", "48 ff cb")
        )) {
            assertFails { SysVObjectSize.analyzeInlined(machineCode(changed), 0, 256) }
        }
    }

    @Test
    fun rejectsLostReceiverUnrestoredTemporaryAndFrameAlias() {
        for (changed in listOf(
            code.replace("4c 8d 73 18", "48 8d 5b 18"),
            code.replace("41 5e", "41 5f"),
            code.replace("48 89 df", "4c 89 f7"),
            code.replace("4c 8d 73 18", "4c 8d 75 18"),
        )) assertFails { SysVObjectSize.analyzeInlined(machineCode(changed), 0, 256) }
    }
}
