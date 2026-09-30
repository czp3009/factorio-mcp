@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class InputOwnerPrefixTest {
    @Test
    fun matchesCompiledTypedFieldsAcrossDifferentLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val locations = listOf(1, 23).map { padding ->
            MappedBinary("$directory/input_owner_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun field(name: String) =
                    image.symbol("fixture_$name").let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

                val sizes = listOf("source", "player", "map", "game").map { field("${it}_size") }
                val sourcePlayer = SysVArgumentMember.resolve(image, "_ZN6Source7connectEP6Player", sizes[0])
                val playerMap = SysVArgumentMember.resolve(image, "_ZN6PlayerC2ER3Map", sizes[1])
                assertEquals(field("source_player"), sourcePlayer)
                assertEquals(field("player_map"), playerMap)
                val function = image.symbol("_ZN6Source8evaluateEv")
                val mapGame = InputOwnerPrefix.analyze(
                    image.functionBytes(function, 512), function.size,
                    sizes, sourcePlayer, playerMap, listOf(field("game_player"))
                )
                assertEquals(field("map_game"), mapGame)
                assertFails {
                    InputOwnerPrefix.analyze(
                        image.functionBytes(function, 512), function.size,
                        sizes, sourcePlayer, playerMap, listOf(field("game_player") - 8)
                    )
                }
                val viewFunction = image.symbol("_ZN6Source12evaluateViewEv")
                val viewSizes = sizes + field("view_size")
                val tail = listOf(field("game_view"), field("view_player"))
                assertEquals(
                    mapGame, InputOwnerPrefix.analyze(
                        image.functionBytes(viewFunction, 512), viewFunction.size,
                        viewSizes, sourcePlayer, playerMap, tail
                    )
                )
                assertFails {
                    InputOwnerPrefix.analyze(
                        image.functionBytes(viewFunction, 512), viewFunction.size,
                        viewSizes, sourcePlayer, playerMap, listOf(field("game_player"), field("view_player"))
                    )
                }
                mapGame
            }
        }
        assertNotEquals(locations[0], locations[1])
    }

    @Test
    fun rejectsWrongReceiversNarrowLoadsAndMissingOwnershipGuards() {
        val code = "48 8b 4f 10 48 85 c9 74 30 48 8b 41 20 48 8b 40 30 48 85 c0 74 20 48 39 48 40 75 18"
        fun resolve(value: String = code, sizes: List<Long> = List(4) { 128L }) =
            InputOwnerPrefix.analyze(machineCode(value), 128, sizes, 16, 32, listOf(64))
        assertEquals(48L, resolve())
        for (changed in listOf(
            code.replace("48 8b 4f 10", "48 8b 4e 10"),
            code.replace("48 8b 41 20", "48 8b 47 20"),
            code.replace("48 8b 40 30", "8b 40 30 90"),
            code.replace("74 30", "75 30"),
            code.replace("74 20", "90 90"),
            code.replace("75 18", "74 18"),
            code.replace("74 30", "74 00"),
            code.replace("48 39 48 40", "48 39 50 40"),
        )) assertFails { resolve(changed) }
        assertFails { resolve(sizes = listOf(128, 128, 48, 128)) }
    }

    @Test
    fun requiresViewGuardAndTypedViewBounds() {
        val code = "48 8b 4f 10 48 85 c9 74 50 48 8b 41 20 48 8b 40 30 48 85 c0 74 40 " +
                "48 8b 40 40 48 85 c0 74 30 48 39 48 50 75 28"

        fun resolve(value: String = code, sizes: List<Long> = List(5) { 128L }, tail: List<Long> = listOf(64, 80)) =
            InputOwnerPrefix.analyze(machineCode(value), 128, sizes, 16, 32, tail)
        assertEquals(48L, resolve())
        assertFails { resolve(code.replace("74 30", "90 90")) }
        assertFails { resolve(code.replace("74 30", "75 30")) }
        assertFails { resolve(sizes = listOf(128, 128, 128, 128, 80)) }
        assertFails { resolve(tail = listOf(64, 88)) }
        assertFails { resolve(tail = listOf(72, 80)) }
    }
}
