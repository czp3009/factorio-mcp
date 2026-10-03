@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Instruction
import com.hiczp.factorio.mcp.X64Instructions.Memory
import com.hiczp.factorio.mcp.X64Instructions.Operation
import com.hiczp.factorio.mcp.X64Instructions.Register
import kotlin.test.*
import kotlinx.cinterop.toKString
import platform.posix.getenv

class X64JumpTablesTest {
    @Test
    fun accountsForImplicitWritesWithoutRejectingUnrelatedNativeWork() {
        fun inserted(text: String, insertion: Long): List<X64JumpTables.Table> {
            val extra =
                X64Instructions(
                        machineCode(text),
                        allowUnsignedWideMultiply = true,
                        allowByteCompareExchange = true,
                        allowAtomicExchangeAdd = true,
                    )
                    .all()
            val width = extra.last().offset + extra.last().size
            val body = X64Instructions(code).all()
            fun shifted(value: Long) = if (value >= insertion) value + width else value
            val combined =
                body.filter { it.offset < insertion } +
                    extra.map { it.copy(offset = it.offset + insertion) } +
                    body
                        .filter { it.offset >= insertion }
                        .map { instruction ->
                            val target = instruction.destination
                            val source = instruction.source
                            instruction.copy(
                                offset = instruction.offset + width,
                                destination =
                                    if (
                                        target is Immediate &&
                                            instruction.operation in
                                                setOf(Operation.JMP, Operation.JCC)
                                    )
                                        Immediate(shifted(target.value))
                                    else target,
                                source =
                                    if (source is Memory && source.relative)
                                        source.copy(displacement = source.displacement - width)
                                    else source,
                            )
                        }
            return X64JumpTables.resolve(combined, address) { location, size ->
                assertEquals(tableAddress, location)
                assertEquals(12L, size)
                BinaryView(
                    listOf(23L, 24L, 25L)
                        .flatMap { target ->
                            val displacement = address + shifted(target) - tableAddress
                            List(4) { (displacement ushr (it * 8)).toByte() }
                        }
                        .toByteArray()
                )
            }
        }
        for (text in listOf("48 f7 e6", "f0 0f b0 16", "f0 48 0f c1 0e")) {
            assertEquals(1, inserted(text, 0).size)
        }
        // These instructions replace the bounded index or table base, even without a destination
        // register in the decoded operand list.
        assertFails { inserted("48 f7 e6", 2) }
        assertFails { inserted("f0 0f b0 16", 2) }
        assertFails { inserted("48 f7 e6", 5) }
        assertFails { inserted("f0 48 0f c1 0e", 14) }
    }

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
    private val code =
        machineCode("89 f0 83 f8 02 77 12 48 8d 0d f2 00 00 00 48 63 04 81 48 01 c8 ff e0 90 c3 c3")

    private fun resolve(bytes: BinaryView = code, targets: List<Long> = listOf(23, 24, 25)) =
        X64JumpTables.resolve(X64Instructions(bytes).all(), address) { location, size ->
            assertEquals(tableAddress, location)
            assertEquals(12L, size)
            BinaryView(
                targets
                    .flatMap { target ->
                        val displacement = address + target - tableAddress
                        List(4) { index -> (displacement ushr (index * 8)).toByte() }
                    }
                    .toByteArray()
            )
        }

    @Test
    fun derivesSignedRelativeTargetsAndZeroExtendedIndex() {
        val table = resolve().single()
        assertEquals(listOf(23L, 24L, 25L), table.targets)
        assertEquals(21L, table.jump)
        assertEquals(5L, table.guard)
        assertEquals(4, table.index.width)
        for (opcode in listOf(0xc0, 0xc8)) {
            val unary =
                code.bytes(0, code.size.toInt()).also {
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
        val text =
            "89 f0 3d 0d 01 00 00 77 12 48 8d 0d f0 00 00 00 48 63 04 81 48 01 c8 ff e0 90 c3 c3"
        fun table(code: String) =
            X64JumpTables.resolve(X64Instructions(machineCode(code)).all(), address) {
                location,
                size ->
                assertEquals(tableAddress, location)
                assertEquals(270 * 4L, size)
                BinaryView(
                    List(270) { 25L }
                        .flatMap { target ->
                            val displacement = address + target - tableAddress
                            List(4) { (displacement ushr (it * 8)).toByte() }
                        }
                        .toByteArray()
                )
            }
        assertEquals(270, table(text).single().targets.size)
        val failure =
            assertFailsWith<IllegalArgumentException> {
                table(text.replace("3d 0d 01 00 00", "3d 00 10 00 00"))
            }
        assertContains(failure.message.orEmpty(), "4096-entry")
    }

    @Test
    fun acceptsAZeroExtendedIndexCopyAfterTheGuardAndRejectsBypasses() {
        val copy =
            machineCode(
                "83 ff 02 77 14 89 f8 48 8d 0d f2 00 00 00 48 63 04 81 48 01 c8 ff e0 90 c3 c3"
            )
        val table = resolve(copy).single()
        assertEquals(Register(7, 4), table.index)
        assertEquals(3L, table.guard)
        assertEquals(listOf(23L, 24L, 25L), table.targets)
        fun change(offset: Int, value: Int) =
            BinaryView(copy.bytes(0, copy.size.toInt()).also { it[offset] = value.toByte() })
        assertFails { resolve(change(5, 0x88)) } // A byte copy cannot bound the address index.
        assertFails { resolve(change(6, 0xf0)) } // ESI was not compared with the bound.
        assertFails {
            resolve(change(6, 0xf9))
        } // The copy does not initialize the actual indexed register.
        assertFails { resolve(targets = listOf(23, 24, 5), bytes = copy) }
        assertFails {
            resolve(BinaryView(copy.bytes(0, copy.size.toInt()) + machineCode("eb e9").bytes(0, 2)))
        }
    }

    @Test
    fun acceptsOnlyZeroExtendedCopiesOfAGuardedByteIndex() {
        val text = "3c 02 77 15 0f b6 c0 48 8d 0d f2 00 00 00 48 63 04 81 48 01 c8 ff e0 90 c3 c3"
        val byteIndex = machineCode(text)
        val table = resolve(byteIndex).single()
        assertEquals(Register(0, 1), table.index)
        assertEquals(2L, table.guard)
        assertEquals(listOf(23L, 24L, 25L), table.targets)
        for (changed in
            listOf(
                text.replace("0f b6 c0", "0f be c0"),
                text.replace("0f b6 c0", "0f b6 c1"),
                text.replace("0f b6 c0", "0f b6 c8"),
                text.replace("0f b6 c0", "88 c0 90"),
                text.replace("77 15", "76 15"),
            )) assertFails { resolve(machineCode(changed)) }
        assertFails { resolve(bytes = byteIndex, targets = listOf(23, 24, 4)) }
    }

    @Test
    fun followsRegisterDefinitionsAcrossUnrelatedSetupAndRejectsClobbers() {
        fun analyze(
            wide: Boolean = false,
            mutate: (MutableList<Instruction>) -> Unit = {},
        ): X64JumpTables.Table {
            val width = if (wide) 8 else 4
            val instructions =
                mutableListOf(
                    Instruction(0, 1, Operation.MOV, Register(10, width), Register(6, width)),
                    Instruction(1, 1, Operation.CMP, Register(10, width), Immediate(2)),
                    Instruction(2, 1, Operation.MOV, Register(7, 8), Memory(5, null, 1, -24, 8)),
                    Instruction(3, 1, Operation.JCC, Immediate(16), condition = 7),
                    Instruction(4, 1, Operation.MOV, Register(7, 8), Memory(5, null, 1, -32, 8)),
                    Instruction(5, 1, Operation.MOV, Register(2, width), Register(10, width)),
                    Instruction(
                        6,
                        1,
                        Operation.LEA,
                        Register(11, 8),
                        Memory(null, null, 1, tableAddress - address - 7, 8, relative = true),
                    ),
                    Instruction(7, 1, Operation.MOV, Register(7, 8), Register(3, 8)),
                    Instruction(8, 1, Operation.MOVSX, Register(0, 8), Memory(11, 2, 4, 0, 4)),
                    Instruction(9, 1, Operation.MOV, Register(12, 8), Register(13, 8)),
                    Instruction(10, 1, Operation.ADD, Register(0, 8), Register(11, 8)),
                    Instruction(11, 1, Operation.MOV, Register(7, 8), Memory(5, null, 1, -40, 8)),
                    Instruction(12, 1, Operation.JMP, Register(0, 8)),
                    Instruction(13, 1, Operation.NOP),
                    Instruction(14, 1, Operation.RET),
                    Instruction(15, 1, Operation.RET),
                    Instruction(16, 1, Operation.RET),
                )
            mutate(instructions)
            return X64JumpTables.resolve(instructions, address) { location, size ->
                    assertEquals(tableAddress, location)
                    assertEquals(12L, size)
                    BinaryView(
                        listOf(14L, 15L, 16L)
                            .flatMap { target ->
                                val displacement = address + target - tableAddress
                                List(4) { (displacement ushr (it * 8)).toByte() }
                            }
                            .toByteArray()
                    )
                }
                .single()
        }
        assertEquals(listOf(14L, 15L, 16L), analyze().targets)
        assertEquals(Register(10, 8), analyze(wide = true).index)
        for ((site, register) in listOf(2 to 10, 4 to 10, 7 to 11, 9 to 11, 11 to 0)) {
            assertFails {
                analyze {
                    it[site] =
                        Instruction(
                            site.toLong(),
                            1,
                            Operation.ADD,
                            Register(register, 8),
                            Immediate(1),
                        )
                }
            }
        }
        assertFails {
            analyze { it[7] = Instruction(7, 1, Operation.JCC, Immediate(8), condition = 7) }
        }
    }

    @Test
    fun rejectsUnboundedOrAlteredTableCalculation() {
        val instructions = X64Instructions(code).all()
        fun change(offset: Int, value: Int) =
            BinaryView(code.bytes(0, code.size.toInt()).also { it[offset] = value.toByte() })
        assertFails { resolve(change(5, 0x76)) } // JBE admits out-of-bounds indices.
        assertFails {
            resolve(change(6, 0))
        } // JA falls through to the table even when out of bounds.
        assertFails { resolve(change(17, 0x41)) } // Wrong table element scale.
        assertFails { resolve(change(20, 0xc0)) } // ADD loses the table base.
        val byteWrite =
            code.bytes(0, code.size.toInt()).also {
                it[0] = 0x88.toByte()
                it[1] = 0xc0.toByte()
            }
        assertFails {
            resolve(BinaryView(byteWrite))
        } // MOV AL, AL leaves the high index bits unbounded.
        assertFails { resolve(targets = listOf(23, 24, 100)) }
        assertFails { resolve(targets = listOf(23, 24, 15)) } // Mid-instruction target.
        assertFails {
            resolve(targets = listOf(23, 24, 14))
        } // Bypasses the bound and table address.
        assertEquals(Operation.JMP, instructions.first { it.offset == 21L }.operation)
    }

    @Test
    fun rejectsDirectIngressAndTruncatedTables() {
        val bytes = code.bytes(0, code.size.toInt()) + byteArrayOf(0xeb.toByte(), 0xf2.toByte())
        assertFails { resolve(BinaryView(bytes)) } // An extra direct edge enters the table load.
        for (displacement in listOf(0xef, 0xf0)) {
            val call =
                code.bytes(0, code.size.toInt()) +
                    byteArrayOf(0xe8.toByte(), displacement.toByte(), -1, -1, -1, 0xc3.toByte())
            assertFails {
                resolve(BinaryView(call))
            } // Local calls into an instruction or its interior are rejected.
        }
        assertFails {
            X64JumpTables.resolve(X64Instructions(code).all(), address) { _, _ ->
                BinaryView(ByteArray(8))
            }
        }
    }
}
