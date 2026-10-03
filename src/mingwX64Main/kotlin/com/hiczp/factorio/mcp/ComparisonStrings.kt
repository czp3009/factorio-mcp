package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Interprets the selected native string accessor for the PDB's finite enum domain; never calls game code. */
internal object ComparisonStrings {
    private sealed interface Value
    private data class Scalar(val value: Long) : Value
    private data class Address(val rva: Long) : Value
    private data class Receiver(val offset: Long = 0) : Value
    private data class Stack(val offset: Long) : Value
    private data class Original(val register: Int) : Value

    fun analyze(code: BinaryView, rva: Long, field: Long, extent: Long, domain: Set<Int>,
                read: (Int, Int) -> ByteArray): Map<Int, String> {
        require(code.size in 1..4096 && rva in 1..Int.MAX_VALUE && field in 0 until extent &&
                extent in 1..64 && domain.isNotEmpty() && domain.size <= 256 && domain.all { it in 0..255 })
        val decoder = X64Instructions(code)
        val decoded = mutableMapOf<Long, Instruction>()
        fun instruction(site: Long): Instruction = decoded.getOrPut(site) {
            val result = decoder.decode(site)
            require(decoded.values.none { it.offset < site + result.size && site < it.offset + it.size }) {
                "Comparison accessor branches into an instruction"
            }
            result
        }
        fun bytes(address: Long, size: Int): BinaryView {
            require(address in 1..Int.MAX_VALUE && address <= Int.MAX_VALUE - size)
            return BinaryView(read(address.toInt(), size).also { require(it.size == size) })
        }
        return domain.associateWith { value ->
            val registers = MutableList<Value?>(16) { Original(it) }
            registers[1] = Receiver()
            registers[4] = Stack(0)
            var site = 0L
            var flags: Pair<Long, Long>? = null
            var result: String? = null
            val visited = mutableSetOf<Long>()
            fun scalar(operand: Operand?): Long = when (operand) {
                is Immediate -> operand.value
                is Register -> (registers[operand.number] as? Scalar)?.value?.let {
                    require(operand.width in listOf(1, 2, 4, 8))
                    if (operand.width == 8) it else it and ((1L shl (operand.width * 8)) - 1)
                } ?: error("Unknown comparison scalar")
                else -> error("Unsupported comparison scalar")
            }
            fun address(operand: Memory, current: Instruction): Value {
                val base = if (operand.relative) {
                    require(operand.base == null && operand.index == null)
                    Address(rva + current.offset + current.size)
                } else operand.base?.let { registers[it] } ?: error("Missing comparison pointer")
                val index = operand.index?.let { scalar(Register(it, 8)) * operand.scale } ?: 0
                val adjustment = operand.displacement + index
                require(adjustment in -Int.MAX_VALUE.toLong()..Int.MAX_VALUE.toLong())
                return when (base) {
                    is Address -> Address(base.rva + adjustment)
                    is Receiver -> Receiver(base.offset + adjustment)
                    else -> error("Unsupported comparison pointer provenance")
                }
            }
            while (result == null) {
                require(site in 0 until code.size && visited.add(site) && visited.size <= 128) {
                    "Comparison string accessor has a cycle or exceeds its bound"
                }
                val current = instruction(site)
                site += current.size
                fun write(value: Value) {
                    val target = current.destination as? Register ?: error("Comparison accessor writes memory")
                    require(target.width == 8 || value is Scalar && target.width == 4)
                    registers[target.number] = value
                }
                when (current.operation) {
                    Operation.NOP, Operation.ENDBR -> Unit
                    Operation.MOV, Operation.MOVZX -> when (val source = current.source) {
                        is Memory -> {
                            when (val pointer = address(source, current)) {
                                is Receiver -> {
                                    require(source.width == 1 && pointer.offset == field && current.operation == Operation.MOVZX)
                                    write(Scalar(value.toLong()))
                                }
                                is Address -> {
                                    require(current.operation == Operation.MOV && source.width == 4)
                                    write(Scalar(bytes(pointer.rva, 4).unsigned(0, 4)))
                                }
                                else -> error("Unproven comparison read")
                            }
                        }
                        is Register -> {
                            require(source.width == 8 && current.operation == Operation.MOV)
                            write(checkNotNull(registers[source.number]))
                        }
                        is Immediate -> write(Scalar(source.value))
                        else -> error("Unsupported comparison move")
                    }
                    Operation.LEA -> write(address(current.source as? Memory ?: error("Invalid comparison address"), current))
                    Operation.ADD, Operation.SUB -> {
                        val target = current.destination as? Register ?: error("Comparison arithmetic writes memory")
                        require(target.width == 8)
                        val left = registers[target.number]
                        val right = if (current.source is Register) registers[current.source.number]
                            else Scalar(scalar(current.source))
                        val sign = if (current.operation == Operation.ADD) 1 else -1
                        write(when {
                            left is Stack && right is Scalar -> Stack(left.offset + sign * right.value)
                            left is Address && right is Scalar -> Address(left.rva + sign * right.value)
                            left is Scalar && right is Address && sign == 1 -> Address(left.value + right.rva)
                            left is Scalar && right is Scalar -> Scalar(left.value + sign * right.value)
                            else -> error("Unknown comparison arithmetic")
                        })
                        flags = null
                    }
                    Operation.CMP -> {
                        val target = current.destination as? Register ?: error("Unproven comparison operand")
                        require(target.width in listOf(1, 2, 4, 8))
                        val right = scalar(current.source)
                        flags = scalar(target) to if (target.width == 8) right else right and ((1L shl (target.width * 8)) - 1)
                    }
                    Operation.JCC -> {
                        val (left, right) = checkNotNull(flags)
                        val take = when (current.condition) {
                            4 -> left == right
                            5 -> left != right
                            2 -> left.toULong() < right.toULong()
                            3 -> left.toULong() >= right.toULong()
                            6 -> left.toULong() <= right.toULong()
                            7 -> left.toULong() > right.toULong()
                            else -> error("Unsupported comparison condition")
                        }
                        if (take) site = (current.destination as? Immediate)?.value ?: error("Indirect condition")
                    }
                    Operation.JMP -> site = when (val target = current.destination) {
                        is Immediate -> target.value
                        is Register -> (registers[target.number] as? Address)?.rva?.minus(rva)
                            ?: error("Unproven comparison table target")
                        else -> error("Unsupported comparison jump")
                    }
                    Operation.RET -> {
                        require(registers[4] == Stack(0) && listOf(3, 5, 6, 7, 12, 13, 14, 15).all {
                            registers[it] == Original(it)
                        }) { "Comparison string accessor does not preserve its Win64 frame" }
                        val returned = registers[0] as? Address ?: error("Comparison result is not an immutable string")
                        result = bytes(returned.rva, 64).string(0, 64).also { require(it.isNotEmpty()) }
                    }
                    else -> error("Unsupported comparison accessor operation: ${current.operation}")
                }
            }
            checkNotNull(result)
        }
    }
}
