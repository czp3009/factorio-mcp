@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LocalFieldCopiesTest {
    @Test
    fun tracksIncomingStackAddressesWithoutRecoveringSpillsOrClobberedRegisters() {
        fun read(bytes: String, stack: Boolean = true): SysVArgumentFlow.Read? {
            val flow = X64ControlFlow(X64Instructions(machineCode(bytes)).all())
            return SysVArgumentFlow(flow, includeStack = stack).source(flow.instructions.first {
                it.operation == X64Instructions.Operation.SCALAR_MOV
            }.offset)
        }

        val expected = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(4, 32), 8)
        val body = "55 48 89 e5 41 57 48 83 ec 20 4c 8d 7d 10 e8 ed 00 00 00 f2 41 0f 10 47 18 c3"
        assertEquals(expected, read(body))
        assertEquals(null, read(body, stack = false))
        assertEquals(null, read(body.replace("4c 8d 7d 10", "44 8d 7d 10")))
        assertEquals(null, read("55 48 89 e5 48 8d 75 10 e8 f3 00 00 00 f2 0f 10 46 18 c3"))
        assertEquals(null, read("55 48 89 e5 48 83 ec 20 48 8d 45 10 48 89 45 f8 48 8b 45 f8 f2 0f 10 40 18 c3"))
        assertEquals(null, read("55 48 89 e5 4c 8d 7d 10 85 ff 74 04 49 83 c7 08 f2 41 0f 10 47 18 c3"))
        assertEquals(expected, read("53 48 83 ec 20 48 83 c4 20 5b f2 0f 10 44 24 20 c3"))
    }

    @Test
    fun extractsSelectedBytesFromPackedLoadsAndRejectsTruncatedOrOverwrittenCopies() {
        val prefix = "55 48 89 e5 48 83 ec 20 0f b7 47 08"
        val suffix = "48 8d 75 e0 e8 e7 01 00 00 48 83 c4 20 5d c3"
        fun analyze(body: String, byte: Int, width: Int = 1): List<LocalFieldCopies.Field> {
            val flow = X64ControlFlow(X64Instructions(machineCode("$prefix $body $suffix")).all())
            val sink = flow.instructions.last { it.operation == X64Instructions.Operation.CALL }.offset
            return LocalFieldCopies(flow).fromRead(8, sink, byteOffset = byte, byteWidth = width)
        }
        assertEquals(listOf(LocalFieldCopies.Field(3, 1)), analyze("66 89 45 e3", 0))
        assertEquals(listOf(LocalFieldCopies.Field(4, 1)), analyze("66 89 45 e3", 1))
        assertEquals(listOf(LocalFieldCopies.Field(3, 2)), analyze("66 89 45 e3", 0, 2))
        assertEquals(emptyList(), analyze("88 45 e3", 1))
        assertEquals(emptyList(), analyze("66 89 45 e3 c6 45 e4 00", 1))
        assertEquals(listOf(LocalFieldCopies.Field(3, 1)), analyze("66 89 45 e3 c6 45 e4 00", 0))
        assertFails { analyze("66 89 45 e3", 2) }
        assertFails { analyze("66 89 45 e3", -1) }
        assertFails { analyze("66 89 45 e3", 1, 2) }
    }

    @Test
    fun copiesARegisterAtTheBoundaryIncludingItsFirstInstruction() {
        val prefix = "55 48 89 e5 48 83 ec 20"
        val suffix = "48 8d 75 e0 e8 e7 01 00 00 48 83 c4 20 5d c3"
        fun analyze(body: String): List<LocalFieldCopies.Field> {
            val flow = X64ControlFlow(X64Instructions(machineCode("$prefix $body $suffix")).all())
            val sink = flow.instructions.last { it.operation == X64Instructions.Operation.CALL }.offset
            return LocalFieldCopies(flow).fromRegister(8, sink, 13, 1)
        }
        assertEquals(listOf(LocalFieldCopies.Field(3, 1)), analyze("44 88 6d e3"))
        assertEquals(emptyList(), analyze("45 31 ed 44 88 6d e3"))
        assertEquals(listOf(LocalFieldCopies.Field(3, 1)), analyze("e8 ee 00 00 00 44 88 6d e3"))
        assertEquals(emptyList(), analyze("44 88 6d e3 e8 ee 00 00 00"))
        assertFails { analyze("44 88 6d e3 eb fa") }
    }

    @Test
    fun originalArgumentReadsIncludeCallClobbersAndLoopJoins() {
        fun read(bytes: String): SysVArgumentFlow.Read? {
            val flow = X64ControlFlow(X64Instructions(machineCode(bytes)).all())
            return SysVArgumentFlow(flow).source(flow.instructions.first {
                it.operation == X64Instructions.Operation.SCALAR_MOV
            }.offset)
        }

        val expected = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, 8), 8)
        assertEquals(expected, read("53 48 89 f3 e8 f7 00 00 00 f2 0f 10 43 08 5b c3"))
        assertEquals(null, read("53 e8 fa 00 00 00 f2 0f 10 46 08 5b c3"))
        assertEquals(expected, read("48 89 f3 f2 0f 10 43 08 85 ff 74 05 48 89 f3 eb f2 c3"))
        assertEquals(null, read("48 89 f3 f2 0f 10 43 08 85 ff 74 05 48 89 fb eb f2 c3"))
    }

    @Test
    fun tracesAProvenArgumentLoadWithoutRecoveringSavedPointers() {
        val prefix = "55 48 89 e5 48 83 ec 20 48 89 f3 f2 0f 10 43 08"
        val suffix = "48 8d 75 e0 e8 e7 01 00 00 48 83 c4 20 5d c3"
        fun analyze(bytes: String): Pair<SysVArgumentFlow.Read?, List<LocalFieldCopies.Field>> {
            val flow = X64ControlFlow(X64Instructions(machineCode(bytes)).all())
            val source = flow.instructions.first { it.operation == X64Instructions.Operation.SCALAR_MOV }.offset
            val sink = flow.instructions.last { it.operation == X64Instructions.Operation.CALL }.offset
            return SysVArgumentFlow(flow).source(source) to LocalFieldCopies(flow).fromRead(source, sink)
        }

        val copy = "f2 0f 11 45 e8"
        assertEquals(
            SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, 8), 8) to listOf(LocalFieldCopies.Field(8, 8)),
            analyze("$prefix $copy $suffix")
        )
        assertEquals(null, analyze("${prefix.replace("48 89 f3", "89 f3")} $copy $suffix").first)
        assertEquals(null, analyze("${prefix.replace("48 89 f3", "48 89 75 f0 48 8b 5d f0")} $copy $suffix").first)
        assertEquals(emptyList(), analyze("$prefix $copy e8 ee 00 00 00 $suffix").second)
    }

    @Test
    fun tracesLowReturnBytesThroughPreservedRegistersInCompiledEvents() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/aggregate_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            val function = image.symbol("fixture_construct_modifiers")
            val flow = X64ControlFlow.resolve(image, function)
            fun call(name: String): Long {
                val address = image.symbol(name).address
                return flow.instructions.single {
                    it.operation == X64Instructions.Operation.CALL &&
                            (it.destination as? X64Instructions.Immediate)?.value?.plus(function.address) == address
                }.offset
            }

            val sink = call("fixture_dispatch_modifiers")
            for (name in listOf("shift", "control")) {
                val offset =
                    image.symbol("fixture_event_$name").let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
                assertEquals(
                    listOf(LocalFieldCopies.Field(offset, 1)),
                    LocalFieldCopies(flow).fromReturn(call("fixture_$name"), sink)
                )
            }
        }
    }

    @Test
    fun invalidatesAliasesCallsPartialOverwritesAndMissingBranchStores() {
        val prefix = "55 48 89 e5 48 83 ec 20 e8 f3 00 00 00"
        val suffix = "48 8d 75 e0 e8 e7 01 00 00 48 83 c4 20 5d c3"
        fun analyze(body: String, width: Int = 1, register: Int = 0): List<LocalFieldCopies.Field> {
            val flow = X64ControlFlow(X64Instructions(machineCode("$prefix $body $suffix")).all())
            val calls = flow.instructions.filter { it.operation == X64Instructions.Operation.CALL }
            return LocalFieldCopies(flow).fromReturn(calls.first().offset, calls.last().offset, register, width)
        }
        assertEquals(listOf(LocalFieldCopies.Field(3, 1)), analyze("88 45 e3"))
        assertEquals(listOf(LocalFieldCopies.Field(3, 1)), analyze("89 c3 e8 ee 00 00 00 88 5d e3"))
        assertEquals(listOf(LocalFieldCopies.Field(8, 8)), analyze("f2 0f 11 45 e8", 8, 16))
        assertEquals(emptyList(), analyze("88 45 e3 48 89 07"))
        assertEquals(emptyList(), analyze("88 45 e3 e8 ee 00 00 00"))
        assertEquals(emptyList(), analyze("e8 ee 00 00 00 88 45 e3"))
        assertEquals(emptyList(), analyze("f2 0f 11 45 e8 c6 45 ea 00", 8, 16))
        assertEquals(emptyList(), analyze("85 ff 74 03 88 45 e3"))
        assertEquals(emptyList(), analyze("88 45 e3 85 ff 74 04 c6 45 e3 00"))
        assertFails { analyze("88 45 e3 eb fe") }
    }
}
