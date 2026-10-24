@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlinx.cinterop.toKString
import platform.posix.getenv

class EventHeaderTest {
    @Test
    fun derivesTypedHeaderFieldsAndStrideFromCompiledConstruction() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/event_header_fixture_$padding")
            .use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String) =
                    image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
                assertEquals(
                    EventHeader(
                        constant("fixture_event_size").toInt(),
                        constant("fixture_type_offset"),
                        constant("fixture_time_offset"),
                    ),
                    EventHeader.resolve(image, "fixture_event_emplace"),
                )
            }
    }

    @Test
    fun requiresSurvivingOriginalArgumentStoresIntoOneQueueElement() {
        // Synthetic queue array pointer + unsigned index * 32. No fixture layout is a game offset.
        val prefix = "48 8b 07 8b 4f 08 48 c1 e1 05 48 8d 04 08"
        val stores = "8b 0a 89 48 04 f2 0f 10 06 f2 0f 11 40 08"
        fun analyze(code: String) =
            EventHeader.analyze(X64ControlFlow(X64Instructions(machineCode(code)).all()))
        assertEquals(EventHeader(32, 4, 8), analyze("$prefix $stores c3"))
        // These bodies are inspected for stores only. An unused C++ reference return may be
        // optimized away.
        assertEquals(EventHeader(32, 4, 8), analyze("$prefix $stores 48 89 f0 c3"))
        assertEquals(EventHeader(32, 4, 8), analyze("$prefix $stores 48 8b 17 48 8d 04 0a c3"))
        for (code in
            listOf(
                "$prefix ${stores.replace("8b 0a", "8b 0f")} c3", // Foreign enum source.
                "$prefix ${stores.replace("10 06", "10 02")} c3", // Foreign timestamp source.
                "$prefix ${stores.replace("48 04", "48 1f")} c3", // Out-of-bounds member.
                "$prefix $stores c6 40 05 00 c3", // Partial overwrite destroys enum evidence.
                "$prefix $stores 48 89 0f c3", // Unknown external alias invalidates previous
                                               // stores.
                "$prefix 85 c9 74 0e $stores c3", // Header initialization can be bypassed.
                "$prefix 85 c9 74 10 $stores c3", // A branch leaves the function.
                "$prefix $stores eb fe", // Cyclic construction.
                // Reloading the destination between fields cannot establish that both writes hit
                // one element.
                "$prefix 44 8b 02 44 89 40 04 48 8b 17 48 8d 04 0a f2 0f 10 06 f2 0f 11 40 08 c3",
            )) assertFails { analyze(code) }
    }

    @Test
    fun acceptsPromotedScalarArgumentsAndAnUnusedReturnWithPrivateTimestampSpill() {
        val code =
            "53 41 54 48 83 ec 18 49 89 fc 89 f3 f2 0f 11 04 24 e8 00 01 00 00 " +
                "f2 0f 10 04 24 49 8b 14 24 41 8b 44 24 08 48 6b c0 38 " +
                "89 5c 02 04 f2 0f 11 44 02 10 48 83 c4 18 41 5c 5b c3"
        fun analyze(value: String) =
            EventHeader.analyze(X64ControlFlow(X64Instructions(machineCode(value)).all()))
        assertEquals(EventHeader(56, 4, 16), analyze(code))
        assertFails { analyze(code.replace("89 f3", "89 d3")) }
        assertFails { analyze(code.replace("f2 0f 10 04 24", "f2 0f 10 44 24 08")) }
        assertFails { analyze(code.replace("e8 00 01 00 00", "48 89 e7 e8 00 01 00 00")) }
    }
}
