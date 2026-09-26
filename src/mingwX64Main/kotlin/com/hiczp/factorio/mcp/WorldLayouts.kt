@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.WorldLayout

internal class WorldLayouts(types: DebugTypes) {
    private val scenario = types.pointerMember("Game", "scenario", "Scenario")
    private val luaState =
        types.namedMember("LuaGameScript", "luaState", "LuaState", types.aggregateSize("LuaState"))
    private val setupFinished = types.byteMember("LuaGameScript", "setupFinished", true)
    private val runningOnLoad = types.byteMember("LuaGameScript", "runningOnLoad", true)
    private val playerIndex = types.scalarMember("Player", "index", 2u, 7u)

    fun write(target: WorldLayout) {
        target.supported = 1u
        target.scenario = scenario
        target.luaState = luaState
        target.setupFinished = setupFinished
        target.runningOnLoad = runningOnLoad
        target.playerIndex = playerIndex
    }
}
