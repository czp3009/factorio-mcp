package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Pointer
import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import com.hiczp.factorio.mcp.X64Instructions.*

/** Native paused-input dispatch predicate. Runtime must validate the handler's concrete type and require
 * its Map and InputSource pointers to equal the independently selected world and local input source.
 * Admit only canonical boolean bytes (0 or 1) at runtime.
 */
internal data class InputPauseField(val handlerMap: Long, val handlerSource: Long, val mapPaused: Long) {
    companion object {
        fun resolve(image: ElfImage, mapSize: Long): InputPauseField {
            val size = SysVObjectSize.resolve(image, "17GameActionHandler")
            val method = ItaniumVtable.resolve(image, "_ZTV11InputSource")
                .method(image, "_ZN11InputSource22sendPausedStateChangesEv")
            val override = ItaniumVtable.resolve(image, "_ZTV17PlayerInputSource")
                .method(image, "_ZN17PlayerInputSource22sendPausedStateChangesEv")
            require(method.slot == override.slot)
            val function = image.symbol("_ZN17GameActionHandler6updateESt8functionIFvvEE")
            EhFrames(image).function(function)
            return analyze(
                image.functionBytes(function, 512),
                function.address,
                function.size,
                size,
                mapSize,
                method.slot
            )
        }

        fun analyze(
            bytes: BinaryView, address: Long, functionSize: Long,
            handlerSize: Long, mapSize: Long, pausedSlot: Int
        ): InputPauseField {
            require(
                bytes.size in 1..512 && functionSize >= bytes.size && functionSize <= 32768 &&
                        handlerSize in 8..65536 && mapSize in 1..(64 * 1024 * 1024) && pausedSlot in 0..8191
            )
            val decoder = X64Instructions(bytes)
            val instructions = mutableListOf<X64Instructions.Instruction>()
            var end = 0L
            repeat(64) {
                require(end < bytes.size)
                val instruction = decoder.decode(end)
                instructions += instruction
                end += instruction.size
                require(instruction.operation !in setOf(Operation.JMP, Operation.RET)) {
                    "Paused-input dispatch is not in the entry prefix"
                }
                if (instruction.operation != Operation.CALL) return@repeat
                val prefix = bytes.slice(0, end)
                val source = SysVMemberCalls.virtualAt(prefix, address, pausedSlot, handlerSize, instruction.offset)
                val branch = instructions.filter { it.operation == Operation.JCC }.singleOrNull()
                    ?: error("Paused input has no unique conditional dispatch")
                val compare = ScalarExpression(X64ControlFlow(instructions)).flagDefinition(branch.offset)
                val skipped = (branch.destination as? Immediate)?.value ?: error("Indirect pause guard")
                val trueFallthrough = branch.condition == 5 && compare.source == Immediate(1) ||
                        branch.condition == 4 && compare.source == Immediate(0)
                require(trueFallthrough && skipped in end until functionSize && compare.operation == Operation.CMP) {
                    "Paused input is not selected by a true native boolean"
                }
                val member = compare.destination as? Memory ?: error("Pause guard has no original member")
                require(member.width == 1 && !member.relative && member.index == null && member.displacement in 0 until mapSize)
                val flow = SysVReceiverFlow(prefix, address, handlerSize)
                val map = member.base?.let { flow.before(compare.offset)[it] } as? Pointer
                    ?: error("Pause byte has no pointer member owner")
                require(
                    map.base == Receiver() && map.offset in 0..handlerSize - 8 &&
                            map.offset % 8 == 0L && source % 8 == 0L && map.offset != source &&
                            flow.requiresEdge(instruction.offset, branch.offset, branch.offset + branch.size)
                )
                return InputPauseField(map.offset, source, member.displacement)
            }
            error("Paused input dispatch exceeds entry-prefix bound")
        }
    }
}
