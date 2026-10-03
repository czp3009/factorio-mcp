package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Original Lua direction names, connected to the matched CustomFrame virtual address getter. */
internal object LuaDirectionNames {
    data class Proof(
        val wrapperMember: Long,
        val directionMember: Long,
        val slot: Int,
        val names: Map<Int, String>,
        val source: DwarfSourceLines.Source,
    )

    fun resolve(image: ElfImage): Proof {
        val function = image.symbol("_ZN13LuaGuiElement16luaReadDirectionEP9lua_State")
        val push = image.symbol("lua_pushlstring")
        val wrapper = SysVObjectSize.resolve(image, "13LuaGuiElement")
        val frame = SysVObjectSize.resolve(image, "11CustomFrame")
        val getter =
            ItaniumVtable.resolve(image, "_ZTV11CustomFrame")
                .method(image, "_ZN11CustomFrame12getDirectionEv")
        val member = SysVAccessors.resolveAddress(image, getter.function, 1, frame)
        SysVLuaStringPush.verify(image, LuaStateLayout.resolve(image).allocation.globalOffset)
        EhFrames(image).function(function)
        val flow = X64ControlFlow.resolve(image, function)
        val calls =
            flow.instructions.filter {
                it.offset in flow.reachable && it.operation == Operation.CALL
            }
        val invoke =
            calls.singleOrNull { call ->
                val entry = call.destination as? Memory
                entry != null &&
                    entry.width == 8 &&
                    !entry.relative &&
                    entry.index == null &&
                    entry.displacement == getter.slot * 8L
            } ?: error("Direction reader has no unique matched virtual getter call")
        val values = ConstructorValues(flow.reaching(invoke.offset), emptyMap())
        val receiver =
            values.register(invoke.offset, 7) as? ConstructorValues.Load
                ?: error("Direction getter does not receive the original wrapper's object")
        val entry = invoke.destination as Memory
        require(
            receiver.base == ConstructorValues.Argument(7) &&
                receiver.member in 0..wrapper - 8 &&
                (values.register(invoke.offset, checkNotNull(entry.base))
                        as? ConstructorValues.Load)
                    ?.let { it.base == receiver && it.member == 0L } == true
        ) {
            "Direction dispatch lacks the matched original receiver and primary table"
        }
        val arguments = SysVArgumentFlow(flow)
        val objectLoad = flow.body.getValue(receiver.site)
        require(
            objectLoad.operation == Operation.MOV &&
                arguments.source(objectLoad.offset) ==
                    SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, receiver.member), 8)
        )
        val definitions = ScalarExpression(flow)
        fun returned(site: Long, register: Int, depth: Int = 0): Boolean {
            require(depth < 32)
            val definition = definitions.definition(site, register)
            if (definition == invoke) return register == 0
            val source = definition.source as? Register ?: return false
            return definition.operation == Operation.MOV &&
                definition.destination == Register(register, 8) &&
                source.width == 8 &&
                returned(definition.offset, source.number, depth + 1)
        }
        val read =
            flow.instructions.singleOrNull { instruction ->
                val storage = instruction.source as? Memory
                instruction.offset in flow.reachable &&
                    instruction.operation == Operation.MOVZX &&
                    storage != null &&
                    storage.width == 1 &&
                    !storage.relative &&
                    storage.index == null &&
                    storage.displacement == 0L &&
                    storage.base != null &&
                    runCatching { returned(instruction.offset, storage.base) }.getOrDefault(false)
            }
                ?: error(
                    "Direction reader does not load a unique byte from its native address result"
                )
        val consume = calls.single { it.destination == Immediate(push.address - function.address) }
        require(arguments.register(consume.offset, 7) == SysVArgumentFlow.Reference(6)) {
            "Direction reader does not forward its original Lua state"
        }
        val source = GuiDirectionArgument.verify(image, frame, member)
        return Proof(
            receiver.member,
            member,
            getter.slot,
            ByteEnumNames.analyze(
                flow,
                read.offset,
                consume.offset,
                NativeStringLayout.resolve(image),
            ),
            source,
        )
    }
}
