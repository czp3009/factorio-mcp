package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Raw condition bytes and native serialized comparison text, derived without enum spelling tables. */
internal data class QualityConditionFields(
    val quality: Long,
    val comparison: Long,
    val comparisons: List<String>,
    val registry: Long,
    val registrySize: Long,
    val registryBegin: Long,
    val registryEnd: Long,
    val evidence: ElfEvidence,
) {
    val minimumExtent: Long get() = maxOf(quality, comparison) + 1

    companion object {
        fun resolve(image: ElfImage): QualityConditionFields {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val function = image.symbol("_ZNK16QualityCondition16saveForBlueprintER12PropertyTree")
                    val invalid = image.symbol("_ZN7Logging22logEnumAndAbortOrThrowIN10Comparison4EnumEEEvPKcj8LogLevelT_")
                    val assign = image.symbol("_ZN23ImmutableStringTemplateILm24EE11setInternalEPKc")
                    val registry = image.symbol("_ZN13PrototypeListI16QualityPrototypeE16indexToPrototypeE")
                    require(registry.type == 1 && registry.size in 8..4096)
                    val flow = X64ControlFlow.resolve(image, function)
                    val indexed = flow.instructions.filter { it.offset in flow.reachable &&
                            it.operation == Operation.MOV && it.destination is Register && it.destination.width == 8 &&
                            (it.source as? Memory)?.let { read -> read.width == 8 && read.index != null &&
                                    read.base != null && read.scale == 8 && !read.relative } == true }
                    val table = indexed.mapNotNull { load ->
                        runCatching { BoundedBytePointerLookup.analyze(flow, function.address, load.offset,
                            invalid.address - function.address) }.getOrNull()
                    }.singleOrNull() ?: error("Condition has no unique bounded native comparison table")
                    val pointer = PrivateValueCopies.Read(table.load, InlineArgumentFields.Field(0, 8))
                    val assignments = flow.instructions.filter { it.offset in flow.reachable &&
                            it.operation in listOf(Operation.CALL, Operation.JMP) &&
                            it.destination == Immediate(assign.address - function.address) }
                    require(assignments.any { call ->
                        runCatching { PrivateValueCopies(flow.reaching(call.offset), mapOf(table.load to pointer))
                            .field(call.offset, Register(6, 8)) == pointer }.getOrDefault(false)
                    }) { "Comparison pointer is not the native serialized string" }
                    val tableBytes = table.count * 8L
                    require(image.sections.any { it.flags and 3L == 2L && table.table >= it.address &&
                            tableBytes <= it.size && table.table - it.address <= it.size - tableBytes } ||
                            image.segments.any { it.type == 0x6474e552L && table.table >= it.address &&
                                    tableBytes <= it.memorySize && table.table - it.address <= it.memorySize - tableBytes })
                    val pointers = image.pointers.words(table.table, table.count).map { it.pointer() }
                    val strings = pointers.map { pointer ->
                        val section = image.sections.singleOrNull { it.flags and 7L == 2L && pointer >= it.address &&
                                pointer - it.address < it.size } ?: error("Comparison string is not immutable data")
                        val bytes = minOf(64L, section.size - (pointer - section.address))
                        image.virtualBytes(pointer, bytes).string(0, bytes.toInt()).also { require(it.isNotEmpty()) }
                    }
                    require(strings.distinct().size == strings.size)
                    val arguments = SysVArgumentFlow(flow)
                    val definitions = ScalarExpression(flow)
                    fun load(site: Long, register: Int, depth: Int = 0): Instruction {
                        require(depth < 32)
                        val instruction = definitions.definition(site, register)
                        require(instruction.destination == Register(register, 8))
                        val source = instruction.source
                        if (instruction.operation == Operation.MOV && source is Register && source.width == 8)
                            return load(instruction.offset, source.number, depth + 1)
                        return instruction
                    }
                    val qualities = indexed.mapNotNull { instruction ->
                        val source = instruction.source as Memory
                        val root = runCatching { load(instruction.offset, source.base!!) }.getOrNull()
                            ?: return@mapNotNull null
                        val global = root.source as? Memory ?: return@mapNotNull null
                        if (root.operation != Operation.MOV || global.width != 8 || !global.relative ||
                            global.base != null || global.index != null || source.displacement != 0L) return@mapNotNull null
                        val address = function.address + root.offset + root.size + global.displacement
                        val member = address - registry.address
                        if (member !in 0..registry.size - 8 || member % 8 != 0L) return@mapNotNull null
                        val index = runCatching { definitions.definition(instruction.offset, source.index!!) }.getOrNull()
                            ?: return@mapNotNull null
                        val byte = index.source as? Memory ?: return@mapNotNull null
                        val target = index.destination as? Register ?: return@mapNotNull null
                        if (index.operation != Operation.MOVZX || byte.width != 1 || target.width !in listOf(4, 8))
                            return@mapNotNull null
                        val field = arguments.memory(index.offset, byte) ?: return@mapNotNull null
                        if (field.reference.argument != 7 || field.reference.offset !in 0..63) return@mapNotNull null
                        field.reference.offset to member
                    }.distinct()
                    val (quality, first) = qualities.singleOrNull()
                        ?: error("Condition quality does not index its named native registry")
                    require(quality != table.field)
                    val iteration = image.symbol("_ZZN13PrototypeListI16QualityPrototypeE19getPrototypesOfTypeIS0_EERKSt6vectorIPKT_SaIS6_EEvENKUlvE_clEv")
                    EhFrames(image).function(iteration)
                    val end = GlobalPointerIteration.analyze(X64ControlFlow.resolve(image, iteration), iteration.address,
                        registry.address, registry.size, first)
                    QualityConditionFields(quality, table.field, strings, registry.address, registry.size, first, end,
                        ElfEvidence(emptyList(), emptyList(), pointers.mapIndexed { index, pointer ->
                            table.table + index * 8L to pointer }.toMap(), emptyMap()))
                }
            }
            return resolved.first.copy(evidence = resolved.first.evidence.copy(functions = resolved.second, readonly = readonly))
        }
    }
}
