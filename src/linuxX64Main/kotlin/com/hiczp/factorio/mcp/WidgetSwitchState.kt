package com.hiczp.factorio.mcp

/** State location within the bounded Switch subobject, independently identified by its primary table. */
internal data class WidgetSwitchState(val size: Long, val state: Long, val allowNone: Long) {
    companion object {
        fun resolve(image: ElfImage): WidgetSwitchState {
            val size = SysVObjectSize.resolve(image, "13LabeledSwitch")
            val function = image.symbol("_ZN12CustomSwitch12createWidgetEv")
            val constructor =
                image.symbol("_ZN13LabeledSwitchC2ENSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEES5_")
            val allocator = image.symbol("_Znwm")
            EhFrames(image).function(constructor)
            EhFrames(image).function(allocator)
            val instances = image.inlines.find(function, "createWidget", setOf("setState"))
            // LabeledSwitch's wrapper also refreshes labels. Analyze its innermost named setters,
            // rather than admitting unrelated calls as part of a scalar member assignment.
            val leaves = instances.filter { parent ->
                instances.none { child ->
                    child.ranges != parent.ranges && child.ranges.all { span ->
                        parent.ranges.any { it.contains(span) }
                    }
                }
            }
            val fields = leaves.map { instance ->
                ConstructedInlineByteMember.analyze(
                    image.functionBytes(function, 4096), function.address,
                    allocator.address, constructor.address, size, instance.ranges.map { range ->
                        DwarfRanges.Range(range.start - function.address, range.end - function.address)
                    })
            }.distinct()
            val state = fields.singleOrNull() ?: error("Named Switch state setters disagree on their member")
            val switchSize = SysVObjectSize.resolve(image, "4agui6Switch")
            val identity = ItaniumType.resolve(image, "N4agui6SwitchE")
            val member = EmbeddedPrimaryTable.analyze(
                image.functionBytes(constructor, 4096), constructor.address,
                identity.addressPoint, size, switchSize
            )
            require(state >= member && state - member in 8 until switchSize)
            val customSize = SysVObjectSize.resolve(image, "12CustomSwitch")
            val wrapperSize = SysVObjectSize.resolve(image, "13LuaGuiElement")
            val stack = SysVLuaStack.resolve(image)
            val allow = LuaBooleanMember.resolve(
                image, "_ZN13LuaGuiElement21luaReadAllowNoneStateEP9lua_State",
                "luaReadAllowNoneState", wrapperSize, customSize, stack.top, stack.valueSize
            )
            val copied = ConstructedByteCopy.analyze(
                image.functionBytes(function, 4096), function.address,
                allocator.address, constructor.address, size, customSize, allow.field
            )
            require(copied >= member && copied - member in 8 until switchSize && copied != state)
            return WidgetSwitchState(switchSize, state - member, copied - member)
        }
    }
}
