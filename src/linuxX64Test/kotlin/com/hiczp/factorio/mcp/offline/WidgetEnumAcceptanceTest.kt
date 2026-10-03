@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.*
import kotlin.test.Test
import kotlinx.cinterop.toKString
import platform.posix.getenv

class WidgetEnumAcceptanceTest {
    @Test
    fun connectsProgressNamesToTheTypedDirectionSourceOperation() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val (proof, evidence) =
                image.withFunctionEvidence { WidgetProgressDirection.named(image) }
            check(
                proof.names.values.toSet() == setOf("horizontal", "vertical") &&
                    evidence.isNotEmpty()
            )
            println("Typed progress direction names=$proof; functions=${evidence.size}")
        }
    }

    @Test
    fun resolvesOriginalDirectionNamesAndInspectsTheirInlineIdentity() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val (proof, evidence) = image.withFunctionEvidence { LuaDirectionNames.resolve(image) }
            check(
                proof.names.values.toSet() == setOf("horizontal", "vertical") &&
                    evidence.isNotEmpty()
            )
            println("Original Lua direction names=$proof; functions=${evidence.size}")
            val debug = DwarfInfo(image)
            for ((symbol, owner) in
                listOf(
                    "_ZN13LuaGuiElement16luaReadDirectionEP9lua_State" to "luaReadDirection",
                    "_ZNK4agui11ProgressBar15getBarRectangleEv" to "getBarRectangle",
                    "_ZN4agui5FrameC2ENS_12GuiDirectionEPKNS_10FrameStyleERKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEE" to
                        "Frame",
                )) {
                val instances = image.inlines.all(image.symbol(symbol), owner)
                println(
                    "$owner inline identities=${instances.map { it.name to it.origin }.distinct()}"
                )
                for (instance in
                    instances.filter { it.name == "operator==" }.distinctBy { it.origin }) {
                    println("$owner comparison origin=${debug.entry(instance.origin).attributes}")
                }
            }
        }
    }

    @Test
    fun resolvesSwitchNamesFromTheOriginalLuaReader() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val fields = WidgetSwitchState.resolve(image)
            val proof = WidgetSwitchNames.resolve(image, fields)
            check(proof.names.toSet() == setOf("left", "right", "none"))
            check(proof.proof.pointers.distinct().size == proof.names.size)
            check(proof.evidence.functions.isNotEmpty() && proof.evidence.readonly.isNotEmpty())
            check(proof.evidence.pointers.size == proof.names.size)
            println("Original Lua switch enum table=$proof")
        }
    }
}
