package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Native nested click dispatch copies the complete event and replaces only its discriminator. */
internal object MouseClickEvent {
    data class Proof(val type: Long, val value: Long)

    fun resolve(image: ElfImage, extent: Int): Proof {
        val function = image.symbol("_ZN4agui6Widget17dispatchMouseDownERKNS_10MouseEventE")
        val click = image.symbol("_ZN4agui6Widget13dispatchClickERKNS_10MouseEventE")
        val flow = X64ControlFlow.resolve(image, function)
        return analyze(flow, click.address - function.address, extent)
    }

    fun analyze(flow: X64ControlFlow, click: Long, extent: Int): Proof {
        require(extent in 4..256)
        val arguments = SysVArgumentFlow(flow)
        val calls =
            flow.instructions.filter {
                it.offset in flow.reachable &&
                    it.operation == Operation.CALL &&
                    it.destination == Immediate(click)
            }
        require(calls.isNotEmpty())
        return calls
            .map { call ->
                require(arguments.register(call.offset, 7) == SysVArgumentFlow.Reference(7))
                val output = LocalAggregate(flow).at(call.offset, extent)
                val constant =
                    output.constants.single().also {
                        require(it.width == 4 && it.offset in 0..extent - 4L)
                    }
                val reads = mutableListOf<Instruction>()
                var site = call.offset
                while (reads.size < 256) {
                    val previous = flow.predecessors[site]?.singleOrNull() ?: break
                    val instruction = flow.body.getValue(previous)
                    if (
                        previous >= site ||
                            instruction.operation in
                                setOf(Operation.CALL, Operation.JCC, Operation.JMP, Operation.RET)
                    )
                        break
                    reads += instruction
                    site = previous
                }
                val copied = mutableSetOf<Long>()
                val copies = LocalFieldCopies(flow)
                for (instruction in reads) {
                    if (
                        instruction.operation !in
                            setOf(
                                Operation.MOV,
                                Operation.MOVZX,
                                Operation.SCALAR_MOV,
                                Operation.VECTOR_MOV,
                            )
                    )
                        continue
                    val read = arguments.source(instruction.offset) ?: continue
                    if (read.reference.argument != 6) continue
                    require(
                        read.reference.offset >= 0 && read.reference.offset <= extent - read.width
                    )
                    for (byte in 0 until read.width) {
                        val source = read.reference.offset + byte
                        val fields =
                            copies.fromRead(
                                instruction.offset,
                                call.offset,
                                byteOffset = byte,
                                byteWidth = 1,
                            )
                        val inside = fields.filter { it.offset in 0 until extent.toLong() }
                        if (source in constant.offset until constant.offset + constant.width) {
                            require(inside.isEmpty()) {
                                "Click discriminator also survives in another field"
                            }
                        } else {
                            require(inside == listOf(LocalFieldCopies.Field(source, 1))) {
                                "Click dispatch changes another event field"
                            }
                            copied += source
                        }
                    }
                }
                require(
                    (0 until extent.toLong()).all {
                        it in copied || it in constant.offset until constant.offset + 4
                    }
                ) {
                    "Click dispatch does not copy the complete original event"
                }
                Proof(constant.offset, constant.value)
            }
            .distinct()
            .single()
    }
}
