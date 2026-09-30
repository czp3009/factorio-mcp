package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Original full-width input words connected to a standard byte comparison, without assuming aggregate order. */
internal data class ByteViewArguments(val data: Int, val length: Int) {
    init {
        require(data in ARGUMENTS && length in ARGUMENTS && data != length)
    }

    fun forwarded(
        image: ElfImage, caller: String, callee: String, tail: Boolean = false,
        unchanged: Map<Int, Int> = emptyMap(), zero: Set<Int> = emptySet()
    ): ByteViewArguments {
        val from = image.symbol(caller)
        val to = image.symbol(callee)
        require(from.size in 1..32768)
        EhFrames(image).function(from)
        EhFrames(image).function(to)
        return forwarded(X64ControlFlow.resolve(image, from), to.address - from.address, tail, unchanged, zero)
    }

    fun forwarded(
        flow: X64ControlFlow, callee: Long, tail: Boolean = false,
        unchanged: Map<Int, Int> = emptyMap(), zero: Set<Int> = emptySet()
    ): ByteViewArguments {
        require(unchanged.all { (target, source) -> target in ARGUMENTS && source in ARGUMENTS } &&
                zero.all { it in ARGUMENTS } && zero.intersect(unchanged.keys + setOf(data, length)).isEmpty())
        val operation = if (tail) Operation.JMP else Operation.CALL
        val sites = flow.instructions.filter {
            it.offset in flow.reachable && it.operation == operation &&
                    it.destination == Immediate(callee)
        }
        require(sites.isNotEmpty() && callee !in flow.body) { "Byte view has no selected external dispatch" }
        if (tail) {
            val exits = flow.reachable.filter { flow.successors.getValue(it).isEmpty() }.toSet()
            require(exits == sites.map { it.offset }.toSet()) { "Byte view wrapper has an additional normal exit" }
        }
        return sites.map { call ->
            // Later calls may borrow local objects. Only ancestors of this dispatch establish its input words.
            val reaching = flow.reaching(call.offset)
            val copies = copies(reaching)
            for ((target, source) in unchanged) require(copies.argument(call.offset, Register(target, 8)) == source) {
                "Byte view dispatch changes an additional original argument"
            }
            val scalars = ScalarExpression(reaching)
            for (register in zero) {
                require(
                    scalars.definition(call.offset, register).destination == Register(register, 4) &&
                        ScalarExpression.evaluate(scalars.before(call.offset, Register(register, 4))) {
                            error("Byte view constant depends on an object read")
                        } == 0L) { "Byte view dispatch does not clear the complete additional argument" }
            }
            ByteViewArguments(
                copies.argument(call.offset, Register(data, 8)),
                copies.argument(call.offset, Register(length, 8))
            )
        }.distinct().single()
    }

    companion object {
        private val ARGUMENTS = listOf(7, 6, 2, 1, 8, 9)

        private fun copies(flow: X64ControlFlow) =
            PrivateValueCopies(flow, emptyMap(), scalarArguments = ARGUMENTS.associateWith { 8 })

        fun resolve(image: ElfImage, name: String): ByteViewArguments {
            val entry = image.symbol(name)
            require(entry.size in 1..32768)
            EhFrames(image).function(entry)
            val flow = X64ControlFlow.resolve(image, entry)
            val comparisons = flow.instructions.filter {
                it.offset in flow.reachable && it.operation == Operation.CALL &&
                        (it.destination as? Immediate)?.value?.let { relative ->
                            relative >= -entry.address && relative <= Long.MAX_VALUE - entry.address &&
                                    image.importedFunction(entry.address + relative) in setOf("memcmp", "bcmp")
                        } == true
            }.map { it.offset }.toSet()
            return compared(flow, comparisons)
        }

        fun compared(flow: X64ControlFlow, comparisons: Set<Long>): ByteViewArguments {
            require(comparisons.isNotEmpty() && comparisons.all {
                it in flow.reachable && flow.body[it]?.operation == Operation.CALL
            })
            return comparisons.map { site ->
                val copies = copies(flow.reaching(site))
                val length = copies.argument(site, Register(2, 8))
                val pointers = listOf(7, 6).mapNotNull { register ->
                    runCatching { copies.argument(site, Register(register, 8)) }.getOrNull()
                }
                require(pointers.size == 1) { "Byte comparison does not identify one original requested-data pointer" }
                ByteViewArguments(pointers.single(), length)
            }.distinct().single()
        }
    }
}
