@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class InlineMemberConstructionTest {
    @Test
    fun resolvesPaddedEmbeddedEventsAndCrossChecksNamedFields() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (version in 4..5) MappedBinary("$directory/member_event_fixture_$version").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) =
                image.symbol("fixture_member_$name").let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

            val function = image.symbol("fixture_construct_member")
            val dispatch = image.symbol("fixture_member_dispatch")
            val debug = DwarfInlines(image)
            fun resolve(size: Long = constant("gui_size"), argument: Int = 6) = InlineMemberConstruction.resolve(
                image, function, function.name, "setKeyEvent", dispatch, size, argument, debug
            )

            val proof = resolve()
            assertEquals(constant("event"), proof.member)
            assertEquals(constant("event_size").toInt(), proof.extent)
            fun assignment(name: String, width: Int, floating: Boolean = false, value: Long? = null) =
                InlineMemberConstruction.Assignment(constant(name), width, floating, value)
            assertEquals(
                setOf(
                    assignment("key", 4),
                    assignment("character", 4),
                    assignment("extended", 4),
                    assignment("time", 8, floating = true),
                    assignment("control", 1),
                    assignment("handled", 1, value = 0),
                    assignment("source", 8, value = 0)
                ), proof.assignments.toSet()
            )
            val fields = mapOf(
                "getKey" to InlineArgumentFields.Field(constant("key"), 4),
                "getUnichar" to InlineArgumentFields.Field(constant("character"), 4),
                "getExtendedKey" to InlineArgumentFields.Field(constant("extended"), 4),
                "metaOrControl" to InlineArgumentFields.Field(constant("control"), 1)
            )
            val result = KeyEventFields.crossCheck(proof, fields)
            assertEquals(constant("key"), result.key)
            assertEquals(constant("control"), result.control)
            assertEquals(constant("source"), result.source)
            assertEquals(constant("time"), result.time)
            val defaults = proof.assignments.filter { it.offset !in setOf(result.extended, result.source) }
                .map { InlineArgumentFields.Field(it.offset, it.width) }
            val values = MemberDefaults.resolve(
                image, image.symbol("fixture_default_member"),
                constant("gui_size"), proof.member, defaults
            )
            assertEquals(79, values.getValue(result.key))
            assertEquals(setOf(0), values.filterKeys { it != result.key }.values.toSet())
            assertFails {
                MemberDefaults.resolve(
                    image, image.symbol("fixture_default_member"),
                    constant("gui_size"), proof.member, defaults + InlineArgumentFields.Field(result.extended, 4)
                )
            }
            assertFails { resolve(size = 1) }
            assertFails { resolve(argument = 2) }
            assertFails { KeyEventFields.crossCheck(proof, fields + ("getKey" to InlineArgumentFields.Field(0, 4))) }
            assertFails {
                KeyEventFields.crossCheck(
                    proof,
                    fields + ("getKey" to InlineArgumentFields.Field(constant("key"), 1))
                )
            }
            assertFails {
                KeyEventFields.crossCheck(
                    proof.copy(assignments = proof.assignments.filter { it.offset != result.key }),
                    fields
                )
            }
        }
    }
}
