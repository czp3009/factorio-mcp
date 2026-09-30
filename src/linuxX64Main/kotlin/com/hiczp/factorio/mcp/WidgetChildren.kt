package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Child ranges proved from a receiver's recursive visitor, including private children. */
internal object WidgetChildren {
    data class Range(val begin: Long, val end: Long)

    private sealed interface Value
    private data object Receiver : Value
    private data object Visitor : Value
    private data class Original(val register: Int) : Value
    private data class Stack(val offset: Long) : Value
    private data class Field(val offset: Long, val advance: Long = 0) : Value
    private data class Child(val begin: Long) : Value
    private data class VisitorMember(val offset: Long) : Value
    private data class Constant(val value: Long) : Value
    private data object Unknown : Value

    private data class Loop(val start: Long, val exit: Long, val fields: Set<Long>, var begin: Long? = null)

    fun resolve(image: ElfImage, symbol: String, objectSize: Long): List<Range> {
        val function = image.symbol(symbol)
        require(function.size in 1..4096) { "Recursive widget visitor exceeds analysis bound" }
        EhFrames(image).function(function)
        val badVisitor = image.symbol("_ZSt25__throw_bad_function_callv")
        image.functionBytes(badVisitor, 1)
        return analyze(image.functionBytes(function, 4096), function.address, badVisitor.address, objectSize)
    }

    fun analyze(bytes: BinaryView, address: Long, badVisitor: Long, objectSize: Long): List<Range> {
        require(bytes.size in 1..4096 && objectSize >= 8 && address >= 0 && address <= Long.MAX_VALUE - bytes.size)
        val code = X64Instructions(bytes).all(512).filter { it.operation !in setOf(Operation.NOP, Operation.ENDBR) }
        val indices = code.mapIndexed { index, instruction -> instruction.offset to index }.toMap()
        val values = (0..15).associateWith<Int, Value> { Original(it) }.toMutableMap()
        values[7] = Receiver
        values[6] = Visitor
        values[4] = Stack(0)
        val stack = mutableMapOf<Long, Value>()
        val ranges = mutableListOf<Range>()
        var loop: Loop? = null
        var comparison: Pair<Value, Value>? = null
        var visitorGuard: Long? = null
        var visitorCalled = false
        val preserved = setOf(3, 5, 12, 13, 14, 15)
        val volatile = setOf(0, 1, 2, 6, 7, 8, 9, 10, 11)
        fun top() = (values[4] as? Stack)?.offset ?: error("Unproven visitor stack")
        fun moveStack(offset: Long) {
            require(offset in -4096..0 && offset % 8 == 0L)
            values[4] = Stack(offset)
        }

        fun register(operand: Operand?): Register = (operand as? Register)?.also {
            require(it.width == 8) { "Visitor truncates pointer provenance" }
        } ?: error("Expected a pointer register")

        fun memoryAddress(memory: Memory): Value {
            require(memory.width == 8 && !memory.relative && memory.index == null && memory.base != null)
            return when (val base = values[memory.base]) {
                Receiver -> {
                    require(memory.displacement in 0..objectSize - 8)
                    Field(memory.displacement)
                }

                Visitor -> {
                    require(memory.displacement in 0..256 && memory.displacement % 8 == 0L)
                    VisitorMember(memory.displacement)
                }

                is Stack -> Stack(base.offset + memory.displacement).also {
                    require(it.offset >= top() && it.offset + 8 <= 0) { "Visitor accesses outside its reserved frame" }
                }

                is Field -> {
                    require(loop != null && base.advance == 0L && memory.displacement == 0L)
                    Child(base.offset)
                }

                else -> error("Visitor reads through an unproven pointer")
            }
        }

        fun read(operand: Operand?): Value = when (operand) {
            is Register -> values.getValue(register(operand).number)
            is Immediate -> Constant(operand.value)
            is Memory -> memoryAddress(operand).let { if (it is Stack) stack[it.offset] ?: Unknown else it }
            else -> error("Missing visitor operand")
        }

        fun call() {
            require((8 + top()) % 16 == 0L) { "Visitor call has an unverified System V frame" }
            volatile.forEach { values[it] = Unknown }
            comparison = null
        }

        fun target(operand: Operand?): Long {
            val offset = (operand as? Immediate)?.value ?: error("Expected a direct visitor branch")
            require(offset in indices) { "Visitor branch is outside a decoded instruction boundary" }
            return offset
        }
        for ((index, instruction) in code.withIndex()) {
            when (instruction.operation) {
                Operation.PUSH -> {
                    val value = read(instruction.destination)
                    moveStack(top() - 8)
                    stack[top()] = value
                }

                Operation.POP -> {
                    val destination = register(instruction.destination)
                    require(destination.number != 4)
                    values[destination.number] = stack[top()] ?: error("Visitor restores an unknown frame slot")
                    moveStack(top() + 8)
                }

                Operation.MOV -> {
                    val value = read(instruction.source)
                    when (val destination = instruction.destination) {
                        is Register -> values[register(destination).number] = value
                        is Memory -> {
                            val location = memoryAddress(destination) as? Stack ?: error("Visitor writes object memory")
                            stack[location.offset] = value
                        }

                        else -> error("Unsupported visitor move")
                    }
                }

                Operation.LEA -> {
                    val destination = register(instruction.destination)
                    val source = instruction.source as? Memory ?: error("Missing visitor address expression")
                    val location = memoryAddress(source) as? Stack ?: error("Visitor address is outside its frame")
                    values[destination.number] = location
                }

                Operation.ADD, Operation.SUB -> {
                    val destination = register(instruction.destination)
                    val amount = (instruction.source as? Immediate)?.value ?: error("Nonconstant visitor increment")
                    if (destination.number == 4) {
                        require(amount in 0..4096 && amount % 8 == 0L)
                        moveStack(top() + if (instruction.operation == Operation.ADD) amount else -amount)
                    } else {
                        val active = checkNotNull(loop) { "Iterator adjustment outside a child loop" }
                        val field = values[destination.number] as? Field ?: error("Unproven child iterator")
                        require(
                            instruction.operation == Operation.ADD && amount == 8L && field.advance == 0L &&
                                    active.begin == field.offset
                        ) { "Child loop does not advance by one native pointer" }
                        values[destination.number] = field.copy(advance = amount)
                    }
                    comparison = null
                }

                Operation.CMP -> comparison = read(instruction.destination) to read(instruction.source)
                Operation.JCC -> {
                    val branch = target(instruction.destination)
                    val pair = checkNotNull(comparison) { "Visitor branch has no proven comparison" }
                    if (branch > instruction.offset) {
                        require(instruction.condition == 4 && loop == null && !visitorCalled)
                        val first = pair.first
                        val second = pair.second
                        if (first is Field && second is Field) {
                            require(first.advance == 0L && second.advance == 0L && first.offset != second.offset && ranges.size < 8)
                            loop = Loop(code[index + 1].offset, branch, setOf(first.offset, second.offset))
                        } else {
                            require(first is VisitorMember && second == Constant(0) && visitorGuard == null) {
                                "Visitor skips a subtree for an unsupported condition"
                            }
                            visitorGuard = branch
                        }
                    } else {
                        val active = checkNotNull(loop) { "Unrecognized visitor cycle" }
                        val begin = checkNotNull(active.begin) { "Child loop has no recursive receiver" }
                        val end = active.fields.single { it != begin }
                        require(
                            instruction.condition == 5 && branch == active.start &&
                                    active.exit == code[index + 1].offset &&
                                    setOf(pair.first, pair.second) == setOf(Field(begin, 8), Field(end))
                        ) {
                            "Recursive child loop has unsupported bounds or exit"
                        }
                        ranges += Range(begin, end)
                        // Values changed only on the nonempty path cannot authorize later reads after the merge.
                        values.entries.forEach { if (it.value is Field || it.key in volatile) it.setValue(Unknown) }
                        loop = null
                    }
                    comparison = null
                }

                Operation.CALL -> {
                    when (val destination = instruction.destination) {
                        is Immediate -> {
                            require(destination.value == 0L) { "Recursive visitor calls another function" }
                            val active = checkNotNull(loop) { "Recursive call is not bounded by a child range" }
                            val child =
                                values[7] as? Child ?: error("Recursive receiver is not loaded from a child iterator")
                            require(active.begin == null && child.begin in active.fields && values[6] == Visitor)
                            active.begin = child.begin
                        }

                        is Memory -> {
                            require(
                                loop == null && visitorGuard != null && !visitorCalled &&
                                        memoryAddress(destination) is VisitorMember && values[7] == Visitor
                            )
                            val argument =
                                values[6] as? Stack ?: error("Visitor callback has no native receiver reference")
                            require(stack[argument.offset] == Receiver) { "Visitor callback does not receive its original object" }
                            visitorCalled = true
                        }

                        else -> error("Unsupported recursive visitor dispatch")
                    }
                    call()
                }

                Operation.RET -> {
                    require(
                        loop == null && visitorCalled && ranges.isNotEmpty() && ranges.distinct().size == ranges.size &&
                                top() == 0L && preserved.all { values[it] == Original(it) }) {
                        "Visitor does not restore its frame or has incomplete child ranges"
                    }
                    val guard = checkNotNull(visitorGuard)
                    val tail = code.drop(index + 1)
                    require(
                        tail.size == 1 && tail.single().offset == guard && tail.single().operation == Operation.CALL &&
                                (tail.single().destination as? Immediate)?.value == badVisitor - address
                    ) {
                        "Visitor's empty-callback path is not the standard failure call"
                    }
                    return ranges
                }

                else -> error("Unsupported recursive visitor instruction: ${instruction.operation}")
            }
        }
        error("Recursive visitor has no complete return path")
    }
}
