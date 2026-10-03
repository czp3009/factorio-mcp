@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlinx.cinterop.toKString
import platform.posix.getenv

class DwarfSourceLinesTest {
    private fun integer(value: Long, width: Int) =
        List(width) { (value ushr (it * 8) and 255).toInt() }

    private fun text(value: String) = value.encodeToByteArray().map { it.toInt() and 255 } + 0

    private fun address(value: Long) = listOf(0, 9, 2) + integer(value, 8)

    private val end = listOf(0, 1, 1)

    private fun table(
        program: List<Int>,
        version: Int = 4,
        directory: Int = 1,
        firstFile: String = "same.hpp",
    ): BinaryView {
        val prefix = listOf(1, 1, 1, 251, 14, 13, 0, 1, 1, 1, 1, 0, 0, 0, 1, 0, 0, 1)
        val entries =
            if (version == 4) {
                text("include") +
                    0 +
                    text(firstFile) +
                    listOf(directory, 0, 0) +
                    text("other.hpp") +
                    listOf(1, 0, 0) +
                    0
            } else {
                // Version 5 directories/files start at zero. Keep file register one's actual entry.
                listOf(1, 1, 8, 2) +
                    text("/build") +
                    text("include") +
                    listOf(2, 1, 8, 2, 15, 2) +
                    text("other.hpp") +
                    1 +
                    text(firstFile) +
                    directory
            }
        val header = prefix + entries
        val body =
            integer(version.toLong(), 2) +
                (if (version == 5) listOf(8, 0) else emptyList()) +
                integer(header.size.toLong(), 4) +
                header +
                program
        return BinaryView((integer(body.size.toLong(), 4) + body).map(Int::toByte).toByteArray())
    }

    @Test
    fun readsExactCoordinatesFilesAndSequenceResetInBothFormats() {
        val first = address(0x1000) + listOf(3, 18, 5, 7, 1, 2, 4, 3, 127, 1) + end
        for (version in 4..5) {
            val program =
                first + address(0x2000) + listOf(1, 4, if (version == 4) 2 else 0, 2, 1, 1) + end
            val rows =
                DwarfSourceLines(table(program, version))
                    .rows(0, setOf(0x1000, 0x1004, 0x2000, 0x2001), "/build")
            assertEquals(DwarfSourceLines.Source("/build/include/same.hpp", 19, 7), rows[0x1000])
            assertEquals(DwarfSourceLines.Source("/build/include/same.hpp", 18, 7), rows[0x1004])
            assertEquals(DwarfSourceLines.Source("/build/include/same.hpp", 1, 0), rows[0x2000])
            assertEquals(DwarfSourceLines.Source("/build/include/other.hpp", 1, 0), rows[0x2001])
        }
        val program = address(0x1000) + listOf(3, 18, 5, 7, 1) + end
        assertEquals(
            "/build/same.hpp",
            DwarfSourceLines(table(program, directory = 0))
                .rows(0, setOf(0x1000), "/build")[0x1000]
                ?.path,
        )
        assertEquals(
            "/absolute/same.hpp",
            DwarfSourceLines(table(program, firstFile = "/absolute/same.hpp"))
                .rows(0, setOf(0x1000), "/build")[0x1000]
                ?.path,
        )
    }

    @Test
    fun rejectsMissingAmbiguousInvalidAndUnterminatedRows() {
        val row = address(0x1000) + listOf(1)
        assertFails { DwarfSourceLines(table(row + end)).rows(0, setOf(0x1001), "/build") }
        assertFails { DwarfSourceLines(table(row)).rows(0, setOf(0x1000), "/build") }
        assertFails {
            DwarfSourceLines(table(row + listOf(3, 1, 1) + end)).rows(0, setOf(0x1000), "/build")
        }
        for (extra in
            listOf(listOf(4, 0, 1), listOf(4, 127, 1), listOf(3, 127, 1), listOf(0, 1, 127))) {
            assertFails {
                DwarfSourceLines(table(row + extra + end)).rows(0, setOf(0x1000), "/build")
            }
        }
        assertFails {
            DwarfSourceLines(table(row + end, directory = 127)).rows(0, setOf(0x1000), "/build")
        }
        assertFails {
            DwarfSourceLines(table(row + end, version = 3)).rows(0, setOf(0x1000), "/build")
        }
    }

    @Test
    fun readsWideStringReferencesChecksumsAndDefinedFiles() {
        val prefix = listOf(1, 1, 1, 251, 14, 13, 0, 1, 1, 1, 1, 0, 0, 0, 1, 0, 0, 1)
        val checksum = List(16) { it }
        val header =
            prefix +
                listOf(1, 1, 31, 1) +
                integer(0, 8) +
                listOf(3, 1, 14, 2, 15, 5, 30, 2) +
                integer(0, 8) +
                0 +
                checksum +
                integer(0, 8) +
                0 +
                checksum
        val program = address(0x1000) + listOf(1) + end
        val body =
            integer(5, 2) + listOf(8, 0) + integer(header.size.toLong(), 8) + header + program
        val section =
            BinaryView(
                (integer(0xffffffffL, 4) + integer(body.size.toLong(), 8) + body)
                    .map(Int::toByte)
                    .toByteArray()
            )
        val rows =
            DwarfSourceLines(
                    section,
                    BinaryView(text("界.hpp").map(Int::toByte).toByteArray()),
                    BinaryView(text("/headers").map(Int::toByte).toByteArray()),
                )
                .rows(0, setOf(0x1000), "/build")
        assertEquals(
            DwarfSourceLines.Source("/headers/界.hpp", 1, 0, checksum.map(Int::toByte)),
            rows[0x1000],
        )
        assertFails { DwarfSourceLines(section).rows(0, setOf(0x1000), "/build") }
        val defined = listOf(3) + text("added.hpp") + listOf(1, 0, 0)
        val extra = listOf(0, defined.size) + defined
        val added =
            DwarfSourceLines(table(address(0x1000) + extra + listOf(4, 3, 1) + end))
                .rows(0, setOf(0x1000), "/build")
        assertEquals("/build/include/added.hpp", added[0x1000]?.path)
    }

    @Test
    fun checksCompilerFileIdentitiesInDwarf4And5() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (version in 4..5) MappedBinary("$directory/debug_fixture_$version").use { file ->
            val image = ElfImage(file.view)
            val function = image.symbol("fixture_function")
            val info = DwarfInfo(image)
            val ranges = DwarfRanges(image, info)
            val bound = DwarfRanges.Range(function.address, function.address + function.size)
            val root =
                info.units.map(info::root).single {
                    ranges.ranges(it).any { range -> range.contains(bound) }
                }
            val compilationDirectory =
                (info.attribute(root, 0x1b) as? DwarfInfo.Value.Text)?.value.orEmpty()
            val rows =
                DwarfSourceLines(
                        image.section(".debug_line"),
                        image.section(".debug_str"),
                        image.sections
                            .firstOrNull { it.name == ".debug_line_str" }
                            ?.let { image.section(it.name) },
                    )
                    .rows(
                        checkNotNull(root.number(0x10)),
                        setOf(function.address),
                        compilationDirectory,
                    )
            val source = rows.getValue(function.address)
            assertTrue(source.line > 0 && source.path.endsWith("/debug_fixture.cpp"))
        }
    }
}
