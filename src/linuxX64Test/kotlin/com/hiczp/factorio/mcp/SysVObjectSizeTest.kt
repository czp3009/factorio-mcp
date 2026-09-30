@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.*

class SysVObjectSizeTest {
    @Test
    fun validatesLeafDeletionWithAnUnchangedReceiverAndBoundedBranches() {
        val valid =
            "55 48 89 e5 48 c7 47 08 00 00 00 00 48 85 ff 74 08 48 8b 47 10 48 89 40 08 be 20 00 00 00 5d e9 dc 00 00 00"

        fun analyze(code: String) = SysVObjectSize.analyzeLeaf(machineCode(code), 0x1000, 0x1100)
        assertEquals(32L, analyze(valid))
        for (changed in listOf(
            valid.replace("47 08", "47 20"), // Receiver member exceeds the independently passed size.
            valid.replace("74 08", "74 0a"), // Branch into an instruction.
            valid.replace("74 08", "74 ef"), // Back edge.
            valid.replace("48 89 40 08", "48 89 c7 90"), // Receiver replaced by a child.
            valid.replace("5d e9", "90 e9"), // Caller frame not restored.
            valid.replace("be 20", "ba 20"), // Size supplied in the wrong argument register.
        )) assertFails(changed) { analyze(changed) }
    }

    @Test
    fun rejectsInlinedDestructionWithoutAStableOriginalReceiverOrContainedBranches() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/accessor_fixture_1").use { file ->
            val image = ElfImage(file.view)
            val function = image.symbol("_ZN7fixture5LabelD0Ev")
            val body = X64Instructions(image.functionBytes(function, 256)).all()
            val section = image.sections.single {
                function.address >= it.address &&
                        function.address - it.address < it.size && it.flags and 4L != 0L
            }
            val start = section.offset + function.address - section.address
            val capture = body.single {
                it.operation == X64Instructions.Operation.MOV &&
                        it.source == X64Instructions.Register(7, 8) && it.destination == X64Instructions.Register(3, 8)
            }
            val branch = body.first { it.operation == X64Instructions.Operation.JCC }
            assertEquals(3, capture.size)
            assertEquals(2, branch.size)
            val receiverChanged = file.view.bytes(0, file.view.size.toInt())
            val captureModRm = (start + capture.offset + capture.size - 1).toInt()
            receiverChanged[captureModRm] = (receiverChanged[captureModRm].toInt() xor 8).toByte()
            assertFails { SysVObjectSize.resolve(ElfImage(BinaryView(receiverChanged)), "7fixture5Label") }
            val escaped = file.view.bytes(0, file.view.size.toInt())
            val relative = function.size - branch.offset - branch.size
            assertTrue(relative in 1..127)
            escaped[(start + branch.offset + branch.size - 1).toInt()] = relative.toByte()
            assertFails { SysVObjectSize.resolve(ElfImage(BinaryView(escaped)), "7fixture5Label") }
        }
    }

    @Test
    fun provesCompilerSizedDeletionAcrossChangedLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val sizes = mutableListOf<Long>()
        for (padding in listOf(1, 23)) MappedBinary("$directory/accessor_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            val symbol = image.symbol("fixture_virtual_size")
            val expected = image.virtualBytes(symbol.address, 8).unsigned(0, 8)
            val actual = SysVObjectSize.resolve(image, "14FixtureVirtual")
            assertEquals(expected, actual)
            sizes += actual
        }
        assertNotEquals(sizes[0], sizes[1])
    }

    @Test
    fun refusesADeletingDestructorWhoseSizeArgumentWasOptimizedAway() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/accessor_fixture_inline").use { file ->
            assertFails { SysVObjectSize.resolve(ElfImage(file.view), "14FixtureVirtual") }
        }
    }

    @Test
    fun tracksReceiverAndPreservedRegistersAcrossDestructorCall() {
        val code =
            machineCode("55 48 89 e5 53 50 48 89 fb e8 f2 00 00 00 be 40 00 00 00 48 89 df 48 83 c4 08 5b 5d e9 10 00 00 00")
        assertEquals(64, SysVObjectSize.analyze(code, 0x1000, setOf(0x1100), 0x1031))
        assertFails { SysVObjectSize.analyze(code, 0x1000, setOf(0x1101), 0x1031) }
        assertFails { SysVObjectSize.analyze(code, 0x1000, setOf(0x1100), 0x1032) }
    }

    @Test
    fun separatesExceptionalTrailersFromNormalDeletionPaths() {
        val code = "be 40 00 00 00 e9 f6 00 00 00 48 89 c7 e8 ee 01 00 00"
        assertEquals(64L, SysVObjectSize.analyze(machineCode(code), 0x1000, emptySet(), 0x1100))
        assertFails {
            SysVObjectSize.analyze(machineCode("75 0a $code"), 0x1000, emptySet(), 0x1102)
        }
    }

    @Test
    fun rejectsUnknownSizeReceiverChangesAndUnbalancedFrames() {
        val rejected = listOf(
            "e9 10 00 00 00", // No proven RSI size.
            "be 40 00 00 00 48 89 c7 e9 08 00 00 00", // Unknown RAX receiver.
            "53 be 40 00 00 00 e9 0a 00 00 00", // Unbalanced frame.
            "be 40 00 00 00 48 89 fb e9 08 00 00 00", // RBX is clobbered.
            "be 00 00 00 00 e9 0b 00 00 00", // Zero-size deletion.
        )
        for (code in rejected) assertFails(code) {
            SysVObjectSize.analyze(
                machineCode(code),
                0x1000,
                emptySet(),
                0x1015
            )
        }
    }
}
