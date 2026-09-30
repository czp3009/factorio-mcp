package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Bounded original-receiver subobject initialized with an independently verified primary table. */
internal object EmbeddedPrimaryTable {
    fun analyze(bytes: BinaryView, address: Long, table: Long, ownerSize: Long, memberSize: Long): Long {
        require(
            bytes.size in 1..4096 && address > 0 && address <= Long.MAX_VALUE - bytes.size &&
                    table > 0 && table % 8 == 0L && ownerSize in 8..16 * 1024 * 1024 && memberSize in 8..ownerSize
        )
        val flow = X64ControlFlow(X64Instructions(bytes).all(4096))
        val arguments = SysVArgumentFlow(flow)
        val fields = flow.instructions.zipWithNext().mapNotNull { (load, store) ->
            val source = load.source as? Memory ?: return@mapNotNull null
            if (load.operation != Operation.LEA || !source.relative || source.base != null || source.index != null ||
                address + load.offset + load.size + source.displacement != table || load.offset !in flow.reachable
            )
                return@mapNotNull null
            val register = load.destination as? Register ?: error("Primary table address has no register destination")
            require(
                register.width == 8 && store.operation == Operation.MOV && store.source == register &&
                        flow.predecessors[store.offset] == setOf(load.offset)
            ) {
                "Primary table initialization is not an uninterrupted address/store pair"
            }
            val destination = store.destination as? Memory ?: error("Primary table is not stored in an object")
            require(destination.width == 8 && !destination.relative && destination.index == null)
            val field = arguments.memory(store.offset, destination)
                ?: error("Embedded primary table lost its original receiver")
            require(
                field.reference.argument == 7 && field.reference.offset in 0..ownerSize - memberSize &&
                        field.reference.offset % 8 == 0L
            )
            field.reference.offset
        }.distinct()
        return fields.singleOrNull() ?: error("Constructor has no unique bounded primary-table member")
    }
}
