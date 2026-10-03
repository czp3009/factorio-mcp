package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Provider return bytes connected to the game's typed condition consumer through private storage. */
internal data class QualityConditionReturn(
    val provider: Long,
    val getter: Int,
    val width: Int,
    val quality: Long,
    val comparison: Long,
    val evidence: ElfEvidence,
) {
    companion object {
        fun resolve(image: ElfImage, fields: QualityConditionFields): QualityConditionReturn {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val owner = "12ChooseButtonI19IDWithQualityFilterI2IDI13ItemPrototypetEES3_E"
                    val provider = "16IDButtonProviderI19IDWithQualityFilterI2IDI13ItemPrototypetEEE"
                    val size = SysVObjectSize.resolve(image, owner)
                    val table = ItaniumSubobjectVtable.resolve(image, owner, provider, size)
                    val prefix = if (table.baseOffset == 0L) "_ZNK" else "_ZThn${table.baseOffset}_NK"
                    val getter = table.method(image, "$prefix${owner}5getIDEv")
                    val returned = SysVAccessors.resolve(image, getter.function).withinObject(size - table.baseOffset)
                    val original = SysVAccessors.resolve(image, image.symbol("_ZNK${owner}5getIDEv")).withinObject(size)
                    require(returned.width in listOf(1, 2, 4, 8) && returned.shift == 0 &&
                            returned.maximum == if (returned.width == 8) ULong.MAX_VALUE else
                                (1uL shl (returned.width * 8)) - 1uL)
                    require(original == returned.copy(offset = returned.offset + table.baseOffset))

                    val paintOwner = "8IDButtonI19IDWithQualityFilterI2IDI13ItemPrototypetEEvE"
                    val paintExtent = DestructorPrefixExtent.resolve(image, paintOwner)
                    val paintTable = ItaniumSubobjectVtable.resolve(image, paintOwner, provider, paintExtent)
                    require(getter.slot < paintTable.entries.size)
                    val paint = image.symbol("_ZN${paintOwner}12paintQualityERKN4agui10PaintEventERKNS6_5PointE")
                    val draw = image.symbol("_ZNK16QualityCondition4drawER9DrawQueueRKN4agui9RectangleEb")
                    val drawFlow = X64ControlFlow.resolve(image, draw)
                    val drawArguments = SysVArgumentFlow(drawFlow)
                    val usedFields = drawFlow.instructions.filter { it.offset in drawFlow.reachable }
                        .flatMap { instruction ->
                            listOfNotNull(instruction.source as? Memory, instruction.destination as? Memory)
                                .filter { it.width == 1 }
                                .mapNotNull { drawArguments.memory(instruction.offset, it) }
                                .filter { it.reference.argument == 7 }.map { it.reference.offset }
                        }.toSet()
                    require(fields.quality in usedFields && fields.comparison in usedFields) {
                        "QualityCondition draw does not consume its receiver's proven byte fields"
                    }
                    val flow = X64ControlFlow.resolve(image, paint)
                    val consumers = flow.instructions.filter { it.offset in flow.reachable &&
                            it.operation == Operation.CALL && it.destination == Immediate(draw.address - paint.address) }
                    require(consumers.size == 1)
                    val consumer = consumers.single().offset
                    val selected = flow.reaching(consumer)
                    val calls = selected.instructions.filter { instruction ->
                        instruction.offset in selected.reachable && instruction.operation == Operation.CALL &&
                                instruction.destination !is Immediate && runCatching {
                            providerCall(selected.reaching(instruction.offset), instruction.offset,
                                paintTable.baseOffset, getter.slot)
                        }.getOrDefault(false)
                    }
                    require(calls.isNotEmpty()) { "Condition producer is not its verified provider dispatch" }
                    val copies = PrivateValueCopies(selected, emptyMap(), callResults = calls.associate { call ->
                        call.offset to PrivateValueCopies.Read(call.offset, InlineArgumentFields.Field(0, returned.width))
                    }, terminalConsumer = consumer)
                    fun field(offset: Long) = copies.terminalField(7, fields.minimumExtent.toInt(), offset, 1)
                    val quality = field(fields.quality)
                    val comparison = field(fields.comparison)
                    require(quality.source == comparison.source && quality.source in calls.map { it.offset } &&
                            quality.field.offset != comparison.field.offset &&
                            quality.field.offset in 0 until returned.width.toLong() &&
                            comparison.field.offset in 0 until returned.width.toLong())
                    QualityConditionReturn(ItaniumClass.resolve(image, provider).typeInfo, getter.slot, returned.width,
                        quality.field.offset, comparison.field.offset, ElfEvidence(emptyList(), emptyList(),
                            table.pointers + paintTable.pointers, table.scalars + paintTable.scalars))
                }
            }
            return resolved.first.copy(evidence = resolved.first.evidence.copy(functions = resolved.second, readonly = readonly))
        }

        private fun providerCall(flow: X64ControlFlow, site: Long, base: Long, slot: Int): Boolean {
            val values = ConstructorValues(flow, emptyMap())
            if (values.register(site, 7) != ConstructorValues.Argument(7, base)) return false
            val instruction = flow.body.getValue(site)
            val table = when (val target = instruction.destination) {
                is Memory -> {
                    if (target.width != 8 || target.relative || target.index != null || target.displacement != slot * 8L)
                        return false
                    target.base?.let { values.register(site, it) }
                }
                is Register -> {
                    val function = values.register(site, target.number) as? ConstructorValues.Load ?: return false
                    if (target.width != 8 || function.member != slot * 8L) return false
                    function.base
                }
                else -> return false
            } as? ConstructorValues.Load ?: return false
            val receiver = table.base as? ConstructorValues.Argument ?: return false
            return receiver.register == 7 && receiver.adjustment + table.member == base
        }
    }
}
