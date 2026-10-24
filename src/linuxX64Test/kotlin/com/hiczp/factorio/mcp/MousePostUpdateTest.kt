@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlinx.cinterop.toKString
import platform.posix.getenv

class MousePostUpdateTest {
    @Test
    fun selectsOnlyUnchangedMousePathsInCompiledNativePostProcessing() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/input_state_fixture_$padding")
            .use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String) =
                    image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
                val header =
                    EventHeader(
                        constant("fixture_event_extent").toInt(),
                        constant("fixture_event_type"),
                        constant("fixture_event_time"),
                    )
                val flow = X64ControlFlow.resolve(image, image.symbol("fixture_state_post"))
                val paths = MousePostUpdate.analyze(flow, header, setOf(3, 5))
                assertEquals(setOf(3L, 5L), paths.keys)
                assertEquals(
                    setOf(Operation.RET),
                    paths.values.map { flow.body.getValue(it.last()).operation }.toSet(),
                )
                assertFails { MousePostUpdate.analyze(flow, header, setOf(1, 8)) }
                assertFails {
                    MousePostUpdate.analyze(flow, header.copy(type = header.time), setOf(3, 5))
                }
            }
    }

    @Test
    fun rejectsWritesCallsAndLostPreservedRegistersOnSelectedPaths() {
        fun resolve(code: String): Map<Long, List<Long>> =
            MousePostUpdate.analyze(
                X64ControlFlow(X64Instructions(machineCode(code)).all()),
                EventHeader(32, 0, 8),
                setOf(3, 5),
            )
        // The rejected type cases write the receiver; mouse cases branch around them to the shared
        // epilogue.
        val valid =
            "55 48 89 e5 41 56 53 8b 06 83 f8 02 74 05 83 f8 0b 75 06 c7 07 01 00 00 00 5b 41 5e 5d c3"
        assertEquals(setOf(3L, 5L), resolve(valid).keys)
        val reserved =
            valid.replace("53 8b", "53 48 83 ec 20 8b").replace("5b 41 5e", "48 83 c4 20 5b 41 5e")
        assertEquals(setOf(3L, 5L), resolve(reserved).keys)
        assertFails { resolve(reserved.replace("c4 20", "c4 28")) }
        assertFails { resolve(reserved.replace("c4 20", "c4 18")) }
        assertFails { resolve(reserved.replace("ec 20", "e8 20")) }
        assertFails { resolve(valid.replace("5b 41 5e 5d", "5d 41 5e 5b")) }
        assertFails { resolve(valid.replace("8b 06", "8b 07")) }
        assertFails { resolve(valid.replace("75 06", "75 00")) }
        assertFails { resolve("55 48 89 e5 e8 00 01 00 00 5d c3") }
        assertFails { resolve("55 48 89 e5 48 89 fb 5d c3") }
    }
}
