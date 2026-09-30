@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SysVRectangleAbiTest {
    private fun eachFixture(test: (ElfImage, ElfImage.Symbol, Long, Long, Set<Int>) -> Unit) {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/accessor_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun value(name: String): Long {
                val symbol = image.symbol(name)
                return image.virtualBytes(symbol.address, symbol.size).unsigned(0, 8)
            }

            val slots = setOf("x", "y").map { ((value("fixture_geometry_$it") - 1) / 8).toInt() }.toSet()
            test(
                image, image.symbol("_ZNK15FixtureGeometry9rectangleEv"), value("fixture_geometry_size"),
                value("fixture_geometry_parent"), slots
            )
        }
    }

    @Test
    fun verifiesAggregateReturnAndParentWalkAcrossCompilerLayouts() =
        eachFixture { image, function, size, parent, slots ->
            val table = ItaniumVtable.resolve(image, "_ZTV15FixtureGeometry")
            val proof = SysVRectangleAbi.resolve(image, function, size, table)
            assertEquals(parent, proof.parent)
            assertEquals(slots, proof.virtualSlots)
            assertFails { table.function(image, 4095) }
        }

    @Test
    fun rejectsIncompleteObjectBoundsAndUnapprovedDispatch() = eachFixture { image, function, _, _, _ ->
        val body = image.functionBytes(function, 4096)
        assertFails { SysVRectangleAbi.analyze(body, 8) {} }
        assertFails { SysVRectangleAbi.analyze(body, 4096) { error("Unapproved virtual method") } }
    }

    @Test
    fun rejectsMissingReturnWordAndReversedLoopCondition() = eachFixture { image, function, size, _, _ ->
        val body = image.functionBytes(function, 4096)
        val instructions = X64Instructions(body).all()
        val secondWord = instructions.single {
            it.operation == X64Instructions.Operation.MOV && it.destination == X64Instructions.Register(2, 8)
        }
        val missing = body.bytes(0, body.size.toInt())
        repeat(secondWord.size) { missing[secondWord.offset.toInt() + it] = 0x90.toByte() }
        assertFails { SysVRectangleAbi.analyze(BinaryView(missing), size) {} }
        val loop = instructions.single {
            it.operation == X64Instructions.Operation.JCC &&
                    (it.destination as X64Instructions.Immediate).value < it.offset
        }
        val reversed = body.bytes(0, body.size.toInt())
        check(reversed[loop.offset.toInt()] == 0x75.toByte())
        reversed[loop.offset.toInt()] = 0x74
        assertFails { SysVRectangleAbi.analyze(BinaryView(reversed), size) {} }
        val guard = instructions.first { it.operation == X64Instructions.Operation.JCC }
        val redirected = body.bytes(0, body.size.toInt())
        check(guard.size == 2 && redirected[guard.offset.toInt()] == 0x74.toByte())
        val destination = (loop.destination as X64Instructions.Immediate).value
        redirected[guard.offset.toInt() + 1] = (destination - guard.offset - guard.size).toByte()
        assertFails { SysVRectangleAbi.analyze(BinaryView(redirected), size) {} }
    }

    @Test
    fun rejectsAdditionalInputAndWritesOutsideTheStack() = eachFixture { image, function, size, _, _ ->
        val body = image.functionBytes(function, 4096)
        val instructions = X64Instructions(body).all()
        // Replace the entry copy of the receiver's parent with an uninitialized second argument.
        val parentLoad = instructions.first {
            it.operation == X64Instructions.Operation.MOV && it.source is X64Instructions.Memory &&
                    it.source.base == 7
        }
        val extra = body.bytes(0, body.size.toInt())
        repeat(parentLoad.size) { extra[parentLoad.offset.toInt() + it] = 0x90.toByte() }
        byteArrayOf(0x48, 0x89.toByte(), 0xf0.toByte()).copyInto(extra, parentLoad.offset.toInt()) // mov rax,rsi
        assertFails { SysVRectangleAbi.analyze(BinaryView(extra), size) {} }

        val secondWord = instructions.single {
            it.operation == X64Instructions.Operation.MOV && it.destination == X64Instructions.Register(2, 8)
        }
        val store = body.bytes(0, body.size.toInt())
        val opcode = secondWord.offset.toInt() + 1
        check(store[opcode] == 0x8b.toByte())
        store[opcode] = 0x89.toByte() // Reverse the final object load into a store.
        assertFails { SysVRectangleAbi.analyze(BinaryView(store), size) {} }
    }
}
