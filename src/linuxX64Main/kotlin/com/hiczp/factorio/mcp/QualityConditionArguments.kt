package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Optimized scalar inputs linked to the named registry and the same typed enum-error consumer. */
internal data class QualityConditionArguments(val quality: Int, val comparison: Int) {
    companion object {
        private val inputs = listOf(7, 6, 2, 1, 8, 9)

        fun resolve(
            image: ElfImage,
            draw: ElfImage.Symbol,
            fields: QualityConditionFields,
        ): QualityConditionArguments {
            val reference = image.symbol("_ZNK16QualityCondition16saveForBlueprintER12PropertyTree")
            val error =
                image.symbol(
                    "_ZN7Logging22logEnumAndAbortOrThrowIN10Comparison4EnumEEEvPKcj8LogLevelT_"
                )
            EhFrames(image).function(error)
            return analyze(
                X64ControlFlow.resolve(image, reference),
                error.address - reference.address,
                fields.comparison,
                X64ControlFlow.resolve(image, draw),
                draw.address,
                error.address - draw.address,
                fields.registry + fields.registryBegin,
            )
        }

        fun analyze(
            reference: X64ControlFlow,
            referenceError: Long,
            comparisonField: Long,
            draw: X64ControlFlow,
            address: Long,
            drawError: Long,
            registry: Long,
        ): QualityConditionArguments {
            require(comparisonField in 0..63 && address > 0 && registry > 0)
            fun errorCall(flow: X64ControlFlow, target: Long) =
                flow.instructions
                    .single {
                        it.offset in flow.reachable &&
                            it.operation == Operation.CALL &&
                            it.destination == Immediate(target)
                    }
                    .offset
            val referenceCall = errorCall(reference, referenceError)
            val referencePrefix = reference.reaching(referenceCall)
            val references = SysVArgumentFlow(referencePrefix)
            val member = NativeField(comparisonField, 1)
            val reads =
                referencePrefix.instructions
                    .filter { instruction ->
                        instruction.offset in referencePrefix.reachable &&
                            instruction.operation == Operation.MOVZX &&
                            references.source(instruction.offset) ==
                                SysVArgumentFlow.Read(
                                    SysVArgumentFlow.Reference(7, comparisonField),
                                    1,
                                )
                    }
                    .associate { it.offset to PrivateValueCopies.Read(0, member) }
            require(reads.isNotEmpty())
            val copied =
                PrivateValueCopies(referencePrefix, reads, terminalConsumer = referenceCall)
            val enumArgument =
                inputs.single { register ->
                    runCatching {
                            copied.field(referenceCall, Register(register, 1)) ==
                                PrivateValueCopies.Read(0, member)
                        }
                        .getOrDefault(false)
                }
            val drawCall = errorCall(draw, drawError)
            val drawPrefix = draw.reaching(drawCall)
            val scalars = inputs.associateWith { 1 }
            val comparison =
                PrivateValueCopies(
                        drawPrefix,
                        emptyMap(),
                        scalarArguments = scalars,
                        terminalConsumer = drawCall,
                    )
                    .argument(drawCall, Register(enumArgument, 1))

            val pointers = OriginalPointerOrigins(draw, address)
            val definitions = ScalarExpression(draw)
            val quality =
                draw.instructions
                    .mapNotNull { instruction ->
                        val source = instruction.source as? Memory ?: return@mapNotNull null
                        if (
                            instruction.offset !in draw.reachable ||
                                instruction.operation != Operation.MOV ||
                                instruction.destination !is Register ||
                                instruction.destination.width != 8 ||
                                source.width != 8 ||
                                source.relative ||
                                source.base == null ||
                                source.index == null ||
                                source.scale != 8 ||
                                source.displacement != 0L
                        )
                            return@mapNotNull null
                        val table =
                            pointers.register(instruction.offset, source.base)
                                as? OriginalPointerOrigins.Global ?: return@mapNotNull null
                        if (table.address != registry) return@mapNotNull null
                        // The complete address index must be zero-extended from the original scalar
                        // byte.
                        fun extension(site: Long, register: Int, depth: Int = 0) {
                            require(depth < 32)
                            val definition = definitions.definition(site, register)
                            val target =
                                definition.destination as? Register
                                    ?: error("Quality index has no register definition")
                            val input =
                                definition.source as? Register
                                    ?: error("Quality index is not an original scalar")
                            require(target.width in listOf(4, 8))
                            if (definition.operation == Operation.MOVZX) require(input.width == 1)
                            else {
                                require(
                                    definition.operation == Operation.MOV &&
                                        input.width == target.width
                                )
                                extension(definition.offset, input.number, depth + 1)
                            }
                        }
                        runCatching {
                                extension(instruction.offset, source.index)
                                PrivateValueCopies(
                                        draw.reaching(instruction.offset),
                                        emptyMap(),
                                        scalarArguments = scalars,
                                    )
                                    .argument(instruction.offset, Register(source.index, 1))
                            }
                            .getOrNull()
                    }
                    .distinct()
                    .singleOrNull()
                    ?: error(
                        "Quality draw has no unique original byte indexing its native registry"
                    )
            require(quality != comparison)
            return QualityConditionArguments(quality, comparison)
        }
    }
}
