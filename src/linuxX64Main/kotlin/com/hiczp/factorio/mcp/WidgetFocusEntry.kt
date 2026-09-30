package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Original widget and low-byte focus flag forwarded to the named focus manager. Internal focus policy stays native. */
internal object WidgetFocusEntry {
    fun verify(image: ElfImage, widgetSize: Long): ElfImage.Symbol {
        val function = image.symbol("_ZN4agui6Widget5focusE9NamedBoolINS_11TabbedInTagEE")
        val manager = image.symbol("_ZN4agui12FocusManager16setFocusedWidgetEPNS_6WidgetE9NamedBoolINS_11TabbedInTagEE")
        EhFrames(image).function(function)
        EhFrames(image).function(manager)
        verify(image.functionBytes(function, 2048), function.address, manager.address, widgetSize)
        return function
    }

    fun verify(bytes: BinaryView, address: Long, manager: Long, widgetSize: Long) {
        val flow = X64ControlFlow(X64Instructions(bytes).all(512))
        val arguments = SysVArgumentFlow(flow)
        val frame = SysVLocalArgument(flow)
        val values = SysVReceiverFlow(bytes, address, widgetSize)
        val definitions = ScalarExpression(flow)
        val dispatches = flow.instructions.filter {
            it.offset in flow.reachable &&
                    it.operation in listOf(
                Operation.JMP,
                Operation.CALL
            ) && it.destination == Immediate(manager - address)
        }
        require(dispatches.size == 1) { "Widget focus has no unique named manager dispatch" }
        val dispatch = dispatches.single()
        require(arguments.register(dispatch.offset, 6) == SysVArgumentFlow.Reference(7)) {
            "Focus manager does not receive the original widget"
        }
        val flag = definitions.definition(dispatch.offset, 2)
        val source = flag.source as? Register ?: error("Focus flag does not come from an entry register")
        require(
            flag.operation == Operation.MOVZX && flag.destination == Register(2, 4) && source.width == 1 &&
                    arguments.register(flag.offset, source.number) == SysVArgumentFlow.Reference(6)
        ) {
            "Focus manager does not receive the original low-byte flag"
        }
        require(flow.instructions.none { it.offset in flow.reachable && it.operation == Operation.CALL && it != dispatch }) {
            "Widget focus has an additional unverified entry boundary"
        }
        for (instruction in flow.instructions.filter { it.offset in flow.reachable }) {
            if (instruction.operation == Operation.RET || instruction == dispatch && instruction.operation == Operation.JMP) {
                require(frame.registers(instruction.offset)[4] == 0L)
                val restored = values.before(instruction.offset)
                require(listOf(3, 5, 12, 13, 14, 15).all { restored[it] == SysVReceiverFlow.Original(it) }) {
                    "Widget focus does not restore its preserved registers"
                }
            }
            if (instruction.operation == Operation.JMP && flow.successors.getValue(instruction.offset).isEmpty())
                require(instruction == dispatch) { "Widget focus exits through another tail call" }
        }
    }
}
