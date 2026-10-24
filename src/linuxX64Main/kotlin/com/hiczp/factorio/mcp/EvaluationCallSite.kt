package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Exact native evaluation caller identity. Thread ownership and synchronization remain separate
 * requirements.
 */
internal object EvaluationCallSite {
    fun resolve(image: ElfImage, gameSize: Long, gameSource: Long, slot: Int): Long {
        val function = image.symbol("_ZN4Game17updateInputSourceESt8functionIFvvEE")
        EhFrames(image).function(function)
        val bytes = image.functionBytes(function, 8192)
        val table = ItaniumVtable.resolve(image, "_ZTV17PlayerInputSource")
        val flow =
            flow(bytes, gameSize, gameSource) { tailSlot ->
                val target = table.function(image, tailSlot)
                require(
                    target.address !in function.address until function.address + function.size
                ) {
                    "Game evaluation tail target reenters its own body"
                }
            }
        return function.address + analyze(flow, gameSize, gameSource, slot)
    }

    /**
     * Recognizes virtual exits only after proving the original frame and selected source receiver.
     */
    fun flow(
        bytes: BinaryView,
        gameSize: Long,
        gameSource: Long,
        validateTail: (Int) -> Unit,
    ): X64ControlFlow {
        val instructions = X64Instructions(bytes).all(2048)
        val tails =
            instructions.filter { it.operation == Operation.JMP && it.destination !is Immediate }
        if (tails.isEmpty()) return X64ControlFlow(instructions)
        // Temporary exits bound the proof to each candidate's predecessors. Every candidate must
        // pass below;
        // the substituted bytes are analysis-only and are never installed or used as executable
        // evidence.
        val bounded = bytes.bytes(0, bytes.size.toInt())
        for (tail in tails) {
            require(tail.destination is Register && tail.destination.width == 8)
            bounded[tail.offset.toInt()] = 0xc3.toByte()
            for (index in 1 until tail.size) bounded[tail.offset.toInt() + index] = 0x90.toByte()
        }
        val provenance = SysVReceiverFlow(BinaryView(bounded), 0, gameSize)
        for (tail in tails) {
            val registers = provenance.before(tail.offset)
            require(
                registers[4] == SysVReceiverFlow.Stack(0) &&
                    listOf(3, 5, 12, 13, 14, 15).all {
                        registers[it] == SysVReceiverFlow.Original(it)
                    }
            ) {
                "Virtual evaluation tail does not restore the original frame"
            }
            val target =
                registers[(tail.destination as Register).number] as? SysVReceiverFlow.Pointer
                    ?: error("Evaluation tail target has no virtual provenance")
            val table =
                target.base as? SysVReceiverFlow.Pointer ?: error("Evaluation tail has no table")
            val receiver =
                table.base as? SysVReceiverFlow.Pointer ?: error("Evaluation tail has no source")
            require(
                receiver.base == SysVReceiverFlow.Receiver() &&
                    receiver.offset == gameSource &&
                    registers[7] == receiver &&
                    table.offset == 0L &&
                    target.offset in 0..65528 &&
                    target.offset % 8 == 0L
            ) {
                "Evaluation tail does not dispatch the selected source"
            }
            validateTail((target.offset / 8).toInt())
        }
        val exits = tails.map { it.offset }.toSet()
        return X64ControlFlow(
            instructions.map {
                if (it.offset in exits) it.copy(operation = Operation.RET, destination = null)
                else it
            }
        )
    }

    fun analyze(flow: X64ControlFlow, gameSize: Long, gameSource: Long, slot: Int): Long {
        require(
            gameSize in 8..(64 * 1024 * 1024) &&
                gameSource in 0..gameSize - 8 &&
                gameSource % 8 == 0L &&
                slot in 0..8191
        )
        val arguments = SysVArgumentFlow(flow)
        val definitions = ScalarExpression(flow)
        fun load(site: Long, register: Int): X64Instructions.Instruction? {
            var at = site
            var number = register
            repeat(32) {
                val definition =
                    try {
                        definitions.definition(at, number)
                    } catch (_: IllegalArgumentException) {
                        return null
                    } catch (_: IllegalStateException) {
                        return null
                    }
                if (
                    definition.operation != Operation.MOV ||
                        definition.destination != Register(number, 8)
                )
                    return null
                val source = definition.source
                if (source is Memory) return definition.takeIf { source.width == 8 }
                if (source !is Register || source.width != 8) return null
                at = definition.offset
                number = source.number
            }
            error("Evaluation receiver copy chain exceeds bounds")
        }

        val calls =
            flow.instructions.filter { instruction ->
                if (
                    instruction.offset !in flow.reachable || instruction.operation != Operation.CALL
                )
                    return@filter false
                val target = instruction.destination as? Memory ?: return@filter false
                if (
                    target.width != 8 ||
                        target.relative ||
                        target.index != null ||
                        target.base == null ||
                        target.displacement != slot * 8L
                )
                    return@filter false
                val receiver = load(instruction.offset, 7) ?: return@filter false
                if (
                    arguments.source(receiver.offset) !=
                        SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, gameSource), 8)
                )
                    return@filter false
                val table = load(instruction.offset, target.base) ?: return@filter false
                val memory = table.source as Memory
                return@filter !memory.relative &&
                    memory.index == null &&
                    memory.base != null &&
                    memory.displacement == 0L &&
                    load(table.offset, memory.base)?.offset == receiver.offset
            }
        val call =
            calls.singleOrNull()
                ?: error("Game lacks a unique evaluation call through its input source")
        val returned = call.offset + call.size
        require(
            flow.successors.getValue(call.offset) == listOf(returned) && returned in flow.body
        ) {
            "Native evaluation call lacks an in-function return site"
        }
        return returned
    }
}
