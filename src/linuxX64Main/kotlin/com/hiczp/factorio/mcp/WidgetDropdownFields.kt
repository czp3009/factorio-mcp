package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Selection and list storage metadata. Item/button/text identity is verified separately before observation. */
internal data class WidgetDropdownFields(
    val size: Long, val selected: Long, val list: Long,
    val first: Long, val last: Long, val stride: Long, val button: Long
) {
    companion object {
        fun resolve(image: ElfImage): WidgetDropdownFields {
            val size = SysVObjectSize.resolve(image, "4agui8DropDown")
            val selected = SysVAccessors.resolve(image, image.symbol("_ZNK4agui8DropDown16getSelectedIndexEv"))
                .withinObject(size)
            require(selected.width == 4 && selected.mask == 0xffffffffUL && selected.shift == 0)
            val listSize = SysVObjectSize.resolve(image, "4agui7ListBox")
            val count = VectorCountAccessor.resolve(image, "_ZNK4agui7ListBox12getItemCountEv", listSize)
            val wrapper = image.symbol("_ZNK4agui8DropDown9getItemAtB5cxx11Ei")
            val target = image.symbol("_ZNK4agui7ListBox9getItemAtB5cxx11Ei")
            EhFrames(image).function(wrapper)
            EhFrames(image).function(target)
            val list = embedding(image.functionBytes(wrapper, 256), target.address - wrapper.address, size, listSize)
            require(selected.offset + 4 <= list || selected.offset >= list + listSize)
            val button = ListItemTextPointer.resolve(image, listSize, count.first, count.stride)
            return WidgetDropdownFields(
                size,
                selected.offset,
                list,
                list + count.first,
                list + count.last,
                count.stride,
                button
            )
        }

        /** Hidden output remains RDI; the wrapper forwards RSI's embedded list and the original EDX index. */
        fun embedding(bytes: BinaryView, target: Long, size: Long, listSize: Long): Long {
            require(bytes.size in 1..256 && listSize > 0 && size >= listSize)
            val flow = X64ControlFlow(X64Instructions(bytes).all())
            val calls = flow.instructions.filter { it.operation == Operation.CALL }
            val call = calls.singleOrNull() ?: error("Dropdown item wrapper has no unique list call")
            require(call.destination == Immediate(target) && call.offset in flow.reachable)
            val arguments = SysVArgumentFlow(flow)
            require(
                arguments.register(call.offset, 7) == SysVArgumentFlow.Reference(7) &&
                        arguments.register(call.offset, 2) == SysVArgumentFlow.Reference(2)
            )
            val receiver = arguments.register(call.offset, 6) ?: error("List wrapper lost its receiver")
            require(receiver.argument == 6 && receiver.offset in 0..size - listSize)
            val exits = flow.instructions.filter { it.operation == Operation.RET && it.offset in flow.reachable }
            require(exits.isNotEmpty() && exits.all {
                arguments.register(
                    it.offset,
                    0
                ) == SysVArgumentFlow.Reference(7)
            })
            return receiver.offset
        }
    }
}
