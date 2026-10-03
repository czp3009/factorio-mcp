package com.hiczp.factorio.mcp

/** Associates native Lua enum names with the Switch byte copied unchanged during construction. */
internal object WidgetSwitchNames {
    data class Layout(val proof: LuaEnumNames.Proof, val evidence: ElfEvidence) {
        val names: List<String>
            get() = proof.names
    }

    fun resolve(image: ElfImage, fields: WidgetSwitchState): Layout {
        val (resolved, readonly) =
            image.withReadonlyEvidence {
                image.withFunctionEvidence { resolveProof(image, fields) }
            }
        val (proof, functions) = resolved
        val pointers =
            proof.pointers
                .mapIndexed { index, pointer -> proof.table + index * 8L to pointer }
                .toMap()
        return Layout(proof, ElfEvidence(functions, readonly, pointers, emptyMap()))
    }

    private fun resolveProof(image: ElfImage, fields: WidgetSwitchState): LuaEnumNames.Proof {
        val wrapper = SysVObjectSize.resolve(image, "13LuaGuiElement")
        val custom = SysVObjectSize.resolve(image, "12CustomSwitch")
        val labeled = SysVObjectSize.resolve(image, "13LabeledSwitch")
        val names =
            LuaEnumNames.resolve(
                image,
                "_ZN13LuaGuiElement18luaReadSwitchStateEP9lua_State",
                wrapper,
                custom,
            )
        val constructor =
            image.symbol(
                "_ZN13LabeledSwitchC2ENSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEES5_"
            )
        EhFrames(image).function(constructor)
        val member =
            EmbeddedPrimaryTable.analyze(
                image.functionBytes(constructor, 4096),
                constructor.address,
                ItaniumType.resolve(image, "N4agui6SwitchE").addressPoint,
                labeled,
                fields.size,
            )
        val create = image.symbol("_ZN12CustomSwitch12createWidgetEv")
        val allocate = image.symbol("_Znwm")
        EhFrames(image).function(create)
        EhFrames(image).function(allocate)
        val copied =
            ConstructedByteCopy.analyze(
                image.functionBytes(create, 4096),
                create.address,
                allocate.address,
                constructor.address,
                labeled,
                custom,
                names.field,
            )
        require(copied - member == fields.state) {
            "Lua enum reader and native Switch construction disagree"
        }
        return names
    }
}
