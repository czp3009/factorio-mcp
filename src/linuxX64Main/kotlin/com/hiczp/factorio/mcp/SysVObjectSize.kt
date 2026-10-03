package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Proves the complete-object size passed by a deleting destructor to sized operator delete. */
internal object SysVObjectSize {
    private sealed interface Value

    private data object Receiver : Value

    private data class Original(val register: Int) : Value

    private data class Constant(val value: Long) : Value

    private data class Stack(val offset: Long) : Value

    fun resolve(image: ElfImage, encodedType: String): Long {
        require(encodedType.isNotEmpty()) {
            "Expected a class encoding for the destructor's nested name"
        }
        val destructor = image.symbol("_ZN${encodedType}D0Ev")
        if (destructor.size > 256) return SysVDeletingTailSize.resolve(image, destructor)
        require(destructor.size in 1..256) { "Deleting destructor exceeds analysis bound" }
        val names = setOf("_ZN${encodedType}D1Ev", "_ZN${encodedType}D2Ev")
        val ordinary =
            image
                .symbols()
                .filter { it.name in names }
                .toList()
                .groupBy { it.name }
                .map { (_, matches) ->
                    // Optimizers may retain only one of the D1/D2 aliases. Any alias used by a call
                    // must still be present.
                    val function =
                        matches.distinct().singleOrNull() ?: error("Ambiguous destructor symbol")
                    image.functionBytes(function, 1)
                    function.address
                }
                .toSet()
        return resolveDeletion(image, destructor, ordinary)
    }

    /**
     * A typed nonvirtual deleter may inline destruction before deleting its original pointer
     * argument.
     */
    fun resolveDeleter(image: ElfImage, name: String): Long =
        resolveDeletion(image, image.symbol(name), emptySet())

    private fun resolveDeletion(
        image: ElfImage,
        destructor: ElfImage.Symbol,
        ordinary: Set<Long>,
    ): Long {
        require(destructor.size in 1..256)
        val deallocate = image.symbol("_ZdlPvm")
        image.functionBytes(deallocate, 1)
        EhFrames(image).function(destructor)
        val bytes = image.functionBytes(destructor, 256)
        val body = normalBody(bytes)
        if (body.none { it.operation == Operation.CALL } && body.any { it.destination is Memory }) {
            return analyzeLeaf(bytes, destructor.address, deallocate.address)
        }
        val inlined =
            body.any {
                it.operation == Operation.JCC ||
                    (it.operation == Operation.MOV && it.destination is Memory)
            }
        val elided = if (inlined) inlineBody(body, destructor.address, deallocate.address) else null
        return analyze(body, destructor.address, ordinary, deallocate.address, elided)
    }

    private fun normalBody(bytes: BinaryView): List<Instruction> {
        val flow = X64ControlFlow(X64Instructions(bytes).all())
        return flow.instructions.filter {
            it.offset in flow.reachable &&
                it.operation != Operation.NOP &&
                it.operation != Operation.ENDBR
        }
    }

    /** An inlined leaf destructor can retain its receiver in RDI throughout destruction. */
    fun analyzeLeaf(bytes: BinaryView, address: Long, deallocate: Long): Long {
        require(bytes.size in 1..256)
        val body = X64Instructions(bytes).all()
        val terminal = body.last()
        require(
            terminal.operation == Operation.JMP &&
                (terminal.destination as? Immediate)?.value == deallocate - address
        )
        val size =
            body
                .filter { it.operation == Operation.MOV && it.destination == Register(6, 4) }
                .mapNotNull { (it.source as? Immediate)?.value }
                .distinct()
                .single()
        require(size in 8..(16 * 1024 * 1024))
        for (instruction in body.dropLast(1)) {
            require(instruction.operation !in listOf(Operation.CALL, Operation.RET))
            if (instruction.operation in listOf(Operation.JMP, Operation.JCC)) {
                val target =
                    (instruction.destination as? Immediate)?.value
                        ?: error("Indirect leaf destructor branch")
                require(target > instruction.offset && target <= terminal.offset)
            }
        }
        val flow = SysVReceiverFlow(bytes, address, size)
        val registers = flow.before(terminal.offset)
        require(
            registers[7] == SysVReceiverFlow.Receiver() &&
                registers[6] == SysVReceiverFlow.Constant(size) &&
                registers[4] == SysVReceiverFlow.Stack(0) &&
                listOf(3, 5, 12, 13, 14, 15).all { registers[it] == SysVReceiverFlow.Original(it) }
        ) {
            "Leaf deletion does not preserve its receiver, size or caller frame"
        }
        return size
    }

    /**
     * Inlined member destruction may free children; only the final original-receiver deletion
     * establishes size.
     */
    private fun inlineBody(body: List<Instruction>, address: Long, deallocate: Long): IntRange {
        val last = body.last()
        require(
            last.operation == Operation.JMP &&
                (last.destination as? Immediate)?.value == deallocate - address
        ) {
            "Inlined destructor does not finish with sized deletion"
        }
        val savedReceiver = setOf(3, 12, 13, 14, 15)
        val capture =
            body.indexOfFirst {
                it.operation == Operation.MOV &&
                    it.source == Register(7, 8) &&
                    (it.destination as? Register)?.let { register ->
                        register.width == 8 && register.number in savedReceiver
                    } == true
            }
        require(capture >= 0) { "Inlined destructor does not preserve its original receiver" }
        var suffix = body.lastIndex
        while (suffix > capture + 1) {
            val previous = body[suffix - 1]
            if (
                (previous.operation == Operation.MOV && previous.destination is Register) ||
                    previous.operation == Operation.POP ||
                    (previous.operation in setOf(Operation.ADD, Operation.SUB) &&
                        previous.destination == Register(4, 8))
            ) {
                suffix--
            } else break
        }
        require(suffix > capture + 1) {
            "Inlined destructor has no separate terminal deletion setup"
        }
        val receiverRegister = (body[capture].destination as Register).number
        val region = capture + 1 until suffix
        val firstOffset = body[region.first].offset
        val lastOffset = body[suffix].offset
        val boundaries = body.map { it.offset }.toSet()
        val volatile = setOf(0, 1, 2, 6, 7, 8, 9, 10, 11)
        fun noFrameAccess(operand: X64Instructions.Operand?) {
            require(
                when (operand) {
                    is Register -> operand.number !in setOf(4, 5)
                    is Memory -> operand.base !in setOf(4, 5) && operand.index !in setOf(4, 5)
                    else -> true
                }
            ) {
                "Inlined destruction accesses the preserved stack frame"
            }
        }
        // This region is analyzed, never executed. Back edges preserve the same receiver/frame
        // invariant;
        // every normal branch exit must reach the validated deletion suffix, regardless of the
        // member loop's trip count.
        for (index in region) {
            val instruction = body[index]
            noFrameAccess(instruction.destination)
            noFrameAccess(instruction.source)
            when (instruction.operation) {
                Operation.JMP,
                Operation.JCC -> {
                    val target =
                        (instruction.destination as? Immediate)?.value
                            ?: error("Indirect destructor branch")
                    require(target in firstOffset..lastOffset && target in boundaries) {
                        "Inlined destruction escapes its validated body or enters an instruction"
                    }
                }

                Operation.CALL ->
                    Unit // System V calls preserve the saved receiver and stack frame.
                Operation.CMP,
                Operation.TEST -> Unit
                Operation.MOV,
                Operation.MOVZX,
                Operation.LEA,
                Operation.ADD,
                Operation.SUB,
                Operation.INC,
                Operation.DEC,
                Operation.AND,
                Operation.OR,
                Operation.XOR,
                Operation.SHL,
                Operation.SHR,
                Operation.SAR -> {
                    val register = instruction.destination as? Register
                    require(
                        register == null ||
                            register.number in (volatile + savedReceiver - receiverRegister)
                    ) {
                        "Inlined destruction changes its saved receiver or frame register"
                    }
                }

                else -> error("Unsupported inlined destruction operation: ${instruction.operation}")
            }
        }
        return region
    }

    fun analyzeInlined(bytes: BinaryView, address: Long, deallocate: Long): Long {
        require(bytes.size in 1..256 && address >= 0 && address <= Long.MAX_VALUE - bytes.size)
        val body = normalBody(bytes)
        return analyze(body, address, emptySet(), deallocate, inlineBody(body, address, deallocate))
    }

    fun analyze(bytes: BinaryView, address: Long, ordinary: Set<Long>, deallocate: Long): Long {
        require(bytes.size in 1..256 && address >= 0 && address <= Long.MAX_VALUE - bytes.size)
        val body = normalBody(bytes)
        return analyze(body, address, ordinary, deallocate, null)
    }

    private fun analyze(
        body: List<Instruction>,
        address: Long,
        ordinary: Set<Long>,
        deallocate: Long,
        elided: IntRange?,
    ): Long {
        val preserved = setOf(3, 5, 12, 13, 14, 15)
        val volatile = setOf(0, 1, 2, 6, 7, 8, 9, 10, 11)
        val registers = (0..15).associateWith<Int, Value> { Original(it) }.toMutableMap()
        registers[7] = Receiver
        registers[4] = Stack(0)
        val stack = mutableMapOf<Long, Value>()
        var destroyed = false
        var deletedSize: Long? = null
        fun stackOffset() =
            (registers[4] as? Stack)?.offset ?: error("Destructor stack provenance is unknown")
        fun moveStack(offset: Long) {
            require(offset in -256..0 && offset % 8 == 0L) {
                "Unsupported destructor stack adjustment"
            }
            registers[4] = Stack(offset)
        }

        fun restored() {
            require(stackOffset() == 0L && preserved.all { registers[it] == Original(it) }) {
                "Deleting destructor does not restore its caller's frame"
            }
        }
        for ((index, instruction) in body.withIndex()) {
            if (elided != null && index in elided) {
                if (index == elided.first) {
                    require((8 + stackOffset()) % 16 == 0L) {
                        "Inlined destructor has an unaligned call frame"
                    }
                    val receiverRegister = (body[elided.first - 1].destination as Register).number
                    require(registers[receiverRegister] == Receiver)
                    val written =
                        elided
                            .map { body[it] }
                            .filter {
                                it.operation !in
                                    setOf(
                                        Operation.CMP,
                                        Operation.TEST,
                                        Operation.CALL,
                                        Operation.JMP,
                                        Operation.JCC,
                                    )
                            }
                            .mapNotNull { (it.destination as? Register)?.number }
                            .toSet()
                    val clobbered = volatile + written
                    require((0..15).filter { it !in setOf(4, 5) }.none { registers[it] is Stack }) {
                        "Inlined destructor receives an alias of its frame"
                    }
                    for (register in clobbered) registers[register] =
                        Original(16 + index * 16 + register)
                }
                continue
            }
            val destination = instruction.destination
            when (instruction.operation) {
                Operation.PUSH -> {
                    val register = destination as? Register ?: error("Unsupported destructor push")
                    require(register.width == 8)
                    val value = registers.getValue(register.number)
                    val offset = stackOffset() - 8
                    moveStack(offset)
                    stack[offset] = value
                }

                Operation.POP -> {
                    val register = destination as? Register ?: error("Unsupported destructor pop")
                    require(register.width == 8 && register.number != 4)
                    val offset = stackOffset()
                    registers[register.number] =
                        stack[offset] ?: error("Destructor reads an unproven stack slot")
                    moveStack(offset + 8)
                }

                Operation.MOV -> {
                    val target =
                        destination as? Register ?: error("Deleting destructor writes memory")
                    val value =
                        when (val source = instruction.source) {
                            is Register -> {
                                require(source.width == 8 && target.width == 8) {
                                    "Destructor truncates register provenance"
                                }
                                registers.getValue(source.number)
                            }

                            is Immediate -> {
                                require(target.width == 4 || target.width == 8)
                                Constant(
                                    if (target.width == 4) source.value and 0xffffffffL
                                    else source.value
                                )
                            }

                            else -> error("Deleting destructor reads unproven memory")
                        }
                    if (target.number == 4) {
                        require(value is Stack)
                        moveStack(value.offset)
                    } else registers[target.number] = value
                }

                Operation.ADD,
                Operation.SUB -> {
                    require(destination == Register(4, 8)) {
                        "Destructor modifies a non-stack value"
                    }
                    val amount =
                        (instruction.source as? Immediate)?.value
                            ?: error("Destructor stack adjustment is not constant")
                    require(amount in 0..256)
                    moveStack(
                        stackOffset() +
                            if (instruction.operation == Operation.ADD) amount else -amount
                    )
                }

                Operation.CALL,
                Operation.JMP -> {
                    val relative =
                        (destination as? Immediate)?.value
                            ?: error("Destructor dispatch is indirect")
                    require(relative >= -address && relative <= Long.MAX_VALUE - address)
                    val target = address + relative
                    require(registers[7] == Receiver) {
                        "Deleting destructor changes the object being freed"
                    }
                    if (instruction.operation == Operation.CALL)
                        require((8 + stackOffset()) % 16 == 0L) {
                            "Destructor call violates System V stack alignment"
                        }
                    if (target == deallocate) {
                        require(deletedSize == null) {
                            "Deleting destructor frees the object more than once"
                        }
                        val size =
                            (registers[6] as? Constant)?.value
                                ?: error("Object delete size is not proven constant")
                        require(size in 1..(16 * 1024 * 1024)) {
                            "Object size exceeds analysis bound"
                        }
                        deletedSize = size
                        if (instruction.operation == Operation.JMP) {
                            require(index == body.lastIndex) {
                                "Unexpected code after destructor tail call"
                            }
                            restored()
                            return size
                        }
                    } else {
                        require(
                            instruction.operation == Operation.CALL &&
                                target in ordinary &&
                                !destroyed &&
                                deletedSize == null
                        ) {
                            "Unexpected deleting-destructor call"
                        }
                        destroyed = true
                    }
                    for (register in volatile) registers[register] =
                        Original(16 + index * 16 + register)
                }

                Operation.RET -> {
                    require(index == body.lastIndex && deletedSize != null) {
                        "Destructor returns without proven sized deletion"
                    }
                    restored()
                    return deletedSize
                }

                else -> error("Unsupported deleting-destructor operation: ${instruction.operation}")
            }
        }
        error("Deleting destructor has no terminal sized deletion")
    }
}
