package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class WidgetCheckPredicateTest {
    private val branch = "53 48 89 fb 83 bb 20 00 00 00 01 75 00 5b c3"

    @Test
    fun derivesEnumComparisonFromOriginalReceiver() {
        assertEquals(
            WidgetCheckPredicate(32, 1),
            WidgetCheckPredicate.analyze(machineCode(branch), 4, 11)
        )
        assertEquals(
            WidgetCheckPredicate(40, 7),
            WidgetCheckPredicate.analyze(machineCode(branch.replace("20 00 00 00 01", "28 00 00 00 07")), 4, 11)
        )
        val materialized = "53 48 89 fb 83 bb 20 00 00 00 01 0f 94 c0 5b c3"
        assertEquals(
            WidgetCheckPredicate(32, 1),
            WidgetCheckPredicate.analyze(machineCode(materialized), 4, 14)
        )
    }

    @Test
    fun rejectsWrongReceiverBoundsWidthAndInlineRange() {
        for (code in listOf(
            branch.replace("89 fb", "89 f3"), branch.replace("83 bb", "80 bb"),
            branch.replace("20 00 00 00", "ff ff ff ff")
        )) {
            assertFails { WidgetCheckPredicate.analyze(machineCode(code), 4, 11) }
        }
        assertFails { WidgetCheckPredicate.analyze(machineCode(branch), 5, 11) }
        assertFails { WidgetCheckPredicate.analyze(machineCode(branch), 4, 10) }
        assertFails { WidgetCheckPredicate.analyze(machineCode(branch), 4, 11).withinObject(35) }
        val unequal = "53 48 89 fb 83 bb 20 00 00 00 01 0f 95 c0 5b c3"
        assertFails { WidgetCheckPredicate.analyze(machineCode(unequal), 4, 14) }
        val writeObject = "53 48 89 fb 83 bb 20 00 00 00 01 0f 94 03 5b c3"
        assertFails { WidgetCheckPredicate.analyze(machineCode(writeObject), 4, 14) }
    }
}
