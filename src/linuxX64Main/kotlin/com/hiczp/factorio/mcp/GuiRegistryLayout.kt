@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import com.hiczp.factorio.mcp.linuxbridge.FmGuiRegistryLayout

/** Script/widget identity only. All script attributes are read by the shared Lua program. */
internal data class GuiRegistryLayout(
    val playerSize: Long,
    val playerGui: Long,
    val guiExtent: Long,
    val head: Long,
    val count: Long,
    val elementSize: Long,
    val widget: Long,
    val owner: Long,
    val index: Long,
    val evidence: ElfEvidence,
) {
    fun writeTo(output: FmGuiRegistryLayout) {
        output.supported = 1u
        output.playerSize = playerSize.toUInt()
        output.playerGui = playerGui.toUInt()
        output.guiSize = guiExtent.toUInt()
        output.map = 0u
        output.mapHead = head.toUInt()
        output.mapCount = count.toUInt()
        // libstdc++'s _Rb_tree_node_base ABI, checked against the typed map's erase/insertion paths.
        output.nodeSize = 48u
        output.left = 16u
        output.right = 24u
        output.parent = 8u
        output.nil = 0u
        output.key = 32u
        output.value = 40u
        output.elementSize = elementSize.toUInt()
        output.elementWidget = widget.toUInt()
        output.elementGui = owner.toUInt()
        output.elementIndex = index.toUInt()
        output.embeddedHead = 1u
    }

    companion object {
        private const val ERASE = "_ZNSt8_Rb_treeIPN4agui6WidgetESt4pairIKS2_P16CustomGuiElementESt10_Select1stIS7_ESt4lessIS2_ESaIS7_EE8_M_eraseEPSt13_Rb_tree_nodeIS7_E"
        private const val BALANCE = "_ZSt29_Rb_tree_insert_and_rebalancebPSt18_Rb_tree_node_baseS0_RS_"

        fun resolve(image: ElfImage, playerSize: Long): GuiRegistryLayout {
            val (fields, functions) = image.withFunctionEvidence {
                val wrapperSize = SysVObjectSize.resolve(image, "6LuaGui")
                val playerWrapperSize = SysVObjectSize.resolve(image, "9LuaPlayer")
                val playerMember = SysVArgumentMember.resolve(image, "_ZN9LuaPlayerC2EP6PlayerP9lua_State", playerWrapperSize)
                val allocator = image.symbol("_Znwm")
                val deletion = image.symbol("_ZdlPvm")
                val balance = image.symbol(BALANCE)
                val widgetFront = image.symbol("_ZN4agui6Widget12bringToFrontEv")
                val playerConstructor = image.symbol("_ZN9LuaPlayerC2EP6PlayerP9lua_State")

                fun flow(name: String): Pair<ElfImage.Symbol, X64ControlFlow> {
                    val entry = image.symbol(name)
                    require(entry.size in 1..8192) { "Script GUI evidence exceeds function bounds" }
                    EhFrames(image).function(entry)
                    return entry to X64ControlFlow.resolve(image, entry)
                }
                fun calls(entry: ElfImage.Symbol, body: X64ControlFlow, target: ElfImage.Symbol) =
                    body.instructions.filter { it.offset in body.reachable &&
                        it.operation in setOf(Operation.CALL, Operation.JMP) &&
                        it.destination == Immediate(target.address - entry.address) }
                fun allocations(entry: ElfImage.Symbol, body: X64ControlFlow): Map<Long, ConstructorValues.Allocation> {
                    val values = ScalarExpression(body)
                    return calls(entry, body, allocator).mapNotNull { call ->
                        val size = runCatching { GuiRegistryProof.constant(values.before(call.offset, Register(7, 4))) }
                            .getOrNull() ?: return@mapNotNull null
                        require(size in 8..16 * 1024 * 1024)
                        call.offset to ConstructorValues.Allocation(call.offset, size)
                    }.toMap()
                }

                // The abstract base has no deleting path. Its own constructor proves a readable
                // prefix through direct original-receiver accesses, without inventing sizeof.
                val (_, elementFlow) = flow("_ZN16CustomGuiElementC2ERK12PropertyTree")
                val elementSize = GuiRegistryProof.receiverExtent(elementFlow)

                // The LuaGui reader passes its CustomGui owner's existing Player to the typed constructor.
                val (readPlayer, readPlayerFlow) = flow("_ZN6LuaGui13luaReadPlayerEP9lua_State")
                val readOrigins = OriginalPointerOrigins(readPlayerFlow, readPlayer.address)
                val playerSources = calls(readPlayer, readPlayerFlow, playerConstructor).map { call ->
                    val player = readOrigins.register(call.offset, 6) as? OriginalPointerOrigins.Load
                        ?: error("LuaGui player has no original pointer source")
                    val gui = player.base as? OriginalPointerOrigins.Load
                        ?: error("LuaGui player has no CustomGui owner")
                    require(gui.base == OriginalPointerOrigins.Argument(7) && gui.member in 0..wrapperSize - 8)
                    gui.member to player.member
                }.distinct().single()
                val (wrapperGui, guiPlayer) = playerSources
                require(guiPlayer in 0..16 * 1024 * 1024 - 8)

                // Link the inverse Player field to the same LuaGui wrapper member, before registration escapes it.
                val (create, createFlow) = flow("_ZN9LuaPlayer10luaReadGuiEP9lua_State")
                val createValues = ConstructorValues(createFlow, emptyMap(), allocations = allocations(create, createFlow))
                val playerGui = createFlow.instructions.mapNotNull { instruction ->
                    if (instruction.offset !in createFlow.reachable || instruction.operation != Operation.MOV)
                        return@mapNotNull null
                    val destination = instruction.destination as? Memory ?: return@mapNotNull null
                    val source = instruction.source as? Register ?: return@mapNotNull null
                    if (destination.width != 8 || destination.relative || destination.index != null || source.width != 8)
                        return@mapNotNull null
                    val allocation = destination.base?.let { createValues.register(instruction.offset, it) }
                        as? ConstructorValues.Allocation ?: return@mapNotNull null
                    if (allocation.size != wrapperSize || allocation.adjustment + destination.displacement != wrapperGui)
                        return@mapNotNull null
                    val gui = createValues.register(instruction.offset, source.number) as? ConstructorValues.Load
                        ?: return@mapNotNull null
                    val player = gui.base as? ConstructorValues.Load ?: return@mapNotNull null
                    if (player.base != ConstructorValues.Argument(7) || player.member != playerMember)
                        return@mapNotNull null
                    gui.member.also { require(it in 0..playerSize - 8) }
                }.distinct().single()

                val (front, frontFlow) = flow("_ZN16CustomGuiElement12bringToFrontEv")
                val frontOrigins = OriginalPointerOrigins(frontFlow, front.address)
                val widget = calls(front, frontFlow, widgetFront).map { call ->
                    val source = frontOrigins.register(call.offset, 7) as? OriginalPointerOrigins.Load
                        ?: error("Script widget is not an original member")
                    require(source.base == OriginalPointerOrigins.Argument(7) && source.member in 0..elementSize - 8)
                    source.member
                }.distinct().single()

                val (build, buildFlow) = flow("_ZN16CustomGuiElement22buildWidgetRecursivelyEv")
                val buildOrigins = OriginalPointerOrigins(buildFlow, build.address)
                // The typed Widget*/CustomGuiElement* map's erase argument identifies its root.
                // Connect that root to the same Player member read by Lua, then to registration.
                val (erase, eraseFlow) = flow(ERASE)
                val (destroy, destroyFlow) = flow("_ZN6PlayerD2Ev")
                val destroyOrigins = OriginalPointerOrigins(destroyFlow, destroy.address)
                val typedHead = calls(destroy, destroyFlow, erase).map { call ->
                    GuiRegistryProof.registryHead(GuiRegistryProof.pointer(destroyFlow, destroyOrigins, call.offset, 7), playerGui, playerSize)
                }.distinct().single()
                val headers = calls(build, buildFlow, balance).mapNotNull { call ->
                    runCatching { GuiRegistryProof.header(GuiRegistryProof.pointer(buildFlow, buildOrigins, call.offset, 1), elementSize) }
                        .getOrNull()?.takeIf { it.second == typedHead }
                }.distinct().single()
                val (owner, head) = headers
                val count = head + 32
                require(buildFlow.instructions.any { instruction ->
                    val increment = instruction.operation == Operation.INC ||
                        instruction.operation == Operation.ADD && instruction.source == Immediate(1)
                    if (instruction.offset !in buildFlow.reachable || !increment) return@any false
                    val field = instruction.destination as? Memory ?: return@any false
                    if (field.width != 8 || field.relative || field.index != null || field.displacement != count)
                        return@any false
                    val base = field.base?.let { GuiRegistryProof.pointer(buildFlow, buildOrigins, instruction.offset, it) }
                        as? OriginalPointerOrigins.Load ?: return@any false
                    base.base == OriginalPointerOrigins.Argument(7) && base.member == owner
                }) { "Script GUI registration does not update its connected SDK tree count" }

                // Verify the selected typed map's SDK node extent and child-member widths.
                val eraseValues = ScalarExpression(eraseFlow)
                val sizes = calls(erase, eraseFlow, deletion).map { call ->
                    GuiRegistryProof.constant(eraseValues.before(call.offset, Register(6, 4)))
                }.distinct()
                require(sizes == listOf(48L)) { "Typed registry node deletion differs from its SDK ABI" }
                require(eraseFlow.instructions.filter { it.operation == Operation.MOV && it.source is Memory }
                    .map { it.source as Memory }.filter { !it.relative && it.index == null && it.width == 8 }
                    .map { it.displacement }.toSet().containsAll(setOf(16L, 24L)))

                val (readIndex, indexFlow) = flow("_ZN13LuaGuiElement12luaReadIndexEP9lua_State")
                val index = GuiRegistryProof.index(indexFlow, readIndex.address, elementSize)
                val (_, setFlow) = flow("_ZN16CustomGuiElement19setIndexRecursivelyEP9CustomGui")
                val setOrigins = OriginalPointerOrigins(setFlow)
                require(setFlow.instructions.any { instruction ->
                    val field = instruction.destination as? Memory
                    instruction.offset in setFlow.reachable && instruction.operation == Operation.MOV &&
                        field != null && field.width == 4 && !field.relative && field.index == null &&
                        field.displacement == index && field.base?.let { setOrigins.register(instruction.offset, it) } ==
                        OriginalPointerOrigins.Argument(7) && instruction.source is Register && instruction.source.width == 4
                }) { "GUI index registration and the Lua index reader disagree" }

                // These are lower extents established by typed game accesses, not guessed complete-object sizes.
                GuiRegistryLayout(playerSize, playerGui, maxOf(guiPlayer + 8, head + 40), head, count,
                    elementSize, widget, owner, index, ElfEvidence(emptyList(), emptyList(), emptyMap(), emptyMap()))
            }
            return fields.copy(evidence = ElfEvidence(functions, emptyList(), emptyMap(), emptyMap()))
        }
    }
}

internal object GuiRegistryProof {
    // A unique preserved register definition remains valid across a loop that does not replace it.
    // Loads at different sites stay distinct; unknown calls still clobber every volatile register.
    fun pointer(flow: X64ControlFlow, origins: OriginalPointerOrigins, site: Long, register: Int): OriginalPointerOrigins.Value {
        val definitions = ScalarExpression(flow)
        val active = mutableSetOf<Pair<Long, Int>>()
        var work = 0
        fun adjusted(value: OriginalPointerOrigins.Value, amount: Long): OriginalPointerOrigins.Value = when {
            amount == 0L -> value
            value is OriginalPointerOrigins.Argument -> value.copy(adjustment = value.adjustment + amount)
            value is OriginalPointerOrigins.Adjusted -> value.copy(amount = value.amount + amount)
            else -> OriginalPointerOrigins.Adjusted(value, amount)
        }
        fun resolve(point: Long, number: Int): OriginalPointerOrigins.Value {
            origins.register(point, number)?.let { return it }
            require(++work <= 256 && active.add(point to number)) { "GUI pointer definitions are cyclic or exceed bounds" }
            try {
                return definitions.definitions(point, number).map { definition ->
                    val destination = definition.destination as? Register ?: error("GUI pointer has no register definition")
                    require(destination.width == 8 && destination.number == number)
                    when (definition.operation) {
                        Operation.MOV -> when (val source = definition.source) {
                            is Register -> {
                                require(source.width == 8)
                                resolve(definition.offset, source.number)
                            }
                            is Memory -> {
                                require(source.width == 8 && !source.relative && source.index == null)
                                OriginalPointerOrigins.Load(resolve(definition.offset, checkNotNull(source.base)), source.displacement, definition.offset)
                            }
                            else -> error("GUI pointer is not an original member or register copy")
                        }
                        Operation.LEA -> {
                            val source = definition.source as? Memory ?: error("GUI pointer address is unavailable")
                            require(!source.relative && source.index == null)
                            adjusted(resolve(definition.offset, checkNotNull(source.base)), source.displacement)
                        }
                        Operation.ADD, Operation.SUB -> {
                            val amount = (definition.source as? Immediate)?.value ?: error("GUI pointer adjustment is not constant")
                            require(amount in -65536..65536)
                            adjusted(resolve(definition.offset, number), if (definition.operation == Operation.ADD) amount else -amount)
                        }
                        else -> error("GUI pointer definition is not a preserved pointer")
                    }
                }.distinct().singleOrNull() ?: error("GUI pointer has ambiguous original definitions")
            } finally {
                active.remove(point to number)
            }
        }
        return resolve(site, register)
    }

    fun constant(value: ScalarExpression.Value): Long {
        require(ScalarExpression.inputs(value).isEmpty()) { "Registry extent depends on runtime input" }
        return ScalarExpression.evaluate(value) { error("Registry extent is not constant") }
    }

    fun receiverExtent(flow: X64ControlFlow): Long {
        val origins = OriginalPointerOrigins(flow)
        return flow.instructions.flatMap { instruction ->
            if (instruction.offset !in flow.reachable || instruction.operation == Operation.LEA)
                return@flatMap emptyList()
            listOfNotNull(instruction.destination as? Memory, instruction.source as? Memory).mapNotNull { field ->
                if (field.relative || field.index != null || field.width !in listOf(1, 2, 4, 8, 16) ||
                    field.displacement !in 0..16 * 1024 * 1024 - field.width) return@mapNotNull null
                val owner = field.base?.let { runCatching { origins.register(instruction.offset, it) }.getOrNull() }
                (field.displacement + field.width).takeIf { owner == OriginalPointerOrigins.Argument(7) }
            }
        }.maxOrNull()?.also { require(it > 8) } ?: error("No typed original-receiver extent")
    }

    fun registryHead(value: OriginalPointerOrigins.Value?, playerGui: Long, playerSize: Long): Long {
        require(playerGui in 0..playerSize - 8)
        val root = value as? OriginalPointerOrigins.Load ?: error("Typed GUI map has no original root load")
        val gui = root.base as? OriginalPointerOrigins.Load ?: error("Typed GUI map has no Player owner")
        require(gui.base == OriginalPointerOrigins.Argument(7) && gui.member == playerGui)
        require(root.member in 8..16 * 1024 * 1024 - 32 && root.member % 8 == 0L)
        return root.member - 8 // libstdc++'s embedded header stores its root after the color/alignment word.
    }

    fun header(value: OriginalPointerOrigins.Value?, elementSize: Long): Pair<Long, Long> {
        val header = value as? OriginalPointerOrigins.Adjusted ?: error("Registry header has no member address")
        val owner = header.base as? OriginalPointerOrigins.Load ?: error("Registry header has no script GUI owner")
        require(owner.base == OriginalPointerOrigins.Argument(7) && owner.member in 0..elementSize - 8)
        require(header.amount in 0..16 * 1024 * 1024 - 40 && header.amount % 8 == 0L)
        return owner.member to header.amount
    }

    fun index(flow: X64ControlFlow, address: Long, elementSize: Long): Long {
        val fields = flow.instructions.filter {
            it.offset in flow.reachable && it.operation == Operation.INT_TO_DOUBLE
        }.mapNotNull { conversion ->
            runCatching { indexField(flow, address, elementSize, conversion) }.getOrNull()
        }.distinct()
        return fields.singleOrNull() ?: error("No unambiguous unsigned GUI element index conversion")
    }

    private fun indexField(flow: X64ControlFlow, address: Long, elementSize: Long, conversion: Instruction): Long {
        var register = conversion.source as? Register ?: error("GUI index conversion has no integer source")
        var site = conversion.offset
        val definitions = ScalarExpression(flow)
        val origins = OriginalPointerOrigins(flow, address)
        val visited = mutableSetOf<Pair<Long, Int>>()
        while (visited.add(site to register.number)) {
            val definition = definitions.definition(site, register.number)
            require(definition.operation == Operation.MOV)
            val source = definition.source
            if (source is Register) {
                require(source.width in listOf(4, 8))
                register = source
                site = definition.offset
                continue
            }
            val field = source as? Memory ?: error("GUI index is not an existing scalar member")
            val destination = definition.destination as? Register ?: error("GUI index has no zero-extended load")
            require(destination.width == 4 && field.width == 4 && !field.relative && field.index == null &&
                    field.displacement in 0..elementSize - 4)
            val owner = field.base?.let { origins.register(definition.offset, it) } as? OriginalPointerOrigins.Load
                ?: error("GUI index has no LuaGuiElement target")
            require(owner.base == OriginalPointerOrigins.Argument(7) && owner.member >= 0)
            return field.displacement
        }
        error("Cyclic GUI index definitions")
    }
}
