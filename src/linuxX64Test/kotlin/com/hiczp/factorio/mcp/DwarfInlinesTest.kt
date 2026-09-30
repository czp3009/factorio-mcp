@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class DwarfInlinesTest {
    @Test
    fun crossChecksIncomingStackFieldsWithTheSameAbstractGetters() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (version in 4..5) MappedBinary("$directory/inline_fixture_$version").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            val extent = constant("fixture_inline_extent")
            val debug = DwarfInlines(image)
            val anchors = listOf(
                Triple("getButton", "button", 2),
                Triple("control", "control", 1)
            ).flatMap { (getter, suffix, width) ->
                val owner = "fixture_read_$suffix"
                InlineArgumentFields.resolveEvidence(
                    image, image.symbol(owner), owner, 6, extent,
                    mapOf(getter to width), debug
                ).entries.map { it.toPair() }
            }.toMap()
            val owner = "fixture_read_stack"
            val function = image.symbol(owner)
            val widths = mapOf("getButton" to 2, "control" to 1, "getStamp" to 8)
            fun resolve(selected: Map<String, InlineArgumentFields.Evidence>, size: Long = extent) =
                InlineArgumentFields.resolveStack(image, function, owner, size, selected, widths, debug)

            val result = resolve(anchors)
            assertEquals(8, result.base)
            assertEquals(InlineArgumentFields.Field(constant("fixture_inline_stamp"), 8), result.fields["getStamp"])
            assertEquals(anchors.mapValues { it.value.field }, result.fields.filterKeys { it in anchors })
            val button = anchors.getValue("getButton")
            assertFails { resolve(anchors + ("getButton" to button.copy(origin = button.origin + 1))) }
            assertFails { resolve(anchors + ("getButton" to button.copy(field = button.field.copy(offset = button.field.offset + 1)))) }
            assertFails { resolve(anchors, 1) }
            assertFails { resolve(anchors.filterKeys { it == "getButton" }) }
            assertFails {
                InlineArgumentFields.resolveStack(
                    image, image.symbol("fixture_read_stack_folded"),
                    "fixture_read_stack_folded", extent, anchors, widths, debug
                )
            }
        }
    }

    @Test
    fun rejectsEscapingOrSplitRangesCyclicOriginsAndTruncatedContributions() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (version in 4..5) MappedBinary("$directory/inline_fixture_$version").use { file ->
            val original = file.view.bytes(0, file.view.size.toInt())
            val image = ElfImage(file.view)
            val function = image.symbol("fixture_read_button")
            val inline = DwarfInlines(image).find(function, "fixture_read_button", setOf("getButton")).single()
            val info = DwarfInfo(image)
            val entry = info.entry(inline.entry)
            fun change(section: String, offset: Long, width: Int, value: Long): ElfImage {
                val bytes = original.copyOf()
                val start = image.sections.single { it.name == section }.offset + offset
                for (byte in 0 until width) bytes[(start + byte).toInt()] = (value ushr (byte * 8)).toByte()
                return ElfImage(BinaryView(bytes))
            }
            if (version == 4) {
                assertEquals(0x01, entry.forms[0x11])
                val start = checkNotNull(entry.number(0x11))
                val position = (entry.offset until entry.next - 7).single {
                    image.section(".debug_info").unsigned(it, 8) == start
                }
                assertFails {
                    DwarfInlines(change(".debug_info", position, 8, function.address + function.size))
                        .find(function, "fixture_read_button", setOf("getButton"))
                }
                assertFails {
                    InlineArgumentFields.resolve(
                        change(".debug_info", position, 8, start + 1),
                        function, "fixture_read_button", 6, 64, mapOf("getButton" to 2)
                    )
                }
                assertEquals(0x13, entry.forms[0x31])
                val origin = inline.origin - info.unit(entry.offset).start
                val reference = (entry.offset until entry.next - 3).single {
                    image.section(".debug_info").unsigned(it, 4) == origin
                }
                assertFails {
                    DwarfInlines(
                        change(
                            ".debug_info", reference, 4,
                            entry.offset - info.unit(entry.offset).start
                        )
                    ).find(function, "fixture_read_button", setOf("getButton"))
                }
            } else {
                assertTrue(image.sections.any { it.name == ".debug_rnglists" })
                assertFails {
                    DwarfInlines(change(".debug_rnglists", 0, 4, 3))
                        .find(function, "fixture_read_button", setOf("getButton"))
                }
                assertFails {
                    DwarfInlines(change(".debug_addr", 6, 1, 4))
                        .find(function, "fixture_read_button", setOf("getButton"))
                }
            }
        }
    }

    @Test
    fun resolvesInlineGetterFieldsWithoutClassLayoutsInDwarf4And5() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (version in 4..5) MappedBinary("$directory/inline_fixture_$version").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            val extent = constant("fixture_inline_extent")
            val debug = DwarfInlines(image)
            val layouts = DwarfTypes(DwarfInfo(image), setOf("InlinePacket"))
            assertFails { layouts.size("InlinePacket") }
            for ((name, suffix, width) in listOf(Triple("getButton", "button", 2), Triple("control", "control", 1))) {
                val owner = "fixture_read_$suffix"
                val function = image.symbol(owner)
                val result =
                    InlineArgumentFields.resolve(image, function, owner, 6, extent, mapOf(name to width), debug)
                assertEquals(
                    mapOf(name to InlineArgumentFields.Field(constant("fixture_inline_$suffix"), width)),
                    result
                )
                assertFails {
                    InlineArgumentFields.resolve(
                        image,
                        function,
                        owner,
                        7,
                        extent,
                        mapOf(name to width),
                        debug
                    )
                }
                assertFails { InlineArgumentFields.resolve(image, function, owner, 6, 1, mapOf(name to width), debug) }
                assertFails { InlineArgumentFields.resolve(image, function, owner, 6, extent, mapOf(name to 8), debug) }
                assertFails { debug.find(function, "foreign_owner", setOf(name)) }
                assertFails { debug.find(function, owner, setOf("missing_getter")) }
            }
        }
    }

    @Test
    fun distinguishesAddressAndLengthFormsAndRejectsOverflow() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/inline_fixture_4").use { file ->
            val image = ElfImage(file.view)
            val info = DwarfInfo(image)
            val root = info.root(info.units.first())
            val ranges = DwarfRanges(image, info)
            fun entry(low: Long, high: Long, form: Int) = root.copy(
                attributes = mapOf(0x11 to DwarfInfo.Value.Number(low), 0x12 to DwarfInfo.Value.Number(high)),
                forms = mapOf(0x11 to 0x01, 0x12 to form)
            )
            assertEquals(listOf(DwarfRanges.Range(256, 272)), ranges.ranges(entry(256, 16, 0x06)))
            assertEquals(listOf(DwarfRanges.Range(256, 272)), ranges.ranges(entry(256, 272, 0x01)))
            assertEquals(listOf(DwarfRanges.Range(256, 528)), ranges.ranges(entry(256, 272, 0x06)))
            assertTrue(ranges.ranges(entry(256, 0, 0x06)).isEmpty())
            assertFails { ranges.ranges(entry(256, 16, 0x01)) }
            assertFails { ranges.ranges(entry(256, -1, 0x0d)) }
            assertFails { ranges.ranges(entry(Long.MAX_VALUE, 1, 0x06)) }
            assertFails { ranges.ranges(entry(256, 16, 0x17)) }
        }
    }

    private fun words(vararg values: Long): BinaryView = BinaryView(values.flatMap { value ->
        (0..7).map { (value ushr (it * 8)).toByte() }
    }.toByteArray())

    @Test
    fun boundsLegacyRangesAndRequiresABaseForOffsets() {
        assertEquals(
            listOf(DwarfRanges.Range(102, 110), DwarfRanges.Range(201, 204)),
            DwarfRanges.decode4(words(2, 6, 6, 10, -1, 200, 1, 4, 0, 0), 0, 100)
        )
        assertEquals(listOf(DwarfRanges.Range(201, 204)), DwarfRanges.decode4(words(-1, 200, 1, 4, 0, 0), 0, null))
        assertFails { DwarfRanges.decode4(words(1, 4, 0, 0), 0, null) }
        assertFails { DwarfRanges.decode4(words(1, 4), 0, 0) }
        assertFails { DwarfRanges.decode4(words(4, 1, 0, 0), 0, 0) }
        assertFails { DwarfRanges.decode4(words(1, 4, 0, 0), 0, Long.MAX_VALUE) }
        assertFails { DwarfRanges.decode4(words(-1, -2, 0, 0), 0, 0) }
        // Link-time optimization can place more than 65536 function ranges in one compilation unit.
        val large = ByteArray(65538 * 16)
        for (index in 0..65536) {
            for ((word, value) in listOf(index * 2L + 1, index * 2L + 2).withIndex()) {
                for (byte in 0..7) large[index * 16 + word * 8 + byte] = (value ushr (byte * 8)).toByte()
            }
        }
        assertEquals(65537, DwarfRanges.decode4(BinaryView(large), 0, 0).size)
    }

    @Test
    fun decodesIndexedAndExplicitDwarf5RangesWithBoundedArithmetic() {
        val view = machineCode(
            "01 00 04 02 05 02 01 02 03 03 02 05 64 00 00 00 00 00 00 00 " +
                    "04 01 03 06 c8 00 00 00 00 00 00 00 ca 00 00 00 00 00 00 00 " +
                    "07 2c 01 00 00 00 00 00 00 02 00"
        )
        val addresses = listOf(10L, 20L, 25L, 30L)
        assertEquals(
            listOf(
                DwarfRanges.Range(12, 15), DwarfRanges.Range(20, 25), DwarfRanges.Range(30, 32),
                DwarfRanges.Range(101, 103), DwarfRanges.Range(200, 202), DwarfRanges.Range(300, 302)
            ),
            DwarfRanges.decode5(view, 0, null) { addresses[it.toInt()] })
        assertFails { DwarfRanges.decode5(machineCode("04 01 02 00"), 0, null) { 0 } }
        assertFails { DwarfRanges.decode5(machineCode("03 00 01 00"), 0, null) { Long.MAX_VALUE } }
        assertFails { DwarfRanges.decode5(machineCode("01 00 00"), 0, null) { -1 } }
        assertFails { DwarfRanges.decode5(machineCode("09 00"), 0, 0) { 0 } }
        assertFails { DwarfRanges.decode5(machineCode("04 01 02"), 0, 0) { 0 } }
    }
}
