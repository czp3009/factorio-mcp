package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Conditional computation/copy evidence. Lookup ABI and the loaded receiver's type require separate proofs. */
internal data class InlineModifier(val receiverLoad: Long, val end: Long, val register: Int, val field: Long) {
    companion object {
        fun resolve(image: ElfImage, function: ElfImage.Symbol, flow: X64ControlFlow, sink: Long): InlineModifier {
            val getter = image.symbol("_ZNK10InputState9isAltDownEv")
            val lookups = listOf(
                "_ZN7FlatMapI12SDL_ScancodeN10InputState8KeyStateESt4lessIvEE17private_subscriptERKS0_",
                "_ZN7FlatMapI12SDL_ScancodeN10InputState8KeyStateESt4lessIvEE17private_subscriptEOS0_",
            ).map { image.symbol(it).also { symbol -> EhFrames(image).function(symbol) }.address }
            // These selected scalar arguments describe comparison evidence, not an authorization to call the helpers.
            val arguments = lookups.associateWith { listOf(Register(7, 8), Register(6, 4)) }
            val expected = ScalarDecisionTrace(X64ControlFlow.resolve(image, getter), getter.address, arguments).paths()
            val analyzer = ScalarDecisionTrace(flow, function.address, arguments)
            val starts = buildSet {
                for (call in flow.instructions.filter {
                    it.operation == Operation.CALL &&
                            (it.destination as? Immediate)?.value?.plus(function.address) in lookups
                }) {
                    var position = call.offset
                    for (step in 0 until 8) {
                        position = flow.predecessors[position]?.singleOrNull() ?: break
                        val instruction = flow.body.getValue(position)
                        if (instruction.operation != Operation.MOV) break
                        val target = instruction.destination as? Register ?: break
                        if (instruction.source is Memory) {
                            if (target.width == 8 && target.number in listOf(3, 12, 13, 14, 15)) add(position)
                            break
                        }
                    }
                }
            }
            val copies = LocalFieldCopies(flow)
            val matches = mutableListOf<InlineModifier>()
            for (load in starts) {
                val instruction = flow.body.getValue(load)
                val receiver = (instruction.destination as Register).number
                val start = load + instruction.size
                for (end in flow.reachable.filter { flow.predecessors[it].orEmpty().size > 1 }) {
                    for (register in listOf(3, 12, 13, 14, 15)) {
                        try {
                            if (analyzer.paths(start, receiver, end, Register(register, 1)) != expected) continue
                            val field = copies.fromRegister(end, sink, register, 1).single()
                            matches += InlineModifier(load, end, register, field.offset)
                        } catch (_: IllegalArgumentException) {
                            // A candidate is not a complete equivalent decision tree or a surviving field copy.
                        } catch (_: IllegalStateException) {
                            // Unknown provenance cannot establish a match.
                        }
                    }
                }
            }
            return matches.singleOrNull() ?: error("Inline Alt computation has no unique proven field copy")
        }
    }
}
