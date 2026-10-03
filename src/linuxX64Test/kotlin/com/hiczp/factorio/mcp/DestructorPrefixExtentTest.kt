package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class DestructorPrefixExtentTest {
    private val prefix = "55 48 89 e5 53 48 89 fb 48 8d 05 71 00 00 00 48 89 07 48 8b 5f 58 48 85 db 74 05 e8 00 01 00 00 5b 5d c3"

    @Test
    fun derivesReadablePrefixFromMatchingVptrAndOriginalReceiver() {
        assertEquals(96L, DestructorPrefixExtent.analyze(machineCode(prefix), 0x1000, 0x1080))
        assertEquals(96L, DestructorPrefixExtent.analyze(machineCode(prefix.replace("48 8b 5f 58", "48 8b 5b 58")), 0x1000, 0x1080))
    }

    @Test
    fun rejectsWrongRttiUnknownReceiversEscapesAndLateLoads() {
        assertFails { DestructorPrefixExtent.analyze(machineCode(prefix), 0x1000, 0x1090) }
        assertFails { DestructorPrefixExtent.analyze(machineCode(prefix.replace("48 8b 5f 58", "48 8b 5e 58")), 0x1000, 0x1080) }
        assertFails { DestructorPrefixExtent.analyze(machineCode(prefix.replace("48 89 07", "48 89 3e")), 0x1000, 0x1080) }
        assertFails { DestructorPrefixExtent.analyze(machineCode(prefix.replace("48 8b 5f 58", "e8 00 01 00 00 48 8b 5f 58")), 0x1000, 0x1080) }
        assertFails { DestructorPrefixExtent.analyze(machineCode(prefix.replace("48 8b 5f 58", "90 90 90 90")), 0x1000, 0x1080) }
    }

    @Test
    fun acceptsOnlyMatchingSecondaryTablesInsideTheIndependentPrefix() {
        val code = prefix.replace("48 8b 5f 58", "48 b8 00 20 00 00 00 00 00 00 48 89 47 20 48 8b 5f 58")
        // Use a RIP-relative constant, as emitted for PIE vtables; unrelated constants do not prove an address.
        val relative = code.replace("48 b8 00 20 00 00 00 00 00 00", "48 8d 05 e7 0f 00 00")
        assertEquals(96L, DestructorPrefixExtent.analyze(machineCode(relative), 0x1000, 0x1080, mapOf(32L to 0x2000L)))
        assertFails { DestructorPrefixExtent.analyze(machineCode(relative), 0x1000, 0x1080) }
        assertFails { DestructorPrefixExtent.analyze(machineCode(relative), 0x1000, 0x1080, mapOf(32L to 0x2008L)) }
        assertFails { DestructorPrefixExtent.analyze(machineCode(relative.replace("48 8b 5f 58", "48 8b 5f 20")),
            0x1000, 0x1080, mapOf(32L to 0x2000L)) }
        assertFails { DestructorPrefixExtent.analyze(machineCode(relative.replace("48 89 47 20", "48 89 47 70")),
            0x1000, 0x1080, mapOf(112L to 0x2000L)) }
    }
}
