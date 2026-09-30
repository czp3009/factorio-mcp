@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.*

class NativeAccessorTest {
    @Test
    fun resolvesIndependentlyCompiledLayoutsWithoutDebugTypes() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val resolved = mutableListOf<Long>()
        for (padding in listOf(1, 23)) MappedBinary("$directory/accessor_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun compilerValue(name: String): Long {
                val symbol = image.symbol(name)
                return image.virtualBytes(symbol.address, symbol.size).unsigned(0, 8)
            }

            val size = compilerValue("fixture_accessor_size")
            val flags = SysVAccessors.resolve(image, image.symbol("fixture_enabled"), boolean = true).withinObject(size)
            assertEquals(compilerValue("fixture_flags_offset"), flags.offset)
            assertEquals(1, flags.width)
            for (raw in 0uL..255uL) assertEquals(if (raw and 32uL != 0uL) 1uL else 0uL, flags.extract(raw))
            val count = SysVAccessors.resolve(image, image.symbol("fixture_count")).withinObject(size)
            assertEquals(compilerValue("fixture_count_offset"), count.offset)
            assertEquals(4, count.width)
            assertEquals(count.offset, SysVAccessors.resolveAddress(image, image.symbol("fixture_address"), 4, size))
            assertFails { SysVAccessors.resolveAddress(image, image.symbol("fixture_pointer"), 8, size) }
            assertEquals(0x12345678uL, count.extract(0x12345678u))
            val pointer = SysVAccessors.resolve(image, image.symbol("fixture_pointer")).withinObject(size)
            assertEquals(compilerValue("fixture_pointer_offset"), pointer.offset)
            assertEquals(8, pointer.width)
            assertEquals(0xfedcba9876543210uL, pointer.extract(0xfedcba9876543210u))
            assertFailsWith<IllegalArgumentException> { pointer.withinObject(pointer.offset + 7) }
            assertFails { SysVAccessors.resolve(image, image.symbol("fixture_mutating")) }
            assertFails { SysVAccessors.resolve(image, image.symbol("fixture_indirect")) }
            assertFails { SysVAccessors.resolve(image, image.symbol("fixture_count"), boolean = true) }
            resolved += flags.offset
        }
        assertNotEquals(resolved[0], resolved[1])
    }

    @Test
    fun acceptsReceiverAliasesAndProvenBooleanBitTransforms() {
        val accessor =
            SysVAccessors.analyze(machineCode("48 89 f9 48 8d 51 20 0f b6 42 03 24 08 c0 e8 03 c3"), boolean = true)
        assertEquals(NativeAccessor(35, 1, 8u, 3), accessor)
        val reversed = SysVAccessors.analyze(machineCode("0f b6 47 23 c0 e8 03 24 01 c3"), boolean = true)
        assertEquals(accessor, reversed)
    }

    @Test
    fun rejectsUnprovenReceiversSideEffectsFramesAndPartialRegisters() {
        val rejected = listOf(
            "8b 46 10 c3", // RSI is not the receiver.
            "48 8b 47 18 8b 40 10 c3", // Pointer chasing needs another proof.
            "8b 47 10 89 47 14 c3", // A store is not a passive accessor.
            "8b 47 10 e8 00 00 00 00 c3", // Calls invalidate this leaf proof.
            "8a 47 10 c3", // AL does not establish the rest of RAX.
            "8b 47 10 24 01 c3", // Partial masking leaves other field bits.
            "55 48 89 e5 8b 47 10 c3", // Unbalanced frame.
            "8b 5f 10 89 d8 c3", // Unpreserved RBX.
            "8b 05 00 00 00 00 c3", // Global access is not a member proof.
            "8b 44 b7 10 c3", // Unvalidated indexed access.
            "8b 47 f0 c3", // No proof of storage before the receiver.
        )
        for (code in rejected) assertFails(code) { SysVAccessors.analyze(machineCode(code)) }
        assertEquals(16, SysVAccessors.analyze(machineCode("8b 47 10 c3 90")).offset)
        assertFails { SysVAccessors.analyze(machineCode("8b 47 10 c3 8b 47 14")) }
    }

    @Test
    fun rejectsUnboundedAddressesAndObjectLoadsAsAddressProofs() {
        val code = machineCode("48 89 f9 48 8d 41 18 c3")
        assertEquals(24, SysVAccessors.analyzeAddress(code, 32, 56))
        assertFails { SysVAccessors.analyzeAddress(code, 32, 55) }
        assertFails { SysVAccessors.analyzeAddress(code, 0, 56) }
        assertFails { SysVAccessors.analyzeAddress(machineCode("48 8b 47 18 c3"), 8, 64) }
        assertFails { SysVAccessors.analyzeAddress(machineCode("48 8d 46 18 c3"), 8, 64) }
    }
}
