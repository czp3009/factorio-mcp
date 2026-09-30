package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Embedded member of a loaded pointer, passed at the first named call. Not a callable function ABI. */
internal object PointerMemberArgument {
    data class Proof(val input: Int, val pointer: Long, val member: Long, val call: Long)
    private sealed interface Value
    private data class Input(val register: Int) : Value
    private data class Pointer(val input: Int, val field: Long, val adjustment: Long = 0) : Value
    private data class Constant(val value: Long) : Value
    private data object Unknown : Value

    fun resolve(
        image: ElfImage, caller: String, callee: String, argument: Int, ownerExtent: Long,
        pointeeExtent: Long, forwarded: Map<Int, Int> = emptyMap()
    ): Proof {
        val from = image.symbol(caller)
        val to = image.symbol(callee)
        EhFrames(image).function(from)
        EhFrames(image).function(to)
        return analyze(
            image.functionBytes(from, 512), from.address, to.address, argument,
            ownerExtent, pointeeExtent, forwarded
        )
    }

    fun analyze(
        bytes: BinaryView, address: Long, callee: Long, argument: Int, ownerExtent: Long,
        pointeeExtent: Long, forwarded: Map<Int, Int> = emptyMap()
    ): Proof {
        require(bytes.size in 1..512 && address >= 0 && callee >= 0 && argument in listOf(7, 6, 2, 1, 8, 9))
        require(
            ownerExtent in 8..65536 && pointeeExtent in 1..65536 &&
                    forwarded.all { (target, source) ->
                        target in listOf(7, 6, 2, 1, 8, 9) && source in listOf(
                            7,
                            6,
                            2,
                            1,
                            8,
                            9
                        )
                    })
        val registers = MutableList<Value>(16) { Input(it) }
        val decoder = X64Instructions(bytes)
        val guards = mutableListOf<Pair<Pointer, Long>>()
        var tested: Pointer? = null
        var position = 0L
        fun read(operand: X64Instructions.Operand?): Value = when (operand) {
            is Register -> if (operand.width == 8) registers[operand.number]
            else (registers[operand.number] as? Constant)?.let { Constant(it.value and 0xffffffffL) } ?: Unknown

            is Immediate -> Constant(operand.value)
            is Memory -> {
                val base = operand.base?.let { registers[it] }
                if (operand.width == 8 && !operand.relative && operand.index == null && base is Input &&
                    base.register in listOf(
                        7,
                        6
                    ) && operand.displacement in 0..ownerExtent - 8 && operand.displacement % 8 == 0L
                )
                    Pointer(base.register, operand.displacement) else Unknown
            }

            else -> Unknown
        }

        fun sum(left: Value, right: Value): Value {
            val pointer = (left as? Pointer) ?: (right as? Pointer) ?: return Unknown
            val amount = (if (left is Pointer) right else left) as? Constant ?: return Unknown
            require(amount.value in 0 until pointeeExtent && pointer.adjustment + amount.value < pointeeExtent)
            return pointer.copy(adjustment = pointer.adjustment + amount.value)
        }
        repeat(128) {
            require(position < bytes.size)
            val instruction = decoder.decode(position)
            position += instruction.size
            val previousTest = tested
            tested = null
            val target = instruction.destination
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> require(target is Register && target.width == 8)
                Operation.MOV, Operation.MOVZX -> {
                    val destination = target as? Register ?: error("Member argument prefix writes memory")
                    val value = read(instruction.source)
                    registers[destination.number] = if (destination.width == 8 || value is Constant) value else Unknown
                }

                Operation.SUB -> {
                    require(
                        target == Register(
                            4,
                            8
                        ) && instruction.source is Immediate && instruction.source.value in 0..4096
                    )
                    registers[4] = Unknown
                }

                Operation.ADD -> {
                    val destination = target as? Register ?: error("Member argument prefix modifies memory")
                    require(destination.width == 8 && destination.number !in listOf(4, 5))
                    registers[destination.number] = sum(read(destination), read(instruction.source))
                }

                Operation.LEA -> {
                    val destination = target as? Register ?: error("Member address has no register")
                    val source = instruction.source as? Memory ?: error("Member address has no expression")
                    require(destination.width == 8 && !source.relative && source.index == null)
                    registers[destination.number] =
                        sum(source.base?.let { registers[it] } ?: Unknown, Constant(source.displacement))
                }

                Operation.XOR -> {
                    val destination = target as? Register ?: error("Member argument prefix modifies memory")
                    require(destination == instruction.source && destination.width in listOf(4, 8))
                    registers[destination.number] = Constant(0)
                }

                Operation.TEST -> {
                    require(target == instruction.source && target is Register && target.width == 8)
                    tested = read(target) as? Pointer ?: error("Member null guard does not test a loaded pointer")
                    require(tested.adjustment == 0L)
                }

                Operation.JCC -> {
                    val pointer = previousTest ?: error("Member branch has no adjacent pointer null test")
                    val jump = (target as? Immediate)?.value ?: error("Indirect member branch")
                    require(instruction.condition == 4 && jump > position && jump < bytes.size)
                    guards += pointer to jump
                }

                Operation.CALL -> {
                    require(target == Immediate(callee - address)) { "Member argument prefix calls another function first" }
                    val value = registers[argument] as? Pointer ?: error("Named call lacks an embedded pointer member")
                    require(value.adjustment in 0 until pointeeExtent && guards.all { (pointer, jump) ->
                        pointer == value.copy(adjustment = 0) && jump >= position
                    }) { "Member null guard does not skip the selected call" }
                    require(forwarded.all { (destination, source) -> registers[destination] == Input(source) }) {
                        "Named call does not preserve its other original arguments"
                    }
                    return Proof(value.input, value.field, value.adjustment, instruction.offset)
                }

                else -> error("Unsupported member argument prefix operation: ${instruction.operation}")
            }
        }
        error("Member argument exceeds prefix analysis bound")
    }
}
