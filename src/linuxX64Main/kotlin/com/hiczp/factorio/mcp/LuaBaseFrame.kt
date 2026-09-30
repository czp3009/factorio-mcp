package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.*
import com.hiczp.factorio.mcp.X64Instructions.*

/** An embedded CallInfo address published by state initialization, not a guessed sizeof(CallInfo). */
internal object SysVLuaBaseFrame {
    fun resolve(image: ElfImage, allocation: LuaAllocation, stack: LuaStackLayout, call: LuaProtectedCall): Long {
        val entry = image.symbol("_ZL9f_luaopenP9lua_StatePv")
        val next = image.symbol("_Z8luaH_newP9lua_State")
        val frames = EhFrames(image)
        frames.function(entry)
        frames.function(next)
        return analyze(image.functionBytes(entry, 8192), entry.address, next.address, allocation, stack, call.frameTop)
    }

    fun analyze(
        bytes: BinaryView, address: Long, nextCall: Long, allocation: LuaAllocation,
        stack: LuaStackLayout, frameTop: Long
    ): Long {
        require(bytes.size in 1..8192 && address >= 0 && address <= Long.MAX_VALUE - bytes.size && nextCall >= 0)
        val stateSize = allocation.globalOffset
        require(
            stateSize >= 8 && allocation.size >= stateSize + 8 && allocation.global in 0..stateSize - 8 &&
                    allocation.allocator in 0..allocation.size - stateSize - 8 &&
                    allocation.userdata in 0..allocation.size - stateSize - 8 &&
                    allocation.allocator != allocation.userdata && stack.callInfo in 0..stateSize - 8 &&
                    stack.function in 0..4096 && frameTop in 0..4096
        )
        require(frameTop + 8 <= stack.function || stack.function + 8 <= frameTop)
        val decoder = X64Instructions(bytes)
        val instructions = mutableListOf<X64Instructions.Instruction>()
        var end = 0L
        while (true) {
            require(end < bytes.size && end < 4096 && instructions.size < 512)
            val instruction = decoder.decode(end)
            instructions += instruction
            end += instruction.size
            val relative = (instruction.destination as? Immediate)?.value
            if (instruction.operation == Operation.CALL && relative != null && relative >= -address &&
                relative <= Long.MAX_VALUE - address && address + relative == nextCall
            ) break
        }
        val prefix = bytes.slice(0, end)
        val allocators = mutableSetOf<Long>()
        for (instruction in instructions.filter { it.operation == Operation.CALL && it.destination !is Immediate }) {
            val flow = SysVReceiverFlow(prefix, address, stateSize, heapResults = allocators)
            val registers = flow.call(instruction.offset)
            val target = instruction.destination as? Memory ?: error("Unsupported Lua initializer allocator dispatch")
            require(target.width == 8 && !target.relative && target.index == null && target.displacement == allocation.allocator)
            val global =
                target.base?.let { registers[it] } as? Pointer ?: error("Lua initializer lacks its global state")
            require(
                global.base == Receiver() && global.offset == allocation.global &&
                        registers[7] is Pointer && (registers[7] as Pointer).base == global &&
                        (registers[7] as Pointer).offset == allocation.userdata && registers[6] == Constant(0) && registers[2] == Constant(
                    0
                )
            ) {
                "Lua initializer does not use the verified allocator and original userdata"
            }
            val size = registers[1] as? Constant ?: error("Lua initial stack allocation has no fixed bound")
            require(size.value in stack.valueSize..(1024 * 1024) && size.value % stack.valueSize == 0L)
            allocators += instruction.offset
        }
        require(allocators.isNotEmpty())
        val flow = SysVReceiverFlow(prefix, address, stateSize, heapResults = allocators)
        val next = instructions.last()
        require(flow.call(next.offset)[7] == Receiver()) { "Lua initializer changed its state before the next typed call" }
        val embedded = mutableSetOf<Long>()
        for (instruction in instructions) {
            val destination = instruction.destination as? Memory ?: continue
            if (instruction.operation in listOf(Operation.CMP, Operation.TEST, Operation.LEA, Operation.CALL) ||
                destination.relative || destination.index != null || instruction.offset !in flow.reachable
            ) continue
            val registers = flow.before(instruction.offset)
            val owner = destination.base?.let { registers[it] } as? Receiver ?: continue
            val offset = owner.adjustment + destination.displacement
            if (offset + destination.width <= stack.callInfo || stack.callInfo + 8 <= offset) continue
            require(instruction.operation == Operation.MOV && destination.width == 8 && offset == stack.callInfo) {
                "Lua initializer partially overwrites the call frame pointer"
            }
            val source =
                instruction.source as? Register ?: error("Lua initializer clears or replaces the frame with a constant")
            require(source.width == 8)
            val frame = registers[source.number] as? Receiver ?: error("Lua initial call frame is not embedded")
            require(
                frame.adjustment >= 0 && frame.adjustment <= stateSize - maxOf(stack.function, frameTop) - 8 &&
                        flow.requiresEdge(next.offset, instruction.offset, instruction.offset + instruction.size)
            ) {
                "Lua initial frame is outside the state or not initialized on every path"
            }
            require(instructions.none { it.offset > instruction.offset && it.offset < next.offset && it.operation == Operation.CALL }) {
                "Lua initial frame publication is followed by an unverified call"
            }
            embedded += frame.adjustment
        }
        return embedded.singleOrNull() ?: error("Lua initial frame location is missing or ambiguous")
    }
}
