package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation

/** The current window's owner, derived from the verified native event poll dispatch. */
internal data class FrontendWindow(val global: Long, val globalSize: Long, val member: Long) {
    companion object {
        fun resolve(image: ElfImage): FrontendWindow {
            val global = image.symbol("global")
            require(global.type == 1 && global.size == 8L)
            val size = SysVGlobalAllocation.resolve(
                image, "_ZN9MainTasks6createERK13ParsedOptions",
                "_ZN13GlobalContextC2ERKN10Filesystem4PathES3_", "global",
            ).size
            // nextEvent's verified hidden result precedes its window argument.
            val poll = EventPollCall.resolve(image, EventHeader.resolve(image))
            val bytes = image.functionBytes(poll.pump, 32768).slice(0, poll.pumpReturnOffset)
            val flow = X64ControlFlow(X64Instructions(bytes).all(2048))
            val call = flow.instructions.last()
            require(call.operation == Operation.CALL)
            val member = GlobalPointerLoad(flow, poll.pump.address, global.address, size).at(call.offset, 6)
            return FrontendWindow(global.address, size, member)
        }
    }
}
