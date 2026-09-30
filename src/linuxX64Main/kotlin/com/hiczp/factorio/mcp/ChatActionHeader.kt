package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Cross-entry action discriminator provenance. Does not prove payload ownership or submission phase. */
internal object ChatActionHeader {
    fun resolve(image: ElfImage, size: Long): Long {
        val construct =
            image.symbol("_ZN11InputActionC2E15InputActionTypeRKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEE")
        val noData = image.symbol("_ZN11InputAction6noDataE15InputActionType")
        val destroy = image.symbol("_ZN11InputAction12destroyValueEv")
        val submit = image.symbol("_ZNK6Player15sendToListenersEO11InputAction")
        for (entry in listOf(construct, noData, destroy, submit)) EhFrames(image).function(entry)
        val field = construction(image.functionBytes(construct, 4096), size, noData.address - construct.address)
        destruction(image.functionBytes(destroy, 4096), size, field)
        submission(image.functionBytes(submit, 4096), size, field)
        return field
    }

    private data class Original(val register: Int, val width: Int)

    fun construction(bytes: BinaryView, size: Long, noData: Long): Long {
        require(size in 2..4096)
        val origins = MutableList<Original?>(16) { Original(it, 8) }
        val decoder = X64Instructions(bytes)
        var position = 0L
        var field: Long? = null
        repeat(128) {
            val instruction = decoder.decode(position)
            fun origin(register: Register): Original? = origins[register.number]?.takeIf { it.width >= register.width }
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> require(instruction.destination is Register && instruction.destination.width == 8)
                Operation.MOV -> when (val target = instruction.destination) {
                    is Register -> {
                        val source = instruction.source as? Register ?: error("Action entry reads an unexpected value")
                        origins[target.number] = origin(source)?.copy(width = target.width)
                    }

                    is Memory -> {
                        require(
                            !target.relative && target.index == null && target.base != null &&
                                    origins[target.base] == Original(
                                7,
                                8
                            ) && target.displacement in 0..size - target.width
                        ) {
                            "Action construction writes outside its original receiver"
                        }
                        when (val source = instruction.source) {
                            is Register -> {
                                require(target.width == 2 && source.width == 2 && origin(source)?.register == 6 && field == null) {
                                    "Action type is not the original scalar argument"
                                }
                                field = target.displacement
                            }

                            is Immediate -> require(
                                field == null || target.displacement + target.width <= field!! ||
                                        target.displacement >= field!! + 2
                            ) { "Action entry overwrites its discriminator" }

                            else -> error("Unsupported action entry store")
                        }
                    }

                    else -> error("Unsupported action entry destination")
                }

                Operation.CALL -> {
                    require(instruction.destination == Immediate(noData) && origins[7] == Original(6, 4)) {
                        "Action entry does not pass its original type to the native discriminator helper"
                    }
                    return checkNotNull(field) { "Action constructor does not store a type" }
                }

                else -> error("Unsupported instruction before action discriminator: ${instruction.operation}")
            }
            position += instruction.size
        }
        error("Action discriminator entry exceeds instruction bound")
    }

    fun destruction(bytes: BinaryView, size: Long, field: Long) {
        require(size in 2..4096 && field in 0..size - 2)
        val decoder = X64Instructions(bytes)
        var position = 0L
        repeat(64) {
            val instruction = decoder.decode(position)
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> require(
                    instruction.destination is Register && instruction.destination.width == 8 &&
                            instruction.destination.number != 7
                )

                Operation.MOV -> require(
                    instruction.destination == Register(5, 8) && instruction.source == Register(
                        4,
                        8
                    )
                )

                Operation.MOVZX -> {
                    val memory = instruction.source as? Memory ?: error("Action destructor has no discriminator read")
                    require(
                        memory.base == 7 && !memory.relative && memory.index == null && memory.width == 2 &&
                                memory.displacement == field && (instruction.destination as? Register)?.width == 4
                    ) {
                        "Action destructor reads a different receiver or discriminator"
                    }
                    return
                }

                else -> error("Unsupported action destructor entry")
            }
            position += instruction.size
        }
        error("Action destructor entry exceeds instruction bound")
    }

    fun submission(bytes: BinaryView, size: Long, field: Long) {
        require(size in 2..4096 && field in 0..size - 2)
        // Indirect tails terminate this point-provenance analysis only. This does not validate their callees or ABI.
        val instructions = X64Instructions(bytes).all(1024).map {
            if (it.operation == Operation.JMP && it.destination !is Immediate)
                it.copy(operation = Operation.RET, destination = null) else it
        }
        val flow = X64ControlFlow(instructions)
        val arguments = SysVArgumentFlow(flow)
        val reads = flow.instructions.filter {
            it.offset in flow.reachable && it.operation == Operation.MOVZX &&
                    arguments.source(it.offset) == SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, field), 2)
        }
        require(reads.size == 1 && (reads.single().destination as? Register)?.width == 4) {
            "Submission does not read the same type from its original action argument"
        }
    }
}
