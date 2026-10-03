package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Existing UI ItemStack/Item storage, connected to named Lua inline readers and native typed conversions. */
internal data class WidgetItemLayout(
    val itemType: Long,
    val toolType: Long,
    val ammoType: Long,
    val stackExtent: Long,
    val itemSize: Long,
    val toolSize: Long,
    val ammoSize: Long,
    val stackItem: Long,
    val count: Long,
    val health: Long,
    val durability: Long,
    val magazine: Long,
    val evidence: ElfEvidence,
) {
    companion object {
        private data class StackGetter(val member: Long, val slot: Int, val item: Long)

        fun resolve(image: ElfImage): WidgetItemLayout {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val pointers = mutableMapOf<Long, Long>()
                    val scalars = mutableMapOf<Long, Long>()
                    fun table(owner: String, base: String, extent: Long): ItaniumSubobjectVtable =
                        ItaniumSubobjectVtable.resolve(image, owner, base, extent).also {
                            pointers.putAll(it.pointers)
                            scalars.putAll(it.scalars)
                        }
                    val constructor = image.symbol("_ZN9ItemStackC2E22ItemCreationParametersj")
                    EhFrames(image).function(constructor)
                    val stackExtent = ConstructorPrefixExtent.analyze(image.functionBytes(constructor, 32768))
                    val itemSize = SysVObjectSize.resolve(image, "4Item")
                    val toolSize = SysVObjectSize.resolve(image, "4Tool")
                    val ammoSize = SysVObjectSize.resolve(image, "8AmmoItem")
                    val luaExtent = DestructorPrefixExtent.resolve(image, "12LuaItemStack")
                    val common = table("12LuaItemStack", "13LuaItemCommon", luaExtent)
                    val prefix = if (common.baseOffset == 0L) "_ZN" else "_ZThn${common.baseOffset}_N"
                    val optional = common.method(image, "${prefix}12LuaItemStack15getItemOptionalEv")
                    // The Lua readers use a required getter while the UI adapter reads only an existing Item.
                    // This entry is static call-path evidence; the adapter never invokes this validating getter.
                    val luaItem = common.method(image, "${prefix}12LuaItemStack23getItemFailOnEmptyStackEv")
                    require(luaItem.slot != optional.slot)
                    val plain = stackGetter(image, image.symbol("_ZN12LuaItemStack15getItemOptionalEv"), luaExtent, stackExtent)
                    val adjusted = stackGetter(image, optional.function, luaExtent - common.baseOffset, stackExtent)
                    require(plain == adjusted.copy(member = adjusted.member + common.baseOffset)) {
                        "Optional Item getter disagrees with its adjusted LuaItemCommon receiver"
                    }

                    fun stackField(symbol: String, owner: String, getter: String, width: Int, path: List<Long>): Long {
                        val function = image.symbol(symbol)
                        val flow = X64ControlFlow.resolve(image, function)
                        val inlines = image.inlines.find(function, owner, setOf("getItemStack", getter))
                        val origins = ReturnedPointerOrigins(flow)
                        val fields = inlineReads(function, flow, inlines.filter { it.name == getter }, width).mapNotNull { read ->
                            val value = origins.address(read.offset, read.source as Memory) ?: return@mapNotNull null
                            if (value.members != path) return@mapNotNull null
                            require(contains(function, inlines.filter { it.name == "getItemStack" }, flow.body.getValue(value.call)))
                            val source = originalVirtual(flow, value.call, plain.slot)
                            require(source == plain.member) { "Inline stack reader uses a different original receiver member" }
                            value.adjustment
                        }.distinct()
                        return fields.singleOrNull() ?: error("No unique typed $getter field from the existing stack")
                    }
                    val count = stackField("_ZN12LuaItemStack16luaReadItemCountEP9lua_State", "luaReadItemCount", "getCount", 4, emptyList())
                    val health = stackField("_ZN12LuaItemStack17luaReadItemHealthEP9lua_State", "luaReadItemHealth", "getHealth", 4, listOf(plain.item))
                    require(count in 0..stackExtent - 4 && health in 0..itemSize - 4 &&
                            (count + 4 <= plain.item || plain.item + 8 <= count))

                    fun itemField(type: String, size: Long, cast: String, symbol: String, owner: String,
                                  inlineCast: String, getter: String, width: Int): Long {
                        val conversion = table(type, type, size).method(image, "_ZN$type$cast")
                        require(SysVAccessors.resolveAddress(image, conversion.function, size, size) == 0L)
                        val function = image.symbol(symbol)
                        val flow = X64ControlFlow.resolve(image, function)
                        val inlines = image.inlines.find(function, owner, setOf(inlineCast, getter))
                        val origins = ReturnedPointerOrigins(flow)
                        val fields = inlineReads(function, flow, inlines.filter { it.name == getter }, width).mapNotNull { read ->
                            val value = origins.address(read.offset, read.source as Memory) ?: return@mapNotNull null
                            if (value.members.isNotEmpty()) return@mapNotNull null
                            val castCall = flow.body.getValue(value.call)
                            require(contains(function, inlines.filter { it.name == inlineCast }, castCall))
                            val item = returnedVirtual(flow, origins, value.call, conversion.slot)
                            require(item.members.isEmpty() && item.adjustment == 0L)
                            require(contains(function, inlines.filter { it.name == inlineCast }, flow.body.getValue(item.call)))
                            originalVirtual(flow, item.call, luaItem.slot, direct = true)
                            value.adjustment
                        }.distinct()
                        return (fields.singleOrNull() ?: error("No unique $getter field after its typed conversion"))
                            .also { require(it in 0..size - width) }
                    }
                    val durability = itemField("4Tool", toolSize, "6asToolEv", "_ZN13LuaItemCommon21luaReadItemDurabilityEP9lua_State",
                        "luaReadItemDurability", "getTool", "getDurability", 8)
                    val magazine = itemField("8AmmoItem", ammoSize, "10asAmmoItemEv", "_ZN13LuaItemCommon11luaReadAmmoEP9lua_State",
                        "luaReadAmmo", "getAmmoItem", "getMagazineLeft", 4)
                    WidgetItemLayout(ItaniumClass.resolve(image, "4Item").typeInfo,
                        ItaniumClass.resolve(image, "4Tool").typeInfo, ItaniumClass.resolve(image, "8AmmoItem").typeInfo,
                        stackExtent, itemSize, toolSize, ammoSize, plain.item, count, health, durability, magazine,
                        ElfEvidence(emptyList(), emptyList(), pointers, scalars))
                }
            }
            return resolved.first.copy(evidence = resolved.first.evidence.copy(functions = resolved.second, readonly = readonly))
        }

        private fun stackGetter(image: ElfImage, function: ElfImage.Symbol, extent: Long, stackExtent: Long): StackGetter {
            EhFrames(image).function(function)
            val bytes = image.functionBytes(function, 256)
            val flow = X64ControlFlow(X64Instructions(bytes).all())
            val values = ReturnedPointerOrigins(flow)
            val returns = flow.instructions.filter { it.offset in flow.reachable && it.operation == Operation.RET }
            require(returns.isNotEmpty())
            val result = returns.map { values.register(it.offset, 0) ?: error("Optional getter loses its returned pointer") }.distinct().single()
            require(result.members.size == 1 && result.adjustment == 0L && result.members.single() in 0..stackExtent - 8)
            val call = flow.body.getValue(result.call)
            val target = call.destination as? Memory ?: error("Optional getter lacks a bounded virtual target")
            require(target.width == 8 && target.displacement in 0..2040 && target.displacement % 8 == 0L)
            val slot = (target.displacement / 8).toInt()
            val member = originalVirtual(flow, result.call, slot)
            require(member in 0..extent - 8)
            require(flow.instructions.filter { it.offset in flow.reachable && it.operation == Operation.CALL } == listOf(call))
            require(flow.instructions.none { it.offset in flow.reachable && it.destination is Memory &&
                    it.operation !in listOf(Operation.CALL, Operation.CMP, Operation.TEST, Operation.NOP) }) {
                "Optional getter changes game storage"
            }
            val abi = SysVReceiverFlow(bytes, function.address, extent)
            abi.call(call.offset)
            require(returns.all { ret ->
                val state = abi.before(ret.offset)
                state[4] == SysVReceiverFlow.Stack(0) && listOf(3, 5, 12, 13, 14, 15).all {
                    state[it] == SysVReceiverFlow.Original(it)
                }
            }) { "Optional getter does not restore its System V frame" }
            return StackGetter(member, slot, result.members.single())
        }

        private fun contains(function: ElfImage.Symbol, inlines: List<DwarfInlines.Instance>, instruction: Instruction): Boolean =
            inlines.any { inline -> inline.ranges.any {
                function.address + instruction.offset >= it.start && function.address + instruction.offset + instruction.size <= it.end
            } }

        private fun inlineReads(function: ElfImage.Symbol, flow: X64ControlFlow, inlines: List<DwarfInlines.Instance>, width: Int): List<Instruction> {
            val boundaries = flow.body.keys.map { function.address + it }.toSet() + (function.address + function.size)
            require(inlines.isNotEmpty() && inlines.all { inline -> inline.ranges.all { it.start in boundaries && it.end in boundaries } })
            return flow.instructions.filter { instruction ->
                instruction.offset in flow.reachable && instruction.operation in listOf(Operation.MOV, Operation.SCALAR_MOV) &&
                        instruction.source is Memory && instruction.source.width == width && instruction.destination is Register &&
                        instruction.destination.width == width && contains(function, inlines, instruction)
            }
        }

        /** The original game call supplies the ABI; this establishes its receiver and own-vtable dispatch. */
        private fun originalVirtual(flow: X64ControlFlow, site: Long, slot: Int, direct: Boolean = false): Long {
            val values = ConstructorValues(flow.reaching(site), emptyMap())
            val receiver = values.register(site, 7)
            val member = if (direct) {
                require(receiver == ConstructorValues.Argument(7))
                0L
            } else {
                val pointer = receiver as? ConstructorValues.Load ?: error("Virtual receiver is not an original pointer member")
                require(pointer.base == ConstructorValues.Argument(7))
                pointer.member
            }
            val call = flow.body.getValue(site)
            require(call.operation == Operation.CALL)
            val table = when (val target = call.destination) {
                is Memory -> {
                    require(target.width == 8 && !target.relative && target.index == null && target.displacement == slot * 8L)
                    target.base?.let { values.register(site, it) }
                }
                is Register -> (values.register(site, target.number) as? ConstructorValues.Load)?.let {
                    require(target.width == 8 && it.member == slot * 8L)
                    it.base
                }
                else -> null
            } as? ConstructorValues.Load ?: error("Virtual call does not use a verified table")
            require(table.base == receiver && table.member == 0L)
            val top = checkNotNull(SysVLocalArgument(flow).registers(site)[4])
            require((top + 8) % 16 == 0L)
            return member
        }

        private fun returnedVirtual(flow: X64ControlFlow, values: ReturnedPointerOrigins, site: Long, slot: Int): ReturnedPointerOrigins.Value {
            val receiver = values.register(site, 7) ?: error("Conversion receiver is not a returned Item pointer")
            require(receiver.members.isEmpty() && receiver.adjustment == 0L)
            val call = flow.body.getValue(site)
            require(call.operation == Operation.CALL)
            val expected = receiver.copy(members = listOf(0))
            val table = when (val target = call.destination) {
                is Memory -> {
                    require(target.width == 8 && !target.relative && target.index == null && target.displacement == slot * 8L)
                    target.base?.let { values.register(site, it) }
                }
                is Register -> values.register(site, target.number)?.let {
                    require(target.width == 8 && it == expected.copy(members = listOf(0, slot * 8L)))
                    expected
                }
                else -> null
            }
            require(table == expected) { "Conversion does not dispatch through the returned Item's own table" }
            return receiver
        }
    }
}
