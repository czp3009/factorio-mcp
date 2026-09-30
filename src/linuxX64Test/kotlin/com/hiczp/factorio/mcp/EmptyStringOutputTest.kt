@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class EmptyStringOutputTest {
    @Test
    fun identifiesHiddenOutputAcrossIndependentNativeLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/empty_string_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) =
                image.symbol("fixture_$name").let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

            val function = image.symbol("_Z8optionalPvS_S_S_S_")
            val string = NativeStringLayout(
                constant("data"), constant("length"), constant("local"), constant("size"),
                image.symbol("_ZN6StringD2Ev")
            )
            val debug = DwarfInlines(image)
            val result =
                EmptyStringOutput.resolve(image, function.name, "optional", "fixture_caller", string, debug, "String")
            assertEquals(7, result.argument)
            assertTrue(result.constructors.isNotEmpty() && result.callers.isNotEmpty())
            val flow = X64ControlFlow.resolve(image, function)
            // The successful path deliberately contains a saved frame alias. It remains unsupported by the
            // generic constructor model; selecting the empty-result ancestors must not relax that rejection.
            assertFails { ConstructorValues(flow, emptyMap()) }
            val ranges = debug.find(function, "optional", setOf("String")).filter { it.ranges.size == 1 }
                .map { it.ranges.single() }
                .map { DwarfRanges.Range(it.start - function.address, it.end - function.address) }
            assertFails { EmptyStringOutput.analyze(flow, string.copy(local = string.size), ranges) }
            assertFails { EmptyStringOutput.analyze(flow, string.copy(length = string.length + 1), ranges) }
            assertFails { EmptyStringOutput.analyze(flow, string, emptyList()) }
            val changed = X64ControlFlow(flow.instructions.map {
                if (it.operation == Operation.MOV && it.destination is Memory && it.destination.width == 1 &&
                    it.source == Immediate(0)
                ) it.copy(source = Immediate(1)) else it
            })
            assertFails { EmptyStringOutput.analyze(changed, string, ranges) }
        }
    }

    @Test
    fun usesOriginalOutputRegistersWithoutAcceptingUnrelatedSavedLocalAliases() {
        val storage = NativeStringLayout(8, 16, 40, 64, ElfImage.Symbol("fixture_destroy", 0x2000, 1, 2, 1))
        val prefix = machineCode("55 48 89 e5 48 83 ec 10 48 8d 45 f0 48 89 45 f8")
        val constructor = machineCode("48 8d 47 28 48 89 47 08 48 c7 47 10 00 00 00 00 c6 47 28 00")
        val tail = machineCode("48 83 c4 10 5d c3")
        val bytes = BinaryView(
            prefix.bytes(0, prefix.size.toInt()) + constructor.bytes(
                0,
                constructor.size.toInt()
            ) + tail.bytes(0, tail.size.toInt())
        )
        val flow = X64ControlFlow(X64Instructions(bytes).all())
        assertFails { ConstructorValues(flow, emptyMap()) }
        assertEquals(
            7, EmptyStringOutput.analyze(
                flow, storage,
                listOf(DwarfRanges.Range(prefix.size, prefix.size + constructor.size))
            ).argument
        )
    }

    @Test
    fun recoversOnlyCompleteUnexposedPrivatePointerSpills() {
        // Synthetic output layout, unrelated to any game object.
        val storage = NativeStringLayout(8, 16, 40, 64, ElfImage.Symbol("fixture_destroy", 0x2000, 1, 2, 1))
        val code = "55 48 89 e5 48 83 ec 10 48 89 7d f8 e8 ef 0f 00 00 48 8b 4d f8 " +
                "48 8d 41 28 48 89 41 08 48 c7 41 10 00 00 00 00 c6 41 28 00 48 83 c4 10 5d c3"

        fun resolve(text: String, range: DwarfRanges.Range = DwarfRanges.Range(21, 41)): EmptyStringOutput.Proof {
            val flow = X64ControlFlow(X64Instructions(machineCode(text)).all())
            return EmptyStringOutput.analyze(flow, storage, listOf(range))
        }
        assertEquals(7, resolve(code).argument)
        assertEquals(6, resolve(code.replace("48 89 7d f8", "48 89 75 f8")).argument)
        assertFails { resolve(code.replace("48 89 7d f8", "89 7d f8 90")) }
        assertFails { resolve(code.replace("48 8b 4d f8", "8b 4d f8 90")) }
        assertFails { resolve(code.replace("48 89 7d f8", "48 8d 7d f8")) }
        assertFails { resolve(code.replace("c6 41 28 00", "c6 41 28 01")) }
        assertFails { resolve(code.replace("48 89 41 08", "48 89 41 10")) }
        assertEquals(7, resolve(code, DwarfRanges.Range(25, 41)).argument)
        assertFails { resolve(code, DwarfRanges.Range(29, 41)) }
        assertFails { resolve(code, DwarfRanges.Range(21, 40)) }
    }
}
