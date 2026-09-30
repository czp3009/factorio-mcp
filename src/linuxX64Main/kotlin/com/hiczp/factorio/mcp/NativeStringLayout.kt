package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Native storage and destruction, independently of any function constructing a returned string. */
internal data class NativeStringLayout(
    val data: Long,
    val length: Long,
    val local: Long,
    val size: Long,
    val destructor: ElfImage.Symbol,
) {
    companion object {
        private const val STRING = "St7__cxx1112basic_stringIcSt11char_traitsIcESaIcEE"
        private const val OWNER =
            "_ZNSt19_Sp_counted_deleterIPKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEESt14default_deleteIS6_ESaIvELN9__gnu_cxx12_Lock_policyE2EE10_M_disposeEv"

        fun resolve(image: ElfImage): NativeStringLayout {
            fun member(name: String): Long {
                val field = SysVAccessors.resolve(image, image.symbol("_ZNK$STRING$name"))
                require(field.width == 8 && field.mask == ULong.MAX_VALUE && field.shift == 0 && field.offset in 0..4088)
                return field.offset
            }

            val data = member("4dataEv")
            val length = member("4sizeEv")
            // This initial bound limits analysis; the typed owner below establishes the actual object size.
            val local = SysVAccessors.resolveAddress(image, image.symbol("_ZNK${STRING}13_M_local_dataEv"), 1, 4096)
            val owner = image.symbol(OWNER)
            val destructor = image.symbol("_ZN${STRING}D1Ev")
            val sizedDelete = image.symbol("_ZdlPvm")
            val plainDelete = image.symbol("_ZdlPv")
            require(owner.size in 1..256 && destructor.size in 1..256)
            for (function in listOf(owner, destructor, sizedDelete, plainDelete)) EhFrames(image).function(function)
            val size = StringStorageProof.owner(
                image.functionBytes(owner, 256), owner.address,
                sizedDelete.address, data, local
            )
            require(
                data + 8 <= size && length + 8 <= size && local < size &&
                        (data + 8 <= length || length + 8 <= data) && data + 8 <= local && length + 8 <= local
            ) {
                "Native string fields overlap or exceed its typed deletion size"
            }
            StringStorageProof.destructor(
                image.functionBytes(destructor, 256), destructor.address,
                plainDelete.address, data, local, size
            )
            return NativeStringLayout(data, length, local, size, destructor)
        }
    }
}

/** Complete null/inline/heap paths; only the named allocator may receive the proven original allocations. */
internal object StringStorageProof {
    private sealed interface Value
    private data object Owner : Value
    private data class Object(val offset: Long = 0) : Value
    private data object Buffer : Value
    private data class Capacity(val extra: Long = 0) : Value
    private data class Constant(val value: Long) : Value
    private data class Stack(val offset: Long) : Value
    private data class Original(val register: Int) : Value
    private data class Result(val pointer: Long?, val size: Long?, val reads: Set<Long>)

    fun owner(bytes: BinaryView, address: Long, deallocate: Long, data: Long, local: Long): Long {
        val results = listOf(null, true, false).map {
            path(bytes, address, deallocate, data, local, owner = true, inline = it)
        }
        require(results.map { it.pointer }.distinct().single() != null) { "String owner changes its pointer field" }
        val size = results.drop(1).map { it.size }.distinct().single()
            ?: error("String owner lacks a complete-object delete size")
        require(results.all { result -> result.reads.all { it in 0..size - 8 } } && local < size) {
            "String destruction reads beyond the typed object size"
        }
        return size
    }

    fun destructor(bytes: BinaryView, address: Long, deallocate: Long, data: Long, local: Long, size: Long) {
        require(size in 1..4096 && local in 0 until size)
        for (inline in listOf(true, false)) {
            val result = path(bytes, address, deallocate, data, local, owner = false, inline = inline)
            require(result.reads.all { it in 0..size - 8 }) { "String destructor reads outside its object" }
        }
    }

    private fun path(
        bytes: BinaryView, address: Long, deallocate: Long, data: Long, local: Long,
        owner: Boolean, inline: Boolean?
    ): Result {
        require(bytes.size in 1..256)
        return path(X64Instructions(bytes).all(), address, deallocate, data, local, owner, inline, owner)
    }

    fun sizedDestructor(
        instructions: List<Instruction>, address: Long, deallocate: Long,
        data: Long, local: Long, size: Long
    ) {
        require(size in 1..4096 && data in 0..size - 8 && local in 0..size - 8)
        for (inline in listOf(true, false)) {
            val result = path(instructions, address, deallocate, data, local, false, inline, true)
            require(result.reads.all { it in 0..size - 8 })
        }
    }

    private fun path(
        instructions: List<Instruction>, address: Long, deallocate: Long, data: Long, local: Long,
        owner: Boolean, inline: Boolean?, sized: Boolean
    ): Result {
        require(
            instructions.isNotEmpty() && instructions.size <= 8192 && address >= 0 && deallocate >= 0 && data in 0..4088 && local in 0..4088 &&
                    data + 8 <= local && (owner || inline != null)
        )
        val body = instructions.associateBy { it.offset }
        val registers = MutableList<Value>(16) { Original(it) }
        registers[7] = if (owner) Owner else Object()
        registers[4] = Stack(0)
        val stack = mutableMapOf<Long, Value>()
        val reads = mutableSetOf<Long>()
        var pointer: Long? = null
        var deleted = false
        var size: Long? = null
        var zero: Boolean? = null
        var compared = false
        var position = 0L
        val visited = mutableSetOf<Long>()
        fun top() = (registers[4] as? Stack)?.offset ?: error("String cleanup lost its frame")
        fun restored() {
            require(top() == 0L && listOf(3, 5, 12, 13, 14, 15).all { registers[it] == Original(it) }) {
                "String cleanup does not restore the System V frame"
            }
        }

        fun finish(): Result {
            restored()
            require(
                deleted == (inline == false) && (size != null) == (owner && inline != null) &&
                        (inline == null || compared)
            ) { "String cleanup does not free exactly its owned allocations" }
            return Result(pointer, size, reads)
        }

        fun read(operand: X64Instructions.Operand?): Value = when (operand) {
            is Register -> registers[operand.number].also {
                require(operand.width == 8 || operand.width == 4 && it is Constant) { "String cleanup truncates provenance" }
            }

            is Immediate -> Constant(operand.value)
            is Memory -> {
                require(operand.width == 8 && !operand.relative && operand.index == null)
                when (val base = operand.base?.let { registers[it] }) {
                    Owner -> {
                        require(owner && pointer == null && operand.displacement in 0..4088 && operand.displacement % 8 == 0L)
                        pointer = operand.displacement
                        if (inline == null) Constant(0) else Object()
                    }

                    is Object -> {
                        require(size == null) { "String cleanup reads a freed object" }
                        val field = base.offset + operand.displacement
                        require(field in 0..4088)
                        reads += field
                        when (field) {
                            data -> Buffer
                            local -> {
                                require(inline == false && compared) { "String cleanup reads heap capacity before excluding inline storage" }
                                Capacity()
                            }

                            else -> error("String cleanup reads an unrelated object field")
                        }
                    }

                    else -> error("String cleanup reads an unproven address")
                }
            }

            else -> error("String cleanup reads an unsupported operand")
        }

        fun write(register: Register, value: Value) {
            require(register.width == 8 || register.width == 4 && value is Constant)
            registers[register.number] = if (register.width == 4 && value is Constant)
                Constant(value.value and 0xffffffffL) else value
        }

        fun add(value: Value, amount: Long): Value {
            require(amount in -4096..4096)
            return when (value) {
                is Object -> Object(value.offset + amount).also { require(it.offset in 0..4088 && size == null) }
                is Stack -> Stack(value.offset + amount).also { require(it.offset in -256..0 && it.offset % 8 == 0L) }
                is Capacity -> Capacity(value.extra + amount).also { require(it.extra in 0..1) }
                else -> error("String cleanup modifies an unproven address or size")
            }
        }
        while (true) {
            require(visited.add(position)) { "String cleanup contains a loop" }
            val instruction = body[position] ?: error("String cleanup branch is not an instruction boundary")
            position += instruction.size
            val target = instruction.destination
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    val value = read(target)
                    val next = top() - 8
                    require(next >= -256)
                    stack[next] = value
                    registers[4] = Stack(next)
                }

                Operation.POP -> {
                    val register = target as? Register ?: error("Unsupported string cleanup pop")
                    require(register.width == 8 && register.number != 4)
                    val offset = top()
                    registers[register.number] =
                        stack.remove(offset) ?: error("String cleanup reads an unproven saved register")
                    registers[4] = Stack(offset + 8)
                }

                Operation.MOV -> write(
                    target as? Register ?: error("String cleanup writes memory"),
                    read(instruction.source)
                )

                Operation.LEA -> {
                    val source = instruction.source as? Memory ?: error("String cleanup lacks an address expression")
                    val register = target as? Register ?: error("String cleanup lacks an address register")
                    require(register.width == 8 && !source.relative && source.index == null && source.base != null)
                    write(register, add(registers[source.base], source.displacement))
                }

                Operation.ADD, Operation.SUB, Operation.INC -> {
                    val register = target as? Register ?: error("String cleanup modifies memory")
                    require(register.width == 8)
                    val amount = if (instruction.operation == Operation.INC) 1L else
                        (instruction.source as? Immediate)?.value ?: error("String cleanup uses a dynamic adjustment")
                    write(
                        register,
                        add(read(register), if (instruction.operation == Operation.SUB) -amount else amount)
                    )
                    zero = null
                }

                Operation.TEST -> {
                    require(owner && target == instruction.source && pointer != null && size == null)
                    zero = when (read(target)) {
                        Object() -> false
                        Constant(0) -> true
                        else -> error("String owner tests an unrelated pointer")
                    }
                }

                Operation.CMP -> {
                    require(
                        inline != null && !deleted && size == null &&
                                setOf(read(target), read(instruction.source)) == setOf(Buffer, Object(local))
                    ) {
                        "String cleanup does not compare its data pointer with its own inline storage"
                    }
                    zero = inline
                    compared = true
                }

                Operation.JCC -> {
                    val condition = zero ?: error("String cleanup branch has no proven condition")
                    require(instruction.condition in listOf(4, 5))
                    val jump = (target as? Immediate)?.value ?: error("Indirect string cleanup branch")
                    require(jump > instruction.offset && jump in body)
                    if (condition == (instruction.condition == 4)) position = jump
                }

                Operation.CALL, Operation.JMP -> {
                    val jump = (target as? Immediate)?.value ?: error("Indirect string cleanup dispatch")
                    if (instruction.operation == Operation.JMP && jump in body) {
                        require(jump > instruction.offset)
                        position = jump
                        continue
                    }
                    require(jump == deallocate - address && inline != null) { "String cleanup calls an unrelated entry" }
                    if (instruction.operation == Operation.CALL) require((8 + top()) % 16 == 0L)
                    else restored()
                    when (registers[7]) {
                        Buffer -> {
                            require(compared && inline == false && !deleted && size == null)
                            if (sized) require(registers[6] == Capacity(1)) { "String buffer deletion has an unproven byte count" }
                            deleted = true
                        }

                        Object() -> {
                            require(owner && compared && deleted == (inline == false) && size == null)
                            size = (registers[6] as? Constant)?.value
                                ?: error("String object deletion lacks a constant size")
                            require(size in 1..4096)
                        }

                        else -> error("String cleanup deletes a different allocation")
                    }
                    if (instruction.operation == Operation.JMP) return finish()
                    for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) registers[register] = Original(16 + register)
                    zero = null
                }

                Operation.RET -> return finish()
                else -> error("Unsupported string cleanup instruction: ${instruction.operation}")
            }
        }
    }
}
