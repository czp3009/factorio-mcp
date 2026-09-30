package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** A native caller's boolean argument and integer status use. This does not establish safe reentrant invocation. */
internal object ScalarPumpCall {
    data class Proof(val setup: Long, val call: Long, val result: Long, val value: Int)

    fun resolve(image: ElfImage, caller: ElfImage.Symbol, pump: ElfImage.Symbol): Proof {
        EhFrames(image).function(caller)
        EhFrames(image).function(pump)
        // This proof examines only CFG edges and adjacent explicit scalar operands, not register provenance
        // across arithmetic. Indirect jumps remain unsupported rather than assuming a switch's destinations.
        val instructions = X64Instructions(
            image.functionBytes(caller, 32768),
            allowAtomicExchangeAdd = true, allowUnsignedWideMultiply = true
        ).all(8192)
        return inspect(X64ControlFlow(instructions), pump.address - caller.address)
    }

    fun inspect(flow: X64ControlFlow, target: Long): Proof {
        val calls = flow.instructions.filter {
            it.offset in flow.reachable && it.operation == Operation.CALL && it.destination == Immediate(target)
        }
        require(calls.size == 1) { "Expected one native event-pump call" }
        val call = calls.single()
        val setup = flow.body.getValue(flow.predecessors[call.offset].orEmpty().single())
        require(setup.offset + setup.size == call.offset && setup.destination == Register(7, 4)) {
            "Native pump argument does not immediately precede its call"
        }
        val value = when (setup.operation) {
            Operation.MOV -> (setup.source as? Immediate)?.value
            Operation.XOR -> 0L.takeIf { setup.source == setup.destination }
            else -> null
        }
        require(value == 0L || value == 1L) { "Native pump argument is not a boolean scalar" }
        val result = flow.body.getValue(call.offset + call.size)
        require(flow.predecessors[result.offset] == setOf(call.offset)) {
            "Native pump status use is entered without its call"
        }
        require(
            result.destination == Register(0, 4) && when (result.operation) {
                Operation.TEST -> result.source == result.destination
                Operation.CMP -> result.source is Immediate
                else -> false
            }
        ) { "Native pump return is not consumed as an integer status" }
        val branch = flow.body.getValue(result.offset + result.size)
        require(branch.operation == Operation.JCC && flow.predecessors[branch.offset] == setOf(result.offset)) {
            "Native pump status does not immediately determine a branch"
        }
        return Proof(setup.offset, call.offset, result.offset, value.toInt())
    }
}
