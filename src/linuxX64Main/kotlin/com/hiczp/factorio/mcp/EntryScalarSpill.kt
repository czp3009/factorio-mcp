package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Original scalar argument captured in private frame storage, before any call or branch. Not a complete ABI proof. */
internal object EntryScalarSpill {
    data class Proof(val instruction: Long, val stackOffset: Long, val width: Int)

    fun inspect(bytes: BinaryView, argument: Register): Proof {
        require(
            bytes.size in 1..512 && argument.number in listOf(7, 6, 2, 1, 8, 9) &&
                    argument.width in listOf(1, 2, 4, 8)
        )
        val decoder = X64Instructions(bytes)
        val frames = mutableMapOf(4 to 0L)
        val saved = mutableMapOf<Int, Long>()
        var position = 0L
        repeat(128) {
            require(position < bytes.size) { "No scalar capture in the bounded entry" }
            val instruction = decoder.decode(position)
            val stack = frames.getValue(4)
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    val register = instruction.destination as? Register ?: error("Entry saves a nonregister value")
                    require(
                        register.width == 8 && register.number in listOf(3, 5, 12, 13, 14, 15) &&
                                register.number !in saved && stack >= -16376
                    ) { "Unsupported scalar entry register save" }
                    frames[4] = stack - 8
                    saved[register.number] = stack - 8
                }

                Operation.SUB -> {
                    require(instruction.destination == Register(4, 8))
                    val amount = (instruction.source as? Immediate)?.value ?: error("Dynamic scalar entry frame")
                    require(amount in 8..16384 && amount % 8 == 0L && stack - amount >= -16384)
                    frames[4] = stack - amount
                }

                Operation.MOV -> {
                    val target = instruction.destination
                    if (target is Register) {
                        require(
                            target.width == 8 && target.number in saved && target.number !in frames &&
                                    instruction.source == Register(4, 8)
                        ) { "Scalar entry changes an argument or unknown register" }
                        frames[target.number] = stack
                    } else {
                        require(
                            target is Memory && target.width == argument.width && instruction.source == argument &&
                                    !target.relative && target.index == null && target.displacement in -16384..16384
                        )
                        val base = checkNotNull(frames[target.base]) { "Scalar argument is not stored in its frame" }
                        val offset = base + target.displacement
                        require(offset >= stack && offset <= -argument.width && saved.values.none {
                            offset < it + 8 && it < offset + argument.width
                        }) { "Scalar capture overlaps saved registers, return address or unallocated storage" }
                        return Proof(position, offset, argument.width)
                    }
                }

                else -> error("Unsupported instruction before scalar capture: ${instruction.operation}")
            }
            position += instruction.size
        }
        error("Scalar entry exceeds instruction bound")
    }
}
