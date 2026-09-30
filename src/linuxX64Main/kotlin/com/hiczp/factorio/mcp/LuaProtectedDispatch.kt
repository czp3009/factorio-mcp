package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Original
import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import com.hiczp.factorio.mcp.X64Instructions.*

/** The internal wrapper preserves the state/callback/userdata ABI at raw protection. */
internal object SysVLuaProtectedDispatch {
    fun verify(image: ElfImage, stateSize: Long, loader: LuaLoadFrame) {
        val entry = image.symbol("_Z10luaD_pcallP9lua_StatePFvS0_PvES1_ll")
        val raw = image.symbol("_Z20luaD_rawrunprotectedP9lua_StatePFvS0_PvES1_")
        val frames = EhFrames(image)
        frames.function(entry)
        frames.function(raw)
        analyze(
            image.functionBytes(entry, 4096),
            entry.address,
            raw.address,
            stateSize,
            loader.stackBase,
            loader.errorHandler
        )
    }

    fun analyze(bytes: BinaryView, address: Long, raw: Long, stateSize: Long, stackBase: Long, errorHandler: Long) {
        require(bytes.size in 1..4096 && address >= 0 && address <= Long.MAX_VALUE - bytes.size && raw >= 0)
        require(
            stateSize >= 8 && stackBase in 0..stateSize - 8 && errorHandler in 0..stateSize - 8 &&
                    (stackBase + 8 <= errorHandler || errorHandler + 8 <= stackBase)
        )
        val decoder = X64Instructions(bytes)
        var position = 0L
        repeat(128) {
            require(position < bytes.size && position < 1024)
            val instruction = decoder.decode(position)
            position += instruction.size
            if (instruction.operation == Operation.CALL) {
                val relative = (instruction.destination as? Immediate)?.value ?: error("Indirect inner Lua protection")
                require(relative >= -address && relative <= Long.MAX_VALUE - address && address + relative == raw)
                val flow = SysVReceiverFlow(bytes.slice(0, position), address, stateSize)
                val arguments = flow.call(instruction.offset)
                require(arguments[7] == Receiver() && arguments[6] == Original(6) && arguments[2] == Original(2)) {
                    "Inner Lua protection changes state, callback or userdata"
                }
                require(flow.instructions.none {
                    it.operation in listOf(
                        Operation.JMP,
                        Operation.JCC,
                        Operation.RET
                    )
                }) {
                    "Inner Lua protection prefix contains unverified control flow"
                }
                val stores = flow.instructions.filter { candidate ->
                    val target = candidate.destination as? Memory
                    if (target == null || candidate.operation in listOf(
                            Operation.CMP,
                            Operation.TEST,
                            Operation.CALL
                        ) ||
                        target.relative || target.index != null
                    ) false else {
                        val before = flow.before(candidate.offset)
                        val owner = target.base?.let { before[it] } as? Receiver
                        val offset = owner?.adjustment?.plus(target.displacement)
                        if (offset == null || offset + target.width <= errorHandler || errorHandler + 8 <= offset) false else {
                            val source = candidate.source as? Register
                            require(
                                candidate.operation == Operation.MOV && offset == errorHandler && target.width == 8 &&
                                        source?.width == 8 && before[source.number] == Original(8)
                            ) {
                                "Inner Lua protection overwrites or truncates its error-handler argument"
                            }
                            true
                        }
                    }
                }
                require(stores.size == 1) { "Inner Lua protection does not install its original error-handler argument" }
                val candidates = X64Instructions(bytes).all(1024).filter {
                    it.offset >= position && it.operation == Operation.ADD &&
                            it.destination is Register && it.destination.width == 8 && it.source is Memory && it.source.width == 8 &&
                            !it.source.relative && it.source.index == null && it.source.displacement == stackBase
                }
                require(candidates.any { candidate ->
                    val prefix = SysVReceiverFlow(bytes.slice(0, candidate.offset + candidate.size), address, stateSize)
                    val before = prefix.before(candidate.offset)
                    before[(candidate.destination as Register).number] == Original(1) &&
                            (candidate.source as Memory).base?.let { before[it] } == Receiver()
                }) { "Inner Lua protection does not restore the original stack displacement on error" }
                return
            }
        }
        error("Inner Lua protection exceeds prefix bound")
    }
}
