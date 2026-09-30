package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Selected-case reads and possible live argument registers. Liveness does not establish a callee's parameter ABI. */
internal object InputEventUses {
    data class Read(val site: Long, val offset: Long, val width: Int)
    data class PossibleArgument(val site: Long, val target: Long, val registers: Set<Int>)
    data class Proof(val reads: Set<Read>, val possibleArguments: Set<PossibleArgument>)

    fun resolve(image: ElfImage, conversion: SdlButtonConversion): Map<Long, Proof> {
        return resolve(image, conversion.header, conversion.payload.code, conversion.kinds.values.toSet())
    }

    fun resolve(image: ElfImage, header: EventHeader, code: Long, kinds: Set<Long>): Map<Long, Proof> {
        val function = image.symbol("_ZN10InputState6updateERK5Event")
        val tables = X64JumpTables.resolve(image, function)
        return analyze(X64ControlFlow.resolve(image, function), tables.single(), header, code, kinds)
    }

    fun postUpdate(image: ElfImage, header: EventHeader, update: InputStateKeyUpdate): Map<Long, Proof> {
        val function = image.symbol("_ZN10InputState10postUpdateERK5Event")
        return postUpdate(
            X64ControlFlow.resolve(image, function),
            image.symbol(InputStateKeyUpdate.LOOKUP).address - function.address, header, update
        )
    }

    /** Reuse the typed cleanup proof's selected paths to record Event reads and possible pointer arguments. */
    fun postUpdate(
        flow: X64ControlFlow, lookup: Long, header: EventHeader,
        update: InputStateKeyUpdate
    ): Map<Long, Proof> {
        val fields = listOf(header.type to 4, update.code to 4)
        require(fields.all { (offset, width) -> offset >= 0 && offset <= header.extent - width } &&
                (header.type + 4 <= update.code || update.code + 4 <= header.type))
        require(flow.instructions.none { it.operation == Operation.MULTIPLY_WIDE })
        return KeyPostUpdate.analyze(flow, lookup, header, update).mapValues { (_, proof) ->
            inspect(flow, fields, proof.path.zipWithNext().toMap())
        }
    }

    fun analyze(
        flow: X64ControlFlow, table: X64JumpTables.Table, header: EventHeader, code: Long,
        kinds: Set<Long>
    ): Map<Long, Proof> {
        require(header.extent in 16..4096 && kinds.size == 2 && kinds.all { it in 0..0xffffffffL })
        require(header.type + 4 <= code || code + 4 <= header.type)
        require(flow.instructions.none { it.operation == Operation.MULTIPLY_WIDE })
        val fields = listOf(header.type to 4, code to 4)
        require(fields.all { (offset, width) -> offset >= 0 && offset <= header.extent - width })
        val type = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, header.type), 4)
        val scalars = ScalarExpression(flow, mapOf(6 to header.extent.toLong()))
        require(table.index.width in listOf(4, 8)) { "Event switch cannot use a byte-only discriminator" }
        val index = scalars.before(table.guard, Register(table.index.number, 4))
        require(ScalarExpression.inputs(index).map { it.field }.toSet() == setOf(type))
        return kinds.associateWith { kind ->
            val prefix = ScalarBranchPath(flow, mapOf(6 to header.extent.toLong())).to(table.guard) {
                require(it.field == type)
                kind
            }
            val selected = ScalarExpression.evaluate(index) { kind }
            require(selected in 0 until table.targets.size.toLong())
            val forced =
                prefix.zipWithNext().toMap() + (table.guard to table.guard + flow.body.getValue(table.guard).size) +
                        (table.jump to table.targets[selected.toInt()])
            inspect(flow, fields, forced)
        }
    }

    private fun inspect(flow: X64ControlFlow, fields: List<Pair<Long, Int>>, forced: Map<Long, Long>): Proof {
        val incoming = mutableMapOf(0L to setOf(6))
        val pending = ArrayDeque<Long>()
        pending.add(0)
        val reads = mutableSetOf<Read>()
        val possibleArguments = mutableSetOf<PossibleArgument>()
        var steps = 0
        while (pending.isNotEmpty()) {
            require(++steps <= 32768) { "Input event pointer analysis exceeds bounds" }
            val site = pending.removeFirst()
            val instruction = flow.body.getValue(site)
            val aliases = incoming.getValue(site).toMutableSet()
            val source = instruction.source
            val destination = instruction.destination
            fun isAlias(register: Register?) = register != null && register.number in aliases
            for (operand in listOfNotNull(source, destination)) if (operand is Memory &&
                (operand.base in aliases || operand.index in aliases)
            ) {
                require(
                    !operand.relative && operand.index == null && operand.base in aliases &&
                            (operand == source && instruction.operation in listOf(
                                Operation.MOV, Operation.MOVZX,
                                Operation.MOVSX, Operation.CMP, Operation.TEST
                            ) ||
                                    operand == destination && instruction.operation in listOf(
                                Operation.CMP,
                                Operation.TEST
                            ))
                ) {
                    "Input-state handler writes, derives or transforms an event address"
                }
                require(fields.any { (offset, width) ->
                    operand.displacement >= offset &&
                            operand.width <= width && operand.displacement <= offset + width - operand.width
                }) {
                    "Input-state handler reads an uninitialized event field"
                }
                reads += Read(site, operand.displacement, operand.width)
            }
            when (instruction.operation) {
                Operation.MOV -> {
                    if (isAlias(source as? Register)) {
                        require(
                            source is Register && source.width == 8 && destination is Register &&
                                    destination.width == 8 && destination.number != 4
                        ) { "Input-state handler exposes its event pointer" }
                        aliases += destination.number
                    } else if (destination is Register) aliases -= destination.number
                }

                Operation.CALL -> {
                    require(!isAlias(destination as? Register))
                    val arguments = aliases.intersect(setOf(7, 6, 2, 1, 8, 9))
                    if (arguments.isNotEmpty()) {
                        val target =
                            (destination as? Immediate)?.value ?: error("Event reaches an untyped indirect call")
                        possibleArguments += PossibleArgument(site, target, arguments)
                    }
                    aliases.removeAll(setOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31))
                }

                Operation.POP -> if (destination is Register) aliases -= destination.number
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.RET -> require(0 !in aliases) { "Input-state handler returns its borrowed event address" }
                else -> {
                    require(!isAlias(source as? Register) && !isAlias(destination as? Register)) {
                        "Input-state handler has an unsupported event pointer use"
                    }
                    if (destination is Register && instruction.operation !in listOf(
                            Operation.CMP, Operation.TEST,
                            Operation.SCALAR_COMPARE, Operation.PUSH, Operation.JCC, Operation.JMP
                        )
                    )
                        aliases -= destination.number
                }
            }
            val next = forced[site]?.let { listOf(it) } ?: flow.successors.getValue(site)
            require(next.isNotEmpty() || instruction.operation == Operation.RET) { "Input-state handler exits through an unverified tail" }
            for (target in next) {
                require(target in flow.successors.getValue(site))
                val old = incoming[target]
                val merged = old.orEmpty() + aliases
                if (old == null || old != merged) {
                    incoming[target] = merged
                    pending.add(target)
                }
            }
        }
        return Proof(reads, possibleArguments)
    }
}
