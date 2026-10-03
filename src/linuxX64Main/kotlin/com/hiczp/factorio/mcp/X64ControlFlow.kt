package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Entry-rooted edges. Switch targets and optional call cleanup edges require independent same-body
 * proof.
 */
internal class X64ControlFlow
private constructor(
    val instructions: List<Instruction>,
    private val tables: List<X64JumpTables.Table>,
    private val retained: Set<Long>?,
    private val cleanupEdges: Map<Long, Long>,
    private val branches: Map<Long, Long> = emptyMap(),
) {
    constructor(
        instructions: List<Instruction>,
        tables: List<X64JumpTables.Table> = emptyList(),
        // The caller verifies the selected LSDA/personality and ABI restoration before using
        // call-clobber state.
        cleanupEdges: Map<Long, Long> = emptyMap(),
    ) : this(instructions, tables, null, cleanupEdges)

    val body = instructions.associateBy { it.offset }
    val successors: Map<Long, List<Long>>
    val reachable: Set<Long>
    val predecessors: Map<Long, Set<Long>>

    /** Every normal entry path to node crosses dominator. */
    fun dominates(dominator: Long, node: Long): Boolean {
        require(dominator in reachable && node in reachable)
        val pending = ArrayDeque<Long>()
        val visited = mutableSetOf<Long>()
        pending.add(node)
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (site == dominator || !visited.add(site)) continue
            if (site == 0L) return false
            pending.addAll(predecessors[site].orEmpty())
        }
        return true
    }

    /**
     * Result-producing analyzers must discard normal call results on this edge, including a shared
     * continuation.
     */
    fun isCleanupEdge(from: Long, to: Long): Boolean = cleanupEdges[from] == to

    init {
        require(
            instructions.isNotEmpty() &&
                instructions.size <= 8192 &&
                instructions.first().offset == 0L
        )
        require(instructions.all { it.offset >= 0 && it.size in 1..15 })
        require(
            instructions.zipWithNext().all { (left, right) ->
                left.offset + left.size == right.offset
            }
        )
        val end = instructions.last().offset + instructions.last().size
        require(end in 1..32768)
        require(
            cleanupEdges.all { (call, landing) ->
                body[call]?.operation == Operation.CALL && landing in body
            }
        ) {
            "Cleanup edge does not connect a decoded call and landing instruction"
        }
        require(
            branches.all { (site, target) ->
                val branch = body[site]
                branch?.operation == Operation.JCC &&
                    target in body &&
                    (target == site + branch.size ||
                        target == (branch.destination as? Immediate)?.value)
            }
        ) {
            "Specialized branch is not an original conditional edge"
        }
        val switches = tables.associateBy { it.jump }
        require(
            switches.size == tables.size &&
                tables.all {
                    body[it.jump]?.let { instruction ->
                        instruction.operation == Operation.JMP &&
                            instruction.destination !is Immediate
                    } == true &&
                        it.targets.isNotEmpty() &&
                        it.targets.all { target -> target in body }
                }
        )
        successors =
            instructions.associate { instruction ->
                val next = instruction.offset + instruction.size
                val direct = (instruction.destination as? Immediate)?.value
                if (instruction.operation == Operation.CALL)
                    require(direct == null || direct == 0L || direct !in 0 until end) {
                        "Interior calls cannot establish normal-entry argument provenance"
                    }
                // Self-recursion invokes the verified normal entry with a new ABI call frame. It
                // remains a
                // call/clobber boundary, never an interior edge sharing this invocation's original
                // arguments.
                val targets =
                    when (instruction.operation) {
                        Operation.RET -> emptyList()
                        Operation.JMP ->
                            direct?.let { listOf(it) }
                                ?: switches[instruction.offset]?.targets
                                ?: error("Indirect branch has no verified switch table")

                        Operation.JCC ->
                            listOf(next, direct ?: error("Indirect conditional branch"))
                        Operation.CALL -> listOfNotNull(next, cleanupEdges[instruction.offset])
                        else -> listOf(next)
                    }.filter { it in 0 until end }
                require(targets.all { it in body }) { "Control flow enters an instruction" }
                instruction.offset to
                    targets.distinct().filter {
                        (retained == null || it in retained) &&
                            (branches[instruction.offset] == null ||
                                branches[instruction.offset] == it)
                    }
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

    /**
     * All retained paths reaching a selected instruction, including later backedges. Only point
     * provenance is proved.
     */
    fun reaching(site: Long): X64ControlFlow {
        require(site in reachable)
        val ancestors = mutableSetOf<Long>()
        val pending = ArrayDeque<Long>()
        pending.add(site)
        while (pending.isNotEmpty()) {
            val previous = pending.removeFirst()
            if (ancestors.add(previous)) pending.addAll(predecessors[previous].orEmpty())
        }
        return X64ControlFlow(instructions, tables, ancestors, cleanupEdges, branches)
    }

    /**
     * Drops only a terminal instruction; useful when a proof concerns its input state, never its
     * effects.
     */
    fun withoutTerminal(site: Long): X64ControlFlow {
        require(site != 0L && site in reachable && successors.getValue(site).isEmpty())
        return X64ControlFlow(instructions, tables, reachable - site, cleanupEdges, branches)
    }

    /**
     * Enumerates an exact zero-extended byte read for static analysis, without executing or
     * changing code.
     */
    fun withByteValue(site: Long, value: Int): X64ControlFlow {
        val source = body.getValue(site)
        require(
            site in reachable &&
                value in 0..255 &&
                source.operation == Operation.MOVZX &&
                (source.source as? Memory)?.width == 1 &&
                (source.destination as? Register)?.let { it.number in 0..15 && it.width == 4 } ==
                    true
        )
        return X64ControlFlow(
            instructions.map {
                if (it.offset == site)
                    it.copy(operation = Operation.MOV, source = Immediate(value.toLong()))
                else it
            },
            tables,
            retained,
            cleanupEdges,
            branches,
        )
    }

    /** The caller independently proves each chosen condition for the particular analyzed input. */
    fun following(choices: Map<Long, Long>): X64ControlFlow {
        require(
            choices.all { (site, target) ->
                site in reachable &&
                    target in successors.getValue(site) &&
                    (branches[site] == null || branches[site] == target)
            }
        )
        return X64ControlFlow(instructions, tables, retained, cleanupEdges, branches + choices)
    }

    companion object {
        fun resolve(image: ElfImage, function: ElfImage.Symbol): X64ControlFlow {
            val tables = X64JumpTables.resolve(image, function)
            return X64ControlFlow(
                X64Instructions(image.functionBytes(function, 32768)).all(8192),
                tables,
            )
        }
    }
}
