package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** An unchanged binding/event code comparison within one validated native binding type case. */
internal object ControlEventCode {
    fun resolve(
        image: ElfImage, extent: Long, type: Long, code: Long, kind: Int,
        eventExtent: Long, eventCode: Long
    ): Long {
        val function =
            image.symbol("_ZNK17ControlInputValue11triggeredByERK5EventbPPKS_j9NamedBoolI17BlockModifiersTagE")
        val tables = X64JumpTables.resolve(image, function)
        val flow = X64ControlFlow(X64Instructions(image.functionBytes(function, 32768)).all(8192), tables)
        return verify(flow, tables, extent, type, code, kind, eventExtent, eventCode)
    }

    fun verify(
        flow: X64ControlFlow, tables: List<X64JumpTables.Table>, extent: Long, type: Long,
        code: Long, kind: Int, eventExtent: Long, eventCode: Long
    ): Long {
        require(kind in 0..255 && eventExtent in 4..4096 && eventCode in 0..eventExtent - 4)
        val arguments = SysVArgumentFlow(flow)
        val scalars = ScalarExpression(flow, mapOf(7 to extent, 6 to eventExtent), arguments)
        val selector = MemberSwitch(flow, tables, 7, extent, type, code)
        val expected = setOf(
            SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, code), 4),
            SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, eventCode), 4)
        )
        val comparisons = flow.instructions.filter { instruction ->
            if (instruction.operation != Operation.CMP || instruction.offset !in flow.reachable) return@filter false
            try {
                val fields = listOf(instruction.destination, instruction.source).map { operand ->
                    when (operand) {
                        is Memory -> arguments.memory(instruction.offset, operand)
                        is Register -> {
                            require(operand.width == 4)
                            var value = scalars.before(instruction.offset, operand)
                            while (value is ScalarExpression.Narrow) {
                                require(value.width == 4 && value.value.width == 4)
                                value = value.value
                            }
                            (value as? ScalarExpression.Input)?.field
                        }

                        else -> null
                    }
                }
                val branch = flow.body[instruction.offset + instruction.size]
                fields.toSet() == expected && branch?.operation == Operation.JCC &&
                        branch.condition in setOf(4, 5) && selector.case(instruction.offset) == kind
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: IllegalStateException) {
                false
            }
        }
        return comparisons.singleOrNull()?.offset ?: error("No unique original binding/event code comparison")
    }
}
