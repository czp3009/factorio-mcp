@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxLuaApi

/** Callable query entries and their structural/ABI prerequisites from one selected ELF image. */
internal class LuaApi private constructor(
    val state: LuaStateLayout,
    private val entries: List<ElfImage.Symbol>,
    private val evidence: List<ElfImage.Symbol>,
) {
    fun writeTo(output: FmLinuxLuaApi, loadBias: Long) {
        require(loadBias >= 0 && entries.all { it.address >= 0 && it.address <= Long.MAX_VALUE - loadBias - it.size })
        val addresses = entries.map { (loadBias + it.address).toULong() }
        output.absIndex = addresses[0]
        output.setTop = addresses[1]
        output.load = addresses[2]
        output.pushNumber = addresses[3]
        output.pushString = addresses[4]
        output.protectedCall = addresses[5]
        output.toString = addresses[6]
        output.rawProtected = addresses[7]
        output.protectedCallArguments = state.call.arguments.toUInt()
    }

    fun verifyLoaded(image: ElfImage, process: ProcessHandle, loadBias: Long) {
        require(loadBias >= 0)
        for (symbol in evidence) {
            require(symbol.size in 1..(16 * 1024 * 1024) && symbol.address >= 0 && symbol.address <= Long.MAX_VALUE - loadBias - symbol.size)
            require(
                process.readMemory(loadBias + symbol.address, symbol.size.toInt())
                    .contentEquals(image.functionBytes(symbol, symbol.size.toInt()).bytes(0, symbol.size.toInt()))
            ) {
                "Live Lua function differs from the selected executable: ${symbol.name}"
            }
        }
    }

    companion object {
        fun resolve(image: ElfImage): LuaApi {
            val state = LuaStateLayout.resolve(image)
            val size = state.allocation.globalOffset
            val loader = SysVLuaLoadFrame.resolve(image, state.stack, size)
            SysVLuaParserArguments.verify(image, loader)
            SysVLuaStatusReturn.verifyLoader(image)
            SysVLuaStackReset.verify(image, state.stack, state.stackEnd, size)
            SysVLuaNumberPush.verify(image, state.stack, size)
            SysVLuaStringPush.verify(image, size)
            SysVLuaStringRead.resolve(image, state.stack.valueSize)
            SysVLuaProtectedDispatch.verify(image, size, loader)
            val names = listOf(
                "lua_absindex", "lua_settop", "lua_load", "lua_pushnumber", "lua_pushlstring", "lua_pcallk",
                "lua_tolstring", "_Z20luaD_rawrunprotectedP9lua_StatePFvS0_PvES1_"
            )
            val entries = names.map(image::symbol)
            val related = listOf(
                "lua_newstate", "_ZL9f_luaopenP9lua_StatePv", "_Z8luaH_newP9lua_State",
                "_ZL8f_parserP9lua_StatePv", "_ZL6f_callP9lua_StatePv", "_Z9luaD_callP9lua_StateP10lua_TValueii",
                "_Z10luaD_pcallP9lua_StatePFvS0_PvES1_ll", "lua_traceandabort", "index2addr",
                "_Z12luaS_newlstrP9lua_StatePKcm", "_Z14luaC_forcestepP9lua_State"
            ).map(image::symbol)
            val evidence = (entries + related).distinct()
            val frames = EhFrames(image)
            evidence.forEach {
                require(it.size in 1..(16 * 1024 * 1024)) { "Lua code verification exceeds bounds: ${it.name}" }
                frames.function(it)
            }
            return LuaApi(state, entries, evidence)
        }
    }
}
