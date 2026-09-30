@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.*

class ElfDwarfTest {
    @Test
    fun mappedCopiesMatchScalarReadsAndRejectClosedSubviews() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val file = MappedBinary("$directory/debug_fixture_5")
        val view = file.view.slice(7, 41).slice(3, 23)
        try {
            val expected = ByteArray(23) { view.unsigned(it.toLong(), 1).toByte() }
            val bytes = view.bytes(0, 23)
            assertContentEquals(expected, bytes)
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            assertContentEquals(expected, view.bytes(0, 23))
            assertContentEquals(byteArrayOf(), file.view.bytes(file.view.size, 0))
        } finally {
            file.close()
        }
        assertFailsWith<IllegalStateException> { view.bytes(0, 23) }
        assertFailsWith<IllegalStateException> { view.bytes(23, 0) }
        assertFailsWith<IllegalStateException> { view.unsigned(0, 1) }
        file.close()
    }

    @Test
    fun recordsReadonlyLookupDataSeparatelyFromCodeAndWritableStorage() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/byte_table_fixture_1").use { file ->
            val image = ElfImage(file.view)
            val table = image.symbol("fixture_byte_values")
            val function = image.symbol("fixture_byte_lookup")
            val writable = image.sections.first { it.type == 1L && it.flags and 3L == 3L && it.size >= 8 }
            val (functions, data) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    image.virtualBytes(table.address, 4)
                    image.virtualBytes(table.address + 2, 8)
                    image.virtualBytes(writable.address, 8)
                    image.functionBytes(function, 4)
                }.second
            }
            assertEquals(listOf(function), functions)
            assertEquals(listOf(ElfImage.ReadonlyRange(table.address, 10)), data)
            assertFails { image.withReadonlyEvidence { image.withReadonlyEvidence { 0 } } }
            assertFails { image.withReadonlyEvidence { error("failed data analysis") } }
            assertTrue(image.withReadonlyEvidence { 0 }.second.isEmpty())
        }
    }

    @Test
    fun validatesPrimaryTypeIdentityWithoutTreatingSecondaryHeadersAsMethods() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (version in 4..5) MappedBinary("$directory/debug_fixture_$version").use { file ->
            val image = ElfImage(file.view)
            val type = ItaniumType.resolve(image, "N7fixture8CombinedE")
            assertEquals(image.symbol("_ZTVN7fixture8CombinedE").address + 16, type.addressPoint)
            assertEquals(image.symbol("_ZTIN7fixture8CombinedE").address, type.typeInfo)
            assertFails { ItaniumVtable.resolve(image, "_ZTVN7fixture8CombinedE") }
            val table = image.symbol("_ZTVN7fixture8CombinedE")
            val segment = image.segments.single {
                it.type == 1L && table.address >= it.address &&
                        table.address - it.address < it.fileSize
            }
            val changed = file.view.bytes(0, file.view.size.toInt())
            changed[(segment.offset + table.address - segment.address).toInt()] = 8
            assertFails { ItaniumType.resolve(ElfImage(BinaryView(changed)), "N7fixture8CombinedE") }
        }
    }

    @Test
    fun recordsConsultedFunctionRangesAndReleasesTheCollectorAfterFailure() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/debug_fixture_5").use { file ->
            val image = ElfImage(file.view)
            val function = image.symbol("fixture_function")
            val (value, evidence) = image.withFunctionEvidence {
                image.functionBytes(function, 1)
                image.functionBytes(function, 8)
                42
            }
            assertEquals(42, value)
            assertEquals(listOf(function), evidence)
            assertFails { image.withFunctionEvidence { image.withFunctionEvidence { 0 } } }
            assertFails { image.withFunctionEvidence { error("failed metadata analysis") } }
            assertTrue(image.withFunctionEvidence { 0 }.second.isEmpty())
        }
    }

    @Test
    fun resolvesCompilerGeneratedDwarf4And5() {
        val directory =
            checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")) { "Native fixture path is required" }.toKString()
        for (version in 4..5) MappedBinary("$directory/debug_fixture_$version").use { file ->
            val image = ElfImage(file.view)
            assertEquals(20, image.buildId().size)
            val function = image.symbol("fixture_function")
            assertTrue(image.functionBytes(function, 32).size in 1..32)
            val unwind = EhFrames(image).function(function)
            assertEquals(function.address + function.size, unwind.end)
            assertEquals(16, unwind.common.returnRegister)
            assertTrue(unwind.common.instructions.size > 0)
            assertFailsWith<IllegalArgumentException> { EhFrames(image).function(function.copy(size = function.size + 1)) }
            assertFailsWith<IllegalStateException> { image.symbol("absent_symbol") }
            assertFailsWith<IllegalArgumentException> { image.functionBytes(function.copy(size = 0), 32) }
            assertFailsWith<IllegalStateException> { image.virtualBytes(Long.MAX_VALUE - 1, 8) }
            val dwarf = DwarfInfo(image)
            val types = DwarfTypes(dwarf, setOf("fixture::Widget", "fixture::Mode", "fixture::Target"))
            fun compilerValue(name: String): Long {
                val symbol = image.symbol(name)
                assertEquals(8, symbol.size)
                return image.virtualBytes(symbol.address, 8).unsigned(0, 8)
            }
            assertEquals(compilerValue("fixture_widget_size"), types.size("fixture::Widget"))
            assertEquals(
                compilerValue("fixture_enabled_offset"),
                types.scalarMember("fixture::Widget", "enabled", 1, 2)
            )
            assertEquals(compilerValue("fixture_index_offset"), types.scalarMember("fixture::Widget", "index", 2, 7))
            assertEquals(
                compilerValue("fixture_value_offset"),
                types.pointerMember("fixture::Widget", "value", "fixture::Target")
            )
            assertEquals(19, types.enumValue("fixture::Mode", "Busy"))
            val target = types.virtualMethods("fixture::Widget", "target")
            assertTrue(target.isNotEmpty())
            assertTrue(target.all { it.result?.tag == 0x0f && it.parameters.size == 1 })
            val count = types.virtualMethods("fixture::Widget", "count")
            assertTrue(count.all { it.slot == target.first().slot + 1 && it.result?.tag == 0x24 })
            assertFailsWith<IllegalArgumentException> { types.pointerMember("fixture::Widget", "value", "Other") }
            assertFailsWith<IllegalArgumentException> { types.scalarMember("fixture::Widget", "index", 4, 7) }
            assertFailsWith<IllegalArgumentException> { types.scalarMember("fixture::Widget", "absent", 1, 2) }
        }
    }

    @Test
    fun refusesMalformedElfBeforeReadingTables() {
        assertFailsWith<IllegalArgumentException> { ElfImage(BinaryView(ByteArray(64))) }
        assertFailsWith<IllegalArgumentException> { ElfImage(BinaryView(ByteArray(16))) }
    }

    @Test
    fun cachedUnwindLookupStillRejectsRepeatedFunctionStarts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/debug_fixture_5").use { file ->
            val image = ElfImage(file.view)
            val function = image.symbol("fixture_function")
            val frames = EhFrames(image).frames().toList()
            assertTrue(
                frames.all { it.common.pointerEncoding == 0x1b },
                "Fixture must use PC-relative signed 32-bit ranges"
            )
            image.virtualBytes(function.address, 32, executable = true)
            val section = image.sections.single { it.name == ".eh_frame" }
            val cursor = image.section(".eh_frame").cursor()
            val positions = mutableListOf<Long>()
            while (cursor.remaining > 0) {
                val length = cursor.unsigned(4)
                if (length == 0L) break
                val record = cursor.position
                assertTrue(length in 4 until 0xfffffff0L)
                if (cursor.unsigned(4) != 0L) {
                    val position = cursor.position
                    val start = section.address + position + cursor.unsigned(4).toInt()
                    if (start != function.address && frames.any { it.start == start && it.end - it.start <= 32 }) {
                        positions += position
                    }
                }
                cursor.skip(record + length - cursor.position)
            }
            assertTrue(positions.size >= 2, "Fixture must have at least two other bounded function ranges")
            val bytes = file.view.bytes(0, file.view.size.toInt())
            for (position in positions.take(2)) {
                val relative = function.address - section.address - position
                assertTrue(relative in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                for (index in 0..3) bytes[(section.offset + position + index).toInt()] =
                    (relative shr (index * 8)).toByte()
                val modified = ElfImage(BinaryView(bytes.copyOf()))
                repeat(2) {
                    val failure = assertFailsWith<IllegalArgumentException> { EhFrames(modified).function(function) }
                    assertTrue(failure.message.orEmpty().contains("ambiguous unwind range"))
                }
            }
        }
    }

    @Test
    fun mappingCloseIsRepeatableAndInvalidatesViews() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val file = MappedBinary("$directory/debug_fixture_5")
        val view = file.view.slice(0, 64)
        file.close()
        file.close()
        assertFailsWith<IllegalStateException> { view.unsigned(0, 4) }
    }

    @Test
    fun lineTablesAndFunctionSymbolsDoNotEstablishTypeLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/debug_fixture_lines").use { file ->
            val image = ElfImage(file.view)
            assertTrue(image.symbol("fixture_function").size > 0)
            val types = DwarfTypes(DwarfInfo(image), setOf("fixture::Widget"))
            assertFailsWith<IllegalArgumentException> { types.size("fixture::Widget") }
        }
    }

    @Test
    fun rejectsInvalidDwarfLengthsAndAbbreviations() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/debug_fixture_4").use { file ->
            val image = ElfImage(file.view)
            val section = image.sections.single { it.name == ".debug_info" }
            val bytes = file.view.bytes(0, file.view.size.toInt())
            for (index in 0..3) bytes[section.offset.toInt() + index] = 0xff.toByte()
            assertFailsWith<IllegalArgumentException> { DwarfInfo(ElfImage(BinaryView(bytes))) }
            val original = file.view.bytes(0, file.view.size.toInt())
            val first = DwarfInfo(image).units.first()
            original[(section.offset + first.entries).toInt()] = 0x7f
            val dwarf = DwarfInfo(ElfImage(BinaryView(original)))
            assertFailsWith<IllegalStateException> { dwarf.entry(first.entries) }
        }
    }
}
