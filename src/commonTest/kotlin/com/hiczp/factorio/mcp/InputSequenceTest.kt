package com.hiczp.factorio.mcp

import kotlin.test.*
import kotlinx.serialization.json.*

class InputSequenceTest {
    private fun parse(text: String) = parseInputSequence(Json.parseToJsonElement(text).jsonObject)

    @Test
    fun inclusiveSegmentsAndDuplicateKeysRemainIndependent() {
        val request =
            parse(
                """{"timeline":[
            {"device":"keyboard","key":"W","tick":"100-200,201,300-400"},
            {"device":"keyboard","key":"W","tick":"150-350"},
            {"device":"mouse","button":"left","tick":"0-400"}
        ]}"""
            )
        assertEquals(
            listOf(InputInterval(100, 200), InputInterval(201, 201), InputInterval(300, 400)),
            request.timeline[0].intervals,
        )
        assertEquals(
            listOf(InputInterval(5, 20), InputInterval(0, 10), InputInterval(5, 20)),
            parseInputIntervals("5-20,0-10,5-20"),
        )
        assertEquals(3, request.timeline.size)
        assertFalse(request.stopPrevious)
        assertTrue(parse("""{"timeline":[],"stop_previous":true}""").stopPrevious)
        assertEquals(
            InputInterval(MAX_INPUT_TICK, MAX_INPUT_TICK),
            parseInputIntervals("4294967295").single(),
        )
    }

    @Test
    fun dwellComputesAnInclusiveDurationWithoutExpandingPoints() {
        val request =
            parse(
                """{"timeline":[{"device":"mouse","motion":{
            "space":"viewport","from":{"x":200,"y":200},"to":{"x":200,"y":300}
        },"start_tick":100,"per_point_ticks":1}]}"""
            )
        val entry = request.timeline.single()
        assertEquals(listOf(InputInterval(100, 200)), entry.intervals)
        assertEquals(101L, (entry.control as InputControl.Pointer).path.pointCount)
        assertEquals(1L, entry.perPointTicks)
        val stationary =
            parse(
                    """{"timeline":[{"device":"mouse","motion":{
            "space":"world","from":{"x":1.25,"y":2.5},"to":{"x":1.25,"y":2.5}
        },"per_point_ticks":3}]}"""
                )
                .timeline
                .single()
        assertEquals(listOf(InputInterval(0, 2)), stationary.intervals)
    }

    @Test
    fun worldPositionsPreserveFractionsAndCenterSnappingUsesFloor() {
        fun path(snap: String) =
            parse(
                    """{"timeline":[{"device":"mouse","motion":{
            "space":"world",$snap "from":{"x":-1.2,"y":2.25},"to":{"x":-4,"y":2.25}
        },"per_point_ticks":2}]}"""
                )
                .timeline
                .single()
        assertEquals(InputPoint(-1.2, 2.25), (path("").control as InputControl.Pointer).path.from)
        val snapped = path("\"snap\":\"tile_center\",")
        assertEquals(InputPoint(-1.5, 2.5), (snapped.control as InputControl.Pointer).path.from)
        assertEquals(listOf(InputInterval(0, 5)), snapped.intervals)
    }

    @Test
    fun invalidCandidatesAndSupersededSchemaAreRejected() {
        for (tick in
            listOf(
                "",
                "-1",
                "2-1",
                "1.5",
                "4294967296",
                "0,",
                "1;2",
                List(65) { "$it" }.joinToString(","),
            )) {
            assertFails(tick) { parseInputIntervals(tick) }
        }
        val entries =
            listOf(
                """{"device":"keyboard","key":"w","tick":"0"}""",
                """{"device":"controller","key":"W","tick":"0"}""",
                """{"device":"mouse","button":"LEFT","tick":"0"}""",
                """{"device":"mouse","button":"left","wheel":"up","tick":"0"}""",
                """{"device":"mouse","button":"left","tick":0}""",
                """{"device":"keyboard","key":"W","start_tick":0,"per_point_ticks":1}""",
                """{"device":"mouse","position":{"space":"viewport","x":1.5,"y":0},"tick":"0"}""",
                """{"device":"mouse","position":{"space":"viewport","x":-1,"y":0},"tick":"0"}""",
                """{"device":"mouse","position":{"space":"viewport","snap":"tile_center","x":1,"y":0},"tick":"0"}""",
                """{"device":"mouse","position":{"space":"world","x":"1","y":0},"tick":"0"}""",
                """{"device":"mouse","motion":{"space":"viewport","from":{"x":0,"y":0},"to":{"x":2,"y":0}},"tick":"0","per_point_ticks":1}""",
                """{"device":"mouse","motion":{"space":"viewport","from":{"x":0,"y":0},"to":{"x":2,"y":0}},"start_tick":4294967295,"per_point_ticks":1}""",
                """{"device":"mouse","motion":{"space":"viewport","from":{"x":0,"y":0},"to":{"x":2,"y":0}},"per_point_ticks":0}""",
            )
        entries.forEach { assertFails(it) { parse("""{"timeline":[$it],"stop_previous":true}""") } }
        assertFails { parse("""{"operations":[]}""") }
    }
}
