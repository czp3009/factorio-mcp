package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class EventPollSiteTest {
    private val header = EventHeader(24, 0, 8)
    private val setup = "55 48 89 e5 53 48 83 ec 28 48 89 f3"
    private val defaults = "c7 45 d8 11 00 00 00 48 c7 45 e0 00 00 00 00"
    private val call = "48 8b 03 48 89 df 48 8d 75 d8 ff 50 18"
    private val end = "48 83 c4 28 5b 5d c3"

    private fun inspect(code: String) = EventPollSite.analyze(
        machineCode(code.split(' ').filter { it.isNotBlank() }.joinToString(" ")), 0, 3, header
    )

    @Test
    fun identifiesReceiverAcrossUnrelatedCallsAndRegisterAllocation() {
        for (middle in listOf("", "90", "e8 00 10 00 00", "85 ff 74 01 90")) {
            val proof = inspect("$setup $defaults $middle $call $end")
            assertEquals(-48L, proof.eventFromEntry)
            assertEquals(56L, proof.callerReturnFromStack)
            assertEquals(17, proof.defaults.getValue(0))
            assertEquals(0, proof.defaults.getValue(8))
        }
        val changed = "55 48 89 e5 41 54 48 83 ec 28 49 89 f4 $defaults e8 00 10 00 00 " +
                "4d 8b 1c 24 4c 89 e7 48 8d 75 d8 41 ff 53 18 48 83 c4 28 41 5c 5d c3"
        assertEquals(-48L, inspect(changed).eventFromEntry)
        val frameless = "53 48 83 ec 20 48 89 f3 c7 04 24 11 00 00 00 " +
                "48 c7 44 24 08 00 00 00 00 e8 00 10 00 00 " +
                "48 8b 03 48 89 df 48 89 e6 ff 50 18 48 83 c4 20 5b c3"
        val proof = inspect(frameless)
        assertEquals(-40L, proof.eventFromEntry)
        assertEquals(40L, proof.callerReturnFromStack)
    }

    @Test
    fun followsRegisterTargetsWhileRetainingTheBoundedLocalEvent() {
        val loadedCall = call.replace("ff 50 18", "4c 8b 58 18 90 41 ff d3")
        val code = "$setup $defaults $loadedCall $end"
        val proof = inspect(code)
        assertEquals(-48L, proof.eventFromEntry)
        assertEquals(56L, proof.callerReturnFromStack)
        assertEquals(3L, proof.returned - proof.call)
        for (changed in listOf(
            code.replace("4c 8b 58 18", "4c 8b 58 10"),
            code.replace("4c 8b 58 18", "44 8b 58 18"),
            code.replace("90", "45 31 db"),
            code.replace("90", "48 89 cf"),
            code.replace("48 8d 75 d8", "48 8d 75 f0"),
            code.replace("$loadedCall $end", "$loadedCall $loadedCall $end"),
        )) assertFails { inspect(changed) }
    }

    @Test
    fun rejectsOtherReceiversTablesAmbiguityAndUninitializedEvents() {
        val code = "$setup $defaults $call $end"
        for (changed in listOf(
            code.replace("48 89 df", "48 89 cf"),
            code.replace("48 8b 03", "48 8b 01"),
            code.replace("ff 50 18", "ff 50 10"),
            code.replace("48 8d 75 d8", "48 8d 75 f0"),
            code.replace("$call $end", "$call $call $end"),
            code.replace("48 c7 45 e0 00 00 00 00", "48 c7 45 e0 01 00 00 00"),
            code.replace(defaults, "c7 45 d8 11 00 00 00"),
        )) assertFails { inspect(changed) }
        assertFails { inspect("$setup $defaults 48 8d 75 d8 e8 00 10 00 00 $call $end") }
    }
}
