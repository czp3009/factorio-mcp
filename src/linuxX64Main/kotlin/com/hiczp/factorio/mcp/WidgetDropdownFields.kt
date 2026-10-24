package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Selection and list storage metadata. Item/button/text identity is verified separately before
 * observation.
 */
internal data class WidgetDropdownFields(
    val size: Long,
    val selected: Long,
    val list: Long,
    val first: Long,
    val last: Long,
    val stride: Long,
    val button: Long,
) {
    companion object {
        fun resolve(image: ElfImage): WidgetDropdownFields {
            val size = SysVObjectSize.resolve(image, "4agui8DropDown")
            val selected =
                SysVAccessors.resolve(image, image.symbol("_ZNK4agui8DropDown16getSelectedIndexEv"))
                    .withinObject(size)
            require(selected.width == 4 && selected.mask == 0xffffffffUL && selected.shift == 0)
            val listSize = SysVObjectSize.resolve(image, "4agui7ListBox")
            val count =
                VectorCountAccessor.resolve(image, "_ZNK4agui7ListBox12getItemCountEv", listSize)
            val wrapper = image.symbol("_ZNK4agui8DropDown9getItemAtB5cxx11Ei")
            val target =
                ItaniumVtable.resolve(image, "_ZTVN4agui7ListBoxE")
                    .method(image, "_ZNK4agui7ListBox11indexExistsEi")
            EhFrames(image).function(wrapper)
            val list = embedding(image.functionBytes(wrapper, 4096), target.slot, size, listSize)
            require(selected.offset + 4 <= list || selected.offset >= list + listSize)
            val button = ListItemTextPointer.resolve(image, listSize, count.first, count.stride)
            return WidgetDropdownFields(
                size,
                selected.offset,
                list,
                list + count.first,
                list + count.last,
                count.stride,
                button,
            )
        }

        /** The item reader checks the original EDX index against RSI's embedded typed ListBox. */
        fun embedding(bytes: BinaryView, slot: Int, size: Long, listSize: Long): Long {
            require(bytes.size in 1..4096 && listSize > 0 && size >= listSize && slot in 0..8191)
            val flow = X64ControlFlow(X64Instructions(bytes).all())
            val calls =
                flow.instructions.filter {
                    val target = it.destination as? Memory
                    it.operation == Operation.CALL &&
                        it.offset in flow.reachable &&
                        target != null &&
                        target.width == 8 &&
                        !target.relative &&
                        target.index == null &&
                        target.displacement == slot * 8L
                }
            val call =
                calls.singleOrNull() ?: error("Dropdown item wrapper has no unique list call")
            val arguments = SysVArgumentFlow(flow)
            val receiver =
                arguments.register(call.offset, 7) ?: error("List wrapper lost its receiver")
            require(receiver.argument == 6 && receiver.offset in 0..size - listSize)
            val target = call.destination as Memory
            val table =
                OriginalPointerOrigins(flow).register(call.offset, checkNotNull(target.base))
                    as? OriginalPointerOrigins.Load ?: error("List dispatch has no original vtable")
            val base =
                table.base as? OriginalPointerOrigins.Argument
                    ?: error("List dispatch has an indirect owner")
            require(base.register == 6 && base.adjustment + table.member == receiver.offset)
            val copies =
                PrivateValueCopies(
                    flow.reaching(call.offset),
                    emptyMap(),
                    scalarArguments = mapOf(2 to 4),
                )
            require(copies.argument(call.offset, Register(6, 4)) == 2)
            return receiver.offset
        }
    }
}
