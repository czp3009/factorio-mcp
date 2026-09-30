@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class StringResultStorageTest {
    @Test
    fun connectsNativeConstructionToTheCallersCompleteOutputStorage() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val sizes = listOf(1, 23).map { padding ->
            MappedBinary("$directory/string_result_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
                val storage = NativeStringLayout(
                    constant("fixture_result_data"), constant("fixture_result_length"),
                    constant("fixture_result_local"), constant("fixture_result_size"), image.symbol("_ZN6StringD2Ev")
                )
                val debug = DwarfInlines(image)
                fun resolve(layout: NativeStringLayout = storage) = StringResultStorage.resolve(
                    image,
                    "_ZNK7Control4nameEv", "name", "fixture_caller", layout, debug, "initializeStorage", "setLength"
                )

                val proof = resolve()
                assertEquals(7, proof.argument)
                assertEquals(1, proof.callers.size)
                assertFails { resolve(storage.copy(data = storage.data + 8)) }
                assertFails { resolve(storage.copy(local = storage.local + 1)) }
                assertFails { resolve(storage.copy(length = storage.length + 1)) }
                assertFails { resolve(storage.copy(size = 4096)) }
                storage.size
            }
        }
        assertNotEquals(sizes[0], sizes[1])
    }

    @Test
    fun rejectsMissingScopesWrongPointersAndInitializationBypasses() {
        // Synthetic result has data at 8, length at 16 and an inline buffer at 40.
        val code = "48 8d 47 28 48 89 47 08 48 89 57 10 c3"
        fun verify(
            text: String, initial: List<DwarfRanges.Range> = listOf(DwarfRanges.Range(4, 8)),
            lengths: List<DwarfRanges.Range> = listOf(DwarfRanges.Range(8, 12))
        ) =
            StringResultStorage.analyze(
                X64ControlFlow(X64Instructions(machineCode(text)).all()),
                8, 16, 40, 64, initial, lengths
            )
        assertEquals(7, verify(code).argument)
        assertFails { verify(code, initial = emptyList()) }
        assertFails { verify(code, lengths = emptyList()) }
        assertFails { verify(code, initial = listOf(DwarfRanges.Range(4, 7))) }
        for (changed in listOf(
            code.replace("48 8d 47 28", "48 8d 46 28"),
            code.replace("48 89 47 08", "48 89 46 08"),
            code.replace("48 89 57 10", "48 89 56 10"),
            code.replace("48 8d 47 28", "90 8d 47 28"),
            code.replace("48 89 57 10", "90 89 57 10"),
            code.replace("c3", "48 89 57 40 c3"),
        )) assertFails { verify(changed) }
        val bypass = "85 c9 74 08 $code"
        assertFails {
            verify(bypass, listOf(DwarfRanges.Range(8, 12)), listOf(DwarfRanges.Range(12, 16)))
        }
    }

    @Test
    fun rejectsCallerRegistersAndInsufficientOrMisalignedFrames() {
        val code = "48 83 ec 48 48 8d 7c 24 08 e8 f2 0f 00 00 48 83 c4 48 c3"
        fun verify(text: String, size: Long = 64) = StringResultStorage.caller(
            X64ControlFlow(X64Instructions(machineCode(text)).all()), 0x1000, 7, size
        )
        assertEquals(listOf(9L), verify(code))
        assertFails { verify(code, 65) }
        assertFails { verify(code.replace("24 08", "24 10")) }
        assertFails { verify(code.replace("83 ec 48", "83 ec 40")) }
        assertFails { verify(code.replace("8d 7c", "8d 74")) }
        assertFails { verify(code.replace("e8 f2", "e8 f1")) }
        val savedFrame = "55 48 89 e5 48 83 ec 40 48 8d 7c 24 08 e8 ee 0f 00 00 48 83 c4 40 5d c3"
        assertFails { verify(savedFrame) }
    }
}
