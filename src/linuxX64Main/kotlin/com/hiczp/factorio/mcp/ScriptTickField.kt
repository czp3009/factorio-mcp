package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Pointer
import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import com.hiczp.factorio.mcp.X64Instructions.*

/** Raw tick storage read by the named Lua tick accessor. Before runtime use, the script pointer member
 * must equal the independently selected current player's Map; this analysis alone does not type that pointer.
 */
internal data class ScriptTickField(val scriptMap: Long, val mapTick: Long) {
    companion object {
        fun resolve(
            image: ElfImage, scriptSize: Long, mapSize: Long,
            name: String = "_ZN13LuaGameScript11luaReadTickEP9lua_State", owner: String = "luaReadTick",
            debug: DwarfInlines = image.inlines
        ): ScriptTickField {
            val function = image.symbol(name)
            val instances = debug.find(function, owner, setOf("toLuaDouble"))
            require(instances.map { it.origin }.distinct().size == 1)
            val ranges = instances.flatMap { it.ranges }.map {
                DwarfRanges.Range(it.start - function.address, it.end - function.address)
            }
            val end = ranges.maxOf { it.end }
            require(end in 1..512)
            return analyze(
                image.functionBytes(function, 512).slice(0, end),
                function.address,
                ranges,
                scriptSize,
                mapSize
            )
        }

        fun analyze(
            bytes: BinaryView, address: Long, ranges: List<DwarfRanges.Range>,
            scriptSize: Long, mapSize: Long
        ): ScriptTickField {
            require(
                bytes.size in 1..512 && scriptSize in 8..(64 * 1024 * 1024) &&
                        mapSize in 8..(64 * 1024 * 1024) && ranges.isNotEmpty()
            )
            val instructions = X64Instructions(bytes).all(128)
            val boundaries = instructions.map { it.offset }.toSet() + bytes.size
            require(ranges.all { it.start in boundaries && it.end in boundaries && it.start < it.end })
            val loads = instructions.filter { instruction ->
                ranges.any {
                    instruction.offset >= it.start && instruction.offset + instruction.size <= it.end
                } && instruction.operation == Operation.MOV && instruction.source is Memory
            }
            val load = loads.singleOrNull() ?: error("Named tick conversion has no unique scalar member load")
            val member = load.source as Memory
            val register = load.destination as? Register ?: error("Tick does not load to a scalar register")
            require(
                member.width == 8 && register.width == 8 && !member.relative && member.index == null &&
                        member.displacement in 0..mapSize - 8 && member.displacement % 8 == 0L
            )
            require(instructions.takeWhile { it.offset < load.offset }.none {
                it.operation in setOf(Operation.CALL, Operation.JCC, Operation.JMP, Operation.RET)
            }) { "Tick load does not have an uninterrupted original receiver prefix" }
            val flow = SysVReceiverFlow(bytes.slice(0, load.offset + load.size), address, scriptSize)
            val source = member.base?.let { flow.before(load.offset)[it] } as? Pointer
                ?: error("Tick has no pointer member owner")
            require(source.base == Receiver() && source.offset in 0..scriptSize - 8 && source.offset % 8 == 0L)
            return ScriptTickField(source.offset, member.displacement)
        }
    }
}
