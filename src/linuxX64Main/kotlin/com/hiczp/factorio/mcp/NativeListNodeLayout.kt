package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** A typed list's sentinel link, node link, embedded value and sized-deletion extent. */
internal data class NativeListNodeLayout(val head: Long, val next: Long, val value: Long, val size: Long) {
    companion object {
        fun resolve(image: ElfImage, listDestructor: String, valueDestructor: String): NativeListNodeLayout {
            val entry = image.symbol(listDestructor)
            val value = image.symbol(valueDestructor)
            val delete = image.symbol("_ZdlPvm")
            for (symbol in listOf(entry, value, delete)) EhFrames(image).function(symbol)
            require(entry.size in 1..512)
            return analyze(
                X64Instructions(image.functionBytes(entry, 512)).all(),
                entry.address,
                value.address,
                delete.address
            )
        }

        private sealed interface Value
        private data class Owner(val offset: Long = 0) : Value
        private data class Node(val offset: Long = 0) : Value
        private data object Next : Value
        private data class Stack(val offset: Long) : Value
        private data class Original(val register: Int) : Value
        private data class Constant(val value: Long) : Value

        fun analyze(body: List<Instruction>, address: Long, destroy: Long, delete: Long): NativeListNodeLayout {
            require(body.isNotEmpty() && body.size <= 128 && address >= 0 && destroy > 0 && delete > 0)
            require(destroy != delete)
            val instructions = body.associateBy { it.offset }
            require(instructions.size == body.size && body.first().offset == 0L)
            require(body.all { it.size in 1..15 && it.offset >= 0 && it.offset <= Long.MAX_VALUE - it.size } &&
                    body.zipWithNext().all { (left, right) -> left.offset + left.size == right.offset })
            val visited = mutableSetOf<Long>()
            var layout: NativeListNodeLayout? = null
            for (empty in listOf(false, true)) {
                val registers = MutableList<Value>(16) { Original(it) }
                registers[7] = Owner()
                registers[4] = Stack(0)
                val stack = mutableMapOf<Long, Value>()
                var head: Long? = null
                var next: Long? = null
                var value: Long? = null
                var size: Long? = null
                var comparison: Set<Value>? = null
                var branches = 0
                var loop: Long? = null
                var loopRegisters: List<Value>? = null
                val loopWritten = mutableSetOf<Int>()
                val loopInputs = mutableSetOf<Int>()
                var destroyed = false
                var deleted = false
                var position = 0L
                val path = mutableSetOf<Long>()
                fun register(number: Int): Value {
                    if (loop != null && branches == 1 && number !in loopWritten) loopInputs += number
                    return registers[number]
                }

                fun top() = (register(4) as? Stack)?.offset ?: error("List destructor lost its frame")
                fun shifted(base: Value, offset: Long): Value = when (base) {
                    is Owner -> Owner(base.offset + offset).also { require(it.offset in 0..4096) }
                    is Node -> Node(base.offset + offset).also { require(!deleted && it.offset in 0..4096) }
                    is Stack -> Stack(base.offset + offset).also { require(it.offset in -256..0 && it.offset % 8 == 0L) }
                    else -> error("List destructor adjusts an unknown address")
                }

                fun read(operand: X64Instructions.Operand?): Value = when (operand) {
                    is Register -> register(operand.number).also {
                        require(
                            operand.width == 8 ||
                                    operand.width == 4 && it is Constant
                        )
                    }

                    is Immediate -> Constant(operand.value)
                    is Memory -> {
                        require(operand.width == 8 && !operand.relative && operand.index == null)
                        when (val base = operand.base?.let(::register)) {
                            is Owner -> {
                                require(head == null && branches == 0)
                                head = base.offset + operand.displacement
                                require(head in 0..4088 && head % 8 == 0L)
                                Node()
                            }

                            is Node -> {
                                require(!empty && branches == 1 && !destroyed && !deleted && next == null)
                                next = base.offset + operand.displacement
                                require(next in 0..4088 && next % 8 == 0L)
                                loop = position
                                loopRegisters = registers.toList()
                                loopInputs += checkNotNull(operand.base)
                                Next
                            }

                            else -> error("List destructor reads an unrelated object")
                        }
                    }

                    else -> error("Unsupported list destructor operand")
                }

                fun write(target: X64Instructions.Operand?, item: Value) {
                    val register = target as? Register ?: error("List destructor writes external memory")
                    require(register.number in 0..15 && (register.width == 8 || register.width == 4 && item is Constant))
                    registers[register.number] = if (register.width == 4 && item is Constant)
                        Constant(item.value and 0xffffffffL) else item
                    if (loop != null && branches == 1) loopWritten += register.number
                }
                while (true) {
                    require(path.add(position)) { "List destructor has an unvalidated cycle" }
                    visited += position
                    val instruction = instructions[position] ?: error("List branch is not an instruction boundary")
                    var following = position + instruction.size
                    when (instruction.operation) {
                        Operation.NOP, Operation.ENDBR -> Unit
                        Operation.PUSH -> {
                            val item = read(instruction.destination)
                            val offset = top() - 8
                            require(offset >= -256)
                            stack[offset] = item
                            registers[4] = Stack(offset)
                        }

                        Operation.POP -> {
                            require(instruction.destination != Register(4, 8))
                            val offset = top()
                            write(instruction.destination, stack.remove(offset) ?: error("List frame slot is absent"))
                            registers[4] = Stack(offset + 8)
                        }

                        Operation.MOV -> write(instruction.destination, read(instruction.source))
                        Operation.LEA -> {
                            val source = instruction.source as? Memory ?: error("List address expression is absent")
                            require(!source.relative && source.index == null && source.base != null)
                            write(instruction.destination, shifted(register(source.base), source.displacement))
                        }

                        Operation.ADD, Operation.SUB -> {
                            require(instruction.destination == Register(4, 8))
                            val amount = (instruction.source as? Immediate)?.value ?: error("Dynamic list frame")
                            require(amount in 0..256)
                            write(
                                Register(4, 8),
                                shifted(register(4), if (instruction.operation == Operation.SUB) -amount else amount)
                            )
                            comparison = null
                        }

                        Operation.CMP -> comparison = setOf(read(instruction.destination), read(instruction.source))
                        Operation.JCC -> {
                            val target = (instruction.destination as? Immediate)?.value ?: error("Indirect list branch")
                            require(target in instructions)
                            if (branches == 0) {
                                require(
                                    head != null && comparison == setOf(
                                        Node(),
                                        Owner(head!!)
                                    ) && instruction.condition == 4 &&
                                            target > position
                                )
                                if (empty) following = target
                            } else {
                                require(
                                    !empty && branches == 1 && deleted && comparison == setOf(
                                        Next,
                                        Owner(checkNotNull(head))
                                    ) &&
                                            instruction.condition == 5 && target == loop && registers.any { it == Next })
                                // The backedge must restore the iteration's node receiver in exactly the same register.
                                val load = instructions.getValue(target).source as? Memory
                                    ?: error("List loop does not restart at its next link")
                                require(load.base != null && registers[load.base] == Next && load.displacement == next)
                                require(loopInputs.all { index ->
                                    val successor = when (val item = registers[index]) {
                                        Next -> Node()
                                        is Node -> null // An alias of the deleted node cannot seed another iteration.
                                        else -> item
                                    }
                                    successor == checkNotNull(loopRegisters)[index]
                                }) { "List backedge does not restore every live iteration input" }
                            }
                            branches++
                            comparison = null
                        }

                        Operation.CALL -> {
                            val target =
                                (instruction.destination as? Immediate)?.value ?: error("Indirect list destructor call")
                            require(!empty && branches == 1 && next != null && (8 + top()) % 16 == 0L)
                            when (target) {
                                destroy - address -> {
                                    require(!destroyed && !deleted)
                                    value = (register(7) as? Node)?.offset ?: error("List destroys an unrelated value")
                                    destroyed = true
                                }

                                delete - address -> {
                                    require(destroyed && !deleted && register(7) == Node())
                                    size = (register(6) as? Constant)?.value ?: error("List node deletion has no size")
                                    require(size in 16..4096 && value!! in 8 until size && next + 8 <= value)
                                    deleted = true
                                }

                                else -> error("List destructor calls an unrelated entry")
                            }
                            for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) {
                                registers[register] = Original(16 + register)
                                loopWritten += register
                            }
                            comparison = null
                        }

                        Operation.RET -> {
                            require(top() == 0L && listOf(3, 5, 12, 13, 14, 15).all { registers[it] == Original(it) })
                            require(if (empty) branches == 1 && !destroyed && !deleted else branches == 2 && destroyed && deleted)
                            if (!empty) layout = NativeListNodeLayout(head!!, next!!, value!!, size!!)
                            else require(head == layout?.head)
                            break
                        }

                        else -> error("Unsupported list destructor instruction: ${instruction.operation}")
                    }
                    position = following
                }
            }
            require(body.all { it.offset in visited || it.operation in listOf(Operation.NOP, Operation.ENDBR) }) {
                "List destructor has unvalidated instructions"
            }
            return checkNotNull(layout)
        }
    }
}
