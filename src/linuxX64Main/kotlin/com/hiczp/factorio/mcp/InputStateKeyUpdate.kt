package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Selected key-case argument and returned-state store evidence. Construction, lookup layout and cleanup are separate. */
internal data class InputStateKeyUpdate(
    val code: Long,
    val map: Long,
    val held: Long,
    val requiredValueSize: Long,
    val press: Case,
    val release: Case,
) {
    data class Store(val site: Long, val offset: Long, val width: Int, val value: Long)
    data class Case(val kind: Long, val lookup: Long, val code: Long, val map: Long, val stores: List<Store>)

    companion object {
        const val LOOKUP = "_ZN7FlatMapI12SDL_ScancodeN10InputState8KeyStateESt4lessIvEE17private_subscriptERKS0_"

        fun resolve(image: ElfImage, stateSize: Long, header: EventHeader): InputStateKeyUpdate {
            val function = image.symbol("_ZN10InputState6updateERK5Event")
            val lookup = image.symbol(LOOKUP)
            EhFrames(image).function(lookup)
            val tables = X64JumpTables.resolve(image, function)
            val flow = X64ControlFlow(X64Instructions(image.functionBytes(function, 32768)).all(8192), tables)
            return analyze(flow, tables.single(), lookup.address - function.address, stateSize, header)
        }

        fun analyze(
            flow: X64ControlFlow, table: X64JumpTables.Table, lookup: Long, stateSize: Long,
            header: EventHeader
        ): InputStateKeyUpdate {
            require(stateSize in 8..(16 * 1024 * 1024) && header.extent in 16..4096)
            require(header.type in 0..header.extent - 4L && header.time in 0..header.extent - 8L)
            val extents = mapOf(6 to header.extent.toLong())
            val scalars = ScalarExpression(flow, extents)
            val arguments = SysVArgumentFlow(flow)
            val type = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, header.type), 4)

            // Invert only an exact 32-bit type-plus-constant index, including wrapping enum bases.
            // No declaration-order assumption or remembered native event kind is used.
            fun bias(value: ScalarExpression.Value): Long = when (value) {
                is ScalarExpression.Input -> {
                    require(value.width == 4 && value.field == type)
                    0
                }

                is ScalarExpression.Narrow -> {
                    require(value.width == 4 && value.value.width == 4)
                    bias(value.value)
                }

                is ScalarExpression.Binary -> {
                    require(value.width == 4 && value.right is ScalarExpression.Literal && value.right.width == 4)
                    when (value.operation) {
                        Operation.ADD -> bias(value.left) + value.right.value
                        Operation.SUB -> bias(value.left) - value.right.value
                        else -> error("Key switch has a non-affine type index")
                    } and 0xffffffffL
                }

                else -> error("Key switch is not indexed by the original event type")
            }
            require(table.index.width in listOf(4, 8)) { "Keyboard switch cannot use a byte-only discriminator" }
            val index = scalars.before(table.guard, Register(table.index.number, 4))
            val base = bias(index)
            val admission = ScalarBranchPath(flow, extents)
            val candidates = table.targets.mapIndexedNotNull { slot, target ->
                val kind = (slot.toLong() - base) and 0xffffffffL
                try {
                    admission.to(table.guard) {
                        require(it.field == type)
                        kind
                    }
                    require(ScalarExpression.evaluate(index) { kind } == slot.toLong())
                    var site = target
                    var call: Long? = null
                    repeat(32) {
                        if (call != null) return@repeat
                        val instruction = flow.body.getValue(site)
                        if (instruction.operation == Operation.CALL) {
                            require(instruction.destination == Immediate(lookup))
                            call = site
                        } else {
                            require(
                                instruction.operation in listOf(
                                    Operation.MOV, Operation.LEA, Operation.ADD,
                                    Operation.SUB, Operation.NOP, Operation.ENDBR
                                )
                            )
                            require(
                                instruction.destination is Register || instruction.operation in listOf(
                                    Operation.NOP,
                                    Operation.ENDBR
                                )
                            )
                            if (instruction.source is Memory && instruction.operation != Operation.LEA) {
                                val read = checkNotNull(arguments.source(site))
                                require(
                                    read.reference.argument == 6 && read.width == 4 &&
                                            read.reference.offset in 0..header.extent - 4L
                                )
                            }
                            require(flow.successors.getValue(site) == listOf(site + instruction.size))
                            site += instruction.size
                        }
                    }
                    val at = checkNotNull(call) { "Key case has no bounded lookup prefix" }
                    val receiver = checkNotNull(arguments.register(at, 7))
                    require(receiver.argument == 7 && receiver.offset in 0..stateSize - 8)
                    val key = scalars.before(at, Register(6, 4))
                    fun input(value: ScalarExpression.Value): ScalarExpression.Input = when (value) {
                        is ScalarExpression.Input -> value.also { require(it.width == 4 && it.field.width == 4) }
                        is ScalarExpression.Narrow -> {
                            require(value.width == 4 && value.value.width == 4)
                            input(value.value)
                        }

                        else -> error("Key lookup argument is not the original 32-bit event field")
                    }

                    val code = input(key).field.reference
                    require(code.argument == 6 && code.offset in 0..header.extent - 4L)
                    val fields = listOf(header.type to 4, header.time to 8, code.offset to 4)
                    require(fields.indices.all { a ->
                        fields.indices.all { b ->
                            a == b ||
                                    fields[a].first + fields[a].second <= fields[b].first ||
                                    fields[b].first + fields[b].second <= fields[a].first
                        }
                    })
                    val stores = stores(flow, at + flow.body.getValue(at).size)
                    Case(kind, at, code.offset, receiver.offset, stores)
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: IllegalStateException) {
                    null
                }
            }
            val press = candidates.single {
                it.stores.size == 1 && it.stores.single().width == 1 &&
                        it.stores.single().value == 1L
            }
            val held = press.stores.single().offset
            val release = candidates.single {
                it.kind != press.kind && it.code == press.code && it.map == press.map &&
                        it.stores.all { store -> store.value == 0L } && it.stores.any { store -> store.offset == held && store.width == 1 }
            }
            val size = (press.stores + release.stores).maxOf { it.offset + it.width }
            return InputStateKeyUpdate(press.code, press.map, held, size, press, release)
        }

        private fun stores(flow: X64ControlFlow, start: Long): List<Store> {
            val pointers = mutableMapOf(0 to 0L)
            val result = mutableListOf<Store>()
            var site = start
            repeat(32) {
                val instruction = flow.body.getValue(site)
                if (instruction.operation in listOf(Operation.JMP, Operation.RET, Operation.POP) ||
                    instruction.operation == Operation.ADD && instruction.destination == Register(4, 8)
                ) {
                    require(result.isNotEmpty())
                    return result
                }
                when (instruction.operation) {
                    Operation.MOV -> when (val destination = instruction.destination) {
                        is Register -> {
                            val source = instruction.source as? Register ?: error("Unknown returned-state copy")
                            require(destination.width == 8 && source.width == 8)
                            pointers[destination.number] = checkNotNull(pointers[source.number])
                        }

                        is Memory -> {
                            val value = instruction.source as? Immediate ?: error("Nonconstant key-state store")
                            val base = checkNotNull(pointers[destination.base])
                            val offset = base + destination.displacement
                            require(
                                !destination.relative && destination.index == null && destination.width in listOf(
                                    1,
                                    2,
                                    4,
                                    8
                                ) &&
                                        offset >= 0 && offset <= 256 - destination.width && value.value in 0..1
                            )
                            require(result.none { it.offset < offset + destination.width && offset < it.offset + it.width })
                            result += Store(site, offset, destination.width, value.value)
                        }

                        else -> error("Unsupported key-state store")
                    }

                    Operation.NOP, Operation.ENDBR -> Unit
                    else -> error("Key-state stores have an unsupported effect")
                }
                require(flow.successors.getValue(site) == listOf(site + instruction.size))
                site += instruction.size
            }
            error("Key-state store prefix exceeds bound")
        }
    }
}
