package com.hiczp.factorio.mcp

import kotlin.test.*
import kotlinx.serialization.json.*

class InputTransferTest {
    @Test
    fun absenceAndUnavailableAreDifferentFromZeroProgress() {
        val absent = InputTransferSnapshot(true, false, 0).toJson()
        assertEquals(JsonNull, absent["front_batch_blueprint_import"])
        assertEquals(JsonPrimitive(false), absent["client_present"])
        val pending = InputTransferSnapshot(true, true, 3, 0, 8).toJson()
        assertEquals(
            JsonPrimitive(0),
            pending["front_batch_blueprint_import"]!!.jsonObject["segment_index"],
        )
        val unavailable =
            InputTransferSnapshot(false, false, 0, reason = "Missing metadata").toJson()
        assertFalse("front_batch_blueprint_import" in unavailable)
        assertEquals(JsonPrimitive("Missing metadata"), unavailable["reason"])
    }

    @Test
    fun playerQueryIncludesNativeCursorRepresentations() {
        val query =
            parseWorldQuery(
                Json.parseToJsonElement("""{"selection":{"kind":"player"}}""").jsonObject
            )
        val fields = query.arguments.getValue("fields").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(
            fields.containsAll(
                listOf(
                    "cursor_stack",
                    "cursor_ghost",
                    "cursor_record",
                    "cursor_stack_temporary",
                    "hand_location",
                )
            )
        )
    }
}
