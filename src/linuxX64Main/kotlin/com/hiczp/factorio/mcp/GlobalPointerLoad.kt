package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** A bounded pointer member loaded through an exact named global. Does not authorize dereferencing the pointee. */
internal class GlobalPointerLoad(
    private val flow: X64ControlFlow, private val address: Long,
    private val global: Long, private val extent: Long
) {
    private val definitions = ScalarExpression(flow)

    init {
        require(address >= 0 && address <= Long.MAX_VALUE - flow.instructions.last().offset - 15)
        require(global >= 0 && global <= Long.MAX_VALUE - 8 && extent in 8..(64 * 1024 * 1024))
    }

    fun at(site: Long, register: Int): Long {
        fun load(site: Long, register: Int, depth: Int = 0): Pair<Long, Memory> {
            require(depth < 32)
            val instruction = definitions.definition(site, register)
            require(instruction.operation == Operation.MOV && instruction.destination == Register(register, 8)) {
                "Global pointer has no full-width preserved load"
            }
            return when (val source = instruction.source) {
                is Register -> {
                    require(source.width == 8)
                    load(instruction.offset, source.number, depth + 1)
                }

                is Memory -> {
                    require(source.width == 8 && source.index == null)
                    instruction.offset to source
                }

                else -> error("Global pointer is not loaded from memory")
            }
        }
        val (memberSite, member) = load(site, register)
        require(!member.relative && member.displacement in 0..extent - 8)
        val (rootSite, root) = load(memberSite, checkNotNull(member.base))
        require(root.relative && root.base == null)
        val next = address + rootSite + flow.body.getValue(rootSite).size
        require(root.displacement >= -next && root.displacement <= Long.MAX_VALUE - next)
        require(next + root.displacement == global) { "Pointer comes from a different global" }
        return member.displacement
    }
}
