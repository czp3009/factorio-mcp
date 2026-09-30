package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class SdlScancodesTest {
    @Test
    fun usesTheSdkVocabularyAndPreservesChordOrder() {
        assertEquals(listOf(224u, 225u, 41u), SdlScancodes.chord(listOf("LCTRL", "LSHIFT", "ESCAPE")))
        assertEquals(listOf(40u, 43u, 88u, 158u), SdlScancodes.chord(listOf("RETURN", "TAB", "KP_ENTER", "RETURN2")))
        assertEquals(SdlScancodes.codes.size, SdlScancodes.names.size)
        for ((name, value) in SdlScancodes.codes) assertEquals(name, SdlScancodes.names[value])
        assertFalse(0u in SdlScancodes.names)
        assertFalse(130u in SdlScancodes.names)
        assertFalse(512u in SdlScancodes.names)
    }

    @Test
    fun rejectsUnmappedOrInvalidChordsWithoutInventingAliases() {
        for (keys in listOf(
            emptyList(), listOf("UNKNOWN"), listOf("escape"), listOf("ESC"),
            listOf("RETURN", "RETURN"), listOf("SDL_SCANCODE_RETURN"), List(9) { "A" })) {
            assertFails { SdlScancodes.chord(keys) }
        }
    }
}
