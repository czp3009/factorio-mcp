@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class LocalAggregateTest {
    @Test
    fun matchesCompilerGeneratedEventFieldsAcrossDifferentLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/aggregate_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun scalar(name: String): Long {
                val symbol = image.symbol(name)
                return image.virtualBytes(symbol.address, symbol.size).unsigned(0, 8)
            }

            val extent = scalar("fixture_event_extent").toInt()
            val kind = scalar("fixture_event_kind")
            val source = scalar("fixture_event_source")
            for ((name, value) in listOf("down" to 19, "up" to 31)) {
                val function = image.symbol("fixture_construct_$name")
                val callee = image.symbol("fixture_dispatch_$name")
                val body = X64Instructions(image.functionBytes(function, 32768)).all(8192)
                val call = body.single {
                    it.operation == Operation.CALL &&
                            (it.destination as? Immediate)?.value?.plus(function.address) == callee.address
                }
                val proof = LocalAggregate.resolve(image, function).at(call.offset, extent)
                val inline =
                    DwarfInlines(image).find(function, "fixture_construct_$name", setOf("createFixtureEvent")).single()
                val constructed = LocalAggregate.resolve(image, function).constructedAt(
                    call.offset, extent,
                    inline.ranges.map { DwarfRanges.Range(it.start - function.address, it.end - function.address) })
                assertEquals(proof.receiverFields, constructed.receiverFields)
                assertTrue(constructed.constants.containsAll(proof.constants))
                assertEquals(listOf(source), proof.receiverFields)
                assertTrue(proof.constants.any {
                    it.offset <= kind && it.offset + it.width >= kind + 4 &&
                            ((it.value ushr ((kind - it.offset) * 8).toInt()) and 0xffffffffL) == value.toLong()
                })
            }
        }
    }

    private val setup = "55 48 89 e5 48 83 ec 40"
    private val constant = "c7 45 d0 07 00 00 00"
    private val receiver = "48 89 7d e8"
    private val call = "48 8d 75 d0 e8 00 01 00 00"
    private val cleanup = "48 83 c4 40 5d c3"

    private fun proof(code: String): LocalAggregate.Proof {
        val instructions = X64Instructions(machineCode(code)).all()
        val flow = X64ControlFlow(instructions)
        return LocalAggregate(flow).at(instructions.last { it.operation == Operation.CALL }.offset, 32)
    }

    @Test
    fun requiresCompleteInlineConstructionWithoutInheritedFieldsOrCallbacks() {
        fun construct(code: String, begin: Int = 8, finish: Int? = null): LocalAggregate.Proof {
            val instructions = X64Instructions(machineCode(code)).all()
            val site = instructions.last { it.operation == Operation.CALL }.offset
            return LocalAggregate(X64ControlFlow(instructions)).constructedAt(
                site, 32,
                listOf(DwarfRanges.Range(begin.toLong(), finish?.toLong() ?: site))
            )
        }
        assertEquals(
            proof("$setup $constant $receiver $call $cleanup"),
            construct("$setup $constant $receiver $call $cleanup")
        )
        assertFails { construct("$setup $constant $receiver $call $cleanup", begin = 15) }
        assertFails { construct("$setup $constant $receiver $call $cleanup", finish = 15) }
        assertFails { construct("$setup $constant $receiver $call $cleanup", begin = 9) }
        assertEquals(
            listOf(
                LocalAggregate.Constant(0, 1, 7), LocalAggregate.Constant(1, 1, 9),
                LocalAggregate.Constant(2, 2, 0)
            ),
            construct("$setup $constant c6 45 d1 09 $receiver $call $cleanup").constants
        )
        assertFails { construct("$setup $constant e8 00 01 00 00 $receiver $call $cleanup") }
        assertFails { construct("$setup $constant 8b 45 d0 $receiver $call $cleanup") }
        assertFails { construct("$setup $constant ff 45 d0 $receiver $call $cleanup") }
        assertFails { construct("$setup $constant c6 02 00 $receiver $call $cleanup") }
        assertFails { construct("$setup $constant 85 c0 74 04 $receiver $call $cleanup") }
    }

    @Test
    fun derivesFreshConstantsAndTheExactReceiverArgument() {
        val result = proof("$setup $constant $receiver $call $cleanup")
        assertEquals(listOf(LocalAggregate.Constant(0, 4, 7)), result.constants)
        assertEquals(listOf(24L), result.receiverFields)
        assertEquals(listOf(LocalAggregate.Field(0, 4), LocalAggregate.Field(24, 8)), result.written)
        assertTrue(proof("$setup $constant $receiver 48 89 d7 $call $cleanup").receiverFields.isEmpty())
    }

    @Test
    fun discardsOverwrittenAndPotentiallyAliasedWrites() {
        val changed = proof("$setup $constant c6 45 d1 09 $receiver $call $cleanup")
        assertEquals(listOf(LocalAggregate.Constant(1, 1, 9)), changed.constants)
        assertEquals(listOf(LocalAggregate.Field(1, 1), LocalAggregate.Field(24, 8)), changed.written)
        val escaped = proof("$setup $constant c6 02 00 $receiver $call $cleanup")
        assertTrue(escaped.constants.isEmpty())
        assertEquals(listOf(24L), escaped.receiverFields)
        assertEquals(listOf(LocalAggregate.Field(24, 8)), escaped.written)
        val callback = proof("$setup $constant e8 00 01 00 00 $receiver $call $cleanup")
        assertTrue(callback.constants.isEmpty())
    }

    @Test
    fun neverUsesWritesBypassedByAnotherEntryEdge() {
        val result = proof("$setup 85 c0 74 07 $constant $receiver $call $cleanup")
        assertTrue(result.constants.isEmpty())
        assertEquals(listOf(24L), result.receiverFields)
    }

    @Test
    fun recordsTheExactReadOnlyLiteralBytesUsedByPackedStores() {
        val code = "$setup 0f 10 05 00 01 00 00 0f 11 45 d0 $receiver $call $cleanup"
        val instructions = X64Instructions(machineCode(code)).all()
        val flow = X64ControlFlow(instructions)
        val site = instructions.single { it.operation == Operation.CALL }.offset
        val content = ByteArray(16) { it.toByte() }
        val result = LocalAggregate(flow, readLiteral = { address, width ->
            assertEquals(271L, address)
            assertEquals(16, width)
            BinaryView(content)
        }).at(site, 32)
        assertEquals(
            listOf(
                LocalAggregate.Constant(0, 8, 0x0706050403020100L),
                LocalAggregate.Constant(8, 8, 0x0f0e0d0c0b0a0908L)
            ), result.constants
        )
        assertEquals(listOf(LocalAggregate.LiteralRange(271, (0..15).toList())), result.literals)
        assertTrue(LocalAggregate(flow).at(site, 32).constants.isEmpty())
        assertFails { LocalAggregate(flow, readLiteral = { _, _ -> BinaryView(ByteArray(8)) }).at(site, 32) }
    }

    @Test
    fun rejectsNonlocalArgumentsUnknownStackDepthAndSavedPointerReloads() {
        assertFails { proof("$setup $constant $receiver 48 89 d6 e8 00 01 00 00 $cleanup") }
        assertFails { proof("$setup $constant $receiver 48 89 d4 $call $cleanup") }
        assertFails {
            proof("$setup $constant 48 8d 45 d0 48 89 45 c8 48 8b 75 c8 e8 00 01 00 00 $cleanup")
        }
        assertFails { proof("$setup $constant $receiver 48 8d 75 f8 e8 00 01 00 00 $cleanup") }
        assertFails { proof("$setup $constant $receiver 50 $call 58 $cleanup") }
    }
}
