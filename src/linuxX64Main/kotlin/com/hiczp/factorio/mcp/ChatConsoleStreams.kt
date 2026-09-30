package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.ConstructorValues.Argument
import com.hiczp.factorio.mcp.ConstructorValues.Load
import com.hiczp.factorio.mcp.SysVArgumentFlow.Reference
import com.hiczp.factorio.mcp.X64Instructions.*

/** Storage identities traced from the two named submission paths to native list insertion. */
internal data class ChatConsoleStreams(val gameState: Long, val local: Long) {
    data class Selection(val site: Long, val setting: Long, val zero: Long, val nonzero: Long)

    companion object {
        fun resolve(image: ElfImage, lists: ChatConsoleLists, node: NativeListNodeLayout): ChatConsoleStreams {
            val add =
                image.symbol("_ZN13OutputConsole3addERK15LocalisedStringPK6PlayerRK13PrintSettingsOSt6vectorI25SavedSpecialItemReferenceSaISA_EE")
            val flow = X64ControlFlow.resolve(image, add)
            val selection = selection(flow, lists)
            val hook = image.symbol("_ZNSt8__detail15_List_node_base7_M_hookEPS0_")
            EhFrames(image).function(hook)
            val gameState = wrapper(
                image, "_ZN13OutputConsole26addNotificationInGameStateERK15LocalisedStringPK6Player5Color",
                add, selection.setting
            )
            val local = wrapper(
                image, "_ZN13OutputConsole14addNoGameStateERK15LocalisedStringPK6Player5Color",
                add, selection.setting
            )
            require(gameState != local) { "Named console streams select the same storage" }
            fun selected(value: Int): Long = if (value == 0) selection.zero else selection.nonzero
            for (value in listOf(gameState, local))
                verifyInsertion(flow, selection, value, hook.address - add.address, node.next)
            return ChatConsoleStreams(selected(gameState), selected(local))
        }

        fun selection(flow: X64ControlFlow, lists: ChatConsoleLists): Selection {
            val values = SysVArgumentFlow(flow)
            val sentinels = lists.lists.map { it.sentinel }.toSet()
            require(sentinels.size == 2)
            return flow.instructions.zipWithNext().mapNotNull { (compare, select) ->
                if (select.offset !in flow.reachable || select.operation != Operation.CMOV || select.condition !in listOf(
                        4,
                        5
                    )
                )
                    return@mapNotNull null
                val left = select.destination as? Register ?: return@mapNotNull null
                val right = select.source as? Register ?: return@mapNotNull null
                if (left.width != 8 || right.width != 8) return@mapNotNull null
                val first = values.register(select.offset, left.number) ?: return@mapNotNull null
                val second = values.register(select.offset, right.number) ?: return@mapNotNull null
                if (first.argument != 7 || second.argument != 7 || setOf(first.offset, second.offset) != sentinels)
                    return@mapNotNull null
                require(
                    compare.operation == Operation.CMP && compare.source == Immediate(0) &&
                            flow.predecessors[select.offset] == setOf(compare.offset)
                )
                val memory = compare.destination as? Memory ?: error("Console storage condition has no settings byte")
                require(
                    memory.width == 1 && !memory.relative && memory.index == null && memory.base != null &&
                            values.register(
                                compare.offset,
                                memory.base
                            ) == Reference(1) && memory.displacement in 0..255
                )
                Selection(
                    select.offset, memory.displacement,
                    if (select.condition == 4) second.offset else first.offset,
                    if (select.condition == 4) first.offset else second.offset
                )
            }.singleOrNull() ?: error("Console storage selection is absent or ambiguous")
        }

        private fun wrapper(image: ElfImage, name: String, add: ElfImage.Symbol, setting: Long): Int {
            val function = image.symbol(name)
            val flow = X64ControlFlow.resolve(image, function)
            val call = flow.instructions.single {
                it.operation == Operation.CALL &&
                        it.destination == Immediate(add.address - function.address) && it.offset in flow.reachable
            }
            val values = SysVArgumentFlow(flow)
            require(
                values.register(call.offset, 7) == Reference(7) && values.register(
                    call.offset,
                    6
                ) == Reference(6)
            ) {
                "Named console submission changes its original console or text"
            }
            val proof = LocalAggregate(flow).at(call.offset, setting.toInt() + 1, argument = 1)
            val field = proof.constants.singleOrNull { it.offset <= setting && it.offset + it.width > setting }
                ?: error("Named console submission has no constant storage selector")
            return ((field.value ushr ((setting - field.offset).toInt() * 8)) and 255).toInt().also {
                require(it in 0..1)
            }
        }

        fun verifyInsertion(flow: X64ControlFlow, selection: Selection, value: Int, hook: Long, next: Long) {
            require(value in 0..1 && next in 0..4088)
            val select = flow.body.getValue(selection.site)
            require(select.operation == Operation.CMOV && select.condition in listOf(4, 5))
            val specialized = X64ControlFlow(flow.instructions.map {
                if (it.offset == selection.site) it.copy(
                    operation = Operation.MOV, condition = null,
                    source = if ((value == 0) == (select.condition == 4)) it.source else it.destination
                ) else it
            })
            val call = specialized.instructions.single {
                it.operation == Operation.CALL &&
                        it.destination == Immediate(hook) && it.offset in specialized.reachable
            }
            val values = ConstructorValues(specialized.reaching(call.offset), emptyMap())
            val insertion =
                values.register(call.offset, 6) as? Load ?: error("Console insertion has no original list link")
            val sentinel = if (value == 0) selection.zero else selection.nonzero
            require(insertion.base == Argument(7, sentinel) && insertion.member == next) {
                "Console insertion does not use the selected list's next link"
            }
        }
    }
}
