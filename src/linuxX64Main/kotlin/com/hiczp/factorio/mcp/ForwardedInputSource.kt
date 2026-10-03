package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.*
import com.hiczp.factorio.mcp.X64Instructions.*

/** A typed network wrapper that tail-forwards evaluation without changing the original caller. */
internal data class ForwardedInputSource(val type: ItaniumType, val size: Long, val source: Long) {
    companion object {
        fun resolve(image: ElfImage, evaluationSlot: Int): ForwardedInputSource {
            val type = ItaniumType.resolve(image, "19NetworkInputHandler")
            val size = SysVObjectSize.resolve(image, "19NetworkInputHandler")
            val method = ItaniumVtable.resolve(image, "_ZTV19NetworkInputHandler")
                .method(image, "_ZN19NetworkInputHandler16sendStateChangesEv")
            require(method.slot == evaluationSlot) { "Network input evaluation changes the source interface slot" }
            require(method.function.size in 1..256) { "Input wrapper exceeds the analysis bound" }
            EhFrames(image).function(method.function)
            return ForwardedInputSource(type, size, inspect(image.functionBytes(method.function, 256), size, evaluationSlot))
        }

        fun inspect(bytes: BinaryView, size: Long, slot: Int): Long {
            require(size in 16..(64 * 1024 * 1024) && bytes.size in 1..256 && slot in 0..8191)
            val body = X64Instructions(bytes).all()
            val tail = body.last()
            require(tail.operation == Operation.JMP && tail.destination !is Immediate) {
                "Input wrapper does not end in virtual tail forwarding"
            }
            for (instruction in body.dropLast(1)) {
                require(instruction.operation in setOf(Operation.MOV, Operation.PUSH, Operation.POP,
                    Operation.NOP, Operation.ENDBR, Operation.ADD, Operation.SUB)) {
                    "Input wrapper has other work or conditional forwarding"
                }
                require(instruction.destination !is Memory) { "Input wrapper writes object storage" }
                if (instruction.operation in setOf(Operation.ADD, Operation.SUB))
                    require(instruction.destination == Register(4, 8)) { "Input wrapper changes a non-frame value" }
            }
            // Bound the provenance analysis at the tail. Only its pre-transfer state is inspected.
            val bounded = bytes.bytes(0, bytes.size.toInt())
            bounded[tail.offset.toInt()] = 0xc3.toByte()
            for (index in 1 until tail.size) bounded[tail.offset.toInt() + index] = 0x90.toByte()
            val values = SysVReceiverFlow(BinaryView(bounded), 0, size).before(tail.offset)
            require(values[4] == Stack(0) && listOf(3, 5, 12, 13, 14, 15).all { values[it] == Original(it) }) {
                "Input wrapper does not preserve the caller frame"
            }
            val receiver = SysVVirtualCall.receiver(tail.copy(operation = Operation.CALL), values, slot) as? Pointer
                ?: error("Input wrapper target and receiver are unrelated")
            require(receiver.base == Receiver() && receiver.offset in 8..size - 8 && receiver.offset % 8 == 0L) {
                "Input wrapper source exceeds its typed object"
            }
            return receiver.offset
        }
    }
}
