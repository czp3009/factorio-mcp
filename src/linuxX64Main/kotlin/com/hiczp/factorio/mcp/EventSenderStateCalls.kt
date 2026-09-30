package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.*
import com.hiczp.factorio.mcp.X64Instructions.*

/** Native sender argument provenance and call ordering, including the restored InputState tail dispatch.
 * These proofs do not establish callback lifetimes, complete event payload use or a safe invocation phase.
 */
internal object EventSenderStateCalls {
    data class Proof(val updates: List<Long>, val postUpdate: Long)
    data class SourceDispatch(val gameSource: Long, val event: Long, val evaluation: Long)
    data class GuiDispatch(val handlerMember: Long, val event: Long, val logic: Long)
    data class Route(val branch: Long, val zero: Long, val nonzero: Long)
    enum class UpdateOrder { BeforeSource, AfterEvaluation }

    /** Select the native state-update phase for each established event kind, for both source results.
     * Calls retain the game's const Event contract; this proves ordering, not callback safety or payload coverage.
     */
    fun updateOrder(
        flow: X64ControlFlow, state: Proof, source: SourceDispatch, gui: GuiDispatch,
        header: EventHeader, kinds: Set<Long>
    ): Map<Long, UpdateOrder> {
        require(
            header.extent in 4..4096 && header.type in 0..header.extent - 4L &&
                    kinds.isNotEmpty() && kinds.size <= 256 && kinds.all { it in 0..0xffffffffL })
        require(state.updates.isNotEmpty() && state.updates.distinct().size == state.updates.size)
        val sites = state.updates + listOf(source.event, source.evaluation, gui.event, gui.logic, state.postUpdate)
        require(sites.distinct().size == sites.size && sites.all { it in flow.reachable })
        require(state.updates.all { flow.body.getValue(it).operation == Operation.CALL } &&
                flow.body.getValue(state.postUpdate).operation == Operation.JMP &&
                flow.successors.getValue(state.postUpdate).isEmpty())
        val route = route(flow, source, gui)
        val expressions = ScalarExpression(flow, mapOf(6 to header.extent.toLong()))
        val branches = mutableMapOf<Long, ScalarExpression.Value>()
        fun selected(kind: Long, consumed: Boolean): UpdateOrder {
            var site = 0L
            val visited = mutableSetOf<Long>()
            val calls = mutableListOf<Long>()
            while (site != state.postUpdate) {
                require(visited.add(site)) { "Sender event path cycles" }
                val instruction = flow.body.getValue(site)
                if (instruction.operation == Operation.CALL) calls += site
                val next = flow.successors.getValue(site)
                require(next.isNotEmpty()) { "Sender event path bypasses post-update" }
                site = if (instruction.operation == Operation.JCC) {
                    if (site == route.branch) {
                        if (consumed) route.nonzero else route.zero
                    } else {
                        val expression = branches.getOrPut(site) {
                            expressions.branch(site).also { value ->
                                require(ScalarExpression.inputs(value).all {
                                    it.field == SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, header.type), 4)
                                }) { "Sender state ordering depends on an unproven event field" }
                            }
                        }
                        if (ScalarExpression.evaluate(expression) { kind } != 0L)
                            (instruction.destination as Immediate).value else site + instruction.size
                    }.also { require(it in next) }
                } else next.single()
            }
            val update = calls.filter { it in state.updates }.singleOrNull()
                ?: error("Sender event path does not update input state exactly once")
            val dispatch = listOf(source.event) +
                    (if (consumed) emptyList() else listOf(gui.event, gui.logic)) + source.evaluation
            return when (calls) {
                listOf(update) + dispatch -> UpdateOrder.BeforeSource
                dispatch + update -> UpdateOrder.AfterEvaluation
                else -> error("Sender event path has unsupported calls or state-update ordering")
            }
        }
        return kinds.associateWith { kind ->
            selected(kind, false).also { order ->
                require(selected(kind, true) == order) { "Source result changes native state-update ordering" }
            }
        }
    }

    /** AL is the unchanged source result; zero routes through both GUI calls, nonzero skips both. */
    fun route(flow: X64ControlFlow, source: SourceDispatch, gui: GuiDispatch): Route {
        val call = flow.body.getValue(source.event)
        require(
            call.operation == Operation.CALL && call.offset in flow.reachable &&
                    flow.body[source.evaluation]?.operation == Operation.CALL &&
                    flow.body[gui.event]?.operation == Operation.CALL && flow.body[gui.logic]?.operation == Operation.CALL
        )
        val test = flow.body.getValue(call.offset + call.size)
        require(
            test.operation == Operation.TEST && test.destination == Register(0, 1) &&
                    test.source == test.destination && flow.predecessors[test.offset] == setOf(call.offset)
        ) {
            "Sender does not test the original byte return from its source"
        }
        val branch = flow.body.getValue(test.offset + test.size)
        require(
            branch.operation == Operation.JCC && branch.condition in listOf(4, 5) &&
                    flow.predecessors[branch.offset] == setOf(test.offset)
        )
        val target = (branch.destination as Immediate).value
        val fallthrough = branch.offset + branch.size
        fun path(start: Long, expected: List<Long>) {
            val pending = ArrayDeque<Pair<Long, Int>>()
            val visited = mutableSetOf<Pair<Long, Int>>()
            pending.add(start to 0)
            while (pending.isNotEmpty()) {
                val entry = pending.removeFirst()
                if (!visited.add(entry)) continue
                val (site, consumed) = entry
                if (site == source.evaluation) {
                    require(consumed == expected.size) { "Sender bypasses part of its GUI fallback" }
                    continue
                }
                val instruction = flow.body.getValue(site)
                var count = consumed
                if (instruction.operation == Operation.CALL) {
                    require(expected.getOrNull(count) == site) { "Sender route contains an unexpected or repeated call" }
                    ++count
                }
                val next = flow.successors.getValue(site)
                require(next.isNotEmpty() && next.all { it > site }) { "Sender route exits early or cycles" }
                next.forEach { pending.add(it to count) }
            }
        }

        val zero = if (branch.condition == 4) target else fallthrough
        val nonzero = if (branch.condition == 5) target else fallthrough
        path(zero, listOf(gui.event, gui.logic))
        path(nonzero, emptyList())
        return Route(branch.offset, zero, nonzero)
    }

    fun guiDispatch(image: ElfImage, mapSize: Long): GuiDispatch {
        val sender = image.symbol("_ZN16InputEventSender9sendEventER3MapRK5Event")
        val handler = image.symbol("_ZN16InputHandlerAgui12processEventERK5Event")
        for (function in listOf(sender, handler)) EhFrames(image).function(function)
        val instance = image.symbol("_ZN4agui3Gui8instanceE")
        require(instance.type == 1 && instance.size == 8L && instance.address % 8 == 0L)
        val logic = ItaniumVtable.resolve(image, "_ZTVN4agui3GuiE").method(image, "_ZN4agui3Gui5logicEb")
        val size = SysVObjectSize.resolve(image, "4agui3Gui")
        return guiDispatch(
            image.functionBytes(sender, 8192), sender.address, mapSize, instance.address, size,
            handler.address, logic.slot
        )
    }

    /** Native GUI fallback arguments and ordering; callbacks still require independent live lifetime checks. */
    fun guiDispatch(
        bytes: BinaryView, address: Long, mapSize: Long, instance: Long, guiSize: Long,
        handler: Long, logicSlot: Int
    ): GuiDispatch {
        require(
            mapSize in 8..(64 * 1024 * 1024) && guiSize in 8..(64 * 1024 * 1024) &&
                    instance > 0 && handler > 0 && logicSlot in 0..8191
        )
        val flow = X64ControlFlow(X64Instructions(bytes).all(2048))
        val values = SysVReceiverFlow(bytes, address, mapSize, mapOf(instance to guiSize))
        val event = flow.instructions.single {
            it.offset in flow.reachable && it.operation == Operation.CALL &&
                    it.destination == Immediate(handler - address)
        }
        val arguments = values.call(event.offset)
        require(arguments[6] == Original(6)) { "GUI handler does not receive the original Event" }
        val member = GlobalPointerLoad(flow, address, instance, guiSize).at(event.offset, 7)
        val logic = flow.instructions.single {
            it.offset in flow.reachable && it.operation == Operation.CALL &&
                    (it.destination as? Memory)?.let { target ->
                        target.width == 8 && !target.relative &&
                                target.index == null && target.displacement == logicSlot * 8L
                    } == true
        }
        val registers = values.call(logic.offset)
        val gui = registers[7] as? Global ?: error("GUI logic does not use the singleton receiver")
        val target = logic.destination as Memory
        val table = target.base?.let { registers[it] } as? Pointer
        require(gui.address == instance && table?.base == gui && table.offset == 0L && registers[6] == Constant(0)) {
            "GUI logic receiver, primary table or boolean argument differs"
        }
        require(values.requiresEdge(logic.offset, event.offset, event.offset + event.size)) {
            "Sender can enter GUI logic without GUI event dispatch"
        }
        val pending = ArrayDeque<Long>()
        val visited = mutableSetOf<Long>()
        pending.add(event.offset + event.size)
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (site == logic.offset || !visited.add(site)) continue
            val next = flow.successors.getValue(site)
            require(next.isNotEmpty()) { "GUI event path can exit without GUI logic" }
            pending.addAll(next)
        }
        return GuiDispatch(member, event.offset, logic.offset)
    }

    fun sourceDispatch(image: ElfImage, source: InputSourceLayout): SourceDispatch {
        val sender = image.symbol("_ZN16InputEventSender9sendEventER3MapRK5Event")
        EhFrames(image).function(sender)
        val event = ItaniumVtable.resolve(image, "_ZTV17PlayerInputSource")
            .method(image, "_ZN17PlayerInputSource12processEventERK5Event")
        require(event.addressPoint == source.evaluation.addressPoint)
        return sourceDispatch(
            image.functionBytes(sender, 8192), sender.address, source.mapSize, source.mapGame,
            source.gameSize, event.slot, source.evaluation.slot
        )
    }

    /** Both native virtual calls use a source loaded from the original Map's established Game member.
     * Live admission must still match that source to the selected player's exact type and current context.
     */
    fun sourceDispatch(
        bytes: BinaryView, address: Long, mapSize: Long, mapGame: Long, gameSize: Long,
        eventSlot: Int, evaluationSlot: Int
    ): SourceDispatch {
        require(
            mapSize in 8..(64 * 1024 * 1024) && mapGame in 0..mapSize - 8 &&
                    gameSize in 8..(64 * 1024 * 1024) && eventSlot in 0..8191 && evaluationSlot in 0..8191 &&
                    eventSlot != evaluationSlot
        )
        val flow = X64ControlFlow(X64Instructions(bytes).all(2048))
        val values = SysVReceiverFlow(bytes, address, mapSize)
        fun selected(slot: Int): Pair<Long, Long> {
            val call = flow.instructions.filter {
                it.offset in flow.reachable && it.operation == Operation.CALL &&
                        (it.destination as? Memory)?.let { target ->
                            target.width == 8 && !target.relative &&
                                    target.index == null && target.displacement == slot * 8L
                        } == true
            }.single()
            val registers = values.call(call.offset)
            val receiver = registers[7] as? Pointer ?: error("Sender dispatch lacks a source receiver")
            val game = receiver.base as? Pointer ?: error("Sender source does not belong to its Map's Game")
            require(game.base == Receiver() && game.offset == mapGame && receiver.offset in 0..gameSize - 8)
            val target = call.destination as Memory
            val table = target.base?.let { registers[it] } as? Pointer
            require(table?.base == receiver && table.offset == 0L) { "Sender uses a different receiver's vtable" }
            if (slot == eventSlot) require(registers[6] == Original(6)) { "Source does not receive the original Event" }
            return call.offset to receiver.offset
        }

        val event = selected(eventSlot)
        val evaluation = selected(evaluationSlot)
        require(event.second == evaluation.second) { "Event and evaluation use different Game input members" }
        // Require event dispatch before evaluation and evaluation before every normal exit. This proves
        // ordering, not the ABI or semantics of intervening unchanged native calls.
        fun cannotReachWithout(target: Long?, required: Long) {
            val pending = ArrayDeque<Long>()
            val visited = mutableSetOf<Long>()
            pending.add(0)
            while (pending.isNotEmpty()) {
                val site = pending.removeFirst()
                if (site == required || !visited.add(site)) continue
                require(if (target != null) site != target else flow.successors.getValue(site).isNotEmpty()) {
                    "Sender bypasses required input dispatch"
                }
                pending.addAll(flow.successors.getValue(site))
            }
        }
        cannotReachWithout(evaluation.first, event.first)
        cannotReachWithout(null, evaluation.first)
        return SourceDispatch(event.second, event.first, evaluation.first)
    }

    fun resolve(image: ElfImage, state: InputStateLayout, mapSize: Long): Proof {
        val sender = image.symbol("_ZN16InputEventSender9sendEventER3MapRK5Event")
        val update = image.symbol("_ZN10InputState6updateERK5Event")
        val post = image.symbol("_ZN10InputState10postUpdateERK5Event")
        for (function in listOf(sender, update, post)) EhFrames(image).function(function)
        return inspect(image.functionBytes(sender, 8192), sender.address, update.address, post.address, state, mapSize)
    }

    fun inspect(
        bytes: BinaryView, address: Long, update: Long, post: Long,
        state: InputStateLayout, mapSize: Long
    ): Proof {
        require(mapSize in 8..(64 * 1024 * 1024) && update > 0 && post > 0 && update != post)
        val flow = X64ControlFlow(X64Instructions(bytes).all(2048))
        val values = SysVReceiverFlow(bytes, address, mapSize, mapOf(state.global to state.globalSize))
        val globals = GlobalPointerLoad(flow, address, state.global, state.globalSize)
        val updates = flow.instructions.filter {
            it.offset in flow.reachable && it.operation == Operation.CALL &&
                    it.destination == Immediate(update - address)
        }
        require(updates.isNotEmpty()) { "Sender does not update its native input state" }
        for (call in updates) {
            require(values.call(call.offset)[6] == Original(6) && globals.at(call.offset, 7) == state.member) {
                "Input update does not receive the original Event and established service"
            }
        }
        val exits = flow.reachable.filter { flow.successors.getValue(it).isEmpty() }
        val tail = exits.singleOrNull()?.let { flow.body.getValue(it) }
            ?: error("Sender has no unique normal post-update exit")
        require(tail.operation == Operation.JMP && tail.destination == Immediate(post - address)) {
            "Sender can exit without native post-update"
        }
        val restored = values.before(tail.offset)
        require(
            restored[6] == Original(6) && globals.at(tail.offset, 7) == state.member &&
                    restored[4] == Stack(0) && listOf(3, 5, 12, 13, 14, 15).all { restored[it] == Original(it) }) {
            "Post-update tail changes the Event, input service or caller frame"
        }
        return Proof(updates.map { it.offset }, tail.offset)
    }
}
