package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Local argument provenance for the game's read-only sprite-name lookup. No game offsets are
 * assumed.
 */
internal object SpriteNameAbi {
    private data class Pointer(val argument: Int, val offset: Long = 0)

    fun verify(
        code: BinaryView,
        receiver: Int,
        output: Int,
        sprite: Int,
        receiverSize: Long,
        stringSize: Long,
        volatile: Set<Int>,
    ) {
        require(
            setOf(receiver, output, sprite).size == 3 &&
                receiverSize in 8..16 * 1024 * 1024 &&
                stringSize in 1..256
        )
        val instructions = X64Instructions(code).all(4096)
        val body = instructions.associateBy { it.offset }
        val states =
            mutableMapOf(
                0L to
                    List<Pointer?>(32) {
                        if (it in setOf(receiver, output, sprite)) Pointer(it) else null
                    }
            )
        val pending = ArrayDeque<Long>()
        pending.add(0)
        var steps = 0
        while (pending.isNotEmpty()) {
            require(++steps <= 131072) { "Sprite-name ABI analysis exceeds bounds" }
            val site = pending.removeFirst()
            val registers = states.getValue(site).toMutableList()
            val instruction = body.getValue(site)
            fun value(operand: Operand?): Pointer? =
                (operand as? Register)?.takeIf { it.width == 8 }?.let { registers[it.number] }
            fun adjust(pointer: Pointer?, amount: Long): Pointer? =
                pointer?.let {
                    val sum = it.offset + amount
                    it.copy(offset = sum).takeIf {
                        amount in -16777216..16777216 && sum in -16777216..16777216
                    }
                }
            fun write(pointer: Pointer?) {
                (instruction.destination as? Register)?.let {
                    registers[it.number] = pointer.takeIf { _ -> it.width == 8 }
                }
            }
            when (instruction.operation) {
                Operation.MOV -> write(value(instruction.source))
                Operation.LEA ->
                    write(
                        (instruction.source as? Memory)
                            ?.takeIf { !it.relative && it.index == null }
                            ?.let {
                                adjust(it.base?.let { base -> registers[base] }, it.displacement)
                            }
                    )
                Operation.ADD,
                Operation.SUB ->
                    write(
                        (instruction.source as? Immediate)?.let {
                            adjust(
                                value(instruction.destination),
                                if (instruction.operation == Operation.SUB) -it.value else it.value,
                            )
                        }
                    )
                Operation.CMOV ->
                    write(value(instruction.destination).takeIf { it == value(instruction.source) })
                Operation.XCHG -> {
                    val left = value(instruction.destination)
                    write(value(instruction.source))
                    (instruction.source as? Register)?.let {
                        registers[it.number] = left.takeIf { _ -> it.width == 8 }
                    }
                }
                Operation.CALL -> volatile.forEach { registers[it] = null }
                Operation.CMP,
                Operation.TEST,
                Operation.JMP,
                Operation.JCC,
                Operation.PUSH,
                Operation.NOP,
                Operation.ENDBR,
                Operation.RET,
                Operation.TRAP -> Unit
                else -> write(null)
            }
            val next = instruction.offset + instruction.size
            val successors =
                when (instruction.operation) {
                    Operation.RET,
                    Operation.TRAP -> emptyList()
                    Operation.JMP ->
                        listOfNotNull(
                            (instruction.destination as? Immediate)?.value?.takeIf { it in body }
                        )
                    Operation.JCC ->
                        listOf(
                            next,
                            (instruction.destination as? Immediate)?.value
                                ?: error("Indirect name-lookup branch"),
                        )
                    else -> listOf(next).filter { it < code.size }
                }
            for (target in successors) {
                require(target in body) {
                    "Sprite-name lookup branches outside an instruction boundary"
                }
                val old = states[target]
                val merged =
                    old?.mapIndexed { index, pointer -> pointer.takeIf { it == registers[index] } }
                        ?: registers.toList()
                if (old != merged) {
                    states[target] = merged
                    pending.add(target)
                }
            }
        }
        var receiverRead = false
        var spriteCompared = false
        var outputByte = false
        var outputLength = false
        for ((site, registers) in states) {
            val instruction = body.getValue(site)
            for (operand in
                listOf(instruction.destination, instruction.source).filterIsInstance<Memory>()) {
                if (operand.relative || operand.index != null) continue
                val pointer = operand.base?.let { registers[it] } ?: continue
                val offset = pointer.offset + operand.displacement
                if (pointer.argument == receiver) {
                    require(offset in 0..receiverSize - operand.width)
                    if (
                        instruction.operation == Operation.MOV &&
                            operand == instruction.source &&
                            operand.width == 8
                    )
                        receiverRead = true
                }
                if (pointer.argument == output) {
                    require(offset in 0..stringSize - operand.width) {
                        "Sprite-name output exceeds verified string storage"
                    }
                    if (
                        instruction.operation == Operation.MOV &&
                            operand == instruction.destination &&
                            instruction.source == Immediate(0)
                    ) {
                        if (operand.width == 1) outputByte = true
                        if (operand.width == 8) outputLength = true
                    }
                }
            }
            if (
                instruction.operation == Operation.CMP &&
                    listOf(instruction.destination, instruction.source).any {
                        it is Register && it.width == 8 && registers[it.number] == Pointer(sprite)
                    }
            )
                spriteCompared = true
        }
        require(receiverRead && spriteCompared && outputByte && outputLength) {
            "Sprite-name lookup lacks verified receiver, sprite identity or hidden string output"
        }
    }
}
