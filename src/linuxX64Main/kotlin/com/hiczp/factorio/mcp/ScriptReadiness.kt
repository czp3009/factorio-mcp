package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Native flags saved and temporarily changed by the script's load-handler entry. */
internal data class ScriptReadiness(val loading: Long, val enabled: Long)

internal object SysVScopedFlags {
    private sealed interface Value
    private data object Receiver : Value
    private data class Stack(val offset: Long) : Value
    private data class Scalar(val offset: Long, val width: Int) : Value
    private data class Constant(val value: Long) : Value
    private data object Unknown : Value

    fun resolve(image: ElfImage, scriptSize: Long): ScriptReadiness {
        val caller = image.symbol("_ZN13LuaGameScript14checkRunOnLoadERK9SetupData")
        val callee = image.symbol("lua_rawgetglobal")
        EhFrames(image).function(caller)
        EhFrames(image).function(callee)
        return analyze(image.functionBytes(caller, 8192), caller.address, callee.address, scriptSize)
    }

    fun analyze(bytes: BinaryView, address: Long, callee: Long, size: Long): ScriptReadiness {
        require(
            bytes.size in 1..8192 && size in 8..(64 * 1024 * 1024) &&
                    address >= 0 && address <= Long.MAX_VALUE - bytes.size && callee >= 0
        )
        val registers = MutableList<Value>(16) { Unknown }
        registers[7] = Receiver
        registers[4] = Stack(0)
        val saved = mutableMapOf<Pair<Long, Int>, Value>()
        val pushed = mutableSetOf<Long>()
        val writes = mutableMapOf<Long, Long>()
        val branches = mutableListOf<Long>()
        val decoder = X64Instructions(bytes)
        var position = 0L
        fun top() = (registers[4] as? Stack)?.offset ?: error("Unknown script load frame")
        fun slot(memory: Memory): Long? {
            require(!memory.relative && memory.index == null)
            val base = memory.base?.let { registers[it] } as? Stack ?: return null
            val result = base.offset + memory.displacement
            require(result >= top() && result <= -memory.width) { "Script load exceeds its saved frame" }
            return result
        }

        fun read(operand: X64Instructions.Operand?): Value = when (operand) {
            is Immediate -> Constant(operand.value)
            is Register -> {
                val value = registers[operand.number]
                require(operand.width == 8 || value != Receiver && value !is Stack) { "Script load truncates a pointer" }
                if (value is Scalar && operand.width < value.width) Unknown else value
            }

            is Memory -> {
                if (operand.relative) Unknown else slot(operand)?.let { saved[it to operand.width] ?: Unknown }
                    ?: if (operand.base?.let { registers[it] } == Receiver) {
                        require(operand.displacement in 0..size - operand.width)
                        require(operand.displacement !in writes) { "Script flag is read after its temporary override" }
                        if (operand.width == 1) Scalar(operand.displacement, 1) else Unknown
                    } else Unknown
            }

            else -> error("Unsupported script load source")
        }

        fun write(target: X64Instructions.Operand?, value: Value) {
            when (target) {
                is Register -> {
                    require(target.width == 8 || value != Receiver && value !is Stack)
                    if (target.number == 4) require(target.width == 8 && value is Stack)
                    registers[target.number] = if (target.width < 4 && value !is Scalar) Unknown else value
                }

                is Memory -> {
                    val local = slot(target)
                    if (local != null) {
                        require(pushed.none { it < local + target.width && local < it + 8 }) {
                            "Script load overwrites a saved register"
                        }
                        require(value != Receiver || target.width == 8)
                        require(value !is Stack || target.width == 8)
                        saved.keys.removeAll { (start, width) -> start < local + target.width && local < start + width }
                        saved[local to target.width] = value
                    } else {
                        require(target.base?.let { registers[it] } == Receiver && target.width == 1 &&
                                target.displacement in 0 until size && value is Constant && value.value in 0..1) {
                            "Script load prefix writes an unproven flag"
                        }
                        require(saved.any { (slot, savedValue) ->
                            slot.second == 1 && savedValue == Scalar(
                                target.displacement,
                                1
                            )
                        }) {
                            "Script load flag lacks a saved original byte"
                        }
                        require(
                            writes.put(
                                target.displacement,
                                value.value
                            ) == null
                        ) { "Script load changes a flag twice" }
                    }
                }

                else -> error("Unsupported script load destination")
            }
        }
        repeat(128) {
            require(position < bytes.size && position < 512)
            val instruction = decoder.decode(position)
            position += instruction.size
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR, Operation.CMP, Operation.TEST -> Unit
                Operation.PUSH -> {
                    val target = instruction.destination as? Register ?: error("Unsupported script load push")
                    require(target.width == 8)
                    val value = read(target)
                    val next = top() - 8
                    require(next >= -16384)
                    registers[4] = Stack(next)
                    saved[next to 8] = value
                    pushed += next
                }

                Operation.MOV, Operation.MOVZX -> write(instruction.destination, read(instruction.source))
                Operation.LEA -> {
                    val target = instruction.destination as? Register ?: error("Unsupported script load address")
                    val source = instruction.source as? Memory ?: error("Unsupported script load address")
                    require(target.number != 4 && target.width == 8)
                    require(source.relative || source.base?.let { registers[it] } !is Stack)
                    write(target, Unknown)
                }

                Operation.ADD, Operation.SUB, Operation.SHR, Operation.SAR -> {
                    val target = instruction.destination as? Register ?: error("Script load changes unknown memory")
                    val original = registers[target.number]
                    if (target == Register(4, 8)) {
                        val amount = (instruction.source as? Immediate)?.value ?: error("Dynamic script load frame")
                        require(
                            instruction.operation == Operation.SUB && amount in 0..16384 && amount % 8 == 0L &&
                                    top() - amount >= -16384
                        )
                        registers[4] = Stack(top() - amount)
                    } else {
                        require(original != Receiver && original !is Stack)
                        require(read(instruction.source) !is Stack)
                        write(target, Unknown)
                    }
                }

                Operation.JCC -> {
                    val target = (instruction.destination as? Immediate)?.value ?: error("Indirect script load guard")
                    require(target in position until bytes.size)
                    branches += target
                }

                Operation.CALL -> {
                    val target = (instruction.destination as? Immediate)?.value ?: error("Indirect script load call")
                    require(target >= -address && target <= Long.MAX_VALUE - address && address + target == callee)
                    require(branches.all { it >= position } && (8 + top()) % 16 == 0L && writes.size == 2)
                    require(listOf(0, 1, 2, 6, 7, 8, 9, 10, 11).none { registers[it] is Stack })
                    val loading = writes.entries.single { it.value == 1L }.key
                    val enabled = writes.entries.single { it.value == 0L }.key
                    return ScriptReadiness(loading, enabled)
                }

                else -> error("Unsupported script load prefix: ${instruction.operation}")
            }
        }
        error("Script load flag analysis exceeds bound")
    }
}
