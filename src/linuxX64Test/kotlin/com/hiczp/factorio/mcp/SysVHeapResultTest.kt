package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Heap
import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SysVHeapResultTest {
    @Test
    fun limitsAllocatorEvidenceToTheIndependentlySelectedCall() {
        val code = machineCode("53 48 89 fb e8 f7 00 00 00 c6 00 00 48 89 df e8 ec 01 00 00 5b c3")
        assertFails { SysVReceiverFlow(code, 0x1000, 64).call(15) }
        val flow = SysVReceiverFlow(code, 0x1000, 64, heapResults = setOf(4))
        assertEquals(Heap, flow.before(9)[0])
        assertEquals(Receiver(), flow.call(15)[7])
        assertFails { SysVReceiverFlow(code, 0x1000, 64, heapResults = setOf(15)).call(15) }
        assertFails { SysVReceiverFlow(code, 0x1000, 64, heapResults = setOf(3)) }
    }
}
