package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** A native caller's boolean argument and integer status use. This does not establish safe reentrant invocation. */
internal object ScalarPumpCall {
    data class Proof(val setup: Long, val call: Long, val result: Long, val value: Int)

    fun resolve(image: ElfImage, caller: ElfImage.Symbol, pump: ElfImage.Symbol): Proof {
        EhFrames(image).function(caller)
        EhFrames(image).function(pump)
        // Scalar register definitions and status uses are bounded by verified CFG edges. Indirect jumps
        // remain unsupported rather than assuming a switch's destinations.
        val instructions = X64Instructions(
            image.functionBytes(caller, 32768),
            allowAtomicExchangeAdd = true, allowUnsignedWideMultiply = true
        ).all(8192)
        return inspect(X64ControlFlow(instructions), pump.address - caller.address)
    }

    fun inspect(flow: X64ControlFlow, target: Long): Proof {
        val calls = flow.instructions.filter {
            it.offset in flow.reachable && it.operation == Operation.CALL && it.destination == Immediate(target)
        }
        require(calls.size == 1) { "Expected one native event-pump call" }
        val call = calls.single()
        fun definition(site: Long, register: Int): Instruction {
            val visited = mutableSetOf<Long>()
            var position = site
            while (true) {
                require(visited.size < 128 && visited.add(position)) { "Native pump scalar setup exceeds bound" }
                position = flow.predecessors[position].orEmpty().singleOrNull()
                    ?: error("Native pump scalar argument has no unique reaching definition")
                val instruction = flow.body.getValue(position)
                if (register in writes(instruction)) return instruction
            }
        }
        val active = mutableSetOf<Pair<Long, Int>>()
        fun constant(site: Long, register: Int): Long {
            require(active.size < 32 && active.add(site to register))
            val setup = definition(site, register)
            require(setup.destination == Register(register, 4)) { "Native pump scalar setup has an unsupported width" }
            return when (setup.operation) {
                Operation.MOV -> when (val source = setup.source) {
                    is Immediate -> source.value and 0xffffffffL
                    is Register -> {
                        require(source.width == 4 && source.number in 0..15)
                        constant(setup.offset, source.number)
                    }
                    else -> error("Native pump scalar setup is not constant")
                }
                Operation.XOR -> 0L.also { require(setup.source == setup.destination) }
                else -> error("Native pump scalar argument has an unsupported definition")
            }
        }
        val setup = definition(call.offset, 7)
        val value = constant(call.offset, 7)
        require(value == 0L || value == 1L) { "Native pump argument is not a boolean scalar" }
        fun next(instruction: Instruction): Instruction {
            val successor = flow.successors.getValue(instruction.offset).singleOrNull()
                ?: error("Native pump result path branches before its verified status use")
            require(flow.predecessors[successor] == setOf(instruction.offset)) {
                "Native pump status use is entered without its call"
            }
            return flow.body.getValue(successor)
        }
        val copies = mutableSetOf(0)
        val visited = mutableSetOf<Long>()
        var result = next(call)
        while (true) {
            require(visited.size < 128 && visited.add(result.offset) && result.operation != Operation.CALL) {
                "Native pump status use loops or is clobbered by a call"
            }
            val target = result.destination as? Register
            if (target?.width == 4 && target.number in copies && when (result.operation) {
                    Operation.TEST -> result.source == result.destination
                    Operation.CMP -> result.source is Immediate
                    else -> false
                }) break
            val copied = (result.source as? Register)?.takeIf {
                result.operation == Operation.MOV && it.width == 4 && it.number in copies && target?.width == 4
            }
            copies.removeAll(writes(result))
            if (copied != null) copies += checkNotNull(target).number
            require(copies.isNotEmpty()) { "Native pump integer status is overwritten before observation" }
            result = next(result)
        }
        var branch = next(result)
        while (branch.operation != Operation.JCC) {
            require(visited.size < 128 && visited.add(branch.offset) && branch.operation in listOf(
                Operation.MOV, Operation.MOVZX, Operation.MOVSX, Operation.LEA, Operation.PUSH, Operation.POP,
                Operation.NOP, Operation.ENDBR, Operation.JMP,
            )) { "Native pump status flags are clobbered before their branch" }
            branch = next(branch)
        }
        return Proof(setup.offset, call.offset, result.offset, value.toInt())
    }

    private fun writes(instruction: Instruction): Set<Int> = when (instruction.operation) {
        Operation.CALL -> setOf(0, 1, 2, 6, 7, 8, 9, 10, 11)
        Operation.MULTIPLY_WIDE -> setOf(0, 2)
        Operation.BYTE_COMPARE_EXCHANGE -> setOf(0) + listOfNotNull((instruction.destination as? Register)?.number)
        Operation.XCHG, Operation.ATOMIC_EXCHANGE_ADD -> listOfNotNull(
            (instruction.destination as? Register)?.number, (instruction.source as? Register)?.number,
        ).toSet()
        Operation.CMP, Operation.TEST, Operation.BIT_TEST, Operation.SCALAR_COMPARE, Operation.JMP, Operation.JCC,
        Operation.RET, Operation.NOP, Operation.ENDBR, Operation.PUSH -> emptySet()
        else -> setOfNotNull((instruction.destination as? Register)?.number)
    }
}
