package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.ConstructorValues.*
import com.hiczp.factorio.mcp.X64Instructions.*

/** Native reset groups; resolve also verifies reciprocal links. Count semantics require separate evidence. */
internal data class ChatConsoleLists(val lists: List<ListFields>) {
    data class ListFields(val sentinel: Long, val next: Long, val previous: Long, val count: Long)

    companion object {
        fun resolve(image: ElfImage, console: Long, node: NativeListNodeLayout): ChatConsoleLists {
            val wrapperSize = SysVObjectSize.resolve(image, "9LuaPlayer")
            val player = SysVArgumentMember.resolve(image, "_ZN9LuaPlayerC2EP6PlayerP9lua_State", wrapperSize)
            val function = image.symbol("_ZN9LuaPlayer15luaClearConsoleEP9lua_State")
            val lists = analyze(X64ControlFlow.resolve(image, function), player, console, node)
            val previous = NativeListLinks.resolve(image, node)
            require(lists.lists.all { it.previous == it.sentinel + previous }) {
                "Console reset links disagree with native reciprocal unlinking"
            }
            return lists
        }

        fun analyze(flow: X64ControlFlow, player: Long, console: Long, node: NativeListNodeLayout): ChatConsoleLists {
            require(player in 0..4088 && console in 8..4088 && player % 8 == 0L && console % 8 == 0L)
            require(node.next in 0..node.value - 8)
            val values = ConstructorValues(flow, emptyMap())
            fun isConsole(value: ConstructorValues.Value?): Boolean {
                val output = value as? Load ?: return false
                val owner = output.base as? Load ?: return false
                return output.member == console && owner.member == player && owner.base == Argument(7)
            }

            fun member(site: Long, operand: Memory): Long? {
                if (operand.width != 8 || operand.relative || operand.index != null || operand.base == null ||
                    !isConsole(values.register(site, operand.base))
                ) return null
                return operand.displacement.also { require(it in 0..4088 && it % 8 == 0L) }
            }

            fun sentinel(site: Long, operand: Register): Long? {
                if (operand.width != 8) return null
                return when (val value = values.register(site, operand.number)) {
                    is Adjusted -> value.amount.takeIf { isConsole(value.base) }
                    else -> 0L.takeIf { isConsole(value) }
                }
            }

            val groups = flow.instructions.windowed(3).mapNotNull { group ->
                val count = group.last()
                if (count.offset !in flow.reachable || count.operation != Operation.MOV || count.source != Immediate(0))
                    return@mapNotNull null
                val countMemory = count.destination as? Memory ?: return@mapNotNull null
                val countField = member(count.offset, countMemory) ?: return@mapNotNull null
                require(group.zipWithNext().all { (first, second) ->
                    flow.predecessors[second.offset] == setOf(first.offset)
                }) { "Console reset group has an alternate entry" }
                val links = group.take(2).map { instruction ->
                    require(instruction.operation == Operation.MOV)
                    val memory = instruction.destination as? Memory ?: error("Console reset lacks a pointer store")
                    val source = instruction.source as? Register ?: error("Console reset lacks a sentinel reference")
                    val field = member(instruction.offset, memory) ?: error("Console reset changes its owner")
                    val head = sentinel(instruction.offset, source) ?: error("Console reset uses an unrelated sentinel")
                    require(head in 0..4088 && head % 8 == 0L)
                    field to head
                }
                val head =
                    links.map { it.second }.distinct().singleOrNull() ?: error("Console reset uses different sentinels")
                val next = head + node.next
                require(links.count { it.first == next } == 1 && links.map { it.first }.distinct().size == 2)
                val previous = links.single { it.first != next }.first
                require(countField !in listOf(next, previous))
                ListFields(head, next, previous, countField)
            }
            require(groups.size == 2 && groups.map { it.sentinel }.distinct().size == 2)
            val fields = groups.flatMap { listOf(it.next, it.previous, it.count) }
            require(fields.distinct().size == fields.size) { "Console lists overlap" }
            return ChatConsoleLists(groups.sortedBy { it.sentinel })
        }
    }
}
