package com.hiczp.factorio.mcp

/** Cross-checked custom prototype fields. Live readers must require this exact primary vtable before dereferencing. */
internal data class ControlPrototype(
    val member: Long,
    val size: Long,
    val vtable: Long,
    val enabled: Long,
    val spectating: Long,
    val cutscene: Long,
) {
    companion object {
        fun resolve(image: ElfImage, controlSize: Long, debug: DwarfInlines = DwarfInlines(image)): ControlPrototype {
            val size = SysVObjectSize.resolve(image, "20CustomInputPrototype")
            val table = ItaniumVtable.resolve(image, "_ZTV20CustomInputPrototype")
            table.method(image, "_ZN20CustomInputPrototypeD0Ev")
            val wrapperSize = SysVObjectSize.resolve(image, "23LuaCustomInputPrototype")
            val stack = SysVLuaStack.resolve(image)
            val flags =
                listOf("luaReadEnabled", "luaReadEnabledWhileSpectating", "luaReadEnabledWhielInCutscene").map { name ->
                    LuaBooleanMember.resolve(
                        image, "_ZN23LuaCustomInputPrototype${name.length}${name}EP9lua_State",
                        name, wrapperSize, size, stack.top, stack.valueSize, debug
                    )
                }
            require(flags.map { it.objectPointer }.distinct().size == 1 && flags.map { it.field }
                .distinct().size == 3) {
                "Custom input flags disagree on their prototype or overlap"
            }
            val prototypeName = PointerMemberArgument.resolve(
                image,
                "_ZN20LuaPrototypeTemplateI23LuaCustomInputPrototypeL13LuaObjectType47E2IDI20CustomInputPrototypetEE20luaReadLocalisedNameEP9lua_State",
                "_ZN9LuaHelper4pushEP9lua_StateRK15LocalisedString", 6, wrapperSize, size, mapOf(7 to 6)
            )
            val controlName = PointerMemberArgument.resolve(
                image, "_ZNK12ControlInput22getLocalisedNameResultB5cxx11Ev",
                "_ZNK15LocalisedString25updateTranslationIfNeededEPK14LocaleProvider", 7, controlSize, size
            )
            require(
                prototypeName.input == 7 && prototypeName.pointer == flags.first().objectPointer &&
                        controlName.input == 6 && prototypeName.member == controlName.member
            ) {
                "Control and Lua readers disagree on their custom prototype name"
            }
            require(flags.all { it.field >= 8 } && controlName.member >= 8) {
                "Custom prototype fields overlap its primary vptr"
            }
            return ControlPrototype(
                controlName.pointer, size, table.addressPoint,
                flags[0].field, flags[1].field, flags[2].field
            )
        }
    }
}
