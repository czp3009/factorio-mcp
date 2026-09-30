package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.*
import com.hiczp.factorio.mcp.X64Instructions.*

/** Verifies double argument placement when the independently decoded capacity guard permits a push. */
internal object SysVLuaNumberPush {
    fun verify(image: ElfImage, stack: LuaStackLayout, stateSize: Long) {
        val entry = image.symbol("lua_pushnumber")
        EhFrames(image).function(entry)
        analyze(image.functionBytes(entry, 1024), entry.address, stack, stateSize)
    }

    fun analyze(bytes: BinaryView, address: Long, stack: LuaStackLayout, stateSize: Long) {
        val guard = SysVLuaCapacity.guard(bytes, stack, stateSize)
        // Runtime must reserve more than one TValue before calling; no growth callback is admitted by this proof.
        val flow = SysVReceiverFlow(bytes, address, stateSize, selectedEdges = mapOf(guard.branch to guard.available))
        require(flow.instructions.filter {
            it.offset in flow.reachable && it.operation in listOf(
                Operation.JMP,
                Operation.JCC
            )
        }
            .all { (it.destination as? Immediate)?.value?.let { target -> target in 0 until bytes.size } == true }) {
            "Lua number push branches outside its function"
        }
        val stores = flow.instructions.filter {
            it.offset in flow.reachable && it.operation == Operation.SCALAR_MOV &&
                    it.destination is Memory
        }
        val store = stores.single()
        val destination = store.destination as Memory
        require(
            store.source is Register && store.source.width == 8 && store.source.number in 16..31 &&
                    !destination.relative && destination.index == null && destination.width == 8 &&
                    destination.displacement in 0..stack.valueSize - 8
        )
        val registers = flow.before(store.offset)
        require(registers[store.source.number] == Original(16)) { "Lua number push does not use its original XMM0 double" }
        val pointer =
            destination.base?.let { registers[it] } as? Pointer ?: error("Number destination is not a state member")
        require(pointer.base == Receiver() && pointer.offset == stack.top) { "Number destination differs from the verified stack top" }
        val after = store.offset + store.size
        val returns = flow.instructions.filter { it.offset in flow.reachable && it.operation == Operation.RET }
        require(returns.isNotEmpty() && returns.all { flow.requiresEdge(it.offset, store.offset, after) }) {
            "Lua number push can return without storing the requested value"
        }
    }
}
