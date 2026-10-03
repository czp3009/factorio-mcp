package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Copies an independently identified original receiver byte into a bounded constructed allocation.
 */
internal object ConstructedByteCopy {
    fun analyze(
        bytes: BinaryView,
        address: Long,
        allocator: Long,
        constructor: Long,
        size: Long,
        sourceSize: Long,
        sourceField: Long,
    ): Long {
        require(sourceSize in 8..4096 && sourceField in 8 until sourceSize)
        val flow = X64ControlFlow(X64Instructions(bytes).all(4096))
        val arguments = SysVArgumentFlow(flow)
        val scalars = ScalarExpression(flow, mapOf(7 to sourceSize), arguments)
        val ranges =
            flow.instructions.mapNotNull { store ->
                val target = store.destination as? Memory ?: return@mapNotNull null
                val register = store.source as? Register ?: return@mapNotNull null
                if (
                    store.offset !in flow.reachable ||
                        store.operation != Operation.MOV ||
                        target.width != 1 ||
                        register.width != 1 ||
                        register.number !in 0..15
                )
                    return@mapNotNull null
                var value =
                    runCatching { scalars.before(store.offset, register) }.getOrNull()
                        ?: return@mapNotNull null
                // Widening and reading the low byte preserve a byte input. Arithmetic, conditional
                // values, differing incoming definitions and native call clobbers do not prove a
                // copy.
                while (value is ScalarExpression.Narrow) value = value.value
                if (
                    value !is ScalarExpression.Input ||
                        value.width != 1 ||
                        value.field !=
                            SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, sourceField), 1)
                )
                    return@mapNotNull null
                DwarfRanges.Range(store.offset, store.offset + store.size)
            }
        require(ranges.size == 1) { "Identified byte has no unique constructed copy" }
        return ConstructedInlineByteMember.analyze(
            bytes,
            address,
            allocator,
            constructor,
            size,
            ranges,
        )
    }
}
