package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Named inline assignments to an embedded object later passed by reference. Field meaning remains separate. */
internal object InlineMemberConstruction {
    data class Assignment(val offset: Long, val width: Int, val floating: Boolean, val constant: Long?)
    data class Proof(val member: Long, val extent: Int, val assignments: List<Assignment>)

    fun resolve(
        image: ElfImage, function: ElfImage.Symbol, owner: String, constructor: String,
        dispatch: ElfImage.Symbol, ownerSize: Long, argument: Int,
        debug: DwarfInlines = DwarfInlines(image)
    ): Proof {
        EhFrames(image).function(dispatch)
        val flow = X64ControlFlow.resolve(image, function)
        val calls = flow.instructions.filter {
            it.operation == Operation.CALL &&
                    it.destination == Immediate(dispatch.address - function.address)
        }
        val call = calls.singleOrNull() ?: error("Embedded construction has no unique named reference dispatch")
        val instances = debug.find(function, owner, setOf(constructor))
        val first = instances.minBy { inline -> inline.ranges.minOf { it.start } }
        require(first.ranges.all { it.end <= function.address + call.offset })
        val ranges = first.ranges.map { DwarfRanges.Range(it.start - function.address, it.end - function.address) }
        val prefix = image.functionBytes(function, 32768).slice(0, call.offset + call.size)
        return analyze(flow, prefix, call.offset, ranges, ownerSize, argument)
    }

    fun analyze(
        flow: X64ControlFlow, prefix: BinaryView, sink: Long, ranges: List<DwarfRanges.Range>,
        ownerSize: Long, argument: Int
    ): Proof {
        require(ownerSize in 1..(64 * 1024 * 1024) && argument in listOf(7, 6, 2, 1, 8, 9))
        require(flow.body[sink]?.operation == Operation.CALL && prefix.size == sink + flow.body.getValue(sink).size)
        require(ranges.isNotEmpty() && ranges.all { it.start >= 0 && it.end <= sink && it.start < it.end })
        // This is the first dispatch in this invocation. Prove that no path reaching it first leaves
        // the decoded prefix and returns through an omitted part of the enclosing function.
        val reachable = mutableSetOf<Long>()
        val pending = ArrayDeque<Long>()
        pending.add(0)
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (reachable.add(site) && site != sink) pending.addAll(flow.successors.getValue(site))
        }
        val needed = mutableSetOf<Long>()
        pending.add(sink)
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (site in reachable && needed.add(site))
                pending.addAll(flow.predecessors[site].orEmpty().filter { it != sink })
        }
        require(0L in needed && needed.all { it <= sink }) { "First reference dispatch reenters from outside its prefix" }
        val prefixFlow = X64ControlFlow(X64Instructions(prefix).all(2048))
        require(prefixFlow.instructions == flow.instructions.take(prefixFlow.instructions.size))
        val values = ConstructorValues(prefixFlow, emptyMap())
        val reference = values.register(sink, argument) as? ConstructorValues.Argument
            ?: error("Reference dispatch does not use an original receiver member")
        require(reference.register == 7 && reference.adjustment in 0 until ownerSize)
        val boundaries = flow.body.keys + (sink + flow.body.getValue(sink).size)
        require(ranges.all { it.start in boundaries && it.end in boundaries })
        val assignments = flow.instructions.filter { instruction ->
            ranges.any {
                instruction.offset >= it.start && instruction.offset + instruction.size <= it.end
            }
        }.mapNotNull { instruction ->
            if (instruction.operation in listOf(Operation.NOP, Operation.ENDBR)) return@mapNotNull null
            require(
                instruction.offset in needed && instruction.operation in listOf(
                    Operation.MOV,
                    Operation.SCALAR_MOV
                )
            ) {
                "Inline construction is not a bounded sequence of scalar assignments"
            }
            val target =
                instruction.destination as? Memory ?: error("Inline construction contains a non-member assignment")
            require(!target.relative && target.index == null && target.width in listOf(1, 2, 4, 8))
            val base = target.base?.let { values.register(instruction.offset, it) } as? ConstructorValues.Argument
                ?: error("Constructed field has no original receiver provenance")
            require(base.register == 7)
            val member = base.adjustment + target.displacement
            val offset = member - reference.adjustment
            require(member >= 0 && member <= ownerSize - target.width && offset in 0..256L - target.width)
            val floating = instruction.operation == Operation.SCALAR_MOV
            val constant = when (val source = instruction.source) {
                is Immediate -> source.value.also { require(!floating) }
                is Register -> {
                    require(
                        source.width == target.width &&
                                (if (floating) source.number in 16..31 && source.width == 8 else source.number in 0..15)
                    )
                    (values.register(instruction.offset, source.number) as? ConstructorValues.Constant)?.value
                }

                else -> error("Constructed field has an unverified source")
            }
            Assignment(offset, target.width, floating, constant)
        }
        require(assignments.isNotEmpty() && assignments.size <= 32)
        for ((index, left) in assignments.withIndex()) require(assignments.drop(index + 1).all { right ->
            left.offset + left.width <= right.offset || right.offset + right.width <= left.offset
        }) { "Constructed event fields overlap" }
        return Proof(
            reference.adjustment,
            assignments.maxOf { it.offset + it.width }.toInt(),
            assignments.sortedBy { it.offset })
    }
}
