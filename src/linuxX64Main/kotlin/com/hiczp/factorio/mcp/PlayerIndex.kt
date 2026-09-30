package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.*
import com.hiczp.factorio.mcp.X64Instructions.*

/** Native unsigned player index whose one-based value is stored by LuaPlayer's index reader. */
internal data class PlayerIndex(val offset: Long, val width: Int) {
    companion object {
        fun resolve(image: ElfImage, playerSize: Long, state: LuaStateLayout): PlayerIndex {
            val wrapperSize = SysVObjectSize.resolve(image, "9LuaPlayer")
            val member = SysVArgumentMember.resolve(image, "_ZN9LuaPlayerC2EP6PlayerP9lua_State", wrapperSize)
            val entry = image.symbol("_ZN9LuaPlayer12luaReadIndexEP9lua_State")
            require(entry.size in 1..1024)
            EhFrames(image).function(entry)
            return analyze(
                image.functionBytes(entry, 1024), entry.address, wrapperSize, member, playerSize,
                state.stack, state.stackEnd, state.allocation.globalOffset
            )
        }

        fun analyze(
            bytes: BinaryView,
            address: Long,
            wrapperSize: Long,
            player: Long,
            playerSize: Long,
            stack: LuaStackLayout,
            stackEnd: Long,
            stateSize: Long,
        ): PlayerIndex {
            require(bytes.size in 1..1024 && player in 0..wrapperSize - 8 && playerSize in 8..(64 * 1024 * 1024))
            val instructions = X64Instructions(bytes).all()
            val conversion = instructions.single { it.operation == Operation.INT_TO_DOUBLE }
            val index = instructions.indexOf(conversion)
            require(index >= 2)
            val increment = instructions[index - 1]
            val load = instructions[index - 2]
            val register = load.destination as? Register ?: error("Player index has no integer register")
            val field = load.source as? Memory ?: error("Player index is not a field")
            require(
                load.operation == Operation.MOVZX && register.width == 4 && field.width in listOf(1, 2) &&
                        !field.relative && field.index == null && field.displacement in 0..playerSize - field.width &&
                        increment.operation == Operation.INC && increment.destination == register &&
                        conversion.source == register
            ) { "Player index is not an unsigned field converted after adding one" }
            val prefix = SysVReceiverFlow(bytes.slice(0, conversion.offset), address, wrapperSize)
            val owner = field.base?.let { prefix.before(load.offset)[it] } as? Pointer
                ?: error("Player index owner is unknown")
            require(
                owner.base == Receiver() && owner.offset == player &&
                        prefix.requiresEdge(increment.offset, load.offset, increment.offset)
            ) {
                "Player index does not come from the typed LuaPlayer member"
            }

            // Specialize only the independently checked sufficient-capacity edge of the inlined Lua push.
            val stateFlow = SysVReceiverFlow(bytes, address, stateSize, receiverRegister = 6)
            fun member(value: SysVReceiverFlow.Value, expected: Long) =
                value is Pointer && value.base == Receiver() && value.offset == expected

            val guards = instructions.indices.flatMap { position ->
                if (position < 1 || position + 1 >= instructions.size) return@flatMap emptyList()
                val compare = instructions[position]
                val branch = instructions[position + 1]
                if (compare.operation != Operation.CMP || (compare.source as? Immediate)?.value != stack.valueSize ||
                    branch.operation != Operation.JCC || branch.condition != 14
                ) return@flatMap emptyList()
                (maxOf(0, position - 4) until position).mapNotNull { previous ->
                    val subtract = instructions[previous]
                    if (subtract.operation != Operation.SUB || subtract.destination !is Register ||
                        subtract.destination.width != 8 || subtract.source !is Register || subtract.source.width != 8 ||
                        subtract.destination != compare.destination
                    ) return@mapNotNull null
                    if (!instructions.subList(previous + 1, position).all { between ->
                            between.operation == Operation.MOV && between.destination is Register &&
                                    between.destination.number != subtract.destination.number
                        }) return@mapNotNull null
                    listOf(subtract, compare, branch)
                }
            }.filter { (subtract, _, branch) ->
                val registers = stateFlow.before(subtract.offset)
                val target = (branch.destination as? Immediate)?.value
                member(registers[(subtract.destination as Register).number], stackEnd) &&
                        member(registers[(subtract.source as Register).number], stack.top) &&
                        target != null && target in branch.offset + branch.size until bytes.size
            }
            val guard =
                checkNotNull(guards.singleOrNull()) { "Lua index reader has no unambiguous capacity guard" }.last()
            val flow = SysVReceiverFlow(
                bytes, address, stateSize, receiverRegister = 6,
                selectedEdges = mapOf(guard.offset to guard.offset + guard.size)
            )
            val stores = instructions.filter {
                it.offset in flow.reachable && it.operation == Operation.SCALAR_MOV &&
                        it.destination is Memory && it.source is Register
            }.filter { store ->
                val destination = store.destination as Memory
                val registers = flow.before(store.offset)
                !destination.relative && destination.index == null && destination.width == 8 &&
                        destination.displacement in 0..stack.valueSize - 8 && destination.base?.let {
                    member(registers[it], stack.top)
                } == true && registers[(store.source as Register).number] == Converted(conversion.offset)
            }
            val store =
                checkNotNull(stores.singleOrNull()) { "Lua index conversion does not reach one stack value store" }
            require(
                flow.requiresEdge(store.offset, guard.offset, guard.offset + guard.size) &&
                        flow.requiresEdge(store.offset, conversion.offset, conversion.offset + conversion.size) &&
                        flow.requiresEdge(conversion.offset, increment.offset, conversion.offset)
            ) {
                "Lua index result bypasses its conversion or capacity guard"
            }
            return PlayerIndex(field.displacement, field.width)
        }
    }
}
