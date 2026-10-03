package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class WorkerListenerCallTest {
    @Test
    fun observesAnUnchangedCallAfterNativeLocalBorrowing() {
        val prefix = "53 48 89 fb 48 83 ec 10 48 89 e7 e8 00 10 00 00 48 83 c4 10"
        val call = "48 8b 7b 18 48 8b 07 48 89 de ff 50 18 5b c3"
        assertEquals(33L, WorkerListenerCall.analyze(machineCode("$prefix $call"), 0, 64, 24, 3))
        assertFails {
            WorkerListenerCall.analyze(machineCode("$prefix ${call.replace("48 89 de", "48 89 e6")}"),
                0, 64, 24, 3)
        }
    }

    @Test
    fun followsLoadedListenerTargetsAndRejectsUnrelatedTables() {
        val code = "53 48 89 fb 48 8b 7b 18 48 8b 07 48 89 de 4c 8b 58 18 90 41 ff d3 5b c3"
        assertEquals(22L, WorkerListenerCall.analyze(machineCode(code), 0, 64, 24, 3))
        for (changed in listOf(
            code.replace("4c 8b 58 18", "4c 8b 58 10"),
            code.replace("4c 8b 58 18", "44 8b 58 18"),
            code.replace("48 8b 07", "48 8b 03"),
            code.replace("48 89 de", "48 89 ce"),
            code.replace("90", "45 31 db"),
            code.replace("90", "4d 8b 1b"),
        )) assertFails { WorkerListenerCall.analyze(machineCode(changed), 0, 64, 24, 3) }
    }

    @Test
    fun requiresOriginalWorkerAndTheSelectedListenerPrimaryTable() {
        for (member in listOf(16, 24)) {
            val displacement = member.toString(16)
            val code = "53 48 89 fb 48 8b 7b $displacement 48 8b 07 48 89 de ff 50 18 5b c3"
            fun resolve(value: String = code, size: Long = 64, slot: Int = 3) =
                WorkerListenerCall.analyze(machineCode(value), 0, size, member.toLong(), slot)
            assertEquals(17L, resolve())
            for (changed in listOf(
                code.replace("48 89 fb", "48 89 f3"),
                code.replace("48 8b 07", "48 8b 06"),
                code.replace("48 89 de", "48 89 ce"),
                code.replace("7b $displacement", "7b 20"),
                code.replace("ff 50 18", "ff 50 10"),
                code.replace("5b c3", "48 8b 7b $displacement 48 8b 07 48 89 de ff 50 18 5b c3"),
            )) assertFails { resolve(changed) }
            assertFails { resolve(size = member.toLong()) }
            assertFails { resolve(slot = 2) }
        }
    }
}
