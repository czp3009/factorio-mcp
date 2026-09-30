package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Pointer
import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import com.hiczp.factorio.mcp.X64Instructions.*

/** Pointer members established by the receiver passed to a named callee or a verified virtual slot. */
internal object SysVMemberCalls {
    /** Typed association at the first matching entry-prefix call; not a proof of the caller's complete behavior. */
    fun directPrefix(image: ElfImage, caller: String, callee: String, receiverSize: Long): Long {
        val from = image.symbol(caller)
        val to = image.symbol(callee)
        EhFrames(image).function(from)
        EhFrames(image).function(to)
        return directPrefix(image.functionBytes(from, 2048), from.address, to.address, receiverSize)
    }

    fun directPrefix(bytes: BinaryView, address: Long, callee: Long, receiverSize: Long): Long {
        require(bytes.size in 1..2048)
        val decoder = X64Instructions(bytes)
        var position = 0L
        repeat(256) {
            require(position < bytes.size)
            val instruction = decoder.decode(position)
            position += instruction.size
            require(instruction.operation != Operation.RET) { "Typed member call is absent from the entry prefix" }
            if (instruction.operation == Operation.CALL && instruction.destination == Immediate(callee - address)) {
                return direct(bytes.slice(0, position), address, callee, receiverSize)
            }
        }
        error("Typed member call exceeds entry-prefix analysis bound")
    }

    fun direct(
        image: ElfImage,
        caller: String,
        callee: String,
        receiverSize: Long,
        noReturnFunctions: Set<Long> = emptySet()
    ): Long {
        val from = image.symbol(caller)
        val to = image.symbol(callee)
        require(from.size in 1..8192) { "Member caller exceeds complete-function analysis bound" }
        EhFrames(image).function(from)
        EhFrames(image).function(to)
        return direct(image.functionBytes(from, 8192), from.address, to.address, receiverSize, noReturnFunctions)
    }

    fun direct(
        bytes: BinaryView,
        address: Long,
        callee: Long,
        receiverSize: Long,
        noReturnFunctions: Set<Long> = emptySet()
    ): Long {
        require(callee >= 0)
        val flow = SysVReceiverFlow(bytes, address, receiverSize, noReturnFunctions = noReturnFunctions)
        val calls = flow.instructions.filter {
            it.offset in flow.reachable && it.operation == Operation.CALL &&
                    it.destination == Immediate(callee - address)
        }
        require(calls.isNotEmpty()) { "No call to the selected member type" }
        val members = calls.map { instruction ->
            val receiver =
                flow.call(instruction.offset)[7] as? Pointer ?: error("Call receiver is not a pointer member")
            require(receiver.base == Receiver() && receiver.offset in 0..receiverSize - 8) {
                "Call does not use a direct member of its original receiver"
            }
            receiver.offset
        }.distinct()
        return members.singleOrNull() ?: error("Calls identify multiple members of the selected type")
    }

    /** Candidates still require exact live RTTI/vtable validation; a slot alone does not identify a class. */
    fun virtual(bytes: BinaryView, address: Long, slot: Int, receiverSize: Long): List<Long> {
        require(slot in 0..8191)
        val flow = SysVReceiverFlow(bytes, address, receiverSize)
        val calls = flow.instructions.filter { instruction ->
            val target = instruction.destination as? Memory
            instruction.operation == Operation.CALL && target != null && !target.relative && target.index == null &&
                    target.width == 8 && target.displacement == slot * 8L
        }
        require(calls.isNotEmpty()) { "No dispatch through the selected virtual slot" }
        val members = calls.map { virtualMember(flow, it, receiverSize) }.distinct().sorted()
        require(members.size in 1..64) { "Virtual member candidate count exceeds bound" }
        return members
    }

    /** One selected call, retaining the complete caller CFG so later backedges cannot invent an entry receiver. */
    fun virtualAt(bytes: BinaryView, address: Long, slot: Int, receiverSize: Long, call: Long): Long {
        require(slot in 0..8191)
        val flow = SysVReceiverFlow(bytes, address, receiverSize)
        val instruction =
            flow.instructions.singleOrNull { it.offset == call } ?: error("Selected virtual call is absent")
        val target = instruction.destination as? Memory ?: error("Selected call does not use a vtable")
        require(
            instruction.operation == Operation.CALL && !target.relative && target.index == null &&
                    target.width == 8 && target.displacement == slot * 8L
        )
        return virtualMember(flow, instruction, receiverSize)
    }

    private fun virtualMember(flow: SysVReceiverFlow, instruction: Instruction, receiverSize: Long): Long {
        val target = instruction.destination as Memory
        val registers = flow.call(instruction.offset)
        val receiver = registers[7] as? Pointer ?: error("Virtual call receiver is not a pointer member")
        val table = target.base?.let { registers[it] } as? Pointer
        require(
            receiver.base == Receiver() && receiver.offset in 0..receiverSize - 8 &&
                    table?.base == receiver && table.offset == 0L
        ) {
            "Virtual dispatch does not use the pointer member's own primary vtable"
        }
        return receiver.offset
    }
}
