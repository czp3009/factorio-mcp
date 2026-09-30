@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxChatAdmissionConfig
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChatAdmissionLayoutTest {
    private fun fixture() = ChatAdmissionLayout(
        WorldLayout(0x1000, 80, 160, 64, 48, 24, 32, listOf(56), 0x2010, 0x3000),
        PlayerLayout(64, 64, 32, 8, 16, 8, PlayerIndex(24, 2), 0x4010, 0x5000, 0x6010, 0x7000),
        ChatSubmissionGate(16, 24, 32, 16, UInt.MAX_VALUE.toLong()), 48, 40, ItaniumType(0x8010, 0x9000)
    )

    @Test
    fun writesTypedOffsetsAndRelocatesAddressesOnly(): Unit = memScoped {
        val layout = fixture()
        val output = alloc<FmLinuxChatAdmissionConfig>()
        layout.writeTo(output, 0x100000)
        assertEquals(0x108010uL, output.handlerVtable)
        assertEquals(0x109000uL, output.handlerTypeInfo)
        assertEquals(0x101000uL, output.world.global)
        assertEquals(0x104010uL, output.player.playerVtable)
        assertEquals(48u, output.mapSize)
        assertEquals(40u, output.handlerSize)
        assertEquals(16u, output.playerMap)
        assertEquals(24u, output.mapGame)
        assertEquals(32u, output.gameHandler)
        assertEquals(16u, output.handlerField)
        assertEquals(UInt.MAX_VALUE, output.excluded)
        for (bias in listOf(-8L, 1L, Long.MAX_VALUE - 7)) assertFails { layout.writeTo(output, bias) }
    }

    @Test
    fun rejectsMismatchedOwnersOutOfBoundsMembersAndInvalidRtti() {
        val layout = fixture()
        assertFails { layout.copy(world = layout.world.copy(gameSize = 72)) }
        for (gate in listOf(
            layout.gate.copy(playerMap = 0), layout.gate.copy(playerMap = 60),
            layout.gate.copy(mapGame = 48), layout.gate.copy(gameHandler = 8),
            layout.gate.copy(handlerField = 38), layout.gate.copy(handlerField = 0),
            layout.gate.copy(excluded = -1), layout.gate.copy(excluded = 0x100000000),
        )) assertFails { layout.copy(gate = gate) }
        assertFails { layout.copy(handler = ItaniumType(0x8011, 0x9000)) }
        assertFails { layout.copy(handler = ItaniumType(0x8010, Long.MAX_VALUE - 7)) }
    }
}
