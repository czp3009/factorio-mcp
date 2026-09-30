package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Low-32-bit result provenance after a selected protected call; argument/frame safety is checked separately. */
internal object SysVLuaStatusReturn {
    fun verifyLoader(image: ElfImage) {
        val entry = image.symbol("lua_load")
        val protected = image.symbol("_Z10luaD_pcallP9lua_StatePFvS0_PvES1_ll")
        val raw = image.symbol("_Z20luaD_rawrunprotectedP9lua_StatePFvS0_PvES1_")
        val frames = EhFrames(image)
        for (symbol in listOf(entry, protected, raw)) frames.function(symbol)
        analyze(image.functionBytes(protected, 4096), protected.address, raw.address)
        analyze(image.functionBytes(entry, 4096), entry.address, protected.address)
    }

    fun analyze(bytes: BinaryView, address: Long, protected: Long) {
        require(bytes.size in 1..4096 && address >= 0 && address <= Long.MAX_VALUE - bytes.size && protected >= 0)
        val instructions = X64Instructions(bytes).all(1024)
        val body = instructions.associateBy { it.offset }
        val call = instructions.single { instruction ->
            val relative = (instruction.destination as? Immediate)?.value
            instruction.operation == Operation.CALL && relative != null && relative >= -address &&
                    relative <= Long.MAX_VALUE - address && address + relative == protected
        }
        val states = mutableMapOf<Long, Int>()
        val pending = ArrayDeque<Long>()
        fun enqueue(offset: Long, mask: Int) {
            require(offset in body) { "Lua result path leaves the decoded function" }
            val old = states[offset]
            val merged = old?.and(mask) ?: mask
            if (old == null || old != merged) {
                states[offset] = merged
                pending.add(offset)
            }
        }
        enqueue(call.offset + call.size, 1) // EAX is the selected int return, with unrelated upper bits ignored.
        var returns = 0
        var steps = 0
        while (pending.isNotEmpty()) {
            require(++steps <= 8192)
            val offset = pending.removeFirst()
            val instruction = body.getValue(offset)
            var mask = states.getValue(offset)
            fun carries(value: X64Instructions.Operand?) = value is Register && value.number < 16 &&
                    value.width >= 4 && mask and (1 shl value.number) != 0

            fun write(destination: X64Instructions.Operand?, status: Boolean) {
                when (destination) {
                    is Register -> if (destination.number < 16) {
                        mask = if (status && destination.width >= 4) mask or (1 shl destination.number)
                        else mask and (1 shl destination.number).inv()
                    }

                    is Memory -> require(!status) { "Lua result spills need a separate memory-lifetime proof" }
                    else -> error("Unsupported Lua result destination")
                }
            }

            val next = offset + instruction.size
            when (instruction.operation) {
                Operation.MOV, Operation.MOVZX, Operation.MOVSX -> write(
                    instruction.destination,
                    carries(instruction.source)
                )

                Operation.CMOV -> write(
                    instruction.destination,
                    carries(instruction.destination) && carries(instruction.source)
                )

                Operation.LEA, Operation.SET, Operation.POP -> write(instruction.destination, false)
                Operation.ADD, Operation.SUB, Operation.SBB, Operation.INC, Operation.DEC, Operation.AND,
                Operation.OR, Operation.XOR, Operation.SHL, Operation.SHR, Operation.SAR -> write(
                    instruction.destination,
                    false
                )

                Operation.CALL -> {
                    val preserved = listOf(3, 5, 12, 13, 14, 15).fold(0) { bits, register -> bits or (1 shl register) }
                    mask = mask and preserved
                }

                Operation.PUSH -> require(!carries(instruction.destination)) { "Lua result pushes need a separate memory-lifetime proof" }
                Operation.JMP, Operation.JCC -> {
                    val target = (instruction.destination as? Immediate)?.value ?: error("Indirect Lua result branch")
                    enqueue(target, mask)
                    if (instruction.operation == Operation.JCC) enqueue(next, mask)
                    continue
                }

                Operation.RET -> {
                    require(mask and 1 != 0) { "Lua loader returns a different int value" }
                    returns++
                    continue
                }

                Operation.NOP, Operation.ENDBR, Operation.TEST, Operation.CMP -> Unit
                // These decoder operations use the separate XMM bank; they cannot preserve an int GPR result.
                Operation.VECTOR_MOV, Operation.VECTOR_XOR, Operation.SCALAR_MOV -> Unit
                else -> error("Unsupported Lua result operation: ${instruction.operation}")
            }
            enqueue(next, mask)
        }
        require(returns > 0) { "Lua loader has no verified normal return" }
    }
}
