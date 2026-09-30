package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** The native console callback's action type and local object flow, not submission-phase authorization. */
internal data class ChatActionCall(val type: Int, val size: Long) {
    companion object {
        private const val CALLBACK =
            "_ZNSt17_Function_handlerIFvvEZN20CommonMapInteraction18toggleConsoleInputEbE3\$_0E9_M_invokeERKSt9_Any_data"
        private const val CONSTRUCT =
            "_ZN11InputActionC2E15InputActionTypeRKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEE"
        private const val DESTROY = "_ZN11InputAction12destroyValueEv"
        private const val SEND = "_ZNK6Player15sendToListenersEO11InputAction"

        fun resolve(image: ElfImage): ChatActionCall {
            val caller = image.symbol(CALLBACK)
            val construct = image.symbol(CONSTRUCT)
            val destroy = image.symbol(DESTROY)
            val send = image.symbol(SEND)
            for (function in listOf(caller, construct, destroy, send)) EhFrames(image).function(function)
            val size = VectorElementSize.resolve(image, "_ZNSt6vectorI11InputActionSaIS0_EED2Ev", DESTROY)
            val action = analyze(
                X64ControlFlow.resolve(image, caller), size, construct.address - caller.address,
                destroy.address - caller.address, send.address - caller.address
            )
            require(
                NativeScalarFunction.evaluate(
                    image, image.symbol("_ZN11InputAction6noDataE15InputActionType"),
                    action.type
                ) == 0
            ) { "Chat action does not select the native payload-bearing constructor path" }
            return action
        }

        fun analyze(flow: X64ControlFlow, size: Long, construct: Long, destroy: Long, send: Long): ChatActionCall {
            require(size in 1..4096 && setOf(construct, destroy, send).size == 3)
            fun calls(target: Long) = flow.instructions.filter {
                it.offset in flow.reachable &&
                        it.operation == Operation.CALL && it.destination == Immediate(target)
            }

            val construction =
                calls(construct).singleOrNull() ?: error("Chat action construction is absent or ambiguous")
            val submission = calls(send).singleOrNull() ?: error("Chat action submission is absent or ambiguous")
            val destruction =
                calls(destroy).singleOrNull() ?: error("Normal chat action destruction is absent or ambiguous")
            val locals = SysVLocalArgument(flow)
            val value = locals.argument(construction.offset, 7, size.toInt())
            require(
                locals.argument(submission.offset, 6, size.toInt()) == value &&
                        locals.argument(destruction.offset, 7, size.toInt()) == value
            ) {
                "Chat callback submits or destroys a different local action"
            }
            val type = ScalarExpression(flow).before(construction.offset, Register(6, 4))
            require(ScalarExpression.inputs(type).isEmpty()) { "Chat action type is not constant" }
            val resolved = ScalarExpression.evaluate(type) { error("Unexpected action type input") }
            require(resolved in 0..65535)
            // Every path to submission must construct this object; every normal path after construction must
            // destroy it. The native condition that skips submission is preserved as a separate admission concern.
            fun reaches(start: Long, target: Long, excluded: Long): Boolean {
                val pending = ArrayDeque<Long>()
                val visited = mutableSetOf<Long>()
                pending.add(start)
                while (pending.isNotEmpty()) {
                    val at = pending.removeFirst()
                    if (at == excluded || !visited.add(at)) continue
                    if (at == target) return true
                    pending.addAll(flow.successors.getValue(at))
                }
                return false
            }
            require(!reaches(0, submission.offset, construction.offset)) { "Submission can bypass construction" }
            require(!reaches(0, destruction.offset, construction.offset)) { "Destruction can bypass construction" }
            val after = flow.successors.getValue(construction.offset).single()
            require(!reaches(after, construction.offset, destruction.offset)) {
                "Chat action can be reconstructed before destruction"
            }
            for (instruction in flow.instructions.filter {
                it.offset in flow.reachable &&
                        flow.successors.getValue(it.offset).isEmpty()
            }) {
                require(!reaches(after, instruction.offset, destruction.offset)) {
                    "Constructed chat action can exit without normal destruction"
                }
            }
            require(
                !reaches(
                    flow.successors.getValue(submission.offset).single(),
                    submission.offset,
                    destruction.offset
                )
            ) {
                "Chat action submission can replay before destruction"
            }
            return ChatActionCall(resolved.toInt(), size)
        }
    }
}
