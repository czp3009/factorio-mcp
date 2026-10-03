package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertFails

class FrontendVirtualCallTest {
    @Test
    fun acceptsEquivalentTableRegistersIncludingRexInterpretations() {
        for (register in 0..15) {
            val code =
                buildList<Byte> {
                        if (register >= 8) add(0x41)
                        add(0xff.toByte())
                        add((0x50 or (register and 7)).toByte())
                        if (register and 7 == 4) add(0x24)
                        add(0x20)
                    }
                    .toByteArray()
            FrontendVirtualCall.verify(BinaryView(code), 4, setOf(register, register and 7))
        }
        assertFails { FrontendVirtualCall.verify(machineCode("41 ff 50 20"), 4, setOf(8)) }
        assertFails { FrontendVirtualCall.verify(machineCode("41 ff 50 20"), 4, setOf(0)) }
    }

    @Test
    fun rejectsDirectCallsEvenWhenTheirImmediateContainsAVirtualCallSuffix() {
        assertFails { FrontendVirtualCall.verify(machineCode("e8 00 ff 50 20"), 4, setOf(0)) }
        assertFails { FrontendVirtualCall.verify(machineCode("ff d0"), 4, setOf(0)) }
        assertFails { FrontendVirtualCall.verify(machineCode("ff 54 88 20"), 4, setOf(0)) }
        assertFails { FrontendVirtualCall.verify(machineCode("ff 15 20 00 00 00"), 4, setOf(0)) }
    }

    @Test
    fun leavesUnrelatedSetupAndUnsupportedCalculationsOutsideTheProof() {
        for (prefix in listOf("90 31 d2", "f2 0f 51 ca", "dd 45 e8", "48 f7 e2")) {
            FrontendVirtualCall.verify(machineCode("$prefix ff 53 28"), 5, setOf(3))
        }
        FrontendVirtualCall.verify(machineCode("90 41 ff 53 28"), 5, setOf(3, 11))
        assertFails { FrontendVirtualCall.verify(machineCode("90 41 ff 53 28"), 5, setOf(11)) }
    }

    @Test
    fun rejectsWrongSlotsMissingTablesAndInvalidWindows() {
        for (code in listOf("90", "ff 50 21", "ff 50")) {
            assertFails { FrontendVirtualCall.verify(machineCode(code), 4, setOf(0)) }
        }
        val bytes = machineCode("ff 50 20")
        assertFails { FrontendVirtualCall.verify(bytes, 4, emptySet()) }
        assertFails { FrontendVirtualCall.verify(bytes, 4, setOf(3)) }
        assertFails { FrontendVirtualCall.verify(bytes, -1, setOf(0)) }
        assertFails { FrontendVirtualCall.verify(bytes, 4, setOf(16)) }
        assertFails { FrontendVirtualCall.verify(bytes, Int.MAX_VALUE, setOf(0)) }
        assertFails { FrontendVirtualCall.verify(BinaryView(ByteArray(16)), 4, setOf(0)) }
    }
}
