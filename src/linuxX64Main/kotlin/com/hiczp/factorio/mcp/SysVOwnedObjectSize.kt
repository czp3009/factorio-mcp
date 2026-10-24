package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Complete-object bounds proven by a typed owner's destructor, including its null-pointer path. */
internal data class OwnedObjectSize(val pointer: Long, val size: Long)

internal object SysVOwnedObjectSize {
    private sealed interface Value

    private data object Owner : Value

    private data object Object : Value

    private data class Original(val register: Int) : Value

    private data class Stack(val offset: Long) : Value

    private data class Constant(val value: Long) : Value

    /**
     * An optimized typed owner may inline child destruction and tail-delete the owned complete
     * object.
     */
    fun resolveInlined(image: ElfImage, ownerDestructor: String): OwnedObjectSize {
        val owner = image.symbol(ownerDestructor)
        val deallocate = image.symbol("_ZdlPvm")
        for (function in listOf(owner, deallocate)) EhFrames(image).function(function)
        return analyzeInlined(image.functionBytes(owner, 1024), owner.address, deallocate.address)
    }

    fun analyzeInlined(bytes: BinaryView, address: Long, deallocate: Long): OwnedObjectSize {
        require(
            bytes.size in 1..1024 &&
                address >= 0 &&
                address <= Long.MAX_VALUE - bytes.size &&
                deallocate >= 0
        )
        val values = SysVReceiverFlow(bytes, address, 4096)
        val flow = X64ControlFlow(values.instructions)
        val exits =
            flow.instructions.filter {
                it.offset in flow.reachable && flow.successors.getValue(it.offset).isEmpty()
            }
        val tail =
            exits.singleOrNull {
                it.operation == Operation.JMP && it.destination == Immediate(deallocate - address)
            } ?: error("Typed owner lacks a unique complete-object deletion tail")
        val registers = values.before(tail.offset)
        val owned =
            registers[7] as? SysVReceiverFlow.Pointer
                ?: error("Owner deletion does not receive an owned pointer")
        require(
            owned.base == SysVReceiverFlow.Receiver() &&
                owned.offset in 0..4088 &&
                owned.offset % 8 == 0L
        )
        val size =
            (registers[6] as? SysVReceiverFlow.Constant)?.value
                ?: error("Owner deletion lacks a constant complete-object extent")
        require(size in 8..(16 * 1024 * 1024))
        fun restored(site: Long) {
            val state = values.before(site)
            require(
                state[4] == SysVReceiverFlow.Stack(0) &&
                    listOf(3, 5, 12, 13, 14, 15).all { state[it] == SysVReceiverFlow.Original(it) }
            ) {
                "Typed owner exit does not restore its caller frame"
            }
        }
        restored(tail.offset)
        val definitions = ScalarExpression(flow)
        val guards =
            flow.instructions.mapNotNull { branch ->
                if (
                    branch.offset !in flow.reachable ||
                        branch.operation != Operation.JCC ||
                        branch.condition !in setOf(4, 5)
                )
                    return@mapNotNull null
                val test = definitions.flagDefinition(branch.offset)
                val input = test.destination as? Register ?: return@mapNotNull null
                if (
                    test.operation != Operation.TEST ||
                        input.width != 8 ||
                        test.source != input ||
                        values.before(test.offset)[input.number] != owned
                )
                    return@mapNotNull null
                val target =
                    (branch.destination as? Immediate)?.value ?: error("Indirect owner null guard")
                val nonnull = if (branch.condition == 5) target else branch.offset + branch.size
                val absent = if (branch.condition == 4) target else branch.offset + branch.size
                if (!values.requiresEdge(tail.offset, branch.offset, nonnull))
                    return@mapNotNull null
                branch.offset to absent
            }
        val (guard, absent) =
            guards.singleOrNull()
                ?: error("Owned deletion has no unique original-pointer null guard")
        for (exit in exits.filter { it != tail }) {
            require(
                exit.operation == Operation.RET && values.requiresEdge(exit.offset, guard, absent)
            ) {
                "Typed owner has a nonnull exit without complete-object deletion"
            }
            restored(exit.offset)
        }
        return OwnedObjectSize(owned.offset, size)
    }

    fun resolve(
        image: ElfImage,
        ownerDestructor: String,
        objectDestructor: String,
        deallocator: String = "_ZdlPvm",
        ownerPrimaryTable: String? = null,
    ): OwnedObjectSize {
        val owner = image.symbol(ownerDestructor)
        val destroy = image.symbol(objectDestructor)
        val deallocate = image.symbol(deallocator)
        require(owner.size in 1..256)
        for (function in listOf(owner, destroy, deallocate)) EhFrames(image).function(function)
        val table = ownerPrimaryTable?.let { ItaniumVtable.resolve(image, it).addressPoint }
        return analyze(
            image.functionBytes(owner, 256),
            owner.address,
            destroy.address,
            deallocate.address,
            table,
        )
    }

    fun analyze(
        bytes: BinaryView,
        address: Long,
        destructor: Long,
        deallocate: Long,
        primaryTable: Long? = null,
    ): OwnedObjectSize {
        require(
            bytes.size in 1..256 &&
                address >= 0 &&
                address <= Long.MAX_VALUE - bytes.size &&
                destructor >= 0 &&
                deallocate >= 0 &&
                destructor != deallocate
        )
        require(primaryTable == null || primaryTable > 0 && primaryTable % 8 == 0L)
        val body = X64Instructions(bytes).all().associateBy { it.offset }
        fun path(present: Boolean): OwnedObjectSize {
            val registers = (0..15).associateWith<Int, Value> { Original(it) }.toMutableMap()
            registers[7] = Owner
            registers[4] = Stack(0)
            val stack = mutableMapOf<Long, Value>()
            var pointer: Long? = null
            var zero: Boolean? = null
            var destroyed = false
            var size: Long? = null
            var tableInitialized = false
            val visited = mutableSetOf<Long>()
            fun top() = (registers[4] as? Stack)?.offset ?: error("Unknown owner destructor frame")
            fun read(register: Register): Value {
                val value = registers.getValue(register.number)
                require(register.width == 8 || register.width == 4 && value is Constant) {
                    "Owner destructor truncates provenance"
                }
                return value
            }

            fun member(memory: Memory): Long {
                require(
                    memory.width == 8 &&
                        !memory.relative &&
                        memory.index == null &&
                        registers[memory.base] == Owner &&
                        memory.displacement in 0..4096
                ) {
                    "Unproven owner pointer member"
                }
                return memory.displacement
            }

            var position = 0L
            while (true) {
                require(visited.add(position)) { "Owner destructor contains a loop" }
                val instruction =
                    body[position] ?: error("Owner branch is not an instruction boundary")
                position += instruction.size
                when (instruction.operation) {
                    Operation.NOP,
                    Operation.ENDBR -> Unit
                    Operation.PUSH -> {
                        val register =
                            instruction.destination as? Register ?: error("Unsupported owner push")
                        require(register.width == 8)
                        val value = read(register)
                        val next = top() - 8
                        require(next >= -256)
                        stack[next] = value
                        registers[4] = Stack(next)
                    }

                    Operation.POP -> {
                        val register =
                            instruction.destination as? Register ?: error("Unsupported owner pop")
                        require(register.width == 8 && register.number != 4)
                        val offset = top()
                        registers[register.number] =
                            stack.remove(offset) ?: error("Owner reads an unproven saved register")
                        registers[4] = Stack(offset + 8)
                    }

                    Operation.MOV -> {
                        val value =
                            when (val source = instruction.source) {
                                is Register -> read(source)
                                is Immediate -> Constant(source.value)
                                is Memory -> {
                                    val offset = member(source)
                                    require(pointer == null) {
                                        "Owner loads multiple pointer candidates"
                                    }
                                    require(
                                        primaryTable == null || tableInitialized && offset >= 8
                                    ) {
                                        "Owned pointer overlaps the verified primary table"
                                    }
                                    pointer = offset
                                    if (present) Object else Constant(0)
                                }

                                else -> error("Unsupported owner move")
                            }
                        when (val target = instruction.destination) {
                            is Register -> {
                                require(target.width == 8 || target.width == 4 && value is Constant)
                                registers[target.number] =
                                    if (target.width == 4 && value is Constant)
                                        Constant(value.value and 0xffffffffL)
                                    else value
                            }

                            is Memory -> {
                                val offset = member(target)
                                if (
                                    primaryTable != null &&
                                        offset == 0L &&
                                        value == Constant(primaryTable)
                                ) {
                                    require(pointer == null && !tableInitialized)
                                    tableInitialized = true
                                } else
                                    require(
                                        offset == pointer &&
                                            value == Constant(0) &&
                                            (!present || size != null)
                                    ) {
                                        "Owner destructor writes outside its cleared pointer member"
                                    }
                            }

                            else -> error("Unsupported owner destination")
                        }
                    }

                    Operation.LEA -> {
                        val target =
                            instruction.destination as? Register
                                ?: error("Owner table has no register")
                        val source =
                            instruction.source as? Memory ?: error("Owner table has no address")
                        val next = address + instruction.offset + instruction.size
                        require(
                            primaryTable != null &&
                                target.width == 8 &&
                                target.number !in listOf(4, 5) &&
                                source.relative &&
                                source.base == null &&
                                source.index == null &&
                                source.displacement >= -next &&
                                source.displacement <= Long.MAX_VALUE - next &&
                                next + source.displacement == primaryTable
                        ) {
                            "Owner destructor address is not its verified primary table"
                        }
                        registers[target.number] = Constant(primaryTable)
                    }

                    Operation.ADD,
                    Operation.SUB -> {
                        require(instruction.destination == Register(4, 8)) {
                            "Owner changes the object pointer"
                        }
                        val amount =
                            (instruction.source as? Immediate)?.value
                                ?: error("Dynamic owner frame")
                        require(amount in 0..256 && amount % 8 == 0L)
                        val next =
                            top() + if (instruction.operation == Operation.ADD) amount else -amount
                        require(next in -256..0)
                        registers[4] = Stack(next)
                        zero = null
                    }

                    Operation.TEST -> {
                        val register =
                            instruction.destination as? Register ?: error("Unsupported owner test")
                        require(
                            register.width == 8 && instruction.source == register && pointer != null
                        )
                        zero =
                            when (read(register)) {
                                Object -> false
                                Constant(0) -> true
                                else -> error("Owner branch does not test the owned pointer")
                            }
                    }

                    Operation.JCC -> {
                        val flag = zero ?: error("Owner branch has no proven pointer condition")
                        require(instruction.condition in listOf(4, 5)) {
                            "Unsupported owner pointer condition"
                        }
                        val target =
                            (instruction.destination as? Immediate)?.value
                                ?: error("Indirect owner branch")
                        require(target > instruction.offset)
                        if (flag == (instruction.condition == 4)) position = target
                    }

                    Operation.CALL -> {
                        val relative =
                            (instruction.destination as? Immediate)?.value
                                ?: error("Indirect owner call")
                        require(relative >= -address && relative <= Long.MAX_VALUE - address)
                        require(present && registers[7] == Object && (8 + top()) % 16 == 0L) {
                            "Unproven owner call receiver or frame"
                        }
                        when (address + relative) {
                            destructor -> {
                                require(!destroyed && size == null) {
                                    "Object destruction is repeated or late"
                                }
                                destroyed = true
                            }

                            deallocate -> {
                                require(destroyed && size == null) {
                                    "Object deallocation is repeated or precedes destruction"
                                }
                                size =
                                    (registers[6] as? Constant)?.value
                                        ?: error("Owner lacks a constant delete size")
                                require(size in 1..(64 * 1024 * 1024)) {
                                    "Owned object size exceeds bound"
                                }
                            }

                            else -> error("Unexpected owner destructor call")
                        }
                        for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) registers[register] =
                            Original(16 + register)
                        zero = null
                    }

                    Operation.RET -> {
                        require(primaryTable == null || tableInitialized) {
                            "Owner does not initialize its primary table"
                        }
                        require(
                            top() == 0L &&
                                stack.isEmpty() &&
                                listOf(3, 5, 12, 13, 14, 15).all { registers[it] == Original(it) }
                        ) {
                            "Owner destructor does not restore the System V frame"
                        }
                        require(destroyed == present && (size != null) == present) {
                            "Owner destructor does not free exactly the present object"
                        }
                        return OwnedObjectSize(checkNotNull(pointer), size ?: 0)
                    }

                    else ->
                        error("Unsupported owner destructor instruction: ${instruction.operation}")
                }
            }
        }

        val present = path(true)
        require(path(false).pointer == present.pointer) {
            "Owner pointer changes between null and nonnull paths"
        }
        return present
    }
}
