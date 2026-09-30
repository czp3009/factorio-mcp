@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.*

class ItaniumClassTest {
    @Test
    fun derivesBasePositionsFromCompilerRttiAcrossDifferentLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val offsets = mutableListOf<Long>()
        for (padding in listOf(1, 23)) MappedBinary("$directory/rtti_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun scalar(name: String): Long {
                val symbol = image.symbol(name)
                return image.virtualBytes(symbol.address, symbol.size).unsigned(0, 8)
            }

            val size = scalar("fixture_widget_size")
            val extent = scalar("fixture_base_size")
            val base = ItaniumClass.resolve(image, "10Targetable")
            assertTrue(base.bases.isEmpty())
            assertEquals(
                setOf("6Widget", "7Derived", "7Private", "7Virtual"),
                ItaniumClass.descendants(image, "10Targetable").toSet()
            )
            assertEquals(setOf("7Derived"), ItaniumClass.descendants(image, "6Widget").toSet())
            val widget = ItaniumClass.resolve(image, "6Widget")
            val offset = widget.directBase(base, size, extent)
            assertEquals(scalar("fixture_base_offset"), offset)
            assertFails { widget.directBase(base, offset + extent - 1, extent) }
            assertEquals(0, ItaniumClass.resolve(image, "7Derived").directBase(widget, size, size))
            assertFails { ItaniumClass.resolve(image, "7Private").directBase(base, size, extent) }
            assertFails { ItaniumClass.resolve(image, "7Virtual").directBase(base, size, extent) }
            assertFails { widget.directBase(widget, size, size) }
            offsets += offset
        }
        assertNotEquals(offsets[0], offsets[1])
    }

    @Test
    fun rejectsCorruptKindsNamesCountsFlagsAndBaseReferences() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/rtti_fixture_1").use { file ->
            val image = ElfImage(file.view)
            val owner = image.symbol("_ZTI6Widget")
            val raw = file.view.bytes(0, file.view.size.toInt())
            fun changedScalar(address: Long, value: Long): ElfImage {
                val bytes = raw.copyOf()
                val section =
                    image.sections.single { it.flags and 2 != 0L && address >= it.address && address + 8 <= it.address + it.size }
                val start = (section.offset + address - section.address).toInt()
                repeat(8) { bytes[start + it] = (value ushr (it * 8)).toByte() }
                return ElfImage(BinaryView(bytes))
            }

            fun changedPointer(address: Long, value: Long): ElfImage {
                val bytes = raw.copyOf()
                val relocation = image.sections.filter { it.type == 4L }.flatMap { section ->
                    (0 until section.size / 24).map { section.offset + it * 24 }
                }.single { file.view.unsigned(it, 8) == address }
                repeat(8) { bytes[(relocation + 16 + it).toInt()] = (value ushr (it * 8)).toByte() }
                return ElfImage(BinaryView(bytes))
            }

            val countAndFlags = image.virtualBytes(owner.address + 16, 8).unsigned(0, 8)
            assertFails { ItaniumClass.resolve(changedScalar(owner.address + 16, countAndFlags or 4), "6Widget") }
            assertFails { ItaniumClass.resolve(changedScalar(owner.address + 16, 65L shl 32), "6Widget") }
            assertFails { ItaniumClass.resolve(changedScalar(owner.address + 32, 4), "6Widget") }
            assertFails { ItaniumClass.resolve(changedPointer(owner.address + 24, owner.address), "6Widget") }
            assertFails { ItaniumClass.resolve(changedPointer(owner.address, owner.address), "6Widget") }
            assertFails {
                ItaniumClass.resolve(
                    changedPointer(
                        owner.address + 8,
                        image.symbol("_ZTS10Targetable").address
                    ), "6Widget"
                )
            }
        }
    }
}
