@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxWorldQueryConfig

internal class WorldQueryMetadata private constructor(
    private val api: LuaApi,
    private val world: WorldLayout,
    private val context: LuaContextLayout,
    private val script: LuaScriptLayout,
    private val player: PlayerLayout,
    private val viewport: ViewportLayout,
    private val evidence: List<ElfImage.Symbol>,
) {
    fun writeTo(output: FmLinuxWorldQueryConfig, loadBias: Long) {
        world.writeTo(output.world, loadBias)
        context.writeTo(output.script, script, loadBias)
        player.writeTo(output.player, loadBias)
        api.state.writeTo(output.state)
        api.writeTo(output.api, loadBias)
        viewport.writeTo(output.viewport, loadBias)
    }

    fun verifyLoaded(image: ElfImage, process: ProcessHandle, loadBias: Long) {
        api.verifyLoaded(image, process, loadBias)
        viewport.verifyLoaded(image, process, loadBias)
        for (symbol in evidence) {
            require(
                symbol.size in 1..(16 * 1024 * 1024) && symbol.address >= 0 &&
                        loadBias >= 0 && symbol.address <= Long.MAX_VALUE - loadBias - symbol.size
            )
            require(
                process.readMemory(loadBias + symbol.address, symbol.size.toInt())
                    .contentEquals(image.functionBytes(symbol, symbol.size.toInt()).bytes(0, symbol.size.toInt()))
            ) {
                "Live world-query evidence differs from the selected executable: ${symbol.name}"
            }
        }
    }

    companion object {
        fun resolve(image: ElfImage): WorldQueryMetadata {
            val api = LuaApi.resolve(image)

            data class Layouts(
                val world: WorldLayout, val script: LuaScriptLayout, val context: LuaContextLayout,
                val player: PlayerLayout
            )
            val (layouts, evidence) = image.withFunctionEvidence {
                val world = WorldLayout.resolve(image)
                val script = LuaScriptLayout.resolve(image)
                val context = LuaContextLayout.resolve(image, script.size)
                require(world.contextSize == context.size)
                Layouts(world, script, context, PlayerLayout.resolve(image, world.gameSize, api.state))
            }
            val viewport = ViewportLayout.resolve(image, layouts.player.viewSize)
            return WorldQueryMetadata(api, layouts.world, layouts.context, layouts.script, layouts.player, viewport, evidence)
        }
    }
}
