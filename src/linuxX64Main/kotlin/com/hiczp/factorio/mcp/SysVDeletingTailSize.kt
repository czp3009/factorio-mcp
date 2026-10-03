package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Complete-object extent from bounded normal deleting-destructor exits after inlined cleanup. */
internal object SysVDeletingTailSize {
    fun resolve(image: ElfImage, destructor: ElfImage.Symbol): Long {
        val deallocate = image.symbol("_ZdlPvm")
        require(destructor.size in 1..8192)
        for (entry in listOf(destructor, deallocate)) EhFrames(image).function(entry)
        return analyze(
            X64ControlFlow.resolve(image, destructor),
            deallocate.address - destructor.address,
        )
    }

    fun analyze(flow: X64ControlFlow, deallocate: Long): Long {
        val exits =
            flow.instructions.filter {
                it.offset in flow.reachable && flow.successors.getValue(it.offset).isEmpty()
            }
        require(
            exits.isNotEmpty() &&
                exits.size <= 64 &&
                exits.all {
                    it.operation == Operation.JMP && it.destination == Immediate(deallocate)
                }
        ) {
            "Deleting destructor has an unverified normal exit"
        }
        val values = ConstructorValues(flow, emptyMap())
        val frame = SysVLocalArgument(flow)
        val sizes =
            exits
                .map { exit ->
                    require(
                        values.register(exit.offset, 7) == ConstructorValues.Argument(7) &&
                            frame.registers(exit.offset)[4] == 0L
                    ) {
                        "Deleting tail loses its original complete-object receiver or restored frame"
                    }
                    val size =
                        (values.register(exit.offset, 6) as? ConstructorValues.Constant)?.value
                            ?: error("Deleting tail has no unchanged complete size argument")
                    require(size in 8..(16 * 1024 * 1024))
                    size
                }
                .distinct()
        return sizes.singleOrNull()
            ?: error("Normal deleting tails disagree on the complete-object size")
    }
}
