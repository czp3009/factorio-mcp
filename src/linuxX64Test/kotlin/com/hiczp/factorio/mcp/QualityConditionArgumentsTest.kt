package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class QualityConditionArgumentsTest {
    @Test
    fun connectsSplitInputsToTheRegistryAndTheSameTypedConsumer() {
        val reference = "53 0f b6 57 01 e8 f6 00 00 00 5b c3"
        val draw =
            "53 41 89 f1 40 0f b6 ff 48 8b 05 f1 1f 00 00 48 8b 0c f8 " +
                "45 84 c9 74 09 41 0f b6 d1 e8 df 00 00 00 5b c3"
        fun flow(code: String) = X64ControlFlow(X64Instructions(machineCode(code)).all())
        fun analyze(body: String = draw, record: String = reference) =
            QualityConditionArguments.analyze(flow(record), 256, 1, flow(body), 0x1000, 256, 0x3000)
        assertEquals(QualityConditionArguments(7, 6), analyze())
        assertFails { analyze(record = reference.replace("57 01", "57 02")) }
        assertFails { analyze(record = reference.replace("57 01", "56 01")) }
        assertFails { analyze(draw.replace("0f b6 ff", "0f b6 fe")) }
        assertFails { analyze(draw.replace("41 0f b6 d1", "40 0f b6 d7")) }
        assertFails { analyze(draw.replace("f1 1f", "f9 1f")) }
        assertFails { analyze(draw.replace("40 0f b6 ff", "48 89 ff 90")) }
        assertFails { analyze(draw.replace("0c f8", "0c 78")) }
    }
}
