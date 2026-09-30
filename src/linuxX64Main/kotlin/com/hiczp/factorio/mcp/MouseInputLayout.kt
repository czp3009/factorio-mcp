package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlin.math.abs

/** Bounded queue element metadata. Remaining fields, getter ABI and GUI conversion are separate requirements. */
internal data class MouseInputLayout(
    val queue: LocalQueueAppend.Proof,
    val time: Long,
    val control: Long,
    val shift: Long,
    val alt: InlineModifier,
    val handlerSize: Long,
    val handlerModifiers: InputModifierSources,
) {
    init {
        require(queue.extent in 1..4096)
        val fields = listOf(time to 8, control to 1, shift to 1, alt.field to 1)
        require(fields.all { (offset, width) -> offset >= 0 && offset <= queue.extent - width })
        require(fields.withIndex().all { (index, field) ->
            fields.drop(index + 1).all { other ->
                field.first + field.second <= other.first || other.first + other.second <= field.first
            }
        }) { "Mouse input fields overlap" }
        handlerModifiers.validate(handlerSize)
        require(
            queue.queue in 0 until handlerSize && queue.cursor in queue.queue..handlerSize - 8 &&
                    queue.limit in queue.queue..handlerSize - 8 && abs(queue.cursor - queue.limit) >= 8
        )
        require(listOf(handlerModifiers.alt, handlerModifiers.control, handlerModifiers.shift).all { field ->
            listOf(queue.cursor, queue.limit).none { field in it until it + 8 }
        }) { "Handler modifier overlaps a queue pointer" }
    }

    companion object {
        fun resolve(image: ElfImage): MouseInputLayout {
            val header = EventHeader.resolve(image)
            val handlerSize = SysVObjectSize.resolve(image, "16InputHandlerAgui")
            val function = image.symbol("_ZN16InputHandlerAgui12processEventERK5Event")
            val flow = X64ControlFlow.resolve(image, function)
            fun call(name: String): Long {
                val target = image.symbol(name)
                EhFrames(image).function(target)
                return flow.instructions.single {
                    it.operation == Operation.CALL &&
                            (it.destination as? Immediate)?.value?.plus(function.address) == target.address
                }.offset
            }

            val sink = call("_ZNSt5dequeIN4agui10MouseInputESaIS1_EE16_M_push_back_auxIJRKS1_EEEvDpOT_")
            val queue = LocalQueueAppend(flow).at(sink, handlerSize)
            val copies = LocalFieldCopies(flow)
            fun modifier(name: String) = copies.fromReturn(call(name), sink).single().also {
                require(it.width == 1)
            }.offset

            val arguments = SysVArgumentFlow(flow)
            val times = flow.instructions.filter {
                it.operation == Operation.SCALAR_MOV &&
                        arguments.source(it.offset) == SysVArgumentFlow.Read(
                    SysVArgumentFlow.Reference(6, header.time),
                    8
                )
            }.flatMap { copies.fromRead(it.offset, sink) }.distinct()
            val time = times.single().also { require(it.width == 8) }.offset
            val control = modifier("_ZNK10InputState10isCtrlDownEv")
            val shift = modifier("_ZNK10InputState11isShiftDownEv")
            val alt = InlineModifier.resolve(image, function, flow, sink)
            val handlerModifiers = InputModifierSources.resolve(
                flow, sink, handlerSize, queue.extent,
                InputModifierSources(alt.field, control, shift)
            )
            return MouseInputLayout(queue, time, control, shift, alt, handlerSize, handlerModifiers)
        }
    }
}
