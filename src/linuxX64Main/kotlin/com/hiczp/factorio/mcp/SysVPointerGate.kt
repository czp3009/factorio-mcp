package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.*
import com.hiczp.factorio.mcp.X64Instructions.*

/** Identifies an entry pointer guard whose nonnull arm immediately returns without side effects. */
internal data class PointerGate(val member: Long, val nullContinuation: Long)

internal object SysVPointerGate {
    fun resolve(image: ElfImage, name: String, objectSize: Long): PointerGate {
        val symbol = image.symbol(name)
        EhFrames(image).function(symbol)
        return analyze(image.functionBytes(symbol, 8192), objectSize)
    }

    fun analyze(bytes: BinaryView, objectSize: Long): PointerGate {
        require(bytes.size in 1..8192 && objectSize in 8..(16 * 1024 * 1024))
        val decoder = X64Instructions(bytes)
        val prefix = mutableListOf<X64Instructions.Instruction>()
        var position = 0L
        repeat(32) {
            require(position < bytes.size && position < 128)
            val instruction = decoder.decode(position)
            position += instruction.size
            if (instruction.operation != Operation.JCC) {
                require(frameInstruction(instruction) || instruction.operation == Operation.CMP) {
                    "Pointer gate changes state before its condition"
                }
                prefix += instruction
            } else {
                require(instruction.condition in listOf(4, 5))
                val compare = prefix.lastOrNull { it.operation != Operation.NOP && it.operation != Operation.ENDBR }
                    ?: error("Pointer gate has no comparison")
                require(compare.operation == Operation.CMP && compare.source == Immediate(0))
                val member = compare.destination as? Memory ?: error("Pointer gate does not compare a member")
                require(member.width == 8 && !member.relative && member.index == null)
                val flow = SysVReceiverFlow(bytes.slice(0, instruction.offset), 0, objectSize)
                val owner = member.base?.let { flow.before(compare.offset)[it] } as? Receiver
                    ?: error("Pointer gate does not use the original receiver")
                val offset = owner.adjustment + member.displacement
                require(offset in 0..objectSize - 8 && offset % 8 == 0L)
                val target = (instruction.destination as? Immediate)?.value ?: error("Indirect pointer gate")
                require(target in position until bytes.size)
                val nonnull = if (instruction.condition == 4) position else target
                val absent = if (instruction.condition == 4) target else position
                var end = nonnull
                repeat(32) {
                    require(end < bytes.size && end - nonnull < 128)
                    val tail = decoder.decode(end)
                    end += tail.size
                    if (tail.operation == Operation.RET) {
                        require(absent !in nonnull until end) { "Both pointer states return through the same arm" }
                        val straight = BinaryView(
                            bytes.bytes(0, instruction.offset.toInt()) +
                                    bytes.bytes(nonnull, (end - nonnull).toInt())
                        )
                        val returning = SysVReceiverFlow(straight, 0, objectSize)
                        val registers = returning.before(straight.size - tail.size)
                        require(registers[4] == Stack(0) && listOf(3, 5, 12, 13, 14, 15).all {
                            registers[it] == Original(it)
                        }) { "Pointer gate return does not restore the native frame" }
                        return PointerGate(offset, absent)
                    }
                    require(frameInstruction(tail)) { "Nonnull pointer gate has effects instead of an immediate return" }
                }
                error("Pointer gate return exceeds bound")
            }
        }
        error("Pointer gate entry exceeds bound")
    }

    private fun frameInstruction(instruction: X64Instructions.Instruction): Boolean = when (instruction.operation) {
        Operation.NOP, Operation.ENDBR -> true
        Operation.PUSH, Operation.POP -> instruction.destination is Register && instruction.destination.width == 8
        Operation.MOV -> instruction.destination is Register && instruction.source is Register &&
                instruction.destination.width == 8 && instruction.source.width == 8

        Operation.ADD, Operation.SUB -> instruction.destination == Register(4, 8) && instruction.source is Immediate
        else -> false
    }
}
