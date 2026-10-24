package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** A checked native downcast whose normal result preserves the original element address. */
internal object LuaElementIdentity {
    fun resolve(
        image: ElfImage,
        helper: String,
        table: String,
        method: String,
        extent: Long,
    ): Long {
        val entry = image.symbol(helper)
        val virtual = ItaniumVtable.resolve(image, table).method(image, method)
        require(SysVAccessors.resolveAddress(image, virtual.function, extent, extent) == 0L)
        EhFrames(image).function(entry)
        analyze(X64ControlFlow.resolve(image, entry), virtual.slot)
        return entry.address
    }

    fun analyze(flow: X64ControlFlow, slot: Int) {
        require(slot in 0..8191)
        val arguments = OriginalPointerOrigins(flow)
        val returns = ReturnedPointerOrigins(flow)
        val exits =
            flow.instructions.filter {
                it.offset in flow.reachable && it.operation == Operation.RET
            }
        require(exits.isNotEmpty())
        for (exit in exits) {
            val result =
                checkNotNull(returns.register(exit.offset, 0)) {
                    "Element cast loses its virtual result"
                }
            require(result.members.isEmpty() && result.adjustment == 0L)
            val call = flow.body.getValue(result.call)
            require(arguments.register(call.offset, 7) == OriginalPointerOrigins.Argument(7))
            val table =
                when (val destination = call.destination) {
                    is Memory -> {
                        require(
                            destination.width == 8 &&
                                !destination.relative &&
                                destination.index == null &&
                                destination.displacement == slot * 8L
                        )
                        arguments.register(call.offset, checkNotNull(destination.base))
                    }
                    is Register -> {
                        require(destination.width == 8)
                        val target =
                            arguments.register(call.offset, destination.number)
                                as? OriginalPointerOrigins.Load
                                ?: error("Element cast loses its virtual entry")
                        require(target.member == slot * 8L)
                        target.base
                    }
                    else -> error("Element cast does not use its verified virtual entry")
                }
                    as? OriginalPointerOrigins.Load ?: error("Element cast loses its primary table")
            require(table.base == OriginalPointerOrigins.Argument(7) && table.member == 0L)
        }
        require(
            flow.instructions.none {
                it.offset in flow.reachable &&
                    it.operation == Operation.JMP &&
                    (it.destination as? Immediate)?.value !in flow.body
            }
        ) {
            "Element cast has an unverified tail return"
        }
    }
}
