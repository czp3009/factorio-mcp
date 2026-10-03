package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Contiguous original-receiver initialization before any dispatch, branch or publication; never a sizeof proof. */
internal object ConstructorPrefixExtent {
    fun analyze(bytes: BinaryView): Long {
        require(bytes.size in 1..32768)
        val flow = X64ControlFlow(X64Instructions(bytes).all(8192))
        val arguments = SysVArgumentFlow(flow, includeStack = true)
        val initialized = mutableSetOf<Long>()
        for (instruction in flow.instructions) {
            if (instruction.operation in listOf(Operation.CALL, Operation.JMP, Operation.JCC, Operation.RET)) break
            require(instruction.operation in listOf(Operation.MOV, Operation.MOVZX, Operation.MOVSX,
                Operation.LEA, Operation.PUSH, Operation.ADD, Operation.SUB, Operation.CMP, Operation.TEST,
                Operation.XOR, Operation.VECTOR_XOR, Operation.SCALAR_MOV, Operation.VECTOR_MOV,
                Operation.CMOV, Operation.NOP, Operation.ENDBR)) { "Unsupported constructor prefix operation" }
            val destination = instruction.destination as? Memory ?: continue
            if (instruction.operation in listOf(Operation.CMP, Operation.TEST)) continue
            require(instruction.operation in listOf(Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV))
            val target = arguments.memory(instruction.offset, destination)
                ?: error("Constructor prefix writes an unproven address")
            require(target.width in listOf(1, 2, 4, 8, 16))
            when (target.reference.argument) {
                7 -> {
                    require(target.reference.offset in 0..65536L - target.width)
                    repeat(target.width) { initialized += target.reference.offset + it }
                }
                4 -> {
                    val top = arguments.register(instruction.offset, 4)
                    require(top?.argument == 4 && target.reference.offset >= top.offset &&
                            target.reference.offset <= -target.width.toLong())
                }
                else -> error("Constructor prefix publishes storage outside its receiver or private frame")
            }
        }
        var extent = 0L
        while (extent in initialized) extent++
        require(extent > 0) { "Constructor has no contiguous initialized receiver prefix" }
        return extent
    }
}
