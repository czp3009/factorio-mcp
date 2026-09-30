package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Complete selected key post-update paths: typed lookups and bounded zero stores, with no change to the held byte. */
internal object KeyPostUpdate {
    data class Store(val lookup: Long, val site: Long, val offset: Long, val width: Int)
    data class Proof(val path: List<Long>, val lookups: List<Long>, val stores: List<Store>)

    fun resolve(image: ElfImage, header: EventHeader, update: InputStateKeyUpdate): Map<Long, Proof> {
        val function = image.symbol("_ZN10InputState10postUpdateERK5Event")
        return analyze(
            X64ControlFlow.resolve(image, function),
            image.symbol(InputStateKeyUpdate.LOOKUP).address - function.address, header, update
        )
    }

    fun analyze(
        flow: X64ControlFlow,
        lookup: Long,
        header: EventHeader,
        update: InputStateKeyUpdate
    ): Map<Long, Proof> {
        require(
            header.extent in 16..4096 && update.requiredValueSize in 1..256 &&
                    update.held in 0 until update.requiredValueSize
        )
        val arguments = SysVArgumentFlow(flow)
        val frame = SysVLocalArgument(flow)
        val scalars = ScalarExpression(flow, mapOf(6 to header.extent.toLong()))
        val type = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, header.type), 4)
        val code = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, update.code), 4)
        fun key(value: ScalarExpression.Value): Boolean = when (value) {
            is ScalarExpression.Input -> value.width == 4 && value.field == code
            is ScalarExpression.Narrow -> value.width == 4 && value.value.width == 4 && key(value.value)
            else -> false
        }
        return listOf(update.press.kind, update.release.kind).associateWith { kind ->
            val path = mutableListOf<Long>()
            val calls = mutableListOf<Long>()
            val stores = mutableListOf<Store>()
            val pointers = mutableMapOf<Int, Long>()
            val pushes = mutableListOf<Register>()
            val pops = mutableListOf<Register>()
            var site = 0L
            var returned = false
            while (!returned) {
                require(path.size < 256 && site !in path) { "Key post-update loops or exceeds bounds" }
                path += site
                val instruction = flow.body.getValue(site)
                for (operand in listOf(
                    instruction.source,
                    instruction.destination.takeIf {
                        instruction.operation in listOf(
                            Operation.CMP,
                            Operation.TEST
                        )
                    })) {
                    if (operand is Memory && instruction.operation != Operation.LEA) {
                        val local = frame.address(site, operand)
                        if (local != null) {
                            val stack = checkNotNull(frame.registers(site)[4])
                            require(local >= stack - 128 && local <= -operand.width)
                        } else require(arguments.memory(site, operand) in setOf(type, code)) {
                            "Key post-update reads an unverified event field"
                        }
                    }
                }
                var next = site + instruction.size
                when (instruction.operation) {
                    Operation.PUSH -> {
                        val register = instruction.destination as? Register ?: error("Unknown saved register")
                        require(register.width == 8 && register.number in listOf(0, 3, 5, 12, 13, 14, 15))
                        pushes += register
                    }

                    Operation.POP -> {
                        val register = instruction.destination as? Register ?: error("Unknown restored register")
                        require(register.width == 8 && register.number in listOf(0, 3, 5, 12, 13, 14, 15))
                        pops += register
                        pointers.remove(register.number)
                    }

                    Operation.MOV -> when (val destination = instruction.destination) {
                        is Register -> {
                            val source = instruction.source as? Register
                            val pointer =
                                source?.takeIf { it.width == 8 && destination.width == 8 }?.let { pointers[it.number] }
                            if (pointer != null) pointers[destination.number] = pointer
                            else pointers.remove(destination.number)
                        }

                        is Memory -> {
                            val pointer =
                                checkNotNull(pointers[destination.base]) { "Key post-update writes outside its returned key state" }
                            val offset = pointer + destination.displacement
                            require(
                                !destination.relative && destination.index == null && destination.width in listOf(
                                    1,
                                    2,
                                    4,
                                    8
                                ) &&
                                        offset >= 0 && offset <= update.requiredValueSize - destination.width &&
                                        update.held !in offset until offset + destination.width && instruction.source == Immediate(
                                    0
                                )
                            )
                            stores += Store(calls.last(), site, offset, destination.width)
                        }

                        else -> error("Unsupported key post-update move")
                    }

                    Operation.CALL -> {
                        require(instruction.destination == Immediate(lookup)) { "Key post-update calls an unverified entry" }
                        require(arguments.register(site, 7) == SysVArgumentFlow.Reference(7, update.map))
                        require(key(scalars.before(site, Register(6, 4))))
                        require(checkNotNull(frame.registers(site)[4]) and 15L == 8L) { "Key lookup call has a misaligned frame" }
                        calls += site
                        // A lookup may relocate its container. Never validate a store through an older returned pointer.
                        pointers.clear()
                        pointers[0] = 0
                    }

                    Operation.JCC -> {
                        val condition = scalars.branch(site)
                        require(ScalarExpression.inputs(condition).all { it.field == type })
                        if (ScalarExpression.evaluate(condition) { kind } != 0L)
                            next = (instruction.destination as? Immediate)?.value
                                ?: error("Indirect key post-update branch")
                    }

                    Operation.JMP -> next = (instruction.destination as? Immediate)?.value
                        ?: error("Indirect key post-update branch")

                    Operation.CMP, Operation.TEST, Operation.NOP, Operation.ENDBR -> Unit
                    Operation.ADD, Operation.SUB -> {
                        val destination =
                            instruction.destination as? Register ?: error("Unknown key post-update adjustment")
                        val amount =
                            (instruction.source as? Immediate)?.value ?: error("Nonconstant key post-update adjustment")
                        if (destination != Register(4, 8)) {
                            val original = checkNotNull(arguments.register(site, destination.number))
                            val displacement = if (instruction.operation == Operation.ADD) amount else -amount
                            require(
                                destination.width == 8 && original.argument == 7 &&
                                        original.offset + displacement == update.map
                            ) { "Unverified key map adjustment" }
                        }
                        pointers.remove(destination.number)
                    }

                    Operation.LEA -> {
                        val destination =
                            instruction.destination as? Register ?: error("Unknown key map address destination")
                        val source = instruction.source as? Memory ?: error("Unknown key map address")
                        require(
                            destination.width == 8 &&
                                    arguments.memory(site, source)?.reference == SysVArgumentFlow.Reference(
                                7,
                                update.map
                            )
                        ) {
                            "Unverified key map address"
                        }
                        pointers.remove(destination.number)
                    }

                    Operation.RET -> {
                        require(frame.registers(site)[4] == 0L && pushes == pops.reversed()) {
                            "Key post-update does not restore its frame"
                        }
                        returned = true
                    }

                    else -> error("Key post-update has an unsupported effect: ${instruction.operation}")
                }
                if (!returned) require(next in flow.successors.getValue(site))
                site = next
            }
            if (kind == update.press.kind) require(calls.isEmpty() && stores.isEmpty())
            else require(calls.isNotEmpty() && stores.isNotEmpty() && calls.all { call -> stores.any { it.lookup == call } })
            Proof(path, calls, stores)
        }
    }
}
