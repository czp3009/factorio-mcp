package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Normal-entry edges only. Switch targets must have been independently verified from the same body. */
internal class X64ControlFlow private constructor(
    val instructions: List<Instruction>,
    private val tables: List<X64JumpTables.Table>,
    private val retained: Set<Long>?,
) {
    constructor(instructions: List<Instruction>, tables: List<X64JumpTables.Table> = emptyList()) :
            this(instructions, tables, null)

    val body = instructions.associateBy { it.offset }
    val successors: Map<Long, List<Long>>
    val reachable: Set<Long>
    val predecessors: Map<Long, Set<Long>>

    init {
        require(instructions.isNotEmpty() && instructions.size <= 8192 && instructions.first().offset == 0L)
        require(instructions.all { it.offset >= 0 && it.size in 1..15 })
        require(instructions.zipWithNext().all { (left, right) -> left.offset + left.size == right.offset })
        val end = instructions.last().offset + instructions.last().size
        require(end in 1..32768)
        val switches = tables.associateBy { it.jump }
        require(switches.size == tables.size && tables.all {
            body[it.jump]?.let { instruction ->
                instruction.operation == Operation.JMP &&
                        instruction.destination !is Immediate
            } == true && it.targets.isNotEmpty() &&
                    it.targets.all { target -> target in body }
        })
        successors = instructions.associate { instruction ->
            val next = instruction.offset + instruction.size
            val direct = (instruction.destination as? Immediate)?.value
            if (instruction.operation == Operation.CALL) require(direct == null || direct !in 0 until end) {
                "Local calls cannot establish normal-entry argument provenance"
            }
            val targets = when (instruction.operation) {
                Operation.RET -> emptyList()
                Operation.JMP -> direct?.let { listOf(it) } ?: switches[instruction.offset]?.targets
                ?: error("Indirect branch has no verified switch table")

                Operation.JCC -> listOf(next, direct ?: error("Indirect conditional branch"))
                else -> listOf(next)
            }.filter { it in 0 until end }
            require(targets.all { it in body }) { "Control flow enters an instruction" }
            instruction.offset to targets.distinct().filter { retained == null || it in retained }
        }
        reachable = buildSet {
            val pending = ArrayDeque<Long>()
            pending.add(0)
            while (pending.isNotEmpty()) {
                val offset = pending.removeFirst()
                if (add(offset)) pending.addAll(successors.getValue(offset))
            }
        }
        val incoming = mutableMapOf<Long, MutableSet<Long>>()
        for ((from, targets) in successors) if (from in reachable) {
            for (target in targets) incoming.getOrPut(target) { mutableSetOf() } += from
        }
        predecessors = incoming
    }

    /** All normal paths reaching a selected instruction, including later backedges. Only point provenance is proved. */
    fun reaching(site: Long): X64ControlFlow {
        require(site in reachable)
        val ancestors = mutableSetOf<Long>()
        val pending = ArrayDeque<Long>()
        pending.add(site)
        while (pending.isNotEmpty()) {
            val previous = pending.removeFirst()
            if (ancestors.add(previous)) pending.addAll(predecessors[previous].orEmpty())
        }
        return X64ControlFlow(instructions, tables, ancestors)
    }

    companion object {
        fun resolve(image: ElfImage, function: ElfImage.Symbol): X64ControlFlow {
            val tables = X64JumpTables.resolve(image, function)
            return X64ControlFlow(X64Instructions(image.functionBytes(function, 32768)).all(8192), tables)
        }
    }
}
