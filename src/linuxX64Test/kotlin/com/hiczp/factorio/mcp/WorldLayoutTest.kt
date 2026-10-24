@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxWorldLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped

class WorldLayoutTest {
    private fun fixture() = WorldLayout(0x1000, 80, 160, 40, 48, 24, 32, 56)

    @Test
    fun relocatesOnlyAddressesAndCopiesBoundedMembers(): Unit = memScoped {
        val output = alloc<FmLinuxWorldLayout>()
        fixture().writeTo(output, 0x100000)
        assertEquals(0x101000uL, output.global)
        assertEquals(80u, output.globalSize)
        assertEquals(160u, output.scenarioSize)
        assertEquals(40u, output.gameSize)
        assertEquals(48u, output.contextSize)
        assertEquals(24u, output.scenario)
        assertEquals(32u, output.game)
        assertEquals(56u, output.context)
        assertFailsWith<IllegalArgumentException> { fixture().writeTo(output, Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { fixture().writeTo(output, -1) }
    }

    @Test
    fun rejectsUnboundedOverlappingOrMissingEvidence() {
        val layout = fixture()
        for (context in listOf(-8L, 32L, 36L, 57L, 153L, 160L)) {
            assertFailsWith<IllegalArgumentException> { layout.copy(context = context) }
        }
        assertFailsWith<IllegalArgumentException> { layout.copy(global = 0) }
        assertFailsWith<IllegalArgumentException> { layout.copy(globalSize = 1) }
        assertFailsWith<IllegalArgumentException> { layout.copy(gameSize = 65 * 1024 * 1024) }
        assertFailsWith<IllegalArgumentException> { layout.copy(scenario = 73) }
        assertFailsWith<IllegalArgumentException> { layout.copy(game = 153) }
    }
}
