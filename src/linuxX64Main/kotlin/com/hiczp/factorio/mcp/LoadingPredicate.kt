package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.OriginalPointerOrigins.Argument
import com.hiczp.factorio.mcp.OriginalPointerOrigins.Load
import com.hiczp.factorio.mcp.X64Instructions.*

/** The native GlobalContext loading predicate: state-vector any, followed by the selected multiplayer manager. */
internal data class LoadingPredicate(
    val manager: OwnedObjectSize,
    val begin: Long,
    val end: Long,
    val stateSlot: Int,
    val states: List<State>,
    val multiplayer: List<Manager>,
) {
    data class State(val table: ItaniumSubobjectVtable, val method: ItaniumVtable.Method)

    data class Guard(val zero: Long, val nonzero: Long, val branch: Long)

    data class Manager(val member: Long, val size: Long, val primary: ItaniumType,
        val table: ItaniumSubobjectVtable, val method: ItaniumVtable.Method)

    companion object {
        internal fun managerOrder(flow: X64ControlFlow, guards: List<Guard>, call: Long): List<Int> {
            require(guards.size in 1..4 && guards.map { it.branch }.distinct().size == guards.size)
            val order = guards.indices.sortedBy { index -> guards.count { it.branch != guards[index].branch &&
                flow.dominates(it.branch, guards[index].branch) } }
            val ordered = order.map { guards[it] }
            ordered.zipWithNext().forEach { (first, second) ->
                require(flow.dominates(first.branch, second.branch)) { "Manager selection has no unique native priority" }
            }
            ordered.forEachIndexed { index, guard ->
                val choices = ordered.take(index).associate { it.branch to it.zero }
                val selected = flow.following(choices + (guard.branch to guard.nonzero))
                require(call in selected.reachable && ordered.drop(index + 1).none { it.branch in selected.reachable }) {
                    "A nonnull manager does not bypass later selections"
                }
                val absent = flow.following(choices + (guard.branch to guard.zero))
                require(ordered.getOrNull(index + 1)?.branch?.let { it in absent.reachable } ?: (call !in absent.reachable)) {
                    "Null manager does not follow the next native selection"
                }
            }
            return order
        }

        fun resolve(image: ElfImage, global: Long, globalSize: Long): LoadingPredicate {
            val manager = OwnedMemberSize.resolve(image, "_ZN13GlobalContextD2Ev", "_ZN10AppManagerD2Ev", globalSize)
            val function = image.symbol("_ZN13GlobalContext8localiseB5cxx11ESt17basic_string_viewIcSt11char_traitsIcEESt16initializer_listIS3_E")
            val flow = X64ControlFlow.resolve(image, function)
            val instructions = flow.instructions.filter { it.offset in flow.reachable }
            // The abstract base's vtable can be eliminated. Its inherited qualified entry still
            // identifies the slot in matching concrete tables, whose ancestry is independently verified.
            val stateTypes = ItaniumClass.descendants(image, "15AppManagerState", 256)
            val stateTables = stateTypes.mapNotNull { type ->
                if (image.findSymbol("_ZTV$type") == null) return@mapNotNull null
                ItaniumSubobjectVtable.resolveInterface(image, type, "15AppManagerState")
            }
            require(stateTables.isNotEmpty())
            val qualified = "_ZNK15AppManagerState12isLoadingMapEv"
            val inherited = stateTables.mapNotNull { table -> runCatching { table.method(image, qualified) }.getOrNull() }
            val stateMethod = inherited.firstOrNull() ?: error("No matching table retains the qualified loading method")
            require(inherited.map { it.slot }.distinct().size == 1)
            BooleanLeafReturn.analyze(image.functionBytes(stateMethod.function, 256))
            val stateCall = instructions.filter { SysVVirtualCall.isCandidate(it, stateMethod.slot) }.single()
            val prefix = image.functionBytes(function, 32768).slice(0, stateCall.offset + stateCall.size)
            val prefixFlow = X64ControlFlow(X64Instructions(prefix).all(2048))
            val stack = checkNotNull(SysVLocalArgument(prefixFlow).registers(stateCall.offset)[4])
            require((stack + 8) % 16 == 0L)
            val first = OriginalPointerOrigins(prefixFlow, function.address)
            fun virtualReceiver(origins: OriginalPointerOrigins, call: Instruction, slot: Int): OriginalPointerOrigins.Value? {
                val table = when (val target = call.destination) {
                    is Memory -> {
                        require(target.width == 8 && !target.relative && target.index == null && target.displacement == slot * 8L)
                        target.base?.let { origins.register(call.offset, it) }
                    }
                    is Register -> {
                        val entry = origins.register(call.offset, target.number) as? Load ?: return null
                        require(target.width == 8 && entry.member == slot * 8L)
                        entry.base
                    }
                    else -> return null
                } as? Load ?: return null
                return table.base.takeIf { table.member == 0L && it == origins.register(call.offset, 7) }
            }
            val state = virtualReceiver(first, stateCall, stateMethod.slot) as? Load
                ?: error("Loading-state dispatch has no live original receiver")
            val cursor = state.base as? Load ?: error("Loading state is not a pointer-vector element")
            val owner = cursor.base as? Load ?: error("Loading vector has no owned AppManager")
            require(state.member == 0L && owner.base == Argument(6) && owner.member == manager.pointer)
            require(cursor.member in 0..manager.size - 8 && cursor.member % 8 == 0L)
            val origins = OriginalPointerOrigins(flow, function.address)
            val vectorReads = instructions.mapNotNull { instruction ->
                val source = instruction.source as? Memory ?: return@mapNotNull null
                val destination = instruction.destination as? Register ?: return@mapNotNull null
                if (instruction.operation != Operation.MOV || source.width != 8 || destination.width != 8 ||
                    source.relative || source.index != null) return@mapNotNull null
                val pointer = source.base?.let { origins.register(instruction.offset, it) } as? OriginalPointerOrigins.Load
                    ?: return@mapNotNull null
                if (pointer.base != OriginalPointerOrigins.Argument(6) || pointer.member != manager.pointer) return@mapNotNull null
                require(source.displacement in 0..manager.size - 8 && source.displacement % 8 == 0L)
                instruction.offset to PrivateValueCopies.Read(instruction.offset, InlineArgumentFields.Field(source.displacement, 8))
            }.toMap()
            require(vectorReads.size == 2) { "Loading vector has ambiguous native bounds" }
            val end = PointerIteration.analyze(flow, cursor.member, vectorReads)
            val flags = ScalarExpression(flow)
            fun guard(branch: Instruction): Guard {
                require(branch.operation == Operation.JCC && branch.condition in listOf(4, 5))
                val taken = (branch.destination as? Immediate)?.value ?: error("Indirect loading guard")
                val next = branch.offset + branch.size
                return if (branch.condition == 4) Guard(taken, next, branch.offset) else Guard(next, taken, branch.offset)
            }
            fun booleanGuard(call: Instruction): Guard {
                val branch = flow.instructions.filter { it.offset in flow.reachable && it.operation == Operation.JCC }
                    .single { candidate -> runCatching {
                        val test = flags.flagDefinition(candidate.offset)
                        test.operation == Operation.TEST && test.destination == Register(0, 1) && test.source == Register(0, 1) &&
                                flags.definition(test.offset, 0).offset == call.offset
                    }.getOrDefault(false) }
                return guard(branch)
            }
            val stateGuard = booleanGuard(stateCall)
            val managerLoads = instructions.mapNotNull { instruction ->
                val source = instruction.source as? Memory ?: return@mapNotNull null
                if (instruction.operation != Operation.MOV || source.width != 8 || source.relative || source.index != null)
                    return@mapNotNull null
                val base = source.base?.let { origins.register(instruction.offset, it) } as? OriginalPointerOrigins.Global
                    ?: return@mapNotNull null
                if (base.address != global) return@mapNotNull null
                require(source.displacement in 0..globalSize - 8 && source.displacement % 8 == 0L)
                val target = instruction.destination as? Register ?: error("Multiplayer pointer has no register")
                require(target.width == 8)
                val pointer = OriginalPointerOrigins.Load(base, source.displacement, instruction.offset)
                val branch = instructions.single { candidate -> candidate.operation == Operation.JCC && runCatching {
                    val test = flags.flagDefinition(candidate.offset)
                    test.operation == Operation.TEST && test.destination == test.source &&
                            (test.destination as? Register)?.let {
                                it.width == 8 && origins.register(test.offset, it.number) == pointer
                            } == true
                }.getOrDefault(false) }
                Triple(source.displacement, pointer, guard(branch))
            }
            require(managerLoads.size == 2 && managerLoads.map { it.first }.distinct().size == 2)
            val candidates = listOf("24ClientMultiplayerManager", "24ServerMultiplayerManager").map { type ->
                val size = SysVObjectSize.resolve(image, type)
                val table = ItaniumSubobjectVtable.resolve(image, type, "22MultiplayerManagerBase", size)
                val name = if (table.baseOffset == 0L) "_ZNK${type}12isLoadingMapEv"
                    else "_ZThn${table.baseOffset}_NK${type}12isLoadingMapEv"
                Manager(0, size, ItaniumType.resolve(image, type), table, table.method(image, name))
            }
            val slot = candidates.map { it.method.slot }.distinct().single()
            val networkCall = instructions.filter { SysVVirtualCall.isCandidate(it, slot) }.single()
            val networkGuard = booleanGuard(networkCall)
            require(networkGuard.nonzero == stateGuard.nonzero && networkGuard.zero != stateGuard.nonzero)
            val orderedManagers = managerOrder(flow, managerLoads.map { it.third }, networkCall.offset).map { managerLoads[it] }
            val result = orderedManagers.flatMapIndexed { index, (member, pointer, nullGuard) ->
                val choices = orderedManagers.take(index).associate { it.third.branch to it.third.zero } +
                        (nullGuard.branch to nullGuard.nonzero)
                val selectedOrigins = OriginalPointerOrigins(flow.following(choices), function.address)
                val receiver = selectedOrigins.register(networkCall.offset, 7)
                val adjustment = when (receiver) {
                    pointer -> 0L
                    is OriginalPointerOrigins.Adjusted -> {
                        require(receiver.base == pointer)
                        receiver.amount
                    }
                    else -> error("Multiplayer loading dispatch does not use the selected native manager")
                }
                val compatible = candidates.filter { it.table.baseOffset == adjustment }
                require(compatible.isNotEmpty()) { "Multiplayer receiver adjustment has no matching RTTI base" }
                require(virtualReceiver(selectedOrigins, networkCall, slot) == receiver) {
                    "Multiplayer loading dispatch does not use the selected object's own vtable"
                }
                compatible.map { it.copy(member = member) }
            }
            require(orderedManagers.last().third.zero == networkGuard.zero) { "Absent multiplayer state does not follow the native false path" }
            val appPointer = flow.body.getValue(owner.site)
            val lastTest = flags.flagDefinition(networkGuard.branch)
            val selected = flow.instructions.filter { it.offset in appPointer.offset..lastTest.offset }
            require(selected.all { it.operation in setOf(Operation.MOV, Operation.LEA, Operation.TEST, Operation.CMP,
                Operation.JMP, Operation.JCC, Operation.ADD, Operation.CALL, Operation.NOP, Operation.ENDBR) &&
                    !(it.destination is Memory && it.operation == Operation.MOV) }) {
                "Loading inline contains unsupported effects"
            }
            val methodNames = (stateTypes + "15AppManagerState").map { "_ZNK${it}12isLoadingMapEv" }.toSet()
            val methodAddresses = image.symbols().filter { it.name in methodNames }.map { it.address }.toSet()
            val states = stateTables.map { table ->
                val entry = table.entries.getOrNull(stateMethod.slot) ?: error("Loading slot exceeds a native state table")
                require(entry in methodAddresses) { "State table loading entry has no matching qualified method" }
                val native = image.function(entry)
                image.functionBytes(native, 1)
                EhFrames(image).function(native)
                State(table, ItaniumVtable.Method(table.addressPoint, stateMethod.slot, native))
            }
            return LoadingPredicate(manager, cursor.member, end, stateMethod.slot, states, result)
        }
    }
}
