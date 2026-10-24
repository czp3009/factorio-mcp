package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** The native GUI clock's no-input floating return, connected to the named SDL clock. */
internal object InputClock {
    data class Proof(val api: SdlDynamicApi, val divisor: Double)

    fun resolve(image: ElfImage): Proof {
        val function = image.symbol("_ZNK16InputHandlerAgui7getTimeEv")
        require(function.size in 1..2048)
        EhFrames(image).function(function)
        val api = SdlDynamicApi.resolve(image, "SDL_GetTicksNS", emptyList())
        val divisor =
            analyze(image.functionBytes(function, 2048), function.address, api.slot) {
                address,
                width ->
                require(
                    image.sections.any {
                        it.flags and 3L == 2L &&
                            address >= it.address &&
                            width <= it.size &&
                            address - it.address <= it.size - width
                    }
                ) {
                    "Input clock literal is not immutable allocated data"
                }
                image.virtualBytes(address, width.toLong())
            }
        return Proof(api, divisor)
    }

    fun analyze(
        bytes: BinaryView,
        address: Long,
        slot: Long,
        literal: (Long, Int) -> BinaryView,
    ): Double {
        require(bytes.size in 1..2048)
        val flow = X64ControlFlow(X64Instructions(bytes).all(256))
        val call =
            SdlDynamicApi.calls(flow, address, slot).singleOrNull()
                ?: error("Input clock has no unique SDK clock call")
        verifyBoundary(flow, call.offset)
        val division = flow.instructions.single { it.operation == Operation.DOUBLE_DIVIDE }
        val target =
            division.destination as? Register ?: error("Clock division lacks a scalar register")
        UnsignedDoubleConversion(flow, address, null, literal, call.offset)
            .verify(division.offset, target)
        val result = PrivateValueCopies.Read(division.offset, NativeField(0, 8))
        val copies = PrivateValueCopies(flow, emptyMap(), mapOf(division.offset to result))
        require(copies.field(flow.instructions.last().offset, Register(16, 8)) == result) {
            "Input clock does not return its floating timestamp"
        }
        val constant = division.source as? Memory ?: error("Clock divisor is not a literal")
        require(constant.width == 8)
        val divisor =
            Double.fromBits(
                literal(SdlDynamicApi.global(division, constant, address), 8).unsigned(0, 8)
            )
        require(divisor.isFinite() && divisor > 0)
        return divisor
    }

    private sealed interface Value

    private data class Original(val register: Int) : Value

    private data class Frame(val offset: Long) : Value

    private data object Scalar : Value

    /**
     * No hidden output or additional arguments; only a private frame and the SDK result are
     * consumed.
     */
    private fun verifyBoundary(flow: X64ControlFlow, clock: Long) {
        val registers = MutableList<Value?>(32) { if (it < 16) Original(it) else null }
        registers[4] = Frame(0)
        val stack = mutableMapOf<Pair<Long, Int>, Value?>()
        fun top() = (registers[4] as? Frame)?.offset ?: error("Clock stack is unproven")
        fun local(memory: Memory): Long {
            require(!memory.relative && memory.index == null)
            val base =
                memory.base?.let { registers[it] } as? Frame
                    ?: error("Clock accesses external storage")
            val offset = base.offset + memory.displacement
            require(offset in top()..-memory.width && offset >= -2048)
            return offset
        }
        fun read(operand: Operand?): Value? =
            when (operand) {
                is Register ->
                    registers[operand.number].also { require(operand.width == 8 || it == Scalar) }
                is Memory -> stack[local(operand) to operand.width]
                else -> error("Unsupported clock input")
            }
        for (instruction in flow.instructions) {
            when (instruction.operation) {
                Operation.NOP,
                Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    val value = read(instruction.destination)
                    val offset = top() - 8
                    require(offset >= -2048)
                    registers[4] = Frame(offset)
                    stack[offset to 8] = value
                }
                Operation.POP -> {
                    val target = instruction.destination as? Register ?: error("Invalid clock pop")
                    require(target.width == 8 && target.number != 4 && top() < 0)
                    registers[target.number] = stack.remove(top() to 8)
                    registers[4] = Frame(top() + 8)
                }
                Operation.MOV -> {
                    val value = read(instruction.source)
                    when (val target = instruction.destination) {
                        is Register -> {
                            require(target.width == 8 || value == Scalar)
                            registers[target.number] = value
                        }
                        is Memory -> {
                            val offset = local(target)
                            stack.keys.removeAll { (start, size) ->
                                start < offset + target.width && offset < start + size
                            }
                            stack[offset to target.width] = value
                        }
                        else -> error("Invalid clock copy")
                    }
                }
                Operation.ADD,
                Operation.SUB -> {
                    require(instruction.destination == Register(4, 8))
                    val amount =
                        (instruction.source as? Immediate)?.value ?: error("Variable clock frame")
                    require(amount in 0..2048 && amount % 8 == 0L)
                    val next =
                        top() + if (instruction.operation == Operation.ADD) amount else -amount
                    require(next in -2048..0)
                    registers[4] = Frame(next)
                    stack.keys.removeAll { it.first < next }
                }
                Operation.CALL -> {
                    require(instruction.offset == clock && (top() + 8) % 16 == 0L)
                    for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31)) registers[
                        register] = null
                    registers[0] = Scalar
                }
                Operation.SCALAR_MOV,
                Operation.VECTOR_MOV,
                Operation.VECTOR_UNPACK_LOW_FLOATS,
                Operation.VECTOR_UNPACK_LOW_DWORDS,
                Operation.VECTOR_UNPACK_HIGH_QWORDS,
                Operation.VECTOR_SUBTRACT_DOUBLES,
                Operation.DOUBLE_ADD,
                Operation.DOUBLE_DIVIDE -> {
                    val target =
                        instruction.destination as? Register
                            ?: error("Clock writes floating data externally")
                    require(target.number in 16..31)
                    when (val source = instruction.source) {
                        is Register -> require(registers[source.number] == Scalar)
                        is Memory ->
                            require(source.relative && source.base == null && source.index == null)
                        else -> error("Invalid clock arithmetic input")
                    }
                    if (
                        instruction.operation !in listOf(Operation.SCALAR_MOV, Operation.VECTOR_MOV)
                    )
                        require(registers[target.number] == Scalar)
                    registers[target.number] = Scalar
                }
                Operation.RET -> {
                    require(
                        instruction == flow.instructions.last() &&
                            top() == 0L &&
                            registers[16] == Scalar
                    )
                    require(listOf(3, 5, 12, 13, 14, 15).all { registers[it] == Original(it) }) {
                        "Input clock changes preserved registers"
                    }
                }
                else -> error("Unsupported input clock boundary: ${instruction.operation}")
            }
        }
        require(flow.instructions.last().operation == Operation.RET)
    }
}
