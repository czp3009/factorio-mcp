package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** A native modifier getter's successful keyboard arm, using only fields cleared by paired key cleanup. */
internal object ModifierKey {
    data class Proof(val code: Long, val clear: Set<Long>, val path: List<Long>)
    private sealed interface Value
    private data class Saved(val register: Int) : Value
    private data class Owner(val offset: Long) : Value
    private data class Key(val call: Long, val offset: Long = 0) : Value
    private data class Frame(val offset: Long) : Value
    private data class Literal(val value: Long, val width: Int) : Value

    fun resolve(
        image: ElfImage,
        update: InputStateKeyUpdate,
        post: Map<Long, KeyPostUpdate.Proof>
    ): Map<String, Proof> {
        val cleared = post.getValue(update.release.kind).stores.filter { it.width == 1 }.map { it.offset }.toSet()
        val names = mapOf(
            "control" to "_ZNK10InputState10isCtrlDownEv",
            "shift" to "_ZNK10InputState11isShiftDownEv", "alt" to "_ZNK10InputState9isAltDownEv"
        )
        return names.mapValues { (_, name) ->
            val function = image.symbol(name)
            val lookups = listOf(
                InputStateKeyUpdate.LOOKUP,
                "_ZN7FlatMapI12SDL_ScancodeN10InputState8KeyStateESt4lessIvEE17private_subscriptEOS0_"
            ).map {
                val symbol = image.symbol(it)
                EhFrames(image).function(symbol)
                symbol.address - function.address
            }.toSet()
            analyze(X64ControlFlow.resolve(image, function), lookups, update.map, update.held, cleared)
        }.also { require(it.values.map { proof -> proof.code }.distinct().size == names.size) }
    }

    fun analyze(flow: X64ControlFlow, lookups: Set<Long>, map: Long, held: Long, cleared: Set<Long>): Proof {
        require(
            lookups.isNotEmpty() && map in 0..4096 && held in 0..255 &&
                    cleared.isNotEmpty() && cleared.all { it in 0..255 && it != held })
        val registers = (0..15).associateWith<Int, Value> { Saved(it) }.toMutableMap()
        registers[7] = Owner(0)
        registers[4] = Frame(0)
        val stack = mutableMapOf<Long, Value>()
        val path = mutableListOf<Long>()
        val readClear = mutableSetOf<Long>()
        var heldRead = false
        var code: Long? = null
        var calls = 0
        var comparison: Pair<Literal, Literal>? = null
        fun top() = (registers[4] as? Frame)?.offset ?: error("Modifier getter lost its stack")
        fun read(operand: Operand?): Value = when (operand) {
            is Immediate -> Literal(operand.value, 8)
            is Register -> {
                val value = checkNotNull(registers[operand.number]) { "Unproven modifier getter register" }
                if (value is Literal) {
                    require(operand.width <= value.width)
                    Literal(
                        if (operand.width == 8) value.value else value.value and ((1L shl (operand.width * 8)) - 1),
                        operand.width
                    )
                } else value.also { require(operand.width == 8) { "Truncated modifier getter pointer" } }
            }

            is Memory -> {
                require(!operand.relative && operand.index == null && operand.width == 1)
                val pointer =
                    registers[operand.base] as? Key ?: error("Modifier getter reads outside its returned key state")
                require(pointer.call == calls.toLong()) { "Modifier getter uses a key pointer from an earlier lookup" }
                val offset = pointer.offset + operand.displacement
                if (offset == held) {
                    heldRead = true
                    Literal(1, 1)
                } else {
                    require(offset in cleared) { "Modifier getter requires an unverified key field" }
                    readClear += offset
                    Literal(0, 1)
                }
            }

            else -> error("Unknown modifier getter operand")
        }

        fun write(destination: Operand?, value: Value) {
            val register = destination as? Register ?: error("Modifier getter writes memory")
            registers[register.number] = if (value is Literal) {
                require(register.width in listOf(1, 2, 4, 8))
                Literal(
                    if (register.width == 8) value.value else value.value and ((1L shl (register.width * 8)) - 1),
                    register.width
                )
            } else value.also { require(register.width == 8) }
        }

        var site = 0L
        while (true) {
            require(path.size < 128 && site !in path) { "Modifier keyboard arm loops or exceeds bounds" }
            path += site
            val instruction = flow.body.getValue(site)
            var next = site + instruction.size
            when (instruction.operation) {
                Operation.PUSH -> {
                    val value = read(instruction.destination)
                    val offset = top() - 8
                    require(offset in -256..-8)
                    stack[offset] = value
                    registers[4] = Frame(offset)
                }

                Operation.POP -> {
                    val offset = top()
                    write(instruction.destination, checkNotNull(stack.remove(offset)))
                    registers[4] = Frame(offset + 8)
                }

                Operation.MOV, Operation.MOVZX -> write(instruction.destination, read(instruction.source))
                Operation.LEA -> {
                    val source = instruction.source as? Memory ?: error("Unknown modifier map address")
                    require(!source.relative && source.index == null)
                    val owner = registers[source.base] as? Owner ?: error("Modifier map address has no receiver")
                    require(owner.offset + source.displacement == map)
                    write(instruction.destination, Owner(map))
                }

                Operation.ADD, Operation.SUB -> {
                    val amount = (instruction.source as? Immediate)?.value ?: error("Unknown modifier adjustment")
                    val delta = if (instruction.operation == Operation.ADD) amount else -amount
                    val original = read(instruction.destination)
                    val adjusted = when (original) {
                        is Frame -> Frame(original.offset + delta).also { require(it.offset in -256..0) }
                        is Owner -> Owner(original.offset + delta).also { require(it.offset == map) }
                        else -> error("Unverified modifier adjustment")
                    }
                    write(instruction.destination, adjusted)
                    comparison = null
                }

                Operation.CALL -> {
                    require((instruction.destination as? Immediate)?.value in lookups && top() and 15L == 8L)
                    require(registers[7] == Owner(map)) { "Modifier lookup has an unverified receiver" }
                    val argument = read(Register(6, 4)) as? Literal ?: error("Modifier key is not a native constant")
                    require(argument.value in 0..Int.MAX_VALUE.toLong())
                    if (code == null) code = argument.value else require(code == argument.value)
                    require(++calls <= 2)
                    for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) registers.remove(register)
                    registers[0] = Key(calls.toLong())
                    comparison = null
                }

                Operation.CMP, Operation.TEST -> {
                    val left = read(instruction.destination) as? Literal ?: error("Non-scalar modifier predicate")
                    val right = read(instruction.source) as? Literal ?: error("Non-scalar modifier predicate")
                    comparison = if (instruction.operation == Operation.TEST)
                        Literal(left.value and right.value, left.width) to Literal(0, left.width) else left to right
                }

                Operation.JCC, Operation.SET -> {
                    val (left, right) = checkNotNull(comparison)
                    val condition = checkNotNull(instruction.condition)
                    require(condition in setOf(2, 3, 4, 5, 6, 7, 12, 13, 14, 15))
                    val decision = ScalarExpression.Select(
                        condition,
                        ScalarExpression.Literal(left.value, left.width),
                        ScalarExpression.Literal(right.value, left.width),
                        ScalarExpression.Literal(1, 1),
                        ScalarExpression.Literal(0, 1),
                        1
                    )
                    val result = ScalarExpression.evaluate(decision) { error("Unexpected modifier input") }
                    if (instruction.operation == Operation.SET) write(instruction.destination, Literal(result, 1))
                    else if (result != 0L)
                        next = (instruction.destination as? Immediate)?.value ?: error("Indirect modifier branch")
                }

                Operation.JMP -> next =
                    (instruction.destination as? Immediate)?.value ?: error("Indirect modifier branch")

                Operation.RET -> {
                    require(top() == 0L && listOf(3, 5, 12, 13, 14, 15).all { registers[it] == Saved(it) })
                    require(read(Register(0, 1)) == Literal(1, 1) && calls == 2 && heldRead && readClear.isNotEmpty())
                    return Proof(checkNotNull(code), readClear, path)
                }

                Operation.NOP, Operation.ENDBR -> Unit
                else -> error("Unsupported modifier keyboard arm: ${instruction.operation}")
            }
            require(next in flow.successors.getValue(site))
            site = next
        }
    }
}
