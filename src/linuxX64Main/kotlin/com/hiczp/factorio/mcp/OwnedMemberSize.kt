package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Sized deletion of the same original pointer passed to a named member destructor. */
internal object OwnedMemberSize {
    fun resolve(image: ElfImage, owner: String, destructor: String, ownerSize: Long): OwnedObjectSize {
        val from = image.symbol(owner)
        val destroy = image.symbol(destructor)
        val deallocate = image.symbol("_ZdlPvm")
        for (function in listOf(from, destroy, deallocate)) EhFrames(image).function(function)
        val flow = X64ControlFlow(
            X64Instructions(image.functionBytes(from, 32768), allowAtomicExchangeAdd = true).all(8192)
        )
        return analyze(flow, destroy.address - from.address, deallocate.address - from.address, ownerSize)
    }

    fun analyze(flow: X64ControlFlow, destructor: Long, deallocate: Long, ownerSize: Long): OwnedObjectSize {
        require(destructor != deallocate && ownerSize in 8..(64 * 1024 * 1024))
        val origins = OriginalPointerOrigins(flow)
        val constants = RegisterConstant(flow)
        val destructors = flow.instructions.filter {
            it.offset in flow.reachable && it.operation == Operation.CALL && it.destination == Immediate(destructor)
        }
        val frees = flow.instructions.filter {
            it.offset in flow.reachable && it.operation == Operation.CALL && it.destination == Immediate(deallocate)
        }
        val candidates = destructors.flatMap { destroy ->
            val pointer = origins.register(destroy.offset, 7) as? OriginalPointerOrigins.Load
                ?: error("Typed destruction has no original member pointer")
            require(pointer.base == OriginalPointerOrigins.Argument(7) && pointer.member in 0..ownerSize - 8)
            frees.mapNotNull { free ->
                // Every normal path reaching deallocation must cross this destruction of the same loaded object.
                if (!flow.dominates(destroy.offset, free.offset)) return@mapNotNull null
                if (origins.register(free.offset, 7) != pointer) return@mapNotNull null
                val size = constants.value(free.offset, Register(6, 8))
                require(size in 8..(64 * 1024 * 1024)) { "Owned member size exceeds bounds" }
                OwnedObjectSize(pointer.member, size)
            }
        }
        return candidates.singleOrNull() ?: error("Typed member destruction has no unique matching sized deletion")
    }
}
