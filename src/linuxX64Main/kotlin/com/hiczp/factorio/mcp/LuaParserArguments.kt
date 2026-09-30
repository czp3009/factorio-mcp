package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.*
import com.hiczp.factorio.mcp.X64Instructions.*

/** Cross-checks parser arguments in the initial binary/text mode checks, before unrelated parser work. */
internal object SysVLuaParserArguments {
    fun verify(image: ElfImage, layout: LuaLoadFrame) {
        val parser = image.symbol("_ZL8f_parserP9lua_StatePv")
        EhFrames(image).function(parser)
        analyze(image.functionBytes(parser, 8192), parser.address, layout) { image.importedFunction(it) == "strchr" }
    }

    fun analyze(bytes: BinaryView, address: Long, layout: LuaLoadFrame, isCharacterSearch: (Long) -> Boolean) {
        val reader = SysVLuaReader.inspect(bytes)
        require(reader.layout == layout.reader)
        val decoder = X64Instructions(bytes)
        val calls = mutableListOf<X64Instructions.Instruction>()
        var position = 0L
        repeat(256) {
            require(position < bytes.size && position < 1024)
            val instruction = decoder.decode(position)
            position += instruction.size
            val relative = (instruction.destination as? Immediate)?.value
            if (instruction.operation == Operation.CALL && relative != null) {
                require(relative >= -address && relative <= Long.MAX_VALUE - address)
                if (isCharacterSearch(address + relative)) calls += instruction
            }
            if (calls.size == 2) {
                val size = maxOf(layout.name + 8, layout.mode + 8, layout.reader.stream + 8)
                require(size in 8..4096)
                val flow = SysVReceiverFlow(
                    bytes.slice(0, position), address, size, receiverRegister = 6,
                    frameOutputs = mapOf(reader.call to listOf(FrameOutput(2, reader.output, 8)))
                )
                val characters = calls.map { call ->
                    val arguments = flow.call(call.offset)
                    val mode = arguments[7] as? Pointer ?: error("Lua parser mode lacks userdata provenance")
                    require(mode.base == Receiver() && mode.offset == layout.mode) { "Lua parser mode disagrees with loader storage" }
                    (arguments[6] as? Constant)?.value ?: error("Lua parser mode check has an unknown character")
                }
                require(characters.toSet() == setOf('b'.code.toLong(), 't'.code.toLong())) {
                    "Lua parser does not check both binary and text modes"
                }
                val nameRead = flow.instructions.any { item ->
                    val source = when (item.operation) {
                        Operation.MOVZX -> item.source as? Memory
                        Operation.CMP -> item.destination as? Memory
                        else -> null
                    }
                    if (item.offset !in flow.reachable || source == null || source.width != 1 || source.relative ||
                        source.index != null || source.displacement != 0L
                    ) false else {
                        val pointer = source.base?.let { flow.before(item.offset)[it] } as? Pointer
                        pointer?.base == Receiver() && pointer.offset == layout.name
                    }
                }
                require(nameRead) { "Lua parser does not consume the loader's name as a byte string" }
                return
            }
        }
        error("Lua parser argument checks exceed the prefix bound")
    }
}
