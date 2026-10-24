package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Native Event codes, an immutable bounded word table, and the unchanged queued mask. */
internal data class InputButtonMasks(val field: Long, val values: Map<Long, Int>) {
    companion object {
        fun resolve(
            image: ElfImage,
            input: MouseInputLayout,
            conversion: SdlButtonConversion,
        ): InputButtonMasks {
            val function = image.symbol("_ZN16InputHandlerAgui12processEventERK5Event")
            val tables = X64JumpTables.resolve(image, function)
            val flow =
                X64ControlFlow(
                    X64Instructions(image.functionBytes(function, 32768)).all(8192),
                    tables,
                )
            val target =
                image.symbol(
                    "_ZNSt5dequeIN4agui10MouseInputESaIS1_EE16_M_push_back_auxIJRKS1_EEEvDpOT_"
                )
            val sink =
                flow.instructions
                    .single {
                        it.operation == Operation.CALL &&
                            (it.destination as? Immediate)?.value?.plus(function.address) ==
                                target.address
                    }
                    .offset
            val codes =
                SdlButtonAdmission.Button.entries
                    .map { checkNotNull(conversion.admission.table.value(it.sdkValue)) }
                    .toSet()
            val masks =
                conversion.kinds.values.map { kind ->
                    val selected = select(flow, tables, conversion.header, kind)
                    analyze(
                            selected,
                            function.address,
                            sink,
                            conversion.header,
                            conversion.payload.code,
                            codes,
                            input.queue.extent.toLong(),
                        ) { address, size ->
                            require(
                                image.sections.any {
                                    it.flags and 3L == 2L &&
                                        address >= it.address &&
                                        size <= it.size &&
                                        address - it.address <= it.size - size
                                }
                            ) {
                                "Mouse mask table is not allocated read-only data"
                            }
                            image.virtualBytes(address, size)
                        }
                        .also { proof ->
                            require(
                                listOf(
                                        input.time to 8,
                                        input.alt to 1,
                                        input.control to 1,
                                        input.shift to 1,
                                    )
                                    .all {
                                        proof.field + 2 <= it.first ||
                                            it.first + it.second <= proof.field
                                    }
                            )
                        }
                }
            return masks.distinct().single()
        }

        // Only native Event discriminator branches are specialized. Handler state remains unknown.
        private fun select(
            flow: X64ControlFlow,
            tables: List<X64JumpTables.Table>,
            header: EventHeader,
            kind: Long,
        ): X64ControlFlow {
            val scalars = ScalarExpression(flow, mapOf(6 to header.extent.toLong()))
            val type = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, header.type), 4)
            fun evaluate(value: ScalarExpression.Value): Long {
                require(ScalarExpression.inputs(value).all { it.field == type })
                return ScalarExpression.evaluate(value) { kind }
            }
            val edges = mutableMapOf<Long, Long>()
            for (table in tables) {
                val index =
                    runCatching {
                            evaluate(scalars.before(table.guard, table.index.copy(width = 4)))
                        }
                        .getOrNull() ?: continue
                if (index in 0 until table.targets.size.toLong())
                    edges[table.jump] = table.targets[index.toInt()]
            }
            for (branch in
                flow.instructions.filter {
                    it.offset in flow.reachable && it.operation == Operation.JCC
                }) {
                val decision =
                    runCatching { evaluate(scalars.branch(branch.offset)) }.getOrNull() ?: continue
                edges[branch.offset] =
                    if (decision != 0L) (branch.destination as Immediate).value
                    else branch.offset + branch.size
            }
            return flow.following(edges)
        }

        fun analyze(
            flow: X64ControlFlow,
            address: Long,
            sink: Long,
            header: EventHeader,
            code: Long,
            codes: Set<Long>,
            extent: Long,
            read: (Long, Long) -> BinaryView,
        ): InputButtonMasks {
            require(codes.isNotEmpty() && codes.size <= 256 && codes.all { it in 1..255 })
            require(extent in 2..4096 && code in 0..header.extent - 4L)
            require(
                listOf(header.type to 4, header.time to 8).all {
                    code + 4 <= it.first || it.first + it.second <= code
                }
            )
            val scalars = ScalarExpression(flow, mapOf(6 to header.extent.toLong()))
            val field = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, code), 4)
            fun unwrap(value: ScalarExpression.Value): ScalarExpression.Value =
                if (value is ScalarExpression.Narrow && value.width == value.value.width)
                    unwrap(value.value)
                else value
            val rejected = mutableListOf<String>()
            val candidates =
                flow.instructions.mapNotNull { load ->
                    val entry = load.source as? Memory ?: return@mapNotNull null
                    if (
                        load.offset !in flow.reachable ||
                            load.operation != Operation.MOVZX ||
                            entry.width != 2 ||
                            (load.destination as? Register)?.width != 4 ||
                            entry.relative ||
                            entry.base == null ||
                            entry.index == null ||
                            entry.base == entry.index ||
                            entry.scale != 2 ||
                            entry.displacement != 0L
                    )
                        return@mapNotNull null
                    try {
                        val index = scalars.before(load.offset, Register(entry.index, 4))
                        require(
                            ScalarExpression.inputs(index).map { it.field }.toSet() == setOf(field)
                        )
                        val guards =
                            flow.instructions.mapNotNull { branch ->
                                if (
                                    branch.offset !in flow.reachable ||
                                        branch.operation != Operation.JCC ||
                                        branch.condition != 7 ||
                                        !flow.requiresEdge(
                                            load.offset,
                                            branch.offset,
                                            branch.offset + branch.size,
                                        )
                                )
                                    return@mapNotNull null
                                val condition =
                                    runCatching {
                                            scalars.branch(branch.offset) as ScalarExpression.Select
                                        }
                                        .getOrNull() ?: return@mapNotNull null
                                val maximum =
                                    condition.right as? ScalarExpression.Literal
                                        ?: return@mapNotNull null
                                if (
                                    unwrap(condition.left) != unwrap(index) ||
                                        maximum.value !in 0..255
                                )
                                    return@mapNotNull null
                                branch to maximum.value
                            }
                        val (guard, maximum) = guards.single()
                        val indices =
                            codes.associateWith {
                                ScalarExpression.evaluate(index) { input ->
                                        require(input.field == field)
                                        it
                                    }
                                    .also { value -> require(value in 0..maximum) }
                            }
                        val bases = scalars.definitions(load.offset, entry.base)
                        val locations =
                            bases
                                .map { base ->
                                    require(
                                        base.operation == Operation.LEA &&
                                            base.destination == Register(entry.base, 8)
                                    )
                                    val relative =
                                        base.source as? Memory
                                            ?: error("Mouse table has no address")
                                    require(
                                        relative.relative &&
                                            relative.base == null &&
                                            relative.index == null
                                    )
                                    val next = address + base.offset + base.size
                                    require(
                                        next >= address &&
                                            relative.displacement >= -next &&
                                            relative.displacement <= Long.MAX_VALUE - next
                                    )
                                    next + relative.displacement
                                }
                                .distinct()
                        val data = read(locations.single(), (maximum + 1) * 2)
                        require(data.size == (maximum + 1) * 2)
                        val selected =
                            flow.following(mapOf(guard.offset to guard.offset + guard.size))
                        require(selected.dominates(load.offset, sink)) {
                            "Native button case can bypass its mask lookup"
                        }
                        val copy = LocalFieldCopies(selected).fromRead(load.offset, sink).single()
                        require(copy.width == 2 && copy.offset in 0..extent - 2)
                        InputButtonMasks(
                            copy.offset,
                            indices.mapValues { data.unsigned(it.value * 2, 2).toInt() },
                        )
                    } catch (failure: IllegalArgumentException) {
                        rejected += "${load.offset}: ${failure.message}"
                        null
                    } catch (failure: IllegalStateException) {
                        rejected += "${load.offset}: ${failure.message}"
                        null
                    }
                }
            return candidates.singleOrNull()
                ?: error("Native mouse input has no unique bounded mask table: $rejected")
        }
    }
}
