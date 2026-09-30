package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Original
import com.hiczp.factorio.mcp.SysVReceiverFlow.Stack
import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Full normal-return frame proof, separate from constructor payload semantics and exceptional cleanup. */
internal object ChatConstructorFrame {
    fun verify(image: ElfImage, size: Long, externalIndexed: Set<Long>) {
        val entry =
            image.symbol("_ZN11InputActionC2E15InputActionTypeRKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEE")
        val allocate = image.symbol("_Znwm")
        EhFrames(image).function(entry)
        EhFrames(image).function(allocate)
        analyze(image.functionBytes(entry, 4096), entry.address, size, allocate.address, externalIndexed)
    }

    fun analyze(bytes: BinaryView, address: Long, size: Long, allocate: Long, externalIndexed: Set<Long> = emptySet()) {
        require(size in 1..4096)
        val instructions = X64Instructions(bytes).all(1024)
        val allocations = instructions.filter {
            it.operation == Operation.CALL &&
                    it.destination == Immediate(allocate - address)
        }.map { it.offset }.toSet()
        require(allocations.size == 1) { "Chat constructor lacks one verified allocation entry" }
        val values = SysVReceiverFlow(
            bytes, address, size, heapResults = allocations,
            externalIndexedAccesses = externalIndexed
        )
        val returns = values.instructions.filter { it.offset in values.reachable && it.operation == Operation.RET }
        require(returns.isNotEmpty())
        for (exit in returns) {
            val registers = values.before(exit.offset)
            require(registers[4] == Stack(0) && listOf(3, 5, 12, 13, 14, 15).all {
                registers[it] == Original(it)
            }) { "Chat constructor does not restore its original System V frame" }
        }
    }
}
