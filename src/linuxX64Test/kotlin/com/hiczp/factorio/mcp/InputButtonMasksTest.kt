package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.*

class InputButtonMasksTest {
    @Test
    fun connectsBoundedWordTablesToAnUnchangedLocalQueueField() {
        val header = EventHeader(64, 0, 8)
        val bytes =
            "55 48 89 e5 48 83 ec 20 44 8b 46 18 41 ff c8 ba 01 00 00 00 " +
                "41 83 f8 04 77 0f 44 89 c2 4c 8d 05 00 01 00 00 41 0f b7 14 50 " +
                "66 89 55 e0 48 8d 75 e0 e8 00 10 00 00 48 83 c4 20 5d c3"
        fun analyze(
            code: String = bytes,
            codes: Set<Long> = (1L..5L).toSet(),
            short: Boolean = false,
        ): InputButtonMasks {
            val body = X64Instructions(machineCode(code)).all()
            val sink = body.single { it.operation == Operation.CALL }.offset
            return InputButtonMasks.analyze(X64ControlFlow(body), 0, sink, header, 24, codes, 32) {
                _,
                size ->
                BinaryView(
                    ByteArray(if (short) 1 else size.toInt()) {
                        if (it % 2 == 0) (2 shl (it / 2)).toByte() else 0
                    }
                )
            }
        }
        assertEquals(
            InputButtonMasks(0, mapOf(1L to 2, 2L to 4, 3L to 8, 4L to 16, 5L to 32)),
            analyze(),
        )
        assertFails { analyze(codes = setOf(0)) }
        assertFails { analyze(codes = setOf(6)) }
        assertFails { analyze(short = true) }
        assertFails { analyze(bytes.replace("77 0f", "7f 0f")) }
        assertFails { analyze(bytes.replace("44 8b 46 18", "44 8b 47 18")) }
        assertFails { analyze(bytes.replace("44 8b 46 18", "44 8b 46 1c")) }
        assertFails { analyze(bytes.replace("41 0f b7 14 50", "41 0f b7 14 90")) }
        assertFails { analyze(bytes.replace("66 89 55 e0", "66 89 4d e0")) }
        assertFails { analyze(bytes.replace("41 83 f8 04", "41 83 f9 04")) }
    }
}
