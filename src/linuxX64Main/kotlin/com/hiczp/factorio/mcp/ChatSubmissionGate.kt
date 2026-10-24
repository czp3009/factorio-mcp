package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Pointer
import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Preserves the native callback's raw exclusion predicate without assigning gameplay meaning to its
 * value.
 */
internal data class ChatSubmissionGate(
    val playerMap: Long,
    val mapGame: Long,
    val gameHandler: Long,
    val handlerField: Long,
    val excluded: Long,
) {
    companion object {
        private const val CALLBACK =
            "_ZNSt17_Function_handlerIFvvEZN20CommonMapInteraction18toggleConsoleInputEbE3\$_0E9_M_invokeERKSt9_Any_data"

        fun resolve(image: ElfImage): ChatSubmissionGate {
            val callback = image.symbol(CALLBACK)
            val submit = image.symbol("_ZNK6Player15sendToListenersEO11InputAction")
            EhFrames(image).function(callback)
            EhFrames(image).function(submit)
            return analyze(image.functionBytes(callback, 4096), callback.address, submit.address)
        }

        fun analyze(bytes: BinaryView, address: Long, submit: Long): ChatSubmissionGate {
            // This bounds symbolic closure analysis only; no runtime reader uses it as an object
            // extent.
            val values = SysVReceiverFlow(bytes, address, 4096)
            val flow = X64ControlFlow(values.instructions)
            val call =
                flow.instructions.single {
                    it.offset in flow.reachable &&
                        it.operation == Operation.CALL &&
                        it.destination == Immediate(submit - address)
                }
            val ancestors = flow.reaching(call.offset)
            val definitions = ScalarExpression(flow)
            val guards =
                flow.instructions.mapNotNull { branch ->
                    if (
                        branch.offset !in ancestors.reachable ||
                            branch.operation != Operation.JCC ||
                            branch.condition != 4 ||
                            branch.destination == Immediate(branch.offset + branch.size) ||
                            !values.requiresEdge(
                                call.offset,
                                branch.offset,
                                branch.offset + branch.size,
                            )
                    )
                        return@mapNotNull null
                    val comparison = definitions.flagDefinition(branch.offset)
                    val member = comparison.destination as? Memory ?: return@mapNotNull null
                    if (
                        comparison.operation != Operation.CMP ||
                            member.width != 4 ||
                            member.relative ||
                            member.index != null ||
                            member.base == null ||
                            comparison.source !is Immediate
                    )
                        return@mapNotNull null
                    branch to comparison
                }
            val (branch, comparison) =
                guards.singleOrNull()
                    ?: error("Chat submission lacks a unique dominating member exclusion")
            val member =
                comparison.destination as? Memory ?: error("Chat guard is not a member comparison")
            val excluded =
                (comparison.source as? Immediate)?.value
                    ?: error("Chat guard has no constant exclusion")
            require(
                member.width == 4 &&
                    !member.relative &&
                    member.index == null &&
                    member.base != null &&
                    member.displacement in 0..4092 &&
                    excluded in 0..UInt.MAX_VALUE.toLong()
            )
            // Follow the admitted edge, allowing unrelated argument preparation while preserving
            // the compared
            // owner and submitted player. No callback or external write may invalidate either
            // borrowed object.
            var site = comparison.offset + comparison.size
            val visited = mutableSetOf<Long>()
            while (site != call.offset) {
                require(visited.add(site)) { "Chat admission prefix contains a loop" }
                val instruction = flow.body.getValue(site)
                if (site == branch.offset) {
                    site = branch.offset + branch.size
                    continue
                }
                require(
                    instruction.operation in
                        setOf(Operation.MOV, Operation.LEA, Operation.NOP, Operation.JMP)
                ) {
                    "Chat guard can be invalidated before submission"
                }
                if (instruction.operation in setOf(Operation.MOV, Operation.LEA)) {
                    val destination =
                        instruction.destination as? Register
                            ?: error("Chat admission writes through a borrowed object")
                    require(destination.number !in setOf(member.base, 7, 4, 5)) {
                        "Chat guard receiver or call frame changes before submission"
                    }
                }
                site = flow.successors.getValue(site).single()
            }
            val registers = values.borrowedCallValues(call.offset)
            val player =
                registers[7] as? Pointer ?: error("Chat submission has no borrowed player receiver")
            val handler =
                registers[member.base] as? Pointer ?: error("Chat guard has no handler owner")
            val game = handler.base as? Pointer ?: error("Chat handler does not belong to a game")
            val map = game.base as? Pointer ?: error("Chat game does not belong to a map")
            require(
                map.base == player &&
                    listOf(map.offset, game.offset, handler.offset).all {
                        it in 0..4096 && it % 8 == 0L
                    }
            ) {
                "Chat guard and submission use different players"
            }
            return ChatSubmissionGate(
                map.offset,
                game.offset,
                handler.offset,
                member.displacement,
                excluded,
            )
        }
    }
}
