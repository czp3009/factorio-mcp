package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.*
import com.hiczp.factorio.mcp.X64Instructions.*

/** Native string entries used for an owned chat argument; no fabricated string representation is passed to the game. */
internal data class ChatStringEntries(
    val layout: NativeStringLayout,
    val construct: ElfImage.Symbol,
    val assign: ElfImage.Symbol
) {
    companion object {
        private const val STRING = "St7__cxx1112basic_stringIcSt11char_traitsIcESaIcEE"

        fun resolve(image: ElfImage): ChatStringEntries {
            val layout = NativeStringLayout.resolve(image)
            val construct = image.symbol("_ZN${STRING}C1Ev")
            val assign = image.symbol("_ZN${STRING}6assignEPKcm")
            val replace = image.symbol("_ZN${STRING}10_M_replaceEmmPKcm")
            for (function in listOf(construct, assign, replace)) EhFrames(image).function(function)
            constructor(image.functionBytes(construct, 256), layout)
            assignment(image.functionBytes(assign, 256), assign.address, replace.address, layout)
            return ChatStringEntries(layout, construct, assign)
        }

        fun constructor(bytes: BinaryView, string: NativeStringLayout) {
            val flow = X64ControlFlow(X64Instructions(bytes).all(64))
            val body = flow.instructions.filter { it.operation !in listOf(Operation.NOP, Operation.ENDBR) }
            require(body.last().operation == Operation.RET && body.dropLast(1).all {
                it.operation in listOf(Operation.MOV, Operation.LEA) &&
                        (it.operation != Operation.MOV || it.source !is Memory)
            }) { "Native empty string constructor has unsupported effects" }
            val proof = EmptyStringOutput.analyze(
                flow, string,
                listOf(DwarfRanges.Range(body.first().offset, body.last().offset))
            )
            require(proof.argument == 7) { "Empty string constructor has an unexpected receiver" }
        }

        fun assignment(bytes: BinaryView, address: Long, replace: Long, string: NativeStringLayout) {
            val flow = SysVReceiverFlow(bytes, address, string.size)
            val body = flow.instructions.filter { it.operation !in listOf(Operation.NOP, Operation.ENDBR) }
            val tail = body.last()
            require(
                tail.operation == Operation.JMP && tail.destination == Immediate(replace - address) &&
                        replace !in address until address + bytes.size && body.dropLast(1).all {
                    it.operation in listOf(Operation.MOV, Operation.XOR) && it.destination is Register
                }) { "String assignment is not the supported bounded replacement forwarding entry" }
            val arguments = SysVArgumentFlow(X64ControlFlow(flow.instructions))
            val reads = body.dropLast(1).filter { it.source is Memory }
            require(
                reads.size == 1 && arguments.source(reads.single().offset) ==
                        SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, string.length), 8)
            ) {
                "String assignment reads outside its receiver length"
            }
            val registers = flow.before(tail.offset)
            val length = registers[2] as? Pointer ?: error("String assignment does not read its current length")
            require(
                registers[7] == Receiver() && registers[6] == Constant(0) &&
                        length.base == Receiver() && length.offset == string.length &&
                        registers[1] == Original(6) && registers[8] == Original(2) && registers[4] == Stack(0) &&
                        listOf(3, 5, 12, 13, 14, 15).all { registers[it] == Original(it) }) {
                "String assignment changes its receiver, byte range or native frame"
            }
        }
    }
}
