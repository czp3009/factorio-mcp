@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class InlineFrameCopyTest {
    @Test
    fun tracesEachPackedSourceByteAndRejectsLostOrRepeatedOutput() {
        fun resolve(copy: String, reverse: Boolean = false): Map<String, InlineArgumentFields.Field> {
            val code = machineCode("55 48 89 e5 48 83 ec 40 $copy 48 8d 75 c0 e8 00 01 00 00 48 83 c4 40 5d c3")
            val flow = X64ControlFlow(X64Instructions(code).all())
            val sink = flow.instructions.last { it.operation == X64Instructions.Operation.CALL }.offset
            val fields = mapOf("alt" to InlineArgumentFields.Field(0, 1), "control" to InlineArgumentFields.Field(1, 1))
            return if (reverse) FrameFieldCopies.sources(flow, sink, -40, 16, 16, fields)
            else FrameFieldCopies.resolve(flow, sink, -40, 16, 16, fields)
        }

        val copy = "0f b7 45 e0 66 89 45 c0"
        assertEquals(
            mapOf("alt" to InlineArgumentFields.Field(0, 1), "control" to InlineArgumentFields.Field(1, 1)),
            resolve(copy)
        )
        assertFails { resolve("$copy c6 45 c1 00") }
        assertFails { resolve("$copy 66 89 45 c4") }
        assertFails { resolve(copy.replace("66 89 45 c0", "88 45 c0")) }
        assertFails { resolve("$copy e8 00 01 00 00") }
        assertEquals(resolve(copy), resolve(copy, reverse = true))
        assertFails { resolve("$copy c6 45 c1 00", reverse = true) }
        assertFails { resolve("$copy e8 00 01 00 00", reverse = true) }
        assertFails { resolve("0f b7 02 66 89 45 c0", reverse = true) }
        assertEquals(
            mapOf("alt" to InlineArgumentFields.Field(0, 1), "control" to InlineArgumentFields.Field(3, 1)),
            resolve("$copy 0f b6 45 e3 88 45 c1", reverse = true)
        )
    }

    @Test
    fun tracesReorderedFieldsIntoReferenceAndStackArguments() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (version in 4..5) for (stack in listOf(
            false,
            true
        )) MappedBinary("$directory/inline_fixture_$version").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            val name = if (stack) "fixture_convert_stack" else "fixture_convert_frame"
            val target = if (stack) "fixture_read_converted_stack" else "fixture_read_converted"
            val argument = if (stack) 4 else 6
            val function = image.symbol(name)
            val inputExtent = constant("fixture_inline_extent").toInt()
            val outputExtent = constant("fixture_converted_extent").toInt()
            val inline = DwarfInlines(image).find(function, name, setOf("dequeueMouseInput")).single()
            val ranges = inline.ranges.map { DwarfRanges.Range(it.start - function.address, it.end - function.address) }
            val flow = X64ControlFlow.resolve(image, function)
            val source = InlineFrameCopy.resolve(flow, ranges, inputExtent)
            val call = flow.instructions.single {
                it.operation == X64Instructions.Operation.CALL &&
                        (it.destination as? X64Instructions.Immediate)?.value?.plus(function.address) ==
                        image.symbol(target).address
            }.offset
            val widths = mapOf("button" to 2, "control" to 1, "alt" to 1, "shift" to 1, "stamp" to 8)
            val fields = widths.mapValues { (name, width) ->
                InlineArgumentFields.Field(
                    constant("fixture_inline_$name"),
                    width
                )
            }
            val expected = widths.mapValues { (name, width) ->
                InlineArgumentFields.Field(
                    constant("fixture_converted_$name"),
                    width
                )
            }
            assertEquals(
                expected,
                FrameFieldCopies.resolve(flow, call, source.frame, inputExtent, outputExtent, fields, argument)
            )
            assertEquals(
                fields,
                FrameFieldCopies.sources(flow, call, source.frame, inputExtent, outputExtent, expected, argument)
            )
            assertFails { FrameFieldCopies.sources(flow, call, source.frame, inputExtent, 1, expected, argument) }
            assertFails { FrameFieldCopies.sources(flow, call, source.frame, 1, outputExtent, expected, argument) }
            assertFails { FrameFieldCopies.resolve(flow, call, source.frame, inputExtent, 1, fields, argument) }
            assertFails { FrameFieldCopies.resolve(flow, call, source.frame, 1, outputExtent, fields, argument) }
            val frame = SysVLocalArgument(flow)
            val output = if (stack) frame.outgoing(call, outputExtent) else frame.argument(call, 6, outputExtent)
            assertFails { FrameFieldCopies.resolve(flow, call, output, inputExtent, outputExtent, fields, argument) }
        }
    }

    @Test
    fun identifiesTheCompleteLocalCopyInCompiledInlineRanges() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (version in 4..5) MappedBinary("$directory/inline_fixture_$version").use { file ->
            val image = ElfImage(file.view)
            val function = image.symbol("fixture_copy_frame")
            val extent =
                image.symbol("fixture_inline_extent").let { image.virtualBytes(it.address, 8).unsigned(0, 8).toInt() }
            val inline = DwarfInlines(image).find(function, "fixture_copy_frame", setOf("dequeueMouseInput")).single()
            val ranges = inline.ranges.map { DwarfRanges.Range(it.start - function.address, it.end - function.address) }
            val flow = X64ControlFlow.resolve(image, function)
            val result = InlineFrameCopy.resolve(flow, ranges, extent)
            assertEquals(extent, result.extent)
            assertEquals(6, result.sourceRegister)
            assertTrue(result.frame < 0)
            assertFails { InlineFrameCopy.resolve(flow, ranges, extent + 1) }
        }
    }

    @Test
    fun rejectsPartialMixedOverwrittenAndEscapedCopies() {
        fun resolve(copy: String, extent: Int = 32): InlineFrameCopy.Proof {
            val prefix = "55 48 89 e5 48 83 ec 20"
            val body = machineCode("$prefix $copy 48 83 c4 20 5d c3")
            val flow = X64ControlFlow(X64Instructions(body).all())
            return InlineFrameCopy.resolve(flow, listOf(DwarfRanges.Range(8, body.size - 6)), extent)
        }

        val copy = "0f 10 06 0f 10 4e 10 0f 11 45 e0 0f 11 4d f0"
        assertEquals(InlineFrameCopy.Proof(-40, 32, 6), resolve(copy))
        assertFails { resolve(copy, 33) }
        assertFails { resolve(copy.replace("0f 10 4e 10", "0f 10 4f 10")) }
        assertFails { resolve(copy.replace("0f 11 4d f0", "0f 11 4d e0")) }
        assertFails { resolve("$copy c6 45 ff 00") }
        assertFails { resolve(copy.replace("0f 11 4d f0", "0f 11 0f")) }
        assertFails { resolve(copy.replace("0f 10 4e 10", "e8 00 01 00 00 0f 10 4e 10")) }
        assertFails { resolve(copy.replace("0f 11 45 e0", "0f 11 45 f0")) }
    }
}
