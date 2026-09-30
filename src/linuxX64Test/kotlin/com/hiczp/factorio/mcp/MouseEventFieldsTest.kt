package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class MouseEventFieldsTest {
    @Test
    fun requiresAgreementAcrossReferenceStackAndConvertedFields() {
        fun field(offset: Long, width: Int) = InlineArgumentFields.Field(offset, width)
        val copy = MouseEventCopy.Proof(64, 56, 16, 20)
        for (altFirst in listOf(true, false)) {
            val alt = if (altFirst) 8L else 9L
            val control = if (altFirst) 9L else 8L
            val references = mapOf("getButton" to field(28, 2), "control" to field(control, 1), "shift" to field(10, 1))
            val stack = mapOf(
                "getButton" to field(28, 2), "shift" to field(10, 1), "alt" to field(8, 2),
                "getTimeStamp" to field(40, 8), "getEvent" to field(32, 4), "getMouseWheelChange" to field(24, 4)
            )
            val conversion = mapOf(
                "alt" to field(alt, 1), "control" to field(control, 1),
                "shift" to field(10, 1), "time" to field(40, 8)
            )
            val result = MouseEventFields.crossCheck(copy, references, stack, conversion)
            assertEquals(alt, result.alt)
            assertEquals(control, result.control)
            assertFails {
                MouseEventFields.crossCheck(
                    copy,
                    references,
                    stack,
                    conversion + ("alt" to field(control, 1))
                )
            }
            assertFails {
                MouseEventFields.crossCheck(
                    copy,
                    references,
                    stack,
                    conversion + ("control" to field(alt, 1))
                )
            }
            assertFails { MouseEventFields.crossCheck(copy, references, stack, conversion + ("shift" to field(11, 1))) }
            assertFails { MouseEventFields.crossCheck(copy, references, stack, conversion + ("time" to field(48, 8))) }
            assertFails {
                MouseEventFields.crossCheck(
                    copy,
                    references,
                    stack + ("getButton" to field(26, 2)),
                    conversion
                )
            }
            assertFails { MouseEventFields.crossCheck(copy, references, stack + ("alt" to field(8, 1)), conversion) }
            assertFails {
                MouseEventFields.crossCheck(
                    copy,
                    references,
                    stack + ("getEvent" to field(28, 4)),
                    conversion
                )
            }
            assertFails { MouseEventFields.crossCheck(copy.copy(extent = 60), references, stack, conversion) }
        }
    }
}
