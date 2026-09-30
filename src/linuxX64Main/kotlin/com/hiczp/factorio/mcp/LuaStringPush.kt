package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Original
import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Proves the entry arguments at string construction, including paths that run the collector first. */
internal object SysVLuaStringPush {
    fun verify(image: ElfImage, stateSize: Long) {
        val entry = image.symbol("lua_pushlstring")
        val constructor = image.symbol("_Z12luaS_newlstrP9lua_StatePKcm")
        val collector = image.symbol("_Z14luaC_forcestepP9lua_State")
        val frames = EhFrames(image)
        for (symbol in listOf(entry, constructor, collector)) frames.function(symbol)
        analyze(image.functionBytes(entry, 8192), entry.address, constructor.address, collector.address, stateSize)
    }

    fun analyze(bytes: BinaryView, address: Long, constructor: Long, collector: Long, stateSize: Long) {
        require(constructor != collector && constructor >= 0 && collector >= 0)
        val flow = SysVReceiverFlow(bytes, address, stateSize)
        val instructions = flow.instructions
        for (instruction in instructions) if (instruction.offset in flow.reachable &&
            instruction.operation in listOf(Operation.JMP, Operation.JCC)
        ) {
            val destination = (instruction.destination as? Immediate)?.value
            require(destination != null && destination in 0 until bytes.size) { "String push branches outside its function" }
        }
        fun target(instruction: X64Instructions.Instruction): Long? =
            (instruction.destination as? Immediate)?.value?.let {
                require(it >= -address && it <= Long.MAX_VALUE - address)
                address + it
            }

        val call = instructions.single { it.operation == Operation.CALL && target(it) == constructor }
        val arguments = flow.call(call.offset)
        require(arguments[7] == Receiver() && arguments[6] == Original(6) && arguments[2] == Original(2)) {
            "String push does not forward state, bytes and size without truncation"
        }
        val after = call.offset + call.size
        val returns = instructions.filter { it.operation == Operation.RET && it.offset in flow.reachable }
        require(returns.isNotEmpty() && returns.all { flow.requiresEdge(it.offset, call.offset, after) }) {
            "String push can return without constructing the requested string"
        }
        // Find every call on any entry path to construction. Only a state-only collector may run first.
        val pending = ArrayDeque<Long>()
        val ancestors = mutableSetOf<Long>()
        pending.add(call.offset)
        while (pending.isNotEmpty()) {
            val position = pending.removeFirst()
            if (ancestors.add(position)) pending.addAll(flow.predecessors[position].orEmpty())
        }
        for (previous in instructions) if (previous.offset != call.offset && previous.offset in ancestors &&
            previous.operation == Operation.CALL
        ) {
            require(target(previous) == collector && flow.call(previous.offset)[7] == Receiver()) {
                "String push has an unverified call before string construction"
            }
        }
        // The return value is deliberately unused: optimization may remove the source-level string return.
    }
}
