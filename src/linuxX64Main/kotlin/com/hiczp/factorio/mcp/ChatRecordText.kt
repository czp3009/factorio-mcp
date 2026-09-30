package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVArgumentFlow.Reference
import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Typed LocalisedString member association, not its layout or authorization to call the serializer. */
internal object ChatRecordText {
    fun resolve(image: ElfImage, node: NativeListNodeLayout): Long {
        val caller = image.symbol("_ZNK13OutputConsole4Item4saveER13MapSerialiser")
        val callee = image.symbol("_ZNK15LocalisedString4saveER13MapSerialiser")
        EhFrames(image).function(caller)
        EhFrames(image).function(callee)
        require(node.value in 0 until node.size)
        return analyze(X64ControlFlow.resolve(image, caller), callee.address - caller.address, node.size - node.value)
    }

    fun analyze(flow: X64ControlFlow, save: Long, recordSize: Long): Long {
        require(recordSize in 8..4096)
        val call = flow.instructions.filter {
            it.offset in flow.reachable && it.operation == Operation.CALL &&
                    it.destination == Immediate(save)
        }.singleOrNull() ?: error("Console record has no unique text serializer")
        val arguments = SysVArgumentFlow(flow)
        val text = arguments.register(call.offset, 7) ?: error("Console text has no original record provenance")
        require(
            text.argument == 7 && text.offset in 0..recordSize - 8 && text.offset % 8 == 0L &&
                    arguments.register(call.offset, 6) == Reference(6)
        ) {
            "Console text serialization does not use an embedded member and the original serializer"
        }
        return text.offset
    }
}
