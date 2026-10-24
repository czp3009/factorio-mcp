package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SdlButtonAdmission.Button
import kotlin.test.*

class MouseButtonMasksTest {
    @Test
    fun mapsNativeCodesWithoutAssumingTheyAreBitPositions() {
        val codes =
            mapOf(
                Button.LEFT to 3,
                Button.MIDDLE to 7,
                Button.RIGHT to 5,
                Button.X1 to 2,
                Button.X2 to 6,
            )
        val masks = InputButtonMasks(4, mapOf(3L to 128, 7L to 8, 5L to 32, 2L to 64, 6L to 4))
        assertEquals(
            mapOf(
                Button.LEFT to 128,
                Button.MIDDLE to 8,
                Button.RIGHT to 32,
                Button.X1 to 64,
                Button.X2 to 4,
            ),
            MouseButtonMasks.values(masks, codes),
        )
        assertFails { MouseButtonMasks.values(masks, codes - Button.LEFT) }
        for (code in listOf(0, 7, 16, 32, 256)) assertFails {
            MouseButtonMasks.values(masks, codes + (Button.LEFT to code))
        }
        for (value in listOf(0, 1, 3, 8, 65536)) assertFails {
            MouseButtonMasks.values(masks.copy(values = masks.values + (3L to value)), codes)
        }
    }
}
