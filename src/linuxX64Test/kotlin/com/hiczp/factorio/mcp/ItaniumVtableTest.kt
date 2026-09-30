@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.*

class ItaniumVtableTest {
    @Test
    fun followsCompilerGeneratedVirtualTableOrderAndChecksRtti() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val slots = mutableListOf<Int>()
        for (padding in listOf(1, 23)) MappedBinary("$directory/accessor_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            val vtable = ItaniumVtable.resolve(image, "_ZTV14FixtureVirtual")
            val method = vtable.method(image, "_ZN14FixtureVirtual8selectedEj")
            if (image.positionIndependent) assertTrue(
                method.entryAddress in ElfPointers(image).relativeReferences(
                    method.function.address
                )
            )
            val member = image.symbol("fixture_virtual_member")
            val representation = image.virtualBytes(member.address, member.size)
            // Itanium virtual member pointers encode byte displacement + 1, followed by receiver adjustment.
            assertEquals(method.slot * 8L + 1, representation.unsigned(0, 8))
            assertEquals(0, representation.unsigned(8, 8))
            assertEquals(vtable.addressPoint + method.slot * 8L, method.entryAddress)
            assertFails { vtable.method(image, "fixture_count") }
            slots += method.slot
        }
        assertNotEquals(slots[0], slots[1])
    }

    @Test
    fun rejectsChangedRttiRelocationsAndDuplicateMethods() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/accessor_fixture_1").use { file ->
            val image = ElfImage(file.view)
            val table = image.symbol("_ZTV14FixtureVirtual")
            val bytes = file.view.bytes(0, file.view.size.toInt())
            fun changedRelocation(target: Long, change: (ByteArray, Int) -> Unit): ElfImage {
                val result = bytes.copyOf()
                val section = image.sections.single { section ->
                    section.type == 4L && (0 until section.size / 24).any {
                        file.view.unsigned(section.offset + it * 24, 8) == target
                    }
                }
                val index = (0 until section.size / 24).single {
                    file.view.unsigned(section.offset + it * 24, 8) == target
                }
                change(result, (section.offset + index * 24).toInt())
                return ElfImage(BinaryView(result))
            }

            val invalidType = changedRelocation(table.address + 8) { data, offset -> data[offset + 8] = 1 }
            assertFails { ItaniumVtable.resolve(invalidType, table.name) }
            val wrongRtti = changedRelocation(table.address + 8) { data, offset ->
                data[offset + 16] = (data[offset + 16].toInt() xor 8).toByte()
            }
            assertFails { ItaniumVtable.resolve(wrongRtti, table.name) }
            for (target in listOf(table.address - 1, table.address + 9)) {
                val partial = changedRelocation(table.address + 8) { data, offset ->
                    repeat(8) { data[offset + it] = (target ushr (it * 8)).toByte() }
                }
                assertFails { ElfPointers(partial).words(table.address, 3) }
            }
            val repeatedTarget = changedRelocation(table.address + 16) { data, offset ->
                repeat(8) { data[offset + it] = ((table.address + 8) ushr (it * 8)).toByte() }
            }
            assertFails { ElfPointers(repeatedTarget).words(table.address, 3) }
            val method = ItaniumVtable.resolve(image, table.name).method(image, "_ZN14FixtureVirtual8selectedEj")
            val duplicate = changedRelocation(table.address + 16) { data, offset ->
                repeat(8) { data[offset + 16 + it] = (method.function.address ushr (it * 8)).toByte() }
            }
            assertFails { ItaniumVtable.resolve(duplicate, table.name).method(duplicate, method.function.name) }
        }
    }

    @Test
    fun neverUsesRawPieWordsAsUnrelocatedPointers() {
        assertFails { ElfPointers.Word(123, null, true).pointer() }
        assertEquals(0, ElfPointers.Word(0, null, true).pointer())
        assertEquals(456, ElfPointers.Word(123, 456, true).pointer())
        assertEquals(123, ElfPointers.Word(123, null, false).pointer())
        assertFails { ElfPointers.Word(123, 456, true).scalar() }
    }
}
