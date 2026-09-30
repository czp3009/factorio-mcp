package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*
import kotlin.test.*

class InputSequenceTest {
    private fun parse(text: String) = parseInputSequence(Json.parseToJsonElement(text).jsonObject)

    @Test
    fun controllerDevicesAreRejectedBeforeAdmission() {
        for (device in listOf("controller", "gamepad", "joystick")) {
            val request = buildJsonObject {
                put("stop_previous", true)
                putJsonArray("operations") {
                    add(buildJsonObject {
                        putJsonArray("controls") {
                            add(buildJsonObject { put("device", device) })
                        }
                    })
                }
            }
            assertFailsWith<IllegalStateException> { parseInputSequence(request) }
        }
    }

    @Test
    fun motionUsesOperationTicksWithoutOpeningTheChord() {
        val request =
            parse(
                """{
                    "operations": [{
                        "ticks": 5,
                        "controls": [{
                            "device": "mouse", "button": "left",
                            "position": {"space": "viewport", "x": 10, "y": 20},
                            "motion": [
                                {"tick": 2, "position": {"space": "viewport", "x": 30, "y": 40}},
                                {"tick": 5, "position": {"space": "viewport", "x": 50, "y": 60}}
                            ]
                        }]
                    }]
                }"""
            )
        val mouse = request.operations.single().controls.single() as InputControl.Mouse
        assertEquals("left", mouse.button)
        assertEquals(
            listOf(InputMotion(2, InputPosition(30, 40)), InputMotion(5, InputPosition(50, 60))),
            mouse.motion,
        )
        assertEquals(5L, request.operations.single().ticks)
    }

    @Test
    fun malformedMotionIsRejectedBeforeAdmission() {
        fun motion(points: String, ticks: Long = 5) =
            """{"operations":[{"ticks":$ticks,"controls":[{"device":"mouse","motion":[$points]}]}]}"""

        fun point(tick: String, x: Int = 10) =
            """{"tick":$tick,"position":{"space":"viewport","x":$x,"y":20}}"""
        listOf(
            "",
            point("1"),
            point("6"),
            point("2.5"),
            point("\"2\""),
            point("2", -1),
            point("3") + "," + point("2"),
            point("2") + "," + point("2"),
            (2..66).joinToString(",") { point(it.toString()) },
        )
            .forEach { assertFails { parse(motion(it)) } }
        assertFails {
            validateInputMotion(List(65) { InputMotion(it + 2L, InputPosition(0, 0)) }, 100)
        }
        assertFails {
            parse(
                """{"operations":[{"ticks":3,"controls":[{"device":"mouse","motion":[${point("2")}]},{"device":"mouse","motion":[${
                    point(
                        "3"
                    )
                }]}]}]}"""
            )
        }
        assertEquals(
            4294967295L,
            (parse(motion(point("4294967295"), 4294967295L)).operations.single().controls.single()
                    as InputControl.Mouse)
                .motion
                .single()
                .tick,
        )
    }

    @Test
    fun closedChordsWaitsAndStopOnlyPreserveTheRequestedSequence() {
        val sequence =
            parse(
                """{
          "operations":[
            {"controls":[{"device":"keyboard","key":"W"},{"device":"mouse","button":"left","position":{"space":"viewport","x":640,"y":360}}],"ticks":5},
            {"controls":[{"device":"keyboard","key":"D"}],"ticks":2},
            {"controls":[]},
            {"controls":[{"device":"mouse","position":{"space":"viewport","x":0,"y":0}}],"ticks":4294967295}
          ]
        }"""
            )
        assertFalse(sequence.stopPrevious)
        assertEquals(listOf(5L, 2L, 1L, 4294967295L), sequence.operations.map { it.ticks })
        assertEquals(InputControl.Keyboard("W"), sequence.operations[0].controls[0])
        assertEquals(
            InputControl.Mouse("left", InputPosition(640, 360)),
            sequence.operations[0].controls[1],
        )
        assertTrue(sequence.operations[2].controls.isEmpty())
        val stop = parse("""{"operations":[],"stop_previous":true}""")
        assertTrue(stop.stopPrevious && stop.operations.isEmpty())
    }

    @Test
    fun wheelDirectionIsAnImpulseWithinACombination() {
        val request =
            parse(
                """{"operations":[{"controls":[{"device":"keyboard","key":"LCTRL"},{"device":"mouse","wheel":"up","position":{"space":"viewport","x":40,"y":50}}],"ticks":3}]}"""
            )
        assertEquals(
            InputControl.Mouse(null, InputPosition(40, 50), "up"),
            request.operations.single().controls[1],
        )
        for (controls in
        listOf(
            """[{"device":"mouse","wheel":"left"}]""",
            """[{"device":"mouse","wheel":1}]""",
            """[{"device":"mouse","wheel":"up"},{"device":"mouse","wheel":"down"}]""",
        )) {
            assertFailsWith<IllegalArgumentException> {
                parseInputSequence(
                    buildJsonObject {
                        putJsonArray("operations") {
                            add(
                                buildJsonObject {
                                    put("controls", Json.parseToJsonElement(controls))
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    @Test
    fun invalidReplacementIsRejectedBeforeItCanCancelAnything() {
        for (operation in
        listOf(
            """{"controls":[],"ticks":0}""",
            """{"controls":[],"ticks":"2"}""",
            """{"controls":[],"ticks":1.5}""",
            """{"controls":[],"ticks":4294967296}""",
            """{"controls":[{"device":"keyboard","key":"w"}]}""",
            """{"controls":[{"device":"keyboard","key":"W"},{"device":"keyboard","key":"W"}]}""",
            """{"controls":[{"device":"keyboard","key":"W","pressed":false}]}""",
            """{"controls":[{"device":"mouse"}]}""",
            """{"controls":[{"device":"mouse","button":"wheel_up"}]}""",
            """{"controls":[{"device":"mouse","position":{"space":"desktop","x":1,"y":2}}]}""",
            """{"controls":[{"device":"mouse","position":{"space":"viewport","x":-1,"y":2}}]}""",
            """{"controls":[{"device":"mouse","button":"left"},{"device":"mouse","button":"left"}]}""",
            """{"controls":[{"device":"mouse","position":{"space":"viewport","x":1,"y":2}},{"device":"mouse","position":{"space":"viewport","x":3,"y":4}}]}""",
        )) {
            val request = buildJsonObject {
                put("stop_previous", true)
                putJsonArray("operations") { add(Json.parseToJsonElement(operation)) }
            }
            assertFailsWith<IllegalArgumentException>(operation) { parseInputSequence(request) }
        }
    }
}
