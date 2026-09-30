package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Checks the native local-player predicate over every null/equality case, without invoking the range accessor. */
internal object LocalPlayerSelection {
    private sealed interface Value
    private enum class Object : Value { NULL, PLAYER, OTHER_PLAYER, MAP, GAME, VIEW, END }
    private data class Cursor(val advanced: Boolean = false) : Value
    private data class Original(val register: Int) : Value
    private data class Stack(val offset: Int) : Value

    fun verify(image: ElfImage, playerSize: Long, gamePlayer: Long, gameView: Long, viewPlayer: Long) {
        val entry = image.symbol("_ZN3Map14getLocalPlayerEv")
        require(entry.size in 1..512)
        EhFrames(image).function(entry)
        analyze(image.functionBytes(entry, 512), playerSize, gamePlayer, gameView, viewPlayer)
    }

    fun analyze(bytes: BinaryView, playerSize: Long, gamePlayer: Long, gameView: Long, viewPlayer: Long) {
        require(
            bytes.size in 1..512 && playerSize >= 8 && gamePlayer >= 0 && gameView >= 0 && viewPlayer >= 0 &&
                    gamePlayer != gameView && listOf(gamePlayer, gameView, viewPlayer).all { it % 8 == 0L })
        val body = X64Instructions(bytes).all(128).associateBy { it.offset }
        val playerMaps = mutableSetOf<Long>()
        val mapGames = mutableSetOf<Long>()
        val visited = mutableSetOf<Long>()
        val choices = listOf(Object.NULL, Object.PLAYER, Object.OTHER_PLAYER)
        for (empty in listOf(false, true)) for (hasGame in listOf(false, true)) for (direct in choices)
            for (hasView in listOf(false, true)) for (indirect in choices) {
                val registers = MutableList<Value>(16) { Original(it) }
                registers[7] = Cursor(empty)
                registers[6] = Object.END
                registers[4] = Stack(0)
                val frame = mutableMapOf<Int, Value>()
                var zero: Boolean? = null
                var position = 0L
                var returned = false
                fun top() = (registers[4] as? Stack)?.offset ?: error("Local-player predicate loses its frame")
                fun read(operand: X64Instructions.Operand?): Value = when (operand) {
                    is Register -> {
                        require(operand.width == 8)
                        registers[operand.number]
                    }

                    is Memory -> {
                        require(operand.width == 8 && !operand.relative && operand.index == null)
                        when (val base = operand.base?.let { registers[it] }) {
                            is Cursor -> {
                                require(!base.advanced && operand.displacement == 0L)
                                Object.PLAYER
                            }

                            Object.PLAYER -> {
                                require(operand.displacement in 0..playerSize - 8 && operand.displacement % 8 == 0L)
                                playerMaps += operand.displacement
                                Object.MAP
                            }

                            Object.MAP -> {
                                // This intermediate expression is never exported or read at runtime.
                                require(operand.displacement in 0..(64 * 1024 * 1024) && operand.displacement % 8 == 0L)
                                mapGames += operand.displacement
                                if (hasGame) Object.GAME else Object.NULL
                            }

                            Object.GAME -> when (operand.displacement) {
                                gamePlayer -> direct
                                gameView -> if (hasView) Object.VIEW else Object.NULL
                                else -> error("Local-player predicate reads an unverified Game member")
                            }

                            Object.VIEW -> {
                                require(operand.displacement == viewPlayer)
                                indirect
                            }

                            else -> error("Local-player predicate dereferences an unknown or null object")
                        }
                    }

                    else -> error("Unsupported local-player predicate operand")
                }

                fun equal(left: Value, right: Value): Boolean {
                    if (left is Cursor || right is Cursor) {
                        val cursor = if (left is Cursor) left else right as Cursor
                        require(if (left is Cursor) right == Object.END else left == Object.END)
                        return cursor.advanced
                    }
                    require(left is Object && right is Object)
                    return left == right
                }
                repeat(256) {
                    if (returned) return@repeat
                    val instruction =
                        body[position] ?: error("Local-player predicate branches outside decoded instructions")
                    visited += position
                    position += instruction.size
                    when (instruction.operation) {
                        Operation.NOP, Operation.ENDBR -> Unit
                        Operation.MOV -> {
                            val target =
                                instruction.destination as? Register ?: error("Local-player predicate writes memory")
                            require(target.width == 8 && target.number != 4)
                            registers[target.number] = read(instruction.source)
                        }

                        Operation.PUSH -> {
                            val value = read(instruction.destination)
                            val offset = top() - 8
                            require(offset >= -128)
                            frame[offset] = value
                            registers[4] = Stack(offset)
                        }

                        Operation.POP -> {
                            val target = instruction.destination as? Register ?: error("Unsupported predicate pop")
                            require(target.width == 8 && target.number != 4)
                            val offset = top()
                            registers[target.number] =
                                frame.remove(offset) ?: error("Predicate reads an unsaved frame value")
                            registers[4] = Stack(offset + 8)
                        }

                        Operation.CMP -> zero = equal(read(instruction.destination), read(instruction.source))
                        Operation.TEST -> {
                            require(instruction.destination == instruction.source)
                            val value = read(instruction.destination)
                            require(value is Object)
                            zero = value == Object.NULL
                        }

                        Operation.XOR -> {
                            val target = instruction.destination as? Register ?: error("Predicate XOR writes memory")
                            require(target == instruction.source && target.width in listOf(4, 8) && target.number != 4)
                            registers[target.number] = Object.NULL
                            zero = true
                        }

                        Operation.ADD -> {
                            val target = instruction.destination as? Register ?: error("Predicate changes memory")
                            require(
                                target.width == 8 && registers[target.number] == Cursor() &&
                                        (instruction.source as? Immediate)?.value == 8L
                            )
                            registers[target.number] = Cursor(true)
                            zero = null
                        }

                        Operation.JMP -> position = (instruction.destination as? Immediate)?.value
                            ?: error("Indirect predicate jump")

                        Operation.JCC -> {
                            val condition = checkNotNull(zero) { "Predicate uses unproven condition flags" }
                            require(instruction.condition in listOf(4, 5))
                            if (condition == (instruction.condition == 4)) position =
                                (instruction.destination as? Immediate)?.value ?: error("Indirect predicate branch")
                        }

                        Operation.RET -> {
                            val selected = !empty && hasGame && (direct == Object.PLAYER ||
                                    direct == Object.NULL && hasView && indirect == Object.PLAYER)
                            require(registers[0] == if (selected) Object.PLAYER else Object.NULL) {
                                "Native local-player predicate differs from the typed Game/GameView selection"
                            }
                            require(
                                top() == 0 && frame.isEmpty() &&
                                        listOf(3, 5, 12, 13, 14, 15).all { registers[it] == Original(it) })
                            returned = true
                        }

                        else -> error("Unsupported local-player predicate instruction: ${instruction.operation}")
                    }
                }
                require(returned) { "Local-player predicate exceeds the bounded iteration" }
            }
        require(playerMaps.size == 1 && mapGames.size == 1 && body.values.all {
            it.offset in visited || it.operation in listOf(Operation.NOP, Operation.ENDBR)
        }) { "Local-player predicate has ambiguous ownership or uncovered instructions" }
    }
}
