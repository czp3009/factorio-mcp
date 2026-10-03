package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertFails

class ViewportCoordinatesTest {
    private val prefix = "53 41 54 48 8b 57 28 48 89 f3 49 89 dc 83 eb 01 49 c1 ec 20 41 83 ec 01 "
    private val convert = "f2 0f 2a c3 f2 41 0f 2a cc f2 0f 2c c0 f2 0f 2c d1 "
    private val combine = "48 c1 e2 20 48 09 d0 "
    private val finish = "41 5c 5b c3"

    private fun flow(code: String) = X64ControlFlow(X64Instructions(machineCode(code)).all())

    @Test
    fun distinguishesIndirectCallTargetsFromWritesToTheirMemoryOperands() {
        val call = "41 55 41 56 41 57 49 89 ff 49 89 f6 ff 57 18 4c 89 ff 4c 89 f6 41 5f 41 5e 41 5d "
        ViewportCoordinates.verifyPackedBoundary(flow(call + prefix + convert + combine + finish), 40)
    }

    @Test
    fun permitsBackwardBranchPlacementWithoutPermittingControlFlowCycles() {
        val body = machineCode(prefix + convert + combine + finish)
        val bytes = byteArrayOf(0xeb.toByte(), body.size.toByte()) + body.bytes(0, body.size.toInt()) +
                byteArrayOf(0xeb.toByte(), (-body.size - 2).toByte())
        ViewportCoordinates.verifyPackedBoundary(X64ControlFlow(X64Instructions(BinaryView(bytes)).all()), 40)
        assertFails { ViewportCoordinates.verifyPackedBoundary(flow("85 ff 75 fc " + prefix + convert + combine + finish), 40) }
    }

    @Test
    fun requiresPixelWordOrderAndRegisterReturnWithoutHiddenOutput() {
        ViewportCoordinates.verifyPackedBoundary(flow(prefix + convert + combine + finish), 40)
        assertFails { ViewportCoordinates.verifyPackedBoundary(flow(prefix + convert + combine + finish), 48) }
        assertFails { ViewportCoordinates.verifyPackedBoundary(flow(prefix.replace("48 89 f3", "48 89 fb") + convert + combine + finish), 40) }
        assertFails { ViewportCoordinates.verifyPackedBoundary(flow(prefix + convert.replace("f2 41 0f 2a cc", "f2 41 0f 2a c4") + combine + finish), 40) }
        assertFails { ViewportCoordinates.verifyPackedBoundary(flow(prefix + convert + combine + "48 89 07 " + finish), 40) }
        assertFails { ViewportCoordinates.verifyPackedBoundary(flow(prefix + convert + combine + "31 c0 " + finish), 40) }
    }
}
