@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FM_LINUX_CONTEXT_CANDIDATES
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxWorldLayout
import kotlinx.cinterop.set

/** Structural world references only. Script selection, Lua readiness and entry ABIs need separate proofs. */
internal data class WorldLayout(
    val global: Long,
    val globalSize: Long,
    val scenarioSize: Long,
    val gameSize: Long,
    val contextSize: Long,
    val scenario: Long,
    val game: Long,
    val contexts: List<Long>,
    val contextVtable: Long,
    val contextTypeInfo: Long,
) {
    init {
        fun size(value: Long) = value in 8..(64 * 1024 * 1024)
        fun member(offset: Long, size: Long) = offset >= 0 && offset <= size - 8
        require(global > 0 && global % 8 == 0L && global <= Long.MAX_VALUE - 8)
        require(contextVtable >= 16 && contextVtable % 8 == 0L && contextVtable <= Long.MAX_VALUE - 8)
        require(contextTypeInfo > 0 && contextTypeInfo % 8 == 0L && contextTypeInfo <= Long.MAX_VALUE - 16)
        require(listOf(globalSize, scenarioSize, gameSize, contextSize).all(::size))
        require(member(scenario, globalSize) && member(game, scenarioSize))
        require(contexts.size in 1..FM_LINUX_CONTEXT_CANDIDATES && contexts.all { member(it, scenarioSize) })
        val members = (contexts + game).sorted()
        require(members.zipWithNext().all { (first, second) -> second - first >= 8 }) {
            "World pointer members overlap or are ambiguous"
        }
    }

    fun writeTo(output: FmLinuxWorldLayout, loadBias: Long) {
        val addresses = listOf(global, contextVtable, contextTypeInfo)
        require(loadBias >= 0 && loadBias % 8 == 0L && addresses.all { it <= Long.MAX_VALUE - 16 - loadBias })
        output.global = (global + loadBias).toULong()
        output.contextVtable = (contextVtable + loadBias).toULong()
        output.contextTypeInfo = (contextTypeInfo + loadBias).toULong()
        output.globalSize = globalSize.toUInt()
        output.scenarioSize = scenarioSize.toUInt()
        output.gameSize = gameSize.toUInt()
        output.contextSize = contextSize.toUInt()
        output.scenario = scenario.toUInt()
        output.game = game.toUInt()
        output.contextCount = contexts.size.toUInt()
        contexts.forEachIndexed { index, offset -> output.contexts[index] = offset.toUInt() }
    }

    companion object {
        fun resolve(image: ElfImage): WorldLayout {
            val references = WorldReferences.resolve(image)
            val contextSize = SysVObjectSize.resolve(image, "10LuaContext")
            val table = ItaniumVtable.resolve(image, "_ZTV10LuaContext")
            val destructor = table.method(image, "_ZN10LuaContextD0Ev")
            val caller = image.symbol("_ZN8ScenarioD2Ev")
            val contexts = SysVMemberCalls.virtual(
                image.functionBytes(caller, 8192), caller.address,
                destructor.slot, references.scenarioSize
            )
            return WorldLayout(
                references.global, references.globalSize, references.scenarioSize, references.gameSize, contextSize,
                references.scenario, references.game, contexts, table.addressPoint, image.symbol("_ZTI10LuaContext").address
            )
        }
    }
}
