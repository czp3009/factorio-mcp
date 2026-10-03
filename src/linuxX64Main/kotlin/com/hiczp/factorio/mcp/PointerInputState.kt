package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Fresh cursor/window observations in the InputState receiver, connected to its normal native Event updates. */
internal data class PointerInputState(val position: Long, val inWindow: Long) {
    companion object {
        fun resolve(image: ElfImage, state: InputStateLayout, payload: PointerEventPayloads): PointerInputState {
            val function = image.symbol("_ZN10InputState6updateERK5Event")
            val flow = X64ControlFlow.resolve(image, function)
            val table = X64JumpTables.resolve(image, function).single()
            return analyze(flow, table, state.size, payload.header,
                payload.cases.single { it.operation == PointerEventPayloads.OperationKind.MOVE },
                payload.cases.single { it.operation == PointerEventPayloads.OperationKind.ENTER }, payload.leaveKind)
        }

        fun analyze(
            flow: X64ControlFlow, table: X64JumpTables.Table, stateSize: Long, header: EventHeader,
            motion: PointerEventPayloads.Case, enter: PointerEventPayloads.Case, leaveKind: Long,
        ): PointerInputState {
            require(stateSize in 8..16 * 1024 * 1024 && motion.operation == PointerEventPayloads.OperationKind.MOVE &&
                    enter.operation == PointerEventPayloads.OperationKind.ENTER && motion.y == checkNotNull(motion.x) + 4)
            val type = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, header.type), 4)
            val scalar = ScalarExpression(flow, mapOf(6 to header.extent.toLong()))
            val index = scalar.before(table.guard, table.index.copy(width = 4))
            require(ScalarExpression.inputs(index).map { it.field }.toSet() == setOf(type))
            fun selected(kind: Long): X64ControlFlow {
                val prefix = ScalarBranchPath(flow, mapOf(6 to header.extent.toLong())).to(table.guard) {
                    require(it.field == type)
                    kind
                }
                val value = ScalarExpression.evaluate(index) { kind }
                require(value >= 0 && value < table.targets.size)
                val forced = prefix.zipWithNext().toMap() +
                        (table.guard to table.guard + flow.body.getValue(table.guard).size) +
                        (table.jump to table.targets[value.toInt()])
                return X64ControlFlow(flow.instructions.map { instruction ->
                    forced[instruction.offset]?.takeIf {
                        instruction.operation == Operation.JCC || instruction.offset == table.jump
                    }?.let { target ->
                        instruction.copy(operation = Operation.JMP, destination = Immediate(target), source = null, condition = null)
                    } ?: instruction
                })
            }
            val motionFlow = selected(motion.kind)
            val arguments = SysVArgumentFlow(motionFlow)
            val reads = motionFlow.instructions.filter {
                it.offset in motionFlow.reachable && it.operation in listOf(Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV) &&
                        arguments.source(it.offset) == SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, motion.x), 8)
            }.associate { it.offset to PrivateValueCopies.Read(it.offset, InlineArgumentFields.Field(motion.x, 8)) }
            require(reads.isNotEmpty())
            val copies = PrivateValueCopies(motionFlow, reads)
            val writes = motionFlow.instructions.filter { instruction ->
                val target = instruction.destination as? Memory
                instruction.offset in motionFlow.reachable && instruction.operation in listOf(Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV) &&
                        target != null && target.width == 8 && arguments.memory(instruction.offset, target)?.reference?.argument == 7
            }.mapNotNull { instruction ->
                val source = instruction.source as? Register ?: return@mapNotNull null
                val read = try { copies.field(instruction.offset, source) } catch (_: IllegalArgumentException) { null }
                    catch (_: IllegalStateException) { null }
                if (read?.field != InlineArgumentFields.Field(motion.x, 8)) return@mapNotNull null
                val target = instruction.destination as Memory
                val member = checkNotNull(arguments.memory(instruction.offset, target)).reference.offset
                require(member >= 0 && member <= stateSize - 8)
                instruction.offset to member
            }
            val (positionWrite, position) = writes.single()
            require(alwaysVisited(motionFlow, positionWrite)) { "Native motion does not always update its cursor" }

            val enterFlow = selected(enter.kind)
            val enterArguments = SysVArgumentFlow(enterFlow)
            val flags = enterFlow.instructions.filter { instruction ->
                val target = instruction.destination as? Memory
                instruction.offset in enterFlow.reachable && instruction.operation == Operation.MOV &&
                        target?.width == 1 && instruction.source == Immediate(1) &&
                        enterArguments.memory(instruction.offset, target)?.reference?.argument == 7
            }.map { instruction ->
                val member = checkNotNull(enterArguments.memory(instruction.offset, instruction.destination as Memory)).reference.offset
                require(member in 0 until stateSize && alwaysVisited(enterFlow, instruction.offset))
                member
            }
            val inWindow = flags.single()
            val leaveFlow = selected(leaveKind)
            val leaveArguments = SysVArgumentFlow(leaveFlow)
            val clear = leaveFlow.instructions.single { instruction ->
                val target = instruction.destination as? Memory
                instruction.offset in leaveFlow.reachable && instruction.operation == Operation.MOV &&
                        target?.width == 1 && instruction.source == Immediate(0) &&
                        leaveArguments.memory(instruction.offset, target)?.reference == SysVArgumentFlow.Reference(7, inWindow)
            }
            require(alwaysVisited(leaveFlow, clear.offset)) { "Native leave does not always clear its window flag" }
            require(inWindow !in position until position + 8)
            return PointerInputState(position, inWindow)
        }

        private fun alwaysVisited(flow: X64ControlFlow, required: Long): Boolean {
            val visited = mutableSetOf<Long>()
            val pending = ArrayDeque<Long>()
            pending.add(0)
            while (pending.isNotEmpty()) {
                val site = pending.removeFirst()
                if (site == required || !visited.add(site)) continue
                if (flow.successors.getValue(site).isEmpty()) return false
                pending.addAll(flow.successors.getValue(site))
            }
            return true
        }
    }
}
