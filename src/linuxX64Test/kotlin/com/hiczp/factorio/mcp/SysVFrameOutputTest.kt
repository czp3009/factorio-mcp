package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.FrameOutput
import com.hiczp.factorio.mcp.SysVReceiverFlow.Unknown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SysVFrameOutputTest {
    private val code = "53 48 89 f3 48 83 ec 10 48 89 1c 24 48 8d 14 24 " +
            "e8 eb 00 00 00 48 8b 3c 24 e8 e2 01 00 00 48 83 c4 10 5b c3"

    @Test
    fun invalidatesExactlyTheVerifiedOutputAndPreservesDefaultRejection() {
        assertFails { SysVReceiverFlow(machineCode(code), 0x1000, 64, receiverRegister = 6).call(25) }
        for (width in listOf(4, 8)) {
            val flow = SysVReceiverFlow(
                machineCode(code), 0x1000, 64, receiverRegister = 6,
                frameOutputs = mapOf(16L to listOf(FrameOutput(2, -24, width)))
            )
            assertEquals(Unknown, flow.call(25)[7]) // Even a partial overwrite destroys the saved pointer's provenance.
        }
        for (output in listOf(FrameOutput(1, -24, 8), FrameOutput(2, -16, 8), FrameOutput(2, -24, 24))) {
            assertFails {
                SysVReceiverFlow(
                    machineCode(code), 0x1000, 64, receiverRegister = 6,
                    frameOutputs = mapOf(16L to listOf(output))
                ).call(25)
            }
        }
    }

    @Test
    fun rejectsSavedRegisterOutputEvenWhenInspectingTheCallItself() {
        val saved = "53 48 89 f3 48 83 ec 10 48 8d 54 24 10 e8 ee 00 00 00 48 83 c4 10 5b c3"
        val flow = SysVReceiverFlow(
            machineCode(saved), 0x1000, 64, receiverRegister = 6,
            frameOutputs = mapOf(13L to listOf(FrameOutput(2, -8, 8)))
        )
        assertFails { flow.call(13) }
        assertFails { flow.before(18) }
        assertFails {
            SysVReceiverFlow(
                machineCode(saved), 0x1000, 64,
                frameOutputs = mapOf(0L to listOf(FrameOutput(2, -8, 8)))
            )
        }
    }
}
