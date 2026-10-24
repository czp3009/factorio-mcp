package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Key-case code provenance and stores to a typed record. Existing-key search is verified
 * separately.
 */
internal data class InputStateKeyUpdate(
    val code: Long,
    val map: Long,
    val held: Long,
    val requiredValueSize: Long,
    val press: Case,
    val release: Case,
) {
    data class Store(val site: Long, val offset: Long, val width: Int, val value: Long)

    data class Case(
        val kind: Long,
        val lookup: Long,
        val code: Long,
        val map: Long,
        val stores: List<Store>,
        val entry: Long,
    )

    companion object {
        const val INSERT =
            "_ZNSt6vectorI4PairI12SDL_ScancodeN10InputState8KeyStateEESaIS4_EE14_M_emplace_auxIJRKS1_S3_EEEN9__gnu_cxx17__normal_iteratorIPS4_S6_EENSB_IPKS4_S6_EEDpOT_"

        fun resolve(image: ElfImage, stateSize: Long, header: EventHeader): InputStateKeyUpdate {
            val function = image.symbol("_ZN10InputState6updateERK5Event")
            val insertion = image.symbol(INSERT)
            val tables = X64JumpTables.resolve(image, function)
            return analyze(
                X64ControlFlow.resolve(image, function),
                tables.single(),
                insertion.address - function.address,
                stateSize,
                header,
            )
        }

        fun analyze(
            flow: X64ControlFlow,
            table: X64JumpTables.Table,
            insertion: Long,
            stateSize: Long,
            header: EventHeader,
        ): InputStateKeyUpdate {
            require(stateSize in 8..16 * 1024 * 1024 && header.extent in 16..4096)
            val scalars = ScalarExpression(flow, mapOf(6 to header.extent.toLong()))
            val arguments = SysVArgumentFlow(flow)
            val type = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, header.type), 4)
            fun bias(value: ScalarExpression.Value): Long =
                when (value) {
                    is ScalarExpression.Input -> {
                        require(value.width == 4 && value.field == type)
                        0
                    }
                    is ScalarExpression.Narrow -> {
                        require(value.width == 4 && value.value.width == 4)
                        bias(value.value)
                    }
                    is ScalarExpression.Binary -> {
                        require(
                            value.width == 4 &&
                                value.right is ScalarExpression.Literal &&
                                value.right.width == 4
                        )
                        when (value.operation) {
                            Operation.ADD -> bias(value.left) + value.right.value
                            Operation.SUB -> bias(value.left) - value.right.value
                            else -> error("Keyboard switch does not preserve the native type index")
                        } and 0xffffffffL
                    }
                    else -> error("Keyboard switch is not indexed by the original type")
                }
            require(table.index.width in setOf(4, 8))
            val index = scalars.before(table.guard, table.index.copy(width = 4))
            val base = bias(index)
            val calls =
                flow.instructions.filter {
                    it.offset in flow.reachable &&
                        it.operation == Operation.CALL &&
                        it.destination == Immediate(insertion)
                }
            val candidates =
                calls.map { call ->
                    val slot =
                        table.targets.indices.single {
                            flow.dominates(table.targets[it], call.offset)
                        }
                    val kind = (slot.toLong() - base) and 0xffffffffL
                    require(ScalarExpression.evaluate(index) { kind } == slot.toLong())
                    ScalarBranchPath(flow, mapOf(6 to header.extent.toLong())).to(table.guard) {
                        require(it.field == type)
                        kind
                    }
                    val owner = checkNotNull(arguments.register(call.offset, 7))
                    require(owner.argument == 7 && owner.offset in 0..stateSize - 8)
                    var code = scalars.before(call.offset, Register(2, 4))
                    while (code is ScalarExpression.Narrow) {
                        require(code.width == 4 && code.value.width == 4)
                        code = code.value
                    }
                    val input =
                        code as? ScalarExpression.Input
                            ?: error("Key insertion loses its original scalar code")
                    require(
                        input.width == 4 &&
                            input.field.width == 4 &&
                            input.field.reference.argument == 6
                    )
                    val offset = input.field.reference.offset
                    val fields = listOf(header.type to 4, header.time to 8, offset to 4)
                    require(fields.all { (at, width) -> at >= 0 && at <= header.extent - width })
                    require(
                        fields.indices.all { a ->
                            fields.indices.all { b ->
                                a == b ||
                                    fields[a].first + fields[a].second <= fields[b].first ||
                                    fields[b].first + fields[b].second <= fields[a].first
                            }
                        }
                    )
                    Case(
                        kind,
                        call.offset,
                        offset,
                        owner.offset,
                        stores(flow, call.offset + call.size),
                        table.targets[slot],
                    )
                }
            val press =
                candidates.single {
                    it.stores.size == 1 &&
                        it.stores.single().let { field -> field.width == 1 && field.value == 1L }
                }
            val held = press.stores.single().offset
            val release =
                candidates.single {
                    it.kind != press.kind &&
                        it.code == press.code &&
                        it.map == press.map &&
                        it.stores.all { field -> field.value == 0L } &&
                        it.stores.any { field -> field.offset == held && field.width == 1 }
                }
            return InputStateKeyUpdate(
                press.code,
                press.map,
                held,
                (press.stores + release.stores).maxOf { it.offset + it.width },
                press,
                release,
            )
        }

        private fun stores(flow: X64ControlFlow, start: Long): List<Store> {
            val pointers = mutableMapOf(0 to 0L)
            val result = mutableListOf<Store>()
            var site = start
            repeat(32) {
                val instruction = flow.body.getValue(site)
                if (
                    instruction.operation in setOf(Operation.JMP, Operation.RET, Operation.POP) ||
                        instruction.operation == Operation.ADD &&
                            instruction.destination == Register(4, 8)
                ) {
                    require(result.isNotEmpty())
                    return result
                }
                when (val destination = instruction.destination) {
                    is Register -> {
                        val source = instruction.source as? Register
                        val copied =
                            source
                                ?.takeIf {
                                    instruction.operation == Operation.MOV &&
                                        source.width == 8 &&
                                        destination.width == 8
                                }
                                ?.let { pointers[it.number] }
                        if (destination.number in pointers && copied == null) {
                            require(result.isNotEmpty())
                            return result
                        }
                        pointers.remove(destination.number)
                        if (copied != null) pointers[destination.number] = copied
                        require(
                            instruction.operation in
                                setOf(
                                    Operation.MOV,
                                    Operation.MOVZX,
                                    Operation.NOP,
                                    Operation.ENDBR,
                                )
                        )
                    }
                    is Memory -> {
                        require(
                            instruction.operation == Operation.MOV &&
                                !destination.relative &&
                                destination.index == null
                        )
                        val value =
                            instruction.source as? Immediate
                                ?: error("Nonconstant key record store")
                        val offset =
                            checkNotNull(pointers[destination.base]) + destination.displacement
                        require(
                            destination.width in setOf(1, 2, 4, 8) &&
                                offset in 0..256 - destination.width &&
                                value.value in 0..1
                        )
                        require(
                            result.none {
                                it.offset < offset + destination.width &&
                                    offset < it.offset + it.width
                            }
                        )
                        result += Store(site, offset, destination.width, value.value)
                    }
                    else -> require(instruction.operation in setOf(Operation.NOP, Operation.ENDBR))
                }
                require(flow.successors.getValue(site) == listOf(site + instruction.size))
                site += instruction.size
            }
            error("Key record store prefix exceeds bound")
        }
    }
}
