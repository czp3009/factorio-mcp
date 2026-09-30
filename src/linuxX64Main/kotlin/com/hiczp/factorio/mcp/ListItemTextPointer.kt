package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Element pointer consumed by a verified Widget text slot, indexed by the original signed item index. */
internal object ListItemTextPointer {
    fun resolve(image: ElfImage, listSize: Long, first: Long, stride: Long): Long {
        val function = image.symbol("_ZNK4agui7ListBox9getItemAtB5cxx11Ei")
        EhFrames(image).function(function)
        val slot = ItaniumVtable.resolve(image, "_ZTVN4agui6WidgetE")
            .method(image, "_ZNK4agui6Widget7getTextB5cxx11Ev").slot
        return analyze(image.functionBytes(function, 4096), listSize, first, stride, slot)
    }

    fun analyze(bytes: BinaryView, listSize: Long, first: Long, stride: Long, textSlot: Int): Long {
        require(
            bytes.size in 1..4096 && listSize >= 8 && first in 0..listSize - 8 &&
                    stride in 8..4096 && textSlot in 0..4096
        )
        val flow = X64ControlFlow(X64Instructions(bytes).all(4096))
        val arguments = SysVArgumentFlow(flow)
        val definitions = ScalarExpression(flow)
        val call = flow.instructions.filter { instruction ->
            val target = instruction.destination as? Memory
            instruction.offset in flow.reachable && instruction.operation == Operation.CALL && target != null &&
                    !target.relative && target.index == null && target.base != null && target.width == 8 &&
                    target.displacement == textSlot * 8L
        }.singleOrNull() ?: error("Item text reader lacks a unique verified text slot dispatch")
        val target = call.destination as Memory
        val table = definitions.definition(call.offset, checkNotNull(target.base))
        require(
            table.operation == Operation.MOV && table.destination == Register(target.base, 8) &&
                    table.source == Memory(7, null, 1, 0, 8)
        )
        val item = definitions.definition(call.offset, 7)
        require(
            item == definitions.definition(table.offset, 7) && item.operation == Operation.MOV &&
                    item.destination == Register(7, 8)
        )
        val pointer = item.source as? Memory ?: error("Item text receiver is not an indexed element member")
        require(
            !pointer.relative && pointer.base != null && pointer.index != null && pointer.width == 8 &&
                    pointer.base != pointer.index && pointer.displacement in 0..stride - 8
        )
        val base = definitions.definition(item.offset, pointer.base)
        require(
            base.operation == Operation.MOV && base.destination == Register(pointer.base, 8) &&
                    arguments.source(base.offset) == SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, first), 8)
        )
        val index = definitions.definition(item.offset, pointer.index)
        require(index.operation == Operation.SHL && index.destination == Register(pointer.index, 8))
        val shift = (index.source as? Immediate)?.value ?: error("Item stride is not constant")
        require(shift in 0..12 && (1L shl shift.toInt()) * pointer.scale == stride)
        val signed = definitions.definition(index.offset, pointer.index)
        val input = signed.source as? Register ?: error("Item index does not come from the original scalar argument")
        require(
            signed.operation == Operation.MOVSX && signed.destination == Register(
                pointer.index,
                8
            ) && input.width == 4
        )
        fun original(site: Long, register: Int, depth: Int = 0): Boolean {
            require(depth < 16)
            if (arguments.register(site, register) == SysVArgumentFlow.Reference(2)) return true
            val definition = definitions.definition(site, register)
            val source = definition.source as? Register ?: return false
            return definition.operation == Operation.MOV && definition.destination == Register(register, 4) &&
                    source.width == 4 && original(definition.offset, source.number, depth + 1)
        }
        require(original(signed.offset, input.number)) { "Text item index was changed or came from another argument" }
        return pointer.displacement
    }
}
