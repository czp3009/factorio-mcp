package com.hiczp.factorio.mcp

import kotlin.test.*
import kotlinx.serialization.json.*

class InputTimelineEntriesTest {
    @Test
    fun resolutionPreservesArrayOrderOverlappingAliasesAndBounds() {
        val request =
            parseInputSequence(
                Json.parseToJsonElement(
                        """{"timeline":[
            {"device":"keyboard","key":"W","tick":"0-10"},
            {"device":"mouse","button":"button_5","tick":"0-10"},
            {"device":"keyboard","key":"W","tick":"5-20"},
            {"device":"mouse","motion":{"space":"world","snap":"tile_center","from":{"x":-1,"y":1},"to":{"x":10,"y":1}},"per_point_ticks":2},
            {"device":"mouse","wheel":"down","tick":"4294967295"}
        ]}"""
                    )
                    .jsonObject
            )
        val entries =
            resolveInputTaskEntries(request) {
                assertEquals(listOf("W", "W"), it)
                listOf(26u, 26u)
            }
        assertEquals(listOf(0u, 1u, 0u, 3u, 4u), entries.map { it.kind })
        assertEquals(listOf(26u, 5u, 26u, 0u, 2u), entries.map { it.code })
        assertEquals(2u, entries[3].perPointTicks)
        assertEquals(InputPoint(-0.5, 1.5), entries[3].path!!.from)
        assertFails { resolveInputTaskEntries(request) { emptyList() } }
        assertFails { resolveInputTaskEntries(request) { listOf(0u, 1u) } }
    }
}
