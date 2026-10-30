package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * The plain provider's two stored strings, connected to the one-string setter's existing-value
 * comparison.
 */
internal data class TooltipStringFields(val title: Long, val text: Long) {
    fun verifyInitialization(flow: X64ControlFlow, allocation: Long, string: NativeStringLayout) {
        val returned = ReturnedPointerOrigins(flow)
        for (field in listOf(title, text)) {
            require(
                flow.instructions.any { instruction ->
                    val destination = instruction.destination as? Memory
                    val source = instruction.source as? Register
                    instruction.offset in flow.reachable &&
                        instruction.operation == Operation.MOV &&
                        destination?.width == 8 &&
                        source?.width == 8 &&
                        returned.address(instruction.offset, destination) ==
                            ReturnedPointerOrigins.Value(
                                allocation,
                                adjustment = field + string.data,
                            ) &&
                        returned.register(instruction.offset, source.number) ==
                            ReturnedPointerOrigins.Value(
                                allocation,
                                adjustment = field + string.local,
                            )
                }
            ) {
                "Tooltip field lacks independently verified native string initialization"
            }
        }
    }

    companion object {
        fun analyze(
            flow: X64ControlFlow,
            cast: Long,
            comparisons: Set<Long>,
            size: Long,
            string: NativeStringLayout,
        ): TooltipStringFields {
            val origins = OriginalPointerOrigins(flow)
            val returned = ReturnedPointerOrigins(flow)
            fun input(value: OriginalPointerOrigins.Value?, field: Long) =
                value is OriginalPointerOrigins.Load &&
                    value.base == OriginalPointerOrigins.Argument(6) &&
                    value.member == field
            val fields =
                flow.instructions
                    .filter { it.offset in comparisons }
                    .mapNotNull { compare ->
                        require(
                            compare.offset in flow.reachable && compare.operation == Operation.CALL
                        )
                        val current = returned.register(compare.offset, 7) ?: return@mapNotNull null
                        if (
                            current.call != cast ||
                                current.members.size != 1 ||
                                current.adjustment != 0L ||
                                !input(origins.register(compare.offset, 6), string.data) ||
                                !input(origins.register(compare.offset, 2), string.length)
                        )
                            return@mapNotNull null
                        current.members.single() - string.data
                    }
                    .distinct()
            val title =
                fields.singleOrNull() ?: error("Tooltip setter lacks a stored title comparison")
            val empty =
                flow.instructions
                    .filter {
                        it.offset in flow.reachable &&
                            it.operation == Operation.CMP &&
                            it.source == Immediate(0) &&
                            it.destination is Memory &&
                            it.destination.width == 8
                    }
                    .mapNotNull {
                        val origin =
                            returned.address(it.offset, it.destination as Memory)
                                ?: return@mapNotNull null
                        if (origin.call != cast || origin.members.isNotEmpty())
                            return@mapNotNull null
                        origin.adjustment - string.length
                    }
                    .distinct()
            val text =
                empty.singleOrNull() ?: error("Tooltip setter lacks a stored empty body check")
            require(title in 8..size - string.size && text in 8..size - string.size)
            require(title + string.size <= text || text + string.size <= title)
            return TooltipStringFields(title, text)
        }
    }
}
