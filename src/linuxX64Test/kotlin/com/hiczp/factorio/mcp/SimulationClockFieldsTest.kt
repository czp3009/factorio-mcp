@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class SimulationClockFieldsTest {
    @Test
    fun derivesTickAndStopFromDifferentCompiledLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val results = listOf(1, 23).map { padding ->
            MappedBinary("$directory/simulation_clock_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun field(name: String) =
                    image.symbol("fixture_$name").let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

                val debug = DwarfInlines(image)
                val stop = MapStopField.resolve(image, field("map_size"))
                assertEquals(field("map_stop"), stop)
                val tick = ScriptTickField.resolve(image, field("script_size"), field("map_size"), debug = debug)
                assertEquals(ScriptTickField(field("script_map"), field("map_tick")), tick)
                val function = image.symbol("_ZN17GameActionHandler6updateEv")
                val method = ItaniumVtable.resolve(image, "_ZTV11InputSource")
                    .method(image, "_ZN11InputSource22sendPausedStateChangesEv")
                val pause = InputPauseField.analyze(
                    image.functionBytes(function, 512), function.address,
                    function.size, field("handler_size"), field("map_size"), method.slot
                )
                assertEquals(InputPauseField(field("handler_map"), field("handler_source"), field("map_paused")), pause)
                assertEquals(
                    listOf(field("game_handler")), TypedMemberStores.resolve(
                        image,
                        "_ZN4GameC2ER3MapP11InputSource",
                        field("game_size"),
                        ItaniumType.resolve(image, "17GameActionHandler")
                    )
                )
                stop to tick
            }
        }
        assertNotEquals(results[0], results[1])
    }

    @Test
    fun rejectsTickSourcesWithoutOriginalPointerProvenanceOrBounds() {
        val code = "55 48 89 e5 48 8b 47 10 48 8b 48 20 48 83 f9 ff"
        fun resolve(
            value: String = code, scriptSize: Long = 64, mapSize: Long = 64,
            ranges: List<DwarfRanges.Range> = listOf(DwarfRanges.Range(8, 16))
        ) =
            ScriptTickField.analyze(machineCode(value), 0x1000, ranges, scriptSize, mapSize)
        assertEquals(ScriptTickField(16, 32), resolve())
        assertFails { resolve(code.replace("48 8b 47 10", "48 8b 46 10")) }
        assertFails { resolve(code.replace("48 8b 48 20", "48 8b 4f 20")) }
        assertFails { resolve(code.replace("48 8b 48 20", "8b 48 20 90")) }
        assertFails { resolve(code.replace("48 8b 47 10", "48 8b 47 11")) }
        assertFails { resolve(code.replace("48 8b 48 20", "48 8b 48 21")) }
        assertFails { resolve(scriptSize = 23) }
        assertFails { resolve(mapSize = 39) }
        assertFails { resolve(ranges = listOf(DwarfRanges.Range(9, 16))) }
        assertFails { resolve(ranges = listOf(DwarfRanges.Range(4, 16))) }
        assertFails { resolve(code.replace("55 48 89 e5", "eb 02 90 90")) }
    }

    @Test
    fun matchesOriginalMapBetweenHiddenReturnCallAndStopGetter() {
        val code = "55 48 89 e5 53 48 83 ec 18 48 89 fb 48 8d 7d e0 48 89 de e8 e8 00 00 00 fe 43 20 c3"
        fun increment(value: String = code, size: Long = 64) =
            MapStopField.increment(X64ControlFlow(X64Instructions(machineCode(value)).all()), 0x100, size)
        assertEquals(32L, increment())
        assertEquals(32L, increment(code.replace("fe 43 20", "90 48 89 d8 90 fe 43 20")))
        assertFails { increment(code.replace("48 89 de", "48 89 fe")) }
        assertFails { increment(code.replace("48 8d 7d e0", "48 89 df 90")) }
        assertFails { increment(code.replace("fe 43 20", "fe 47 20")) }
        assertFails { increment(code.replace("fe 43 20", "ff 43 20")) }
        assertFails { increment(size = 32) }
        val getter = "55 48 89 e5 48 89 f3 80 7b 20 00"
        fun verify(value: String = getter, field: Long = 32) = MapStopField.verifyGetter(
            X64ControlFlow(X64Instructions(machineCode(value)).all()), listOf(DwarfRanges.Range(7, 11)), 64, field
        )
        verify()
        assertFails { verify(getter.replace("48 89 f3", "48 89 fb")) }
        assertFails { verify(getter.replace("80 7b 20 00", "80 7b 20 01")) }
        assertFails { verify(field = 31) }
    }

    @Test
    fun rejectsPauseGuardsThatDoNotSelectTheTypedSourceDispatch() {
        val code = "55 48 89 e5 53 50 48 89 fb 48 8b 47 20 80 78 30 01 75 10 48 8b 7b 28 48 8b 07 ff 50 18"
        fun resolve(value: String = code, handlerSize: Long = 64, mapSize: Long = 64, slot: Int = 3) =
            InputPauseField.analyze(machineCode(value), 0x1000, 128, handlerSize, mapSize, slot)
        assertEquals(InputPauseField(32, 40, 48), resolve())
        assertEquals(InputPauseField(32, 40, 48), resolve(code.replace("30 01 75", "30 01 90 48 89 da 75")))
        assertFails { resolve(code.replace("30 01 75", "30 01 85 d2 75")) }
        assertEquals(InputPauseField(32, 40, 48), resolve(code.replace("30 01 75", "30 00 74")))
        for (changed in listOf(
            code.replace("48 8b 47 20", "48 8b 46 20"),
            code.replace("80 78 30 01", "80 7f 30 01"),
            code.replace("75 10", "74 10"),
            code.replace("30 01", "30 02"),
            code.replace("75 10", "75 04"),
            code.replace("75 10", "75 70"),
            code.replace("48 8b 7b 28", "48 8b 7b 20"),
            code.replace("48 8b 07", "48 8b 03"),
            code.replace("80 78 30 01", "83 78 30 01"),
        )) assertFails { resolve(changed) }
        assertFails { resolve(handlerSize = 47) }
        assertFails { resolve(mapSize = 48) }
        assertFails { resolve(slot = 4) }
    }
}
