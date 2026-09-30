@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.*

class SysVTargeterReleaseTest {
    @Test
    fun provesUnlinkingAcrossCompilerGeneratedPaddingAndFieldOrder() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val layouts = mutableListOf<SysVTargeterRelease.Layout>()
        for (padding in listOf(1, 23)) MappedBinary("$directory/targeter_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun scalar(name: String): Long {
                val symbol = image.symbol(name)
                return image.virtualBytes(symbol.address, symbol.size).unsigned(0, 8)
            }

            val layout = SysVTargeterRelease.resolve(image, "_ZN8Targeter8attachToEP10Targetable")
            assertEquals(scalar("fixture_target"), layout.target)
            assertEquals(scalar("fixture_previous"), layout.previous)
            assertEquals(scalar("fixture_next"), layout.next)
            assertEquals(scalar("fixture_head"), layout.head)
            SysVTargeterRelease.verifyFreshAttachment(
                image.functionBytes(image.symbol("_ZN8Targeter8attachToEP10Targetable"), 512), layout
            )
            SysVTargeterRelease.verifyClearing(
                image.functionBytes(image.symbol("_ZN10Targetable5clearEv"), 512),
                layout
            )
            layouts += layout
        }
        assertNotEquals(layouts[0].target, layouts[1].target)
        assertNotEquals(layouts[0].previous < layouts[0].next, layouts[1].previous < layouts[1].next)
    }

    @Test
    fun rejectsLifetimeClearingThatLeavesARegisteredRecord() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/targeter_fixture_1").use { file ->
            val image = ElfImage(file.view)
            val layout = SysVTargeterRelease.resolve(image, "_ZN8Targeter8attachToEP10Targetable")
            val symbol = image.symbol("_ZN10Targetable5clearEv")
            val bytes = image.functionBytes(symbol, 512)
            val clear = X64Instructions(bytes).all().first {
                it.operation == X64Instructions.Operation.MOV && it.destination is X64Instructions.Memory &&
                        it.source == X64Instructions.Immediate(0)
            }
            val corrupted = bytes.bytes(0, bytes.size.toInt())
            repeat(clear.size) { corrupted[clear.offset.toInt() + it] = 0x90.toByte() }
            assertFails { SysVTargeterRelease.verifyClearing(BinaryView(corrupted), layout) }
            assertFails { SysVTargeterRelease.verifyClearing(machineCode("c3"), layout) }
        }
    }

    @Test
    fun rejectsFreshAttachmentWithoutRegisteringTheNewHead() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/targeter_fixture_1").use { file ->
            val image = ElfImage(file.view)
            val symbol = image.symbol("_ZN8Targeter8attachToEP10Targetable")
            val bytes = image.functionBytes(symbol, 512)
            val layout = SysVTargeterRelease.analyze(bytes)
            val body = X64Instructions(bytes).all()
            val headStore = body.single { instruction ->
                val target = instruction.destination as? X64Instructions.Memory
                instruction.operation == X64Instructions.Operation.MOV && target?.base == 6 &&
                        target.displacement == layout.head && instruction.source == X64Instructions.Register(7, 8)
            }
            val corrupted = bytes.bytes(0, bytes.size.toInt())
            repeat(headStore.size) { corrupted[headStore.offset.toInt() + it] = 0x90.toByte() }
            // Null release remains valid, but that proof must not authorize a non-null argument.
            assertEquals(layout, SysVTargeterRelease.analyze(BinaryView(corrupted)))
            assertFails { SysVTargeterRelease.verifyFreshAttachment(BinaryView(corrupted), layout) }
            assertFails { SysVTargeterRelease.verifyFreshAttachment(machineCode("c3"), layout) }
        }
    }

    @Test
    fun rejectsMissingCleanupForeignReceiversCallsAndPreservationFailures() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/targeter_fixture_1").use { file ->
            val image = ElfImage(file.view)
            val symbol = image.symbol("_ZN8Targeter8attachToEP10Targetable")
            val bytes = image.functionBytes(symbol, 512).bytes(0, symbol.size.toInt())
            fun insertPrefix(prefix: String) =
                BinaryView(machineCode(prefix).bytes(0, machineCode(prefix).size.toInt()) + bytes)
            assertFails { SysVTargeterRelease.analyze(insertPrefix("e8 00 00 00 00")) }
            assertFails { SysVTargeterRelease.analyze(insertPrefix("48 89 d7")) }
            assertFails { SysVTargeterRelease.analyze(insertPrefix("48 31 db")) }
            val decoded = X64Instructions(BinaryView(bytes)).all()
            val clears = decoded.filter {
                it.operation == X64Instructions.Operation.MOV && it.destination is X64Instructions.Memory &&
                        it.source == X64Instructions.Immediate(0)
            }
            assertTrue(clears.isNotEmpty())
            val corrupted = bytes.copyOf()
            val selected = clears.first()
            repeat(selected.size) { corrupted[selected.offset.toInt() + it] = 0x90.toByte() }
            assertFails { SysVTargeterRelease.analyze(BinaryView(corrupted)) }
        }
        assertFails { SysVTargeterRelease.analyze(machineCode("c3")) }
    }
}
