package com.hiczp.factorio.mcp

/** State-pointer association and load-handler flags; selection and Lua invocation are verified separately. */
internal data class LuaScriptLayout(
    val size: Long,
    val state: Long,
    val vtable: Long,
    val typeInfo: Long,
    val readiness: ScriptReadiness,
) {
    companion object {
        fun resolve(image: ElfImage): LuaScriptLayout {
            val size = SysVObjectSize.resolve(image, "13LuaGameScript")
            val table = ItaniumVtable.resolve(image, "_ZTV13LuaGameScript")
            table.method(image, "_ZN13LuaGameScriptD0Ev")
            val text = SysVMemberCalls.direct(
                image, "_ZN13LuaGameScript20pushOnGuiTextChangedERK10GameAction",
                "lua_pushlstring", size
            )
            val field = SysVMemberCalls.direct(
                image, "_ZN13LuaGameScript20pushOnGuiTextChangedERK10GameAction",
                "lua_setlfield", size
            )
            val event = SysVMemberCalls.direct(
                image, "_ZN13LuaGameScript32pushScriptDestroyedSegmentedUnitERK10GameAction",
                "_ZN9LuaHelper4pushEP9lua_StateP23SegmentedUnitController", size
            )
            require(text == field && text == event) { "Script methods disagree on their Lua state member" }
            return LuaScriptLayout(
                size, text, table.addressPoint, image.symbol("_ZTI13LuaGameScript").address,
                SysVScopedFlags.resolve(image, size)
            )
        }
    }
}
