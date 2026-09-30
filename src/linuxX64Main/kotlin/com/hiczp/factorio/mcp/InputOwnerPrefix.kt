package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Entry-path association between a source's Player, its Map and that Map's Game or GameView player.
 * This proves fields and original receiver provenance, not the behavior of the rejected branches or a callable ABI.
 */
internal object InputOwnerPrefix {
    private data class Path(val fields: List<Long>)

    fun analyze(
        bytes: BinaryView, functionSize: Long, sizes: List<Long>,
        sourcePlayer: Long, playerMap: Long, ownerTail: List<Long>
    ): Long {
        require(
            bytes.size in 1..512 && functionSize >= bytes.size && functionSize <= 65536 &&
                    ownerTail.size in 1..2 && sizes.size == 3 + ownerTail.size &&
                    sizes.all { it in 8..(64 * 1024 * 1024) })
        val known = mapOf(0 to sourcePlayer, 1 to playerMap) +
                ownerTail.mapIndexed { index, offset -> index + 3 to offset }
        require(known.all { (depth, offset) -> offset in 0..sizes[depth] - 8 && offset % 8 == 0L })
        val registers = MutableList<Path?>(16) { null }
        registers[7] = Path(emptyList())
        val exits = mutableListOf<Long>()
        val guarded = mutableSetOf<Path>()
        var compared: Pair<Path, Path?>? = null
        val decoder = X64Instructions(bytes)
        var position = 0L
        fun read(operand: X64Instructions.Operand?): Path? = when (operand) {
            is Register -> if (operand.width == 8) registers[operand.number] else null
            is Memory -> {
                require(operand.width == 8 && !operand.relative && operand.index == null)
                val base = operand.base?.let { registers[it] } ?: error("Ownership load has no original receiver path")
                val depth = base.fields.size
                require(
                    depth in sizes.indices && operand.displacement in 0..sizes[depth] - 8 &&
                            operand.displacement % 8 == 0L && (known[depth] == null || known[depth] == operand.displacement)
                )
                Path(base.fields + operand.displacement)
            }

            else -> null
        }
        repeat(64) {
            require(position < bytes.size)
            val instruction = decoder.decode(position)
            position += instruction.size
            val target = instruction.destination
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> require(target is Register && target.width == 8 && compared == null)
                Operation.SUB -> require(
                    target == Register(4, 8) && instruction.source is Immediate &&
                            instruction.source.value in 0..4096 && compared == null
                )

                Operation.MOV -> {
                    require(compared == null && target is Register && target.width == 8)
                    registers[target.number] = read(instruction.source)
                }

                Operation.TEST, Operation.CMP -> {
                    require(compared == null)
                    val left = read(target)
                    val right = read(instruction.source)
                    compared = when {
                        instruction.operation == Operation.TEST && left != null && left == right -> left to null
                        instruction.operation == Operation.CMP && left != null && instruction.source == Immediate(0) -> left to null
                        instruction.operation == Operation.CMP && left != null && right != null -> left to right
                        else -> error("Ownership condition does not compare original pointers")
                    }
                }

                Operation.JCC -> {
                    val (left, right) = checkNotNull(compared)
                    val rejected = (target as? Immediate)?.value ?: error("Indirect ownership guard")
                    require(rejected in position until functionSize)
                    compared = null
                    if (right == null) {
                        require(instruction.condition == 4 && left.fields.size in 1 until sizes.size)
                        exits += rejected
                        guarded += left
                    } else {
                        require(instruction.condition in listOf(4, 5))
                        if (instruction.condition == 4) {
                            // Optimizers can put an immediate mismatch return before the matched continuation.
                            require(position < bytes.size)
                            val exit = decoder.decode(position)
                            require(exit.operation == Operation.RET && position + exit.size == rejected)
                            exits += position
                        } else exits += rejected
                        val paths = listOf(left, right).sortedBy { it.fields.size }
                        val player = paths[0]
                        val gamePlayerPath = paths[1]
                        require(
                            player.fields == listOf(sourcePlayer) && gamePlayerPath.fields.size == sizes.size &&
                                gamePlayerPath.fields.take(2) == listOf(sourcePlayer, playerMap) &&
                                gamePlayerPath.fields.drop(3) == ownerTail && exits.all { it >= position } &&
                                player in guarded && (3 until sizes.size).all {
                            Path(gamePlayerPath.fields.take(it)) in guarded
                        }) {
                            "Input evaluation does not guard the source and the same Game/GameView player"
                        }
                        return gamePlayerPath.fields[2]
                    }
                }

                else -> error("Unsupported ownership prefix instruction: ${instruction.operation}")
            }
        }
        error("Input ownership comparison exceeds prefix bound")
    }
}
