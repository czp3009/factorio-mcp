package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Wheel code names from the native programmatic configuration serializer, independent of display language. */
internal data class ControlWheelKinds(val kind: Int, val names: Map<Int, String>) {
    companion object {
        fun resolve(
            image: ElfImage, extent: Long, type: Long, code: Long, string: NativeStringLayout,
            function: String = "_ZNK17ControlInputValue4saveB5cxx11Ev",
            allocate: String = "_Znwm",
            consume: String = "_ZNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEE10_M_replaceEmmPKcm"
        ): ControlWheelKinds {
            val entry = image.symbol(function)
            val tables = X64JumpTables.resolve(image, entry)
            val flow = X64ControlFlow(X64Instructions(image.functionBytes(entry, 32768)).all(8192), tables)
            return analyze(
                flow, tables, extent, type, code, string, entry.address,
                image.symbol(allocate).address - entry.address, image.symbol(consume).address - entry.address
            ) { address, width ->
                require(image.sections.any {
                    it.flags and 7L == 2L && address >= it.address &&
                            width <= it.size && address - it.address <= it.size - width
                }) { "Wheel literal is not immutable data" }
                image.virtualBytes(address, width.toLong()).bytes(0, width)
            }
        }

        fun analyze(
            flow: X64ControlFlow, tables: List<X64JumpTables.Table>, extent: Long, type: Long, code: Long,
            string: NativeStringLayout, address: Long, allocate: Long, consume: Long,
            read: (Long, Int) -> ByteArray
        ): ControlWheelKinds {
            val scalar = ScalarExpression(flow, mapOf(6 to extent))
            val selector = MemberSwitch(flow, tables, 6, extent, type, code)
            val errors = mutableListOf<String>()
            val candidates = tables.mapNotNull { table ->
                try {
                    val adjustment = codeAdjustment(scalar.before(table.guard, table.index.copy(width = 4)), code)
                    val kind = selector.case(table.guard)
                    require(table.targets.size in 1..16)
                    val values = table.targets.mapIndexed { index, target ->
                        val nativeCode = index.toLong() - adjustment
                        require(nativeCode in 0..Int.MAX_VALUE.toLong())
                        val literal = LocalStringLiteral.analyze(flow, target, address, string, allocate, consume, read)
                        nativeCode.toInt() to literal.text
                    }.toMap()
                    require(
                        values.values.toSet() == setOf(
                            "mouse-wheel-up",
                            "mouse-wheel-down",
                            "mouse-wheel-left",
                            "mouse-wheel-right"
                        ) &&
                                values.size == 4
                    ) { "Wheel serializer does not uniquely name four directions" }
                    ControlWheelKinds(kind, values.mapValues { it.value.removePrefix("mouse-wheel-") })
                } catch (error: IllegalArgumentException) {
                    errors += error.message.orEmpty()
                    null
                } catch (error: IllegalStateException) {
                    errors += error.message.orEmpty()
                    null
                }
            }
            return candidates.singleOrNull() ?: error(
                "No unique original wheel code serializer: ${
                    errors.take(8).joinToString()
                }"
            )
        }

        internal fun codeAdjustment(value: ScalarExpression.Value, code: Long): Long = when (value) {
            is ScalarExpression.Narrow -> {
                require(value.width == 4 && value.value.width == 4)
                codeAdjustment(value.value, code)
            }

            is ScalarExpression.Input -> {
                require(
                    value.width == 4 && value.field == SysVArgumentFlow.Read(
                        SysVArgumentFlow.Reference(6, code),
                        4
                    )
                )
                0
            }

            is ScalarExpression.Binary -> {
                require(value.width == 4 && value.operation in listOf(Operation.ADD, Operation.SUB))
                val literal =
                    value.right as? ScalarExpression.Literal ?: error("Wheel code has a nonconstant adjustment")
                require(literal.width == 4 && literal.value in -255..255)
                (codeAdjustment(
                    value.left,
                    code
                ) + if (value.operation == Operation.ADD) literal.value else -literal.value)
                    .also { require(it in -255..255) }
            }

            else -> error("Wheel switch does not use its original code")
        }
    }
}
