@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxWorldLayout
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorldLayoutTest {
    private fun fixture() = WorldLayout(
        0x1000, 80, 160, 40, 48, 24, 32,
        listOf(56, 64, 88), 0x2010, 0x3000
    )

    @Test
    fun relocatesOnlyAddressesAndCopiesBoundedMembers(): Unit = memScoped {
        val output = alloc<FmLinuxWorldLayout>()
        fixture().writeTo(output, 0x100000)
        assertEquals(0x101000uL, output.global)
        assertEquals(0x102010uL, output.contextVtable)
        assertEquals(0x103000uL, output.contextTypeInfo)
        assertEquals(80u, output.globalSize)
        assertEquals(160u, output.scenarioSize)
        assertEquals(40u, output.gameSize)
        assertEquals(48u, output.contextSize)
        assertEquals(24u, output.scenario)
        assertEquals(32u, output.game)
        assertEquals(3u, output.contextCount)
        assertEquals(listOf(56u, 64u, 88u), (0..2).map { output.contexts[it] })
        assertFailsWith<IllegalArgumentException> { fixture().writeTo(output, Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { fixture().writeTo(output, -1) }
    }

    @Test
    fun rejectsUnboundedOverlappingOrMissingEvidence() {
        val layout = fixture()
        for (contexts in listOf(
            emptyList(), listOf(56L, 56L), listOf(56L, 60L), listOf(32L),
            listOf(-8L), listOf(153L), List(65) { it * 8L })) {
            assertFailsWith<IllegalArgumentException> { layout.copy(contexts = contexts) }
        }
        assertFailsWith<IllegalArgumentException> { layout.copy(global = 0) }
        assertFailsWith<IllegalArgumentException> { layout.copy(contextVtable = 8) }
        assertFailsWith<IllegalArgumentException> { layout.copy(contextTypeInfo = 3) }
        assertFailsWith<IllegalArgumentException> { layout.copy(globalSize = 1) }
        assertFailsWith<IllegalArgumentException> { layout.copy(gameSize = 65 * 1024 * 1024) }
        assertFailsWith<IllegalArgumentException> { layout.copy(scenario = 73) }
        assertFailsWith<IllegalArgumentException> { layout.copy(game = 153) }
    }
}
