package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Allocation bounds from a line-table-anchored, straight-line allocation/construction sequence. */
internal object SysVLineAllocation {
    private sealed interface Value
    private data object Unknown : Value
    private data class Constant(val value: Long) : Value
    private data class Allocation(val size: Long) : Value

    fun resolve(image: ElfImage, factory: String, constructor: String): Long {
        val entry = image.symbol(factory)
        val construct = image.symbol(constructor)
        val allocator = image.symbol("_Znwm")
        val frames = EhFrames(image)
        for (symbol in listOf(entry, construct, allocator)) frames.function(symbol)
        val code = image.functionBytes(entry, 16 * 1024 * 1024)
        val boundaries = DwarfLines(image.section(".debug_line")).addresses(entry.address, entry.address + entry.size)
        return analyze(code, entry.address, boundaries, allocator.address, construct.address)
    }

    fun analyze(bytes: BinaryView, address: Long, boundaries: Set<Long>, allocator: Long, constructor: Long): Long {
        require(
            bytes.size in 1..(16 * 1024 * 1024) && address >= 0 && address <= Long.MAX_VALUE - bytes.size &&
                    allocator >= 0 && constructor >= 0 && allocator != constructor && boundaries.size <= 65536 &&
                    boundaries.all { it in address until address + bytes.size })
        val sizes = boundaries.mapNotNull { start ->
            val offset = start - address
            try {
                block(bytes.slice(offset, minOf(128, bytes.size - offset)), start, allocator, constructor)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        }.distinct()
        return sizes.singleOrNull() ?: error("No unambiguous allocation bound at a matching DWARF line boundary")
    }

    private fun block(bytes: BinaryView, address: Long, allocator: Long, constructor: Long): Long {
        val registers = MutableList<Value>(16) { Unknown }
        val decoder = X64Instructions(bytes)
        var allocated: Allocation? = null
        var position = 0L
        repeat(32) {
            require(position < bytes.size)
            val instruction = decoder.decode(position)
            position += instruction.size
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR, Operation.TEST, Operation.CMP -> Unit
                Operation.MOV -> {
                    val target = instruction.destination as? Register ?: error("Allocation window writes memory")
                    require(target.number != 4 && target.width in listOf(4, 8))
                    val value = when (val source = instruction.source) {
                        is Immediate -> Constant(source.value)
                        is Register -> {
                            require(source.width == target.width)
                            registers[source.number]
                        }

                        is Memory -> Unknown // Unrelated constructor arguments establish no allocation provenance.
                        else -> error("Unsupported allocation operand")
                    }
                    registers[target.number] = when {
                        target.width == 8 -> value
                        value is Constant -> Constant(value.value and 0xffffffffL)
                        else -> Unknown
                    }
                }

                Operation.LEA -> {
                    val target = instruction.destination as? Register ?: error("Invalid allocation-window address")
                    require(target.number != 4 && target.width in listOf(4, 8))
                    registers[target.number] = Unknown
                }

                Operation.CMOV -> {
                    val target = instruction.destination as? Register ?: error("Invalid conditional argument")
                    require(target.number != 4 && target.width in listOf(4, 8))
                    val source = instruction.source as? Register
                    val previous = registers[target.number]
                    registers[target.number] = if (source != null && source.width == target.width &&
                        previous == registers[source.number]
                    ) previous else Unknown
                }

                Operation.CALL -> {
                    val relative =
                        (instruction.destination as? Immediate)?.value ?: error("Indirect allocation/constructor")
                    require(relative >= -address && relative <= Long.MAX_VALUE - address)
                    when (address + relative) {
                        allocator -> {
                            require(allocated == null)
                            val size =
                                (registers[7] as? Constant)?.value ?: error("Allocation size is not a proven constant")
                            require(size in 8..(64 * 1024 * 1024))
                            allocated = Allocation(size)
                            for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) registers[register] = Unknown
                            registers[0] = checkNotNull(allocated)
                        }

                        constructor -> {
                            val allocation = checkNotNull(allocated)
                            require(registers[7] == allocation) { "Constructor receiver is not the original allocation" }
                            return allocation.size
                        }

                        else -> error("Unverified intervening call in allocation window")
                    }
                }

                else -> error("Allocation window is not a supported straight-line sequence")
            }
        }
        error("Allocation window exceeds instruction bound")
    }
}
