package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class GraphicsDrawableLayoutTest {
    // Synthetic object members and global address; native control-flow shape with two nullable callbacks.
    private val code = """
        55 48 89 e5 48 83 ec 10
        48 8b bf 20 00 00 00 48 8b 07 ff 90 18 00 00 00
        48 8b 3d e1 0f 00 00 48 85 ff 74 1a 48 85 c0 74 0c
        48 8d 8f 60 00 00 00 48 39 08 74 2a
        48 8d 3d 00 10 00 00 eb 07 48 8d 3d 10 10 00 00
        31 c0 e8 34 73 18 00
        8b 4d f8 8b 45 fc 48 c1 e0 20 48 09 c8 48 83 c4 10 5d c3
        4c 8b 87 40 00 00 00 4d 85 c0 75 0c
        4c 8b 87 48 00 00 00 4d 85 c0 74 10
        48 8d 55 f8 48 8d 4d fc 48 89 c6 41 ff d0 eb c5
        8b 48 10 89 4d f8 8b 40 14 89 45 fc eb b7
    """.trimIndent().replace('\n', ' ')

    private fun inspect(bytes: String = code, device: Long = 0x2000) = GraphicsDrawableLayout.analyze(
        X64Instructions(machineCode(bytes)).all(256), 0x1000, device
    )

    @Test
    fun derivesLayoutFromBothCallbacksFallbackAndPackedReturn() {
        assertEquals(GraphicsDrawableLayout(96, 64, 72, 16, 20), inspect())
        assertEquals(
            GraphicsDrawableLayout(104, 80, 88, 24, 32), inspect(
                code
                    .replace("8f 60", "8f 68").replace("87 40", "87 50").replace("87 48", "87 58")
                    .replace("8b 48 10", "8b 48 18").replace("8b 40 14", "8b 40 20")
            )
        )
    }

    @Test
    fun rejectsUnprovenOwnershipBranchesArgumentsAndDimensions() {
        for ((before, after) in listOf(
            "74 1a" to "75 1a", // Wrong device null polarity.
            "74 0c" to "74 38", // Null window enters fallback selection.
            "48 39 08" to "48 39 48 00", // Length change invalidates control-flow boundaries.
            "74 2a" to "75 2a", // Wrong magic comparison polarity.
            "75 0c" to "74 0c", // Calls a null drawable callback.
            "74 10" to "75 10", // Calls a null window-size callback.
            "87 48" to "87 40", // The supposed fallback is the same slot.
            "48 89 c6" to "48 89 ce", // Callback receives an unrelated window.
            "41 ff d0" to "41 ff d1", // Dispatches the wrong function.
            "48 8d 4d fc" to "48 8d 4d f8", // Aliased output pointers.
            "8b 48 10" to "8b 48 00", // Width overlaps window identity.
            "8b 40 14" to "8b 40 10", // Duplicated dimension.
            "89 45 fc" to "89 45 f8", // Fallback overwrites width.
            "48 c1 e0 20" to "48 c1 e0 10", // Changes packed return meaning.
            "48 09 c8" to "48 09 d0", // Return uses an unrelated scalar.
        )) assertFails("Mutation must reject: $before -> $after") { inspect(code.replace(before, after)) }
        assertFails { inspect(device = 0x2008) }
    }
}
