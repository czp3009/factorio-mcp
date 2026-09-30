package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Entry-path evidence for valid argument objects outside the fresh callee frame; never invokes native code. */
internal class ScalarBranchPath(private val flow: X64ControlFlow, extents: Map<Int, Long>) {
    private val scalars = ScalarExpression(flow, extents)
    private val frame = SysVLocalArgument(flow)

    fun to(destination: Long, read: (ScalarExpression.Input) -> Long): List<Long> {
        require(destination in flow.reachable)
        val path = mutableListOf<Long>()
        val visited = mutableSetOf<Long>()
        var site = 0L
        while (site != destination) {
            require(path.size < 1024 && visited.add(site)) { "Scalar admission path loops or exceeds bounds" }
            path += site
            val instruction = flow.body[site] ?: error("Scalar admission path leaves the function")
            require(instruction.operation !in listOf(Operation.CALL, Operation.RET, Operation.POP)) {
                "Scalar admission crosses a call, return or frame restoration"
            }
            val memory = instruction.destination as? Memory
            if (memory != null && instruction.operation !in listOf(
                    Operation.CMP,
                    Operation.TEST,
                    Operation.SCALAR_COMPARE
                )
            ) {
                require(instruction.operation in listOf(Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV)) {
                    "Scalar admission contains an unsupported memory effect"
                }
                val local = frame.address(site, memory) ?: error("Scalar admission may overwrite argument data")
                val stack = checkNotNull(frame.registers(site)[4])
                require(memory.width in 1..16 && local >= stack - 128 && local <= -memory.width) {
                    "Scalar admission writes outside its fresh local frame"
                }
            }
            site = when (instruction.operation) {
                Operation.JCC -> if (ScalarExpression.evaluate(scalars.branch(site), read) != 0L)
                    (instruction.destination as? Immediate)?.value ?: error("Indirect conditional branch")
                else site + instruction.size

                Operation.JMP -> (instruction.destination as? Immediate)?.value ?: error("Indirect admission branch")
                else -> site + instruction.size
            }
            require(site in flow.successors.getValue(instruction.offset)) { "Scalar admission has an invalid successor" }
        }
        return path + destination
    }
}
