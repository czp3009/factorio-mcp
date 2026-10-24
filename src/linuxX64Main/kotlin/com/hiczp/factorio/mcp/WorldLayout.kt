@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxWorldLayout

/**
 * Structural world references only. Script selection, Lua readiness and entry ABIs need separate
 * proofs.
 */
internal data class WorldLayout(
    val global: Long,
    val globalSize: Long,
    val scenarioSize: Long,
    val gameSize: Long,
    val contextSize: Long,
    val scenario: Long,
    val game: Long,
    val context: Long,
) {
    init {
        fun size(value: Long) = value in 8..(64 * 1024 * 1024)
        fun member(offset: Long, size: Long) = offset >= 0 && offset <= size - 8
        require(global > 0 && global % 8 == 0L && global <= Long.MAX_VALUE - 8)
        require(listOf(globalSize, scenarioSize, gameSize, contextSize).all(::size))
        require(member(scenario, globalSize) && member(game, scenarioSize))
        require(member(context, scenarioSize) && context % 8 == 0L)
        require(context + 8 <= game || game + 8 <= context) { "World pointer members overlap" }
    }

    fun writeTo(output: FmLinuxWorldLayout, loadBias: Long) {
        require(loadBias >= 0 && loadBias % 8 == 0L && global <= Long.MAX_VALUE - 8 - loadBias)
        output.global = (global + loadBias).toULong()
        output.globalSize = globalSize.toUInt()
        output.scenarioSize = scenarioSize.toUInt()
        output.gameSize = gameSize.toUInt()
        output.contextSize = contextSize.toUInt()
        output.scenario = scenario.toUInt()
        output.game = game.toUInt()
        output.context = context.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage): WorldLayout {
            val references = WorldReferences.resolve(image)
            val contextSize = contextSize(image)
            val noReturn =
                SysVNoReturn(image, setOf("abort", "__cxa_throw", "_ZSt20__throw_system_errori"))
                    .resolve("_Z19ReleaseAssertFailedPKcjS0_")
            val context =
                SysVMemberCalls.direct(
                    image,
                    "_ZN8ScenarioD2Ev",
                    "_ZN10LuaContextD2Ev",
                    references.scenarioSize,
                    setOf(noReturn),
                )
            return WorldLayout(
                references.global,
                references.globalSize,
                references.scenarioSize,
                references.gameSize,
                contextSize,
                references.scenario,
                references.game,
                context,
            )
        }

        fun contextSize(image: ElfImage): Long =
            SysVOwnedObjectSize.resolve(
                    image,
                    "_ZNSt10unique_ptrI10LuaContextSt14default_deleteIS0_EED2Ev",
                    "_ZN10LuaContextD2Ev",
                )
                .size
    }
}
