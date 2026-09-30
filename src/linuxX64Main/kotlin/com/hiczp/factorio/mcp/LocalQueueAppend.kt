package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** A complete local-to-cursor copy, a matching last-element guard and the same cursor's element increment. */
internal class LocalQueueAppend(private val flow: X64ControlFlow) {
    data class Proof(val queue: Long, val cursor: Long, val limit: Long, val extent: Int, val commits: List<Long>)
    private data class Pointer(val member: Long, val load: Long, val adjustment: Long = 0)
    private data class Value(val pointer: Pointer? = null, val bytes: List<Long?> = List(16) { null })
    private data class State(
        val registers: MutableList<Value>, val copied: MutableMap<Int, Long>,
        var comparison: Long? = null, var limit: Long? = null
    ) {
        fun copyState() = State(registers.toMutableList(), copied.toMutableMap(), comparison, limit)
    }

    private val frame = SysVLocalArgument(flow)
    private val arguments = SysVArgumentFlow(flow)

    fun at(sink: Long, ownerSize: Long): Proof {
        require(ownerSize in 8..1048576 && flow.body[sink]?.operation == Operation.CALL)
        val queue = arguments.register(sink, 7) ?: error("Queue receiver has no original owner")
        require(queue.argument == 7 && queue.offset in 0 until ownerSize)
        val storage = frame.argument(sink, 6, 1)
        val proofs = mutableListOf<Proof>()
        for (commit in flow.instructions) {
            val target = commit.destination as? Memory ?: continue
            val stride = (commit.source as? Immediate)?.value ?: continue
            if (commit.operation != Operation.ADD || target.width != 8 || stride !in 1..4096) continue
            val member = arguments.memory(commit.offset, target) ?: continue
            if (member.reference.argument != 7 || member.reference.offset !in queue.offset..ownerSize - 8) continue
            for (load in flow.instructions) {
                val register = load.destination as? Register ?: continue
                if (load.operation != Operation.MOV || register.width != 8 || arguments.source(load.offset) != member) continue
                val limit = try {
                    copy(
                        load.offset,
                        register.number,
                        commit.offset,
                        sink,
                        member.reference.offset,
                        stride.toInt(),
                        storage,
                        ownerSize
                    )
                } catch (_: IllegalArgumentException) {
                    continue
                } catch (_: IllegalStateException) {
                    continue
                }
                proofs += Proof(queue.offset, member.reference.offset, limit, stride.toInt(), listOf(commit.offset))
            }
        }
        require(proofs.isNotEmpty()) { "No complete local queue append with a matching typed slow path" }
        val layouts = proofs.map { it.copy(commits = emptyList()) }.distinct()
        require(layouts.size == 1) { "Queue append paths disagree on their layout" }
        return layouts.single().copy(commits = proofs.flatMap { it.commits }.distinct().sorted())
    }

    private fun copy(
        load: Long, register: Int, commit: Long, sink: Long, member: Long,
        extent: Int, storage: Long, ownerSize: Long
    ): Long {
        // The selected cursor load must dominate the commit in the complete normal-entry graph.
        val visited = mutableSetOf<Long>()
        val pending = ArrayDeque<Long>()
        pending.add(0)
        while (pending.isNotEmpty()) {
            val position = pending.removeFirst()
            require(position != commit) { "Queue commit bypasses its cursor load" }
            if (position != load && visited.add(position)) pending.addAll(flow.successors.getValue(position))
        }
        require(load in flow.reachable && commit in flow.reachable)
        val needed = mutableSetOf<Long>()
        pending.add(commit)
        while (pending.isNotEmpty()) {
            val position = pending.removeFirst()
            if (position != load && needed.add(position)) pending.addAll(flow.predecessors[position].orEmpty())
        }
        require(needed.size in 1..256 && 0L !in needed)
        val counts =
            needed.associateWith { offset -> flow.predecessors[offset].orEmpty().count { it in needed } }.toMutableMap()
        val order = mutableListOf<Long>()
        pending.addAll(needed.filter { counts.getValue(it) == 0 })
        while (pending.isNotEmpty()) {
            val position = pending.removeFirst()
            order += position
            for (next in flow.successors.getValue(position).filter { it in needed }) {
                counts[next] = counts.getValue(next) - 1
                if (counts[next] == 0) pending.add(next)
            }
        }
        require(order.size == needed.size) { "Queue copy contains a cycle" }
        val cursor = Pointer(member, load)
        val initial = State(MutableList(32) { Value() }, mutableMapOf())
        initial.registers[register] = Value(cursor)
        val incoming =
            flow.successors.getValue(load).filter { it in needed }.associateWith { initial.copyState() }.toMutableMap()

        fun slowPath(start: Long): Boolean {
            var position = start
            val seen = mutableSetOf<Long>()
            repeat(16) {
                if (position == sink) return true
                if (!seen.add(position) || position == commit) return false
                val instruction = flow.body[position] ?: return false
                if (instruction.destination is Memory || instruction.operation !in listOf(
                        Operation.MOV, Operation.LEA, Operation.ADD, Operation.JMP, Operation.NOP, Operation.ENDBR
                    )
                ) return false
                position = flow.successors.getValue(position).singleOrNull() ?: return false
            }
            return false
        }
        for (position in order) {
            val state = incoming[position]?.copyState() ?: error("Queue copy has an unproven entry")
            if (position == commit) continue
            val instruction = flow.body.getValue(position)
            fun read(operand: X64Instructions.Operand?): Value = when (operand) {
                is Register -> state.registers[operand.number].let { value ->
                    Value(
                        value.pointer.takeIf { operand.width == 8 }, value.bytes.take(operand.width)
                    )
                }

                is Memory -> {
                    val local = frame.address(position, operand)
                    if (local != null) {
                        require(local >= checkNotNull(frame.registers(position)[4]) && local <= -operand.width)
                        Value(bytes = List(operand.width) { local + it })
                    } else {
                        val origin = arguments.memory(position, operand)
                        Value(
                            if (origin?.reference?.argument == 7 && origin.width == 8 &&
                                origin.reference.offset in 0..ownerSize - 8
                            ) Pointer(origin.reference.offset, position) else null
                        )
                    }
                }

                else -> Value()
            }

            fun write(operand: X64Instructions.Operand?, value: Value) {
                when (operand) {
                    is Register -> state.registers[operand.number] = Value(
                        value.pointer.takeIf { operand.width == 8 },
                        List(16) { if (it < operand.width) value.bytes.getOrNull(it) else null })

                    is Memory -> {
                        require(!operand.relative && operand.index == null)
                        val pointer = operand.base?.let { state.registers[it].pointer }
                            ?: error("Queue copy writes through an unrelated pointer")
                        require(pointer.copy(adjustment = 0) == cursor && operand.displacement in -4096..4096)
                        val offset = pointer.adjustment + operand.displacement
                        require(offset >= 0 && offset <= extent - operand.width) { "Queue copy exceeds its element stride" }
                        for (byte in 0 until operand.width) {
                            val destination = offset.toInt() + byte
                            state.copied.remove(destination)
                            value.bytes.getOrNull(byte)?.let { state.copied[destination] = it }
                        }
                    }

                    else -> error("Unsupported queue copy destination")
                }
            }

            val comparison = state.comparison
            state.comparison = null
            when (instruction.operation) {
                Operation.MOV, Operation.MOVZX, Operation.SCALAR_MOV, Operation.VECTOR_MOV -> {
                    write(instruction.destination, read(instruction.source))
                    state.comparison = comparison
                }

                Operation.LEA -> {
                    val address = instruction.source as? Memory ?: error("Invalid queue copy address")
                    val base = address.base?.let { state.registers[it].pointer }
                    require(address.displacement in -4096..4096)
                    write(
                        instruction.destination, Value(
                            if (!address.relative && address.index == null && base != null)
                                base.copy(adjustment = base.adjustment + address.displacement) else null
                        )
                    )
                    state.comparison = comparison
                }

                Operation.ADD, Operation.SUB -> {
                    val pointer = read(instruction.destination).pointer
                    val amount = (instruction.source as? Immediate)?.value
                    write(
                        instruction.destination, Value(
                            if (pointer != null && amount != null && amount in -4096..4096)
                                pointer.copy(adjustment = pointer.adjustment + if (instruction.operation == Operation.ADD) amount else -amount)
                            else null
                        )
                    )
                }

                Operation.CMP -> {
                    val left = read(instruction.destination).pointer
                    val right = read(instruction.source).pointer
                    val boundary = if (left == cursor) right else if (right == cursor) left else null
                    if (boundary != null && boundary.member != member && boundary.adjustment == -extent.toLong())
                        state.comparison = boundary.member
                }

                Operation.JCC -> {
                    val target = (instruction.destination as? Immediate)?.value ?: error("Indirect queue guard")
                    if (instruction.condition == 4 && comparison != null && slowPath(target)) state.limit = comparison
                }

                Operation.JMP, Operation.NOP, Operation.ENDBR -> state.comparison = comparison
                Operation.TEST, Operation.SCALAR_COMPARE -> Unit
                Operation.CALL, Operation.PUSH, Operation.POP, Operation.RET -> error("Queue copy has an intervening call or frame change")
                else -> write(instruction.destination, Value())
            }
            for (next in flow.successors.getValue(position).filter { it in needed }) {
                val old = incoming[next]
                incoming[next] = if (old == null) state.copyState() else State(
                    old.registers.mapIndexed { index, value -> if (value == state.registers[index]) value else Value() }
                        .toMutableList(),
                    old.copied.filter { (offset, value) -> state.copied[offset] == value }.toMutableMap(),
                    old.comparison.takeIf { it == state.comparison }, old.limit.takeIf { it == state.limit })
            }
        }
        val result = incoming.getValue(commit)
        require(frame.argument(sink, 6, extent) == storage)
        require((0 until extent).all { result.copied[it] == storage + it }) {
            "Queue append does not copy the complete local argument in order"
        }
        return checkNotNull(result.limit) { "Queue append lacks its matching typed full-queue path" }
    }
}
