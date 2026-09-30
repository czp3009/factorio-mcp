package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Constant initialization of selected receiver bytes at every normal constructor return. Padding is not inferred. */
internal object MemberDefaults {
    fun resolve(
        image: ElfImage, function: ElfImage.Symbol, ownerSize: Long,
        member: Long, fields: List<InlineArgumentFields.Field>
    ): Map<Long, Int> =
        analyze(X64ControlFlow.resolve(image, function), ownerSize, member, fields)

    fun analyze(
        flow: X64ControlFlow, ownerSize: Long, member: Long,
        fields: List<InlineArgumentFields.Field>
    ): Map<Long, Int> {
        require(ownerSize in 1..(64 * 1024 * 1024) && member in 0 until ownerSize && fields.isNotEmpty())
        require(fields.all {
            it.width in listOf(1, 2, 4, 8, 16) && it.offset >= 0 &&
                    it.offset <= ownerSize - member - it.width
        })
        val wanted = fields.flatMap { (offset, width) -> (offset until offset + width).toList() }.toSet()
        require(wanted.size <= 256)
        val arguments = ConstructorValues(flow, emptyMap())
        val frame = SysVLocalArgument(flow)
        val before = mutableMapOf<Long, Map<Long, Int>>(0L to emptyMap())
        val pending = ArrayDeque<Long>()
        pending.add(0)
        var steps = 0
        while (pending.isNotEmpty()) {
            require(++steps <= 65536) { "Constructor defaults analysis exceeds bound" }
            val site = pending.removeFirst()
            val instruction = flow.body.getValue(site)
            val bytes = before.getValue(site).toMutableMap()
            val memory = instruction.destination as? Memory
            if (instruction.operation == Operation.CALL) bytes.clear()
            else if (memory != null && instruction.operation !in listOf(
                    Operation.CMP, Operation.TEST,
                    Operation.CALL, Operation.JMP, Operation.NOP
                )
            ) {
                val base = memory.base?.let { arguments.register(site, it) } as? ConstructorValues.Argument
                if (base?.register == 7 && !memory.relative && memory.index == null) {
                    val offset = base.adjustment + memory.displacement - member
                    val constant = if (instruction.operation in listOf(
                            Operation.MOV,
                            Operation.SCALAR_MOV,
                            Operation.VECTOR_MOV
                        )
                    ) {
                        when (val source = instruction.source) {
                            is Immediate -> source.value
                            is Register -> (arguments.register(
                                site,
                                source.number
                            ) as? ConstructorValues.Constant)?.value

                            else -> null
                        }
                    } else null
                    require(memory.width in listOf(1, 2, 4, 8, 16))
                    for (index in 0 until memory.width) if (offset + index in wanted) {
                        bytes.remove(offset + index)
                        if (constant != null && (memory.width <= 8 || constant == 0L))
                            bytes[offset + index] = if (index >= 8) 0 else (constant ushr (index * 8) and 255).toInt()
                    }
                } else if (!memory.relative && frame.address(site, memory) == null) {
                    // An unproven external destination may alias the object. Frame and ELF static writes
                    // do not initialize fields of this independently allocated receiver.
                    bytes.clear()
                }
            }
            for (next in flow.successors.getValue(site)) {
                val old = before[next]
                val merged =
                    if (old == null) bytes.toMap() else old.filter { (offset, value) -> bytes[offset] == value }
                if (old != merged) {
                    before[next] = merged
                    pending.add(next)
                }
            }
        }
        val returns = flow.instructions.filter { it.offset in flow.reachable && it.operation == Operation.RET }
        require(returns.isNotEmpty())
        for (instruction in flow.instructions.filter { it.offset in flow.reachable })
            if (flow.successors.getValue(instruction.offset)
                    .isEmpty()
            ) require(instruction.operation == Operation.RET) {
                "Constructor defaults have an unverified exit"
            }
        return returns.map { before.getValue(it.offset) }.distinct().singleOrNull()?.also {
            require(it.keys == wanted) { "Constructor does not establish all selected defaults on every path" }
        } ?: error("Constructor default values differ between return paths")
    }
}
