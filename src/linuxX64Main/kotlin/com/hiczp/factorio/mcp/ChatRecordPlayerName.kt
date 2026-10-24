package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVArgumentFlow.Reference
import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation

/**
 * The retained sender string passed to the typed serializer, without rendering or rebuilding chat
 * text.
 */
internal object ChatRecordPlayerName {
    fun resolve(image: ElfImage, node: NativeListNodeLayout, string: NativeStringLayout): Long {
        val caller = image.symbol("_ZNK13OutputConsole4Item4saveER13MapSerialiser")
        val serializer =
            image.symbol(
                "_ZN16SerialiserHelperI13MapSerialiserE9saveValueERS0_RKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEE"
            )
        EhFrames(image).function(serializer)
        require(node.value in 0 until node.size)
        return analyze(
            X64ControlFlow.resolve(image, caller),
            serializer.address - caller.address,
            node.size - node.value,
            string.size,
        )
    }

    fun analyze(flow: X64ControlFlow, serializer: Long, recordSize: Long, stringSize: Long): Long {
        require(recordSize in 8..4096 && stringSize in 8..minOf(recordSize, 256))
        val call =
            flow.instructions
                .filter {
                    it.offset in flow.reachable &&
                        it.operation == Operation.CALL &&
                        it.destination == Immediate(serializer)
                }
                .singleOrNull() ?: error("Console record has no unique retained sender serializer")
        val arguments = SysVArgumentFlow(flow)
        val name =
            arguments.register(call.offset, 6)
                ?: error("Console sender has no original record provenance")
        require(
            name.argument == 7 &&
                name.offset in 0..recordSize - stringSize &&
                name.offset % 8 == 0L &&
                arguments.register(call.offset, 7) == Reference(6)
        ) {
            "Console sender serialization does not use its bounded embedded string and original serializer"
        }
        return name.offset
    }
}
