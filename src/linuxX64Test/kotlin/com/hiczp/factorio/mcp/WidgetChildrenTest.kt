@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class WidgetChildrenTest {
    private fun eachFixture(test: (ElfImage, ElfImage.Symbol, Long, List<WidgetChildren.Range>) -> Unit) {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/accessor_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun compilerValue(name: String): Long {
                val symbol = image.symbol(name)
                return image.virtualBytes(symbol.address, symbol.size).unsigned(0, 8)
            }

            val expected = listOf("first", "second").map {
                WidgetChildren.Range(compilerValue("fixture_tree_${it}_begin"), compilerValue("fixture_tree_${it}_end"))
            }
            test(
                image,
                image.symbol("_ZN11FixtureTree4walkERKSt8functionIFvPS_EE"),
                compilerValue("fixture_tree_size"),
                expected
            )
        }
    }

    @Test
    fun derivesEveryRecursiveChildRangeFromChangedNativeLayouts() = eachFixture { image, function, size, expected ->
        assertEquals(expected, WidgetChildren.resolve(image, function.name, size))
    }

    @Test
    fun rejectsChangedStrideRecursiveTargetAndIncompleteBounds() = eachFixture { image, function, size, _ ->
        val body = image.functionBytes(function, function.size.toInt())
        val fail = image.symbol("_ZSt25__throw_bad_function_callv").address
        assertFails { WidgetChildren.analyze(body, function.address, fail, 8) }
        val instructions = X64Instructions(body).all()
        val increment = instructions.first {
            it.operation == X64Instructions.Operation.ADD && it.source == X64Instructions.Immediate(8) &&
                    it.destination != X64Instructions.Register(4, 8)
        }
        val stride = body.bytes(0, body.size.toInt())
        stride[(increment.offset + increment.size - 1).toInt()] = 16
        assertFails { WidgetChildren.analyze(BinaryView(stride), function.address, fail, size) }
        val recursive = instructions.first {
            it.operation == X64Instructions.Operation.CALL && it.destination == X64Instructions.Immediate(0)
        }
        val redirected = body.bytes(0, body.size.toInt())
        val index = (recursive.offset + recursive.size - 4).toInt()
        redirected[index] = (redirected[index] + 1).toByte()
        assertFails { WidgetChildren.analyze(BinaryView(redirected), function.address, fail, size) }
    }
}
