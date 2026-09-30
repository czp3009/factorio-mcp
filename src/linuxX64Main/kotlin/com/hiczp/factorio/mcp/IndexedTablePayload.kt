package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Associates a lookup result with header stores in one freshly indexed, typed queue element. */
internal object IndexedTablePayload {
    data class Proof(
        val store: Long, val code: Long, val queue: SysVArgumentFlow.Read,
        val kind: ScalarExpression.Value
    )

    fun analyze(
        flow: X64ControlFlow, table: GuardedByteTable.Proof, header: EventHeader,
        extents: Map<Int, Long>, queueCalls: Set<Long>
    ): Proof {
        require(queueCalls.isNotEmpty() && queueCalls.all { it in flow.reachable && flow.body[it]?.operation == Operation.CALL })
        val arguments = SysVArgumentFlow(flow)
        val scalars = ScalarExpression(flow, extents)
        val lookup = flow.body.getValue(table.load)
        require(lookup.operation == Operation.MOV && (lookup.destination as? Register)?.width == 4)
        fun definition(site: Long, register: Int): Instruction = scalars.definition(site, register)
        fun stride(site: Long, register: Int, depth: Int = 0): Long {
            require(depth < 16)
            val instruction = definition(site, register)
            val target = instruction.destination as? Register ?: error("Element index has no register definition")
            require(target.number == register)
            if (target.width == 4) {
                require(
                    instruction.operation in listOf(
                        Operation.MOV, Operation.MOVZX, Operation.ADD, Operation.SUB,
                        Operation.AND, Operation.OR, Operation.XOR, Operation.INC, Operation.DEC
                    )
                )
                return 1
            }
            require(target.width == 8)
            val factor = when (instruction.operation) {
                Operation.SHL -> {
                    val count = (instruction.source as? Immediate)?.value ?: error("Variable element stride")
                    require(count in 0..12)
                    1L shl count.toInt()
                }

                Operation.LEA -> {
                    val source = instruction.source as? Memory ?: error("Invalid element address")
                    require(
                        !source.relative && source.displacement == 0L &&
                                source.base == register && source.index == register && source.scale in listOf(
                            1,
                            2,
                            4,
                            8
                        )
                    )
                    1L + source.scale
                }

                else -> error("Unsupported element stride operation")
            }
            val prior = stride(instruction.offset, register, depth + 1)
            require(prior <= 4096 / factor)
            return prior * factor
        }

        val candidates = mutableListOf<Proof>()
        val rejected = mutableListOf<String>()
        for (store in flow.instructions) {
            if (store.offset !in flow.reachable || store.operation != Operation.MOV) continue
            val memory = store.destination as? Memory ?: continue
            val source = store.source as? Register ?: continue
            if (source.width != 4 || memory.width != 4 || source != lookup.destination) continue
            val candidate = try {
                require(definition(store.offset, source.number) == lookup)
                val base = checkNotNull(memory.base)
                val index = checkNotNull(memory.index)
                require(!memory.relative && memory.scale in listOf(1, 2, 4, 8) && base != index)
                val code = memory.displacement
                val known = listOf(header.type to 4, header.time to 8, code to 4)
                require(known.all { it.first >= 0 && it.first <= header.extent - it.second })
                require(known.indices.all { left ->
                    known.indices.all { right ->
                        left == right ||
                                known[left].first + known[left].second <= known[right].first ||
                                known[right].first + known[right].second <= known[left].first
                    }
                })
                val baseDefinition = definition(store.offset, base)
                require(baseDefinition.operation == Operation.MOV && baseDefinition.destination == Register(base, 8))
                val queue = arguments.source(baseDefinition.offset) ?: error("Element base lacks queue provenance")
                require(queue.width == 8 && queue.reference.offset in 0..4096 - 8)
                require(queueCalls.any { arguments.register(it, 7) == queue.reference.copy(offset = 0) }) {
                    "Element storage has no matching typed queue receiver"
                }
                require(stride(store.offset, index) * memory.scale == header.extent.toLong())
                val indexDefinition = definition(store.offset, index)
                val block = mutableListOf<Instruction>()
                var cursor = store.offset
                while (cursor != indexDefinition.offset) {
                    require(block.size < 64)
                    block += flow.body.getValue(cursor)
                    cursor = flow.predecessors[cursor]?.singleOrNull()
                        ?: error("Element stores have an intervening control-flow entry")
                }
                val writes = block.filter {
                    it.destination is Memory &&
                            it.operation !in listOf(Operation.CMP, Operation.TEST, Operation.SCALAR_COMPARE)
                }
                require(block.none {
                    it.operation in listOf(
                        Operation.CALL,
                        Operation.JMP,
                        Operation.JCC,
                        Operation.RET
                    )
                })
                for (write in writes) {
                    val output = write.destination as Memory
                    require(write.operation in listOf(Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV))
                    require(
                        output.copy(width = 4, displacement = code) == memory &&
                                definition(write.offset, base) == baseDefinition && definition(
                            write.offset,
                            index
                        ) == indexDefinition
                    )
                    require(
                        output.width in 1..16 && output.displacement >= 0 &&
                                output.displacement <= header.extent - output.width
                    )
                }
                fun field(offset: Long, width: Int): Instruction = writes.filter {
                    val output = it.destination as Memory
                    output.displacement < offset + width && offset < output.displacement + output.width
                }.single().also { require(it.destination == memory.copy(displacement = offset, width = width)) }
                require(field(code, 4) == store)
                field(header.time, 8)
                val typeStore = field(header.type, 4)
                require(typeStore.operation == Operation.MOV)
                val kind = when (val value = typeStore.source) {
                    is Register -> {
                        require(value.width == 4)
                        scalars.before(typeStore.offset, value)
                    }

                    is Immediate -> ScalarExpression.Literal(value.value, 4)
                    else -> error("Native kind has an unsupported value")
                }
                Proof(store.offset, code, queue, kind)
            } catch (failure: IllegalArgumentException) {
                rejected += "${store.offset}: ${failure.message}"
                continue
            } catch (failure: IllegalStateException) {
                rejected += "${store.offset}: ${failure.message}"
                continue
            } catch (failure: NoSuchElementException) {
                rejected += "${store.offset}: ${failure.message}"
                continue
            }
            candidates += candidate
        }
        return candidates.singleOrNull() ?: error("Lookup has no unique typed event payload association: $rejected")
    }
}
