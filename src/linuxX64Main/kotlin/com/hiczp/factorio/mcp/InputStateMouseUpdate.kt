package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Mouse-case argument and primary held-mask evidence, not a replacement implementation of native update. */
internal data class InputStateMouseUpdate(
    val member: Long, val stores: Map<SdlButtonAdmission.Transition, Long>,
    val masks: Map<SdlButtonAdmission.Button, Long>
) {
    companion object {
        fun resolve(image: ElfImage, state: InputStateLayout, conversion: SdlButtonConversion): InputStateMouseUpdate {
            val function = image.symbol("_ZN10InputState6updateERK5Event")
            val tables = X64JumpTables.resolve(image, function)
            val flow = X64ControlFlow(X64Instructions(image.functionBytes(function, 32768)).all(8192), tables)
            return analyze(
                flow, tables.single(), state.size, conversion.header, conversion.payload.code,
                conversion.kinds, SdlButtonAdmission.Button.entries.associateWith {
                    checkNotNull(conversion.admission.table.value(it.sdkValue))
                })
        }

        fun analyze(
            flow: X64ControlFlow, table: X64JumpTables.Table, stateSize: Long, header: EventHeader, code: Long,
            kinds: Map<SdlButtonAdmission.Transition, Long>, codes: Map<SdlButtonAdmission.Button, Long>
        ): InputStateMouseUpdate {
            require(stateSize in 8..(16 * 1024 * 1024) && header.extent in 8..4096 && code in 0L..header.extent - 4L)
            val fields = listOf(header.type to 4, header.time to 8, code to 4)
            require(fields.all { it.first >= 0 && it.first <= header.extent - it.second })
            require(fields.withIndex().all { (index, field) ->
                fields.drop(index + 1).all {
                    field.first + field.second <= it.first || it.first + it.second <= field.first
                }
            })
            require(kinds.keys == SdlButtonAdmission.Transition.entries.toSet() && kinds.values.toSet().size == 2)
            require(codes.keys == SdlButtonAdmission.Button.entries.toSet() && codes.values.all { it in 1..255 })
            val extents = mapOf(6 to header.extent.toLong())
            val scalars = ScalarExpression(flow, extents)
            val arguments = SysVArgumentFlow(flow)
            val type = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, header.type), 4)
            require(table.index.width in listOf(4, 8)) { "Mouse switch cannot use a byte-only discriminator" }
            val index = scalars.before(table.guard, Register(table.index.number, 4))
            require(ScalarExpression.inputs(index).map { it.field }.toSet() == setOf(type))
            val admission = ScalarBranchPath(flow, extents)
            val members = mutableSetOf<Long>()
            val stores = mutableMapOf<SdlButtonAdmission.Transition, Long>()
            val expressions = mutableMapOf<SdlButtonAdmission.Transition, ScalarExpression.Value>()
            for ((transition, kind) in kinds) {
                admission.to(table.guard) {
                    require(it.field == type)
                    kind
                }
                val selected = ScalarExpression.evaluate(index) { kind }
                require(selected >= 0 && selected < table.targets.size)
                var site = table.targets[selected.toInt()]
                var found = false
                repeat(32) {
                    if (found) return@repeat
                    val instruction = flow.body.getValue(site)
                    require(
                        instruction.operation !in listOf(
                            Operation.CALL, Operation.JMP, Operation.JCC, Operation.RET,
                            Operation.PUSH, Operation.POP
                        )
                    ) { "Mouse update does not begin with a bounded scalar case" }
                    val memory = instruction.destination as? Memory
                    if (memory != null) {
                        val expected =
                            if (transition == SdlButtonAdmission.Transition.PRESS) Operation.OR else Operation.AND
                        require(instruction.operation == expected && memory.width == 4)
                        val receiver = arguments.memory(site, memory) ?: error("Mouse update loses its receiver")
                        require(receiver.reference.argument == 7 && receiver.reference.offset in 0..stateSize - 4)
                        val register = instruction.source as? Register ?: error("Mouse update has no computed mask")
                        require(register.width == 4)
                        val expression = scalars.before(site, register)
                        val reads = ScalarExpression.inputs(expression)
                        require(reads.isNotEmpty() && reads.all {
                            it.field.reference == SysVArgumentFlow.Reference(6, code) &&
                                    it.field.width in listOf(1, 4)
                        })
                        members += receiver.reference.offset
                        stores[transition] = site
                        expressions[transition] = expression
                        found = true
                    } else {
                        if (instruction.source is Memory && instruction.operation != Operation.LEA) {
                            val read = arguments.source(site) ?: error("Mouse update reads an unproven input")
                            require(read.reference == SysVArgumentFlow.Reference(6, code) && read.width in listOf(1, 4))
                        }
                        require(flow.successors.getValue(site) == listOf(site + instruction.size))
                        site += instruction.size
                    }
                }
                require(found) { "Mouse update mask exceeds case-prefix bound" }
            }
            val masks = codes.mapValues { (_, codeValue) ->
                val press =
                    ScalarExpression.evaluate(expressions.getValue(SdlButtonAdmission.Transition.PRESS)) { codeValue }
                val release =
                    ScalarExpression.evaluate(expressions.getValue(SdlButtonAdmission.Transition.RELEASE)) { codeValue }
                require(press > 0 && press and (press - 1) == 0L && release == (press xor 0xffffffffL)) {
                    "Native mouse cases do not set and clear the same single bit"
                }
                press
            }
            require(masks.values.toSet().size == masks.size)
            return InputStateMouseUpdate(members.single(), stores, masks)
        }
    }
}
