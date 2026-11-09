package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import com.hiczp.factorio.mcp.OriginalPointerOrigins.Argument
import com.hiczp.factorio.mcp.OriginalPointerOrigins.Load
import com.hiczp.factorio.mcp.OriginalPointerOrigins.Adjusted
import com.hiczp.factorio.mcp.OriginalPointerOrigins.Value

/** Stored Graph members derived from typed move, traversal and destruction paths, never game formulas. */
internal data class GraphStorageFields(
    val sampleCount: Long,
    val minimum: Long,
    val maximum: Long,
    val series: Vector,
    val values: Vector,
    val label: Long,
) {
    data class Vector(val first: Long, val last: Long, val end: Long, val stride: Long)
    private data class Loop(val owner: Value, val first: Long, val last: Long, val stride: Long)

    companion object {
        private fun key(value: Value?): Value? = when (value) {
            is Load -> key(value.base)?.let { Load(it, value.member, 0) }
            is Adjusted -> key(value.base)
            else -> value
        }

        /** Keep the first traversal of natural loops. This proves member provenance, never a callable ABI. */
        internal fun firstIteration(flow: X64ControlFlow): X64ControlFlow {
            val removed = flow.successors.mapValues { (site, targets) ->
                if (site !in flow.reachable) emptyList() else targets.filter { flow.dominates(it, site) }
            }.filterValues { it.isNotEmpty() }
            return X64ControlFlow(flow.instructions.map { instruction ->
                if (instruction.offset !in removed) instruction else {
                    require(instruction.operation in setOf(Operation.JMP, Operation.JCC)) { "Natural loop has an unsupported latch" }
                    val retained = flow.successors.getValue(instruction.offset) - removed.getValue(instruction.offset).toSet()
                    when (retained.size) {
                        0 -> instruction.copy(operation = Operation.TRAP, destination = null, source = null, condition = null)
                        1 -> instruction.copy(operation = Operation.JMP, destination = Immediate(retained.single()), source = null, condition = null)
                        else -> error("Natural loop latch is ambiguous")
                    }
                }
            })
        }

        private fun loops(original: X64ControlFlow, first: X64ControlFlow): List<Loop> {
            val origins = OriginalPointerOrigins(first)
            val result = mutableListOf<Loop>()
            for (increment in first.instructions) {
                val cursor = increment.destination as? Register ?: continue
                val amount = (increment.source as? Immediate)?.value ?: continue
                if (increment.offset !in first.reachable || increment.operation !in setOf(Operation.ADD, Operation.SUB) || cursor.width != 8) continue
                val step = if (increment.operation == Operation.SUB) -amount else amount
                if (step !in 1..65536) continue
                val begin = key(origins.register(increment.offset, cursor.number)) as? Load ?: continue
                // The increment must belong to an actual repeating range, not an unrelated pointer adjustment.
                val latches = original.successors.filter { (site, targets) ->
                    site in original.reachable && original.dominates(increment.offset, site) &&
                        targets.any { target -> original.dominates(target, increment.offset) && original.dominates(target, site) }
                }
                if (latches.isEmpty()) continue
                for (site in latches.keys) {
                    if (original.body.getValue(site).operation != Operation.JCC || site !in first.reachable) continue
                    val comparison = runCatching { ScalarExpression(first).flagDefinition(site) }.getOrNull() ?: continue
                    if (comparison.operation != Operation.CMP || !first.dominates(increment.offset, comparison.offset)) continue
                    val registers = listOf(comparison.destination, comparison.source).mapNotNull { it as? Register }
                    if (registers.size != 2 || registers.any { it.width != 8 } || registers.none { it.number == cursor.number }) continue
                    if (key(origins.register(comparison.offset, cursor.number)) != begin) continue
                    val end = key(origins.register(comparison.offset, registers.single { it.number != cursor.number }.number)) as? Load
                    if (end != null && end.base == begin.base && end.member != begin.member)
                        result += Loop(begin.base, begin.member, end.member, step)
                }
            }
            return result.distinct()
        }

        private fun capacity(flow: X64ControlFlow, first: Long, deallocate: Long, size: Long): Long {
            val origins = OriginalPointerOrigins(flow)
            val candidates = flow.instructions.mapNotNull { instruction ->
                if (instruction.offset !in flow.reachable || instruction.operation != Operation.SUB) return@mapNotNull null
                val target = instruction.destination as? Register ?: return@mapNotNull null
                val source = instruction.source as? Register ?: return@mapNotNull null
                if (target.width != 8 || source.width != 8) return@mapNotNull null
                val end = key(origins.register(instruction.offset, target.number)) as? Load ?: return@mapNotNull null
                val begin = key(origins.register(instruction.offset, source.number)) as? Load ?: return@mapNotNull null
                if (begin.base != Argument(7) || begin.member != first || end.base != begin.base || end.member == first)
                    return@mapNotNull null
                val copies = PrivateValueCopies(flow, emptyMap(), produced = mapOf(
                    instruction.offset to PrivateValueCopies.Read(instruction.offset, NativeField(0, 8))))
                val frees = flow.instructions.filter { call ->
                    call.offset in flow.reachable && call.operation in setOf(Operation.CALL, Operation.JMP) &&
                        call.destination == Immediate(deallocate) && key(origins.register(call.offset, 7)) == begin &&
                        runCatching { copies.field(call.offset, Register(6, 8)).source == instruction.offset }.getOrDefault(false)
                }
                if (frees.isEmpty()) null else end.member.also { require(it in 0..size - 8) }
            }.distinct()
            return candidates.singleOrNull() ?: error("Graph vector capacity has no unique typed sized-deletion span")
        }

        private fun movedVector(flow: X64ControlFlow, vector: Vector, size: Long) {
            val firstCall = flow.instructions.firstOrNull { it.offset in flow.reachable && it.operation == Operation.CALL }?.offset
                ?: error("Graph vector move has no subsequent ownership cleanup")
            val prefix = X64ControlFlow(flow.instructions.takeWhile { it.offset < firstCall })
            val arguments = SysVArgumentFlow(prefix)
            val reads = prefix.instructions.mapNotNull { instruction ->
                if (instruction.offset !in prefix.reachable || instruction.operation !in setOf(Operation.MOV, Operation.VECTOR_MOV)) return@mapNotNull null
                val source = arguments.source(instruction.offset) ?: return@mapNotNull null
                if (source.reference.argument != 6 || source.width !in listOf(8, 16) || source.reference.offset !in 0..4096L - source.width)
                    return@mapNotNull null
                instruction.offset to PrivateValueCopies.Read(6, NativeField(source.reference.offset, source.width))
            }.toMap()
            val copies = PrivateValueCopies(prefix, reads)
            val mapping = mutableMapOf<Long, Long>()
            for (instruction in prefix.instructions) {
                val target = instruction.destination as? Memory ?: continue
                val source = instruction.source as? Register ?: continue
                val owner = arguments.memory(instruction.offset, target)?.reference ?: continue
                if (owner.argument != 7 || target.width !in listOf(8, 16) || owner.offset !in 8..size - target.width) continue
                val copied = runCatching { copies.field(instruction.offset, source) }.getOrNull() ?: continue
                if (copied.source != 6L || copied.field.width != target.width) continue
                for (byte in 0 until target.width) mapping[owner.offset + byte] = copied.field.offset + byte
            }
            val fields = listOf(vector.first, vector.last, vector.end)
            val sources = fields.map { field ->
                require(field in 8..size - 8)
                val start = mapping[field] ?: error("Graph traversal storage is not copied from its typed vector argument")
                require((0..7).all { byte -> mapping[field + byte] == start + byte })
                start
            }
            require(sources.distinct().size == 3 && fields.zip(sources).map { it.first - it.second }.distinct().size == 1)
        }

        private fun extremum(flow: X64ControlFlow, operation: Operation, size: Long, samples: Load): Long {
            val origins = OriginalPointerOrigins(flow)
            val arguments = SysVArgumentFlow(flow)
            val sampleReads = flow.instructions.mapNotNull { instruction ->
                val source = instruction.source as? Memory ?: return@mapNotNull null
                if (instruction.offset !in flow.reachable || instruction.operation != Operation.SCALAR_MOV || source.width != 4 ||
                    source.relative || source.index != null || source.displacement != 0L ||
                    key(source.base?.let { origins.register(instruction.offset, it) }) != samples) return@mapNotNull null
                instruction.offset to PrivateValueCopies.Read(instruction.offset, NativeField(0, 4))
            }.toMap()
            val candidates = flow.instructions.filter { it.offset in flow.reachable && it.operation == operation }.map { producer ->
                require(sampleReads.isNotEmpty()) { "Graph extrema have no retained float-vector sample loads" }
                val inputs = PrivateValueCopies(flow, sampleReads)
                val target = producer.destination as? Register ?: error("Graph extremum has no scalar register")
                require(inputs.field(producer.offset, target).source in sampleReads) { "Graph extremum is unrelated to its stored samples" }
                val copies = PrivateValueCopies(flow, emptyMap(), produced = mapOf(producer.offset to PrivateValueCopies.Read(producer.offset, NativeField(0, 4))))
                flow.instructions.mapNotNull { store ->
                    val destination = store.destination as? Memory ?: return@mapNotNull null
                    val source = store.source as? Register ?: return@mapNotNull null
                    if (store.offset !in flow.reachable || store.operation != Operation.SCALAR_MOV || destination.width != 4) return@mapNotNull null
                    val owner = arguments.memory(store.offset, destination)?.reference ?: return@mapNotNull null
                    if (owner.argument != 7 || owner.offset !in 8..size - 4 ||
                        runCatching { copies.field(store.offset, source).source != producer.offset }.getOrDefault(true)) return@mapNotNull null
                    owner.offset
                }.distinct().singleOrNull() ?: error("Graph extremum has no unique receiver float store")
            }.distinct()
            return candidates.singleOrNull() ?: error("Graph float extrema are missing or ambiguous")
        }

        internal fun sampleCount(flow: X64ControlFlow, size: Long): Long {
            val arguments = SysVArgumentFlow(flow)
            val copies = PrivateValueCopies(flow, emptyMap(), scalarArguments = mapOf(6 to 4))
            val fields = flow.instructions.mapNotNull { store ->
                if (store.offset !in flow.reachable || store.operation != Operation.MOV) return@mapNotNull null
                val target = store.destination as? Memory ?: return@mapNotNull null
                val source = store.source as? Register ?: return@mapNotNull null
                val owner = arguments.memory(store.offset, target)?.reference ?: return@mapNotNull null
                if (target.width != 4 || owner.argument != 7 || owner.offset !in 8..size - 4 ||
                    runCatching { copies.argument(store.offset, source) != 6 }.getOrDefault(true)) return@mapNotNull null
                owner.offset
            }.distinct()
            return fields.singleOrNull() ?: error("Graph constructor does not retain one original unsigned sample count")
        }

        fun resolve(image: ElfImage, size: Long, string: NativeStringLayout): GraphStorageFields {
            val setter = image.symbol("_ZN4agui5Graph9setValuesEOSt6vectorINS0_11GraphSeriesESaIS2_EE")
            val original = X64ControlFlow.resolve(image, setter)
            val first = firstIteration(original)
            val loops = loops(original, first)
            val seriesLoop = loops.singleOrNull { it.owner == Argument(7) && it.stride > 4 }
                ?: error("Graph has no unique outer series traversal")
            val sampleOwner = Load(Argument(7), seriesLoop.first, 0)
            val valuesLoop = loops.singleOrNull { it.owner == sampleOwner && it.stride == 4L }
                ?: error("Graph has no unique nested float traversal")
            val deallocate = image.symbol("_ZdlPvm")
            EhFrames(image).function(deallocate)
            val seriesEnd = capacity(first, seriesLoop.first, deallocate.address - setter.address, size)
            val series = Vector(seriesLoop.first, seriesLoop.last, seriesEnd, seriesLoop.stride)
            movedVector(original, series, size)
            val destroy = image.symbol("_ZN4agui5Graph11GraphSeriesD2Ev")
            val destructor = X64ControlFlow.resolve(image, destroy)
            val valuesEnd = capacity(destructor, valuesLoop.first, deallocate.address - destroy.address, series.stride)
            val values = Vector(valuesLoop.first, valuesLoop.last, valuesEnd, 4)
            val origins = OriginalPointerOrigins(destructor)
            val labels = destructor.instructions.mapNotNull { compare ->
                if (compare.offset !in destructor.reachable || compare.operation != Operation.CMP) return@mapNotNull null
                val operands = listOf(compare.destination, compare.source).mapNotNull { it as? Register }
                if (operands.size != 2 || operands.any { it.width != 8 }) return@mapNotNull null
                val a = operands.map { key(origins.register(compare.offset, it.number)) }
                val local = a.filterIsInstance<Argument>().singleOrNull()?.takeIf { it.register == 7 } ?: return@mapNotNull null
                val data = a.filterIsInstance<Load>().singleOrNull()?.takeIf { it.base == Argument(7) } ?: return@mapNotNull null
                val field = local.adjustment - string.local
                if (data.member - string.data != field || field !in 0..series.stride - string.size) return@mapNotNull null
                field
            }.distinct()
            val label = labels.singleOrNull() ?: error("Graph series has no unique native string storage")
            require(listOf(values.first, values.last, values.end).all { it in 0..series.stride - 8 && (it + 8 <= label || it >= label + string.size) })
            val constructor = image.symbol("_ZN4agui5GraphC2EjSt8functionIFNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEEfEES1_IFS7_jEES1_IFS7_fjEEPKNS_10GraphStyleE")
            val sample = sampleCount(X64ControlFlow.resolve(image, constructor), size)
            val source = Load(sampleOwner, values.first, 0)
            val minimum = extremum(first, Operation.FLOAT_MINIMUM, size, source)
            val maximum = extremum(first, Operation.FLOAT_MAXIMUM, size, source)
            require(listOf(sample, minimum, maximum).distinct().size == 3)
            return GraphStorageFields(sample, minimum, maximum, series, values, label)
        }
    }
}
