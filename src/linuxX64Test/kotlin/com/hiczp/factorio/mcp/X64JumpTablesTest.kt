@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import com.hiczp.factorio.mcp.X64Instructions.Register
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.*

class X64JumpTablesTest {
    @Test
    fun resolvesACompilerGeneratedSwitch() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/switch_fixture").use { file ->
            val image = ElfImage(file.view)
            val table = X64JumpTables.resolve(image, image.symbol("fixture_switch")).single()
            assertEquals(8, table.targets.size)
            assertEquals(8, table.targets.distinct().size)
        }
    }

    // Synthetic code/table addresses are fixture data, not game addresses.
    private val address = 0x1000L
    private val tableAddress = 0x1100L
    private val code = machineCode("89 f0 83 f8 02 77 12 48 8d 0d f2 00 00 00 48 63 04 81 48 01 c8 ff e0 90 c3 c3")

    private fun resolve(bytes: BinaryView = code, targets: List<Long> = listOf(23, 24, 25)) =
        X64JumpTables.resolve(X64Instructions(bytes).all(), address) { location, size ->
            assertEquals(tableAddress, location)
            assertEquals(12L, size)
            BinaryView(targets.flatMap { target ->
                val displacement = address + target - tableAddress
                List(4) { index -> (displacement ushr (index * 8)).toByte() }
            }.toByteArray())
        }

    @Test
    fun derivesSignedRelativeTargetsAndZeroExtendedIndex() {
        val table = resolve().single()
        assertEquals(listOf(23L, 24L, 25L), table.targets)
        assertEquals(21L, table.jump)
        assertEquals(5L, table.guard)
        assertEquals(4, table.index.width)
        for (opcode in listOf(0xc0, 0xc8)) {
            val unary = code.bytes(0, code.size.toInt()).also {
                it[0] = 0xff.toByte()
                it[1] = opcode.toByte()
            }
            assertEquals(table, resolve(BinaryView(unary)).single())
            unary[0] = 0xfe.toByte()
            assertFails { resolve(BinaryView(unary)) }
        }
    }

    @Test
    fun acceptsBoundedTablesLargerThanAByteAndRejectsOversizedTables() {
        val text = "89 f0 3d 0d 01 00 00 77 12 48 8d 0d f0 00 00 00 48 63 04 81 48 01 c8 ff e0 90 c3 c3"
        fun table(code: String) =
            X64JumpTables.resolve(X64Instructions(machineCode(code)).all(), address) { location, size ->
                assertEquals(tableAddress, location)
                assertEquals(270 * 4L, size)
                BinaryView(List(270) { 25L }.flatMap { target ->
                    val displacement = address + target - tableAddress
                    List(4) { (displacement ushr (it * 8)).toByte() }
                }.toByteArray())
            }
        assertEquals(270, table(text).single().targets.size)
        val failure = assertFailsWith<IllegalArgumentException> {
            table(text.replace("3d 0d 01 00 00", "3d 00 10 00 00"))
        }
        assertContains(failure.message.orEmpty(), "4096-entry")
    }

    @Test
    fun acceptsAZeroExtendedIndexCopyAfterTheGuardAndRejectsBypasses() {
        val copy = machineCode("83 ff 02 77 14 89 f8 48 8d 0d f2 00 00 00 48 63 04 81 48 01 c8 ff e0 90 c3 c3")
        val table = resolve(copy).single()
        assertEquals(Register(7, 4), table.index)
        assertEquals(3L, table.guard)
        assertEquals(listOf(23L, 24L, 25L), table.targets)
        fun change(offset: Int, value: Int) =
            BinaryView(copy.bytes(0, copy.size.toInt()).also { it[offset] = value.toByte() })
        assertFails { resolve(change(5, 0x88)) } // A byte copy cannot bound the address index.
        assertFails { resolve(change(6, 0xf0)) } // ESI was not compared with the bound.
        assertFails { resolve(change(6, 0xf9)) } // The copy does not initialize the actual indexed register.
        assertFails { resolve(targets = listOf(23, 24, 5), bytes = copy) }
        assertFails { resolve(BinaryView(copy.bytes(0, copy.size.toInt()) + machineCode("eb e9").bytes(0, 2))) }
    }

    @Test
    fun acceptsOnlyZeroExtendedCopiesOfAGuardedByteIndex() {
        val text = "3c 02 77 15 0f b6 c0 48 8d 0d f2 00 00 00 48 63 04 81 48 01 c8 ff e0 90 c3 c3"
        val byteIndex = machineCode(text)
        val table = resolve(byteIndex).single()
        assertEquals(Register(0, 1), table.index)
        assertEquals(2L, table.guard)
        assertEquals(listOf(23L, 24L, 25L), table.targets)
        for (changed in listOf(
            text.replace("0f b6 c0", "0f be c0"),
            text.replace("0f b6 c0", "0f b6 c1"),
            text.replace("0f b6 c0", "0f b6 c8"),
            text.replace("0f b6 c0", "88 c0 90"),
            text.replace("77 15", "76 15"),
        )) assertFails { resolve(machineCode(changed)) }
        assertFails { resolve(bytes = byteIndex, targets = listOf(23, 24, 4)) }
    }

    @Test
    fun rejectsUnboundedOrAlteredTableCalculation() {
        val instructions = X64Instructions(code).all()
        fun change(offset: Int, value: Int) =
            BinaryView(code.bytes(0, code.size.toInt()).also { it[offset] = value.toByte() })
        assertFails { resolve(change(5, 0x76)) } // JBE admits out-of-bounds indices.
        assertFails { resolve(change(6, 0)) } // JA falls through to the table even when out of bounds.
        assertFails { resolve(change(17, 0x41)) } // Wrong table element scale.
        assertFails { resolve(change(20, 0xc0)) } // ADD loses the table base.
        val byteWrite = code.bytes(0, code.size.toInt()).also {
            it[0] = 0x88.toByte()
            it[1] = 0xc0.toByte()
        }
        assertFails { resolve(BinaryView(byteWrite)) } // MOV AL, AL leaves the high index bits unbounded.
        assertFails { resolve(targets = listOf(23, 24, 100)) }
        assertFails { resolve(targets = listOf(23, 24, 15)) } // Mid-instruction target.
        assertFails { resolve(targets = listOf(23, 24, 14)) } // Bypasses the bound and table address.
        assertEquals(Operation.JMP, instructions.first { it.offset == 21L }.operation)
    }

    @Test
    fun rejectsDirectIngressAndTruncatedTables() {
        val bytes = code.bytes(0, code.size.toInt()) + byteArrayOf(0xeb.toByte(), 0xf2.toByte())
        assertFails { resolve(BinaryView(bytes)) } // An extra direct edge enters the table load.
        for (displacement in listOf(0xef, 0xf0)) {
            val call = code.bytes(0, code.size.toInt()) +
                    byteArrayOf(0xe8.toByte(), displacement.toByte(), -1, -1, -1, 0xc3.toByte())
            assertFails { resolve(BinaryView(call)) } // Local calls into an instruction or its interior are rejected.
        }
        assertFails {
            X64JumpTables.resolve(X64Instructions(code).all(), address) { _, _ -> BinaryView(ByteArray(8)) }
        }
    }
}
