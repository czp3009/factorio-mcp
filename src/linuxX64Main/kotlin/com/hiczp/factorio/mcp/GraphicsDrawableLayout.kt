package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Conditional size-reader evidence; callers must separately establish device/window bounds and ownership. */
internal data class GraphicsDrawableLayout(
    val magic: Long,
    val drawableSize: Long,
    val windowSize: Long,
    val width: Long,
    val height: Long,
) {
    companion object {
        fun resolve(image: ElfImage): GraphicsDrawableLayout {
            val function = image.symbol("_ZN23GraphicsInterfaceOpenGL25getDefaultFramebufferSizeEv")
            EhFrames(image).function(function)
            val device = image.symbol("_this")
            require(device.type == 1 && device.size == 8L)
            return analyze(
                X64Instructions(image.functionBytes(function, 1024)).all(256),
                function.address, device.address
            )
        }

        /** Proves both nullable callback branches and the raw fallback through the packed width/height return. */
        fun analyze(instructions: List<Instruction>, address: Long, device: Long): GraphicsDrawableLayout {
            require(address > 0 && address <= Long.MAX_VALUE - 65536 && device > 0 && device % 8 == 0L)
            val flow = X64ControlFlow(instructions)
            val load = instructions.single { instruction ->
                val memory = instruction.source as? Memory
                instruction.operation == Operation.MOV && instruction.destination == Register(7, 8) &&
                        memory != null && memory.relative && memory.base == null && memory.index == null &&
                        memory.width == 8 && memory.displacement == device - address - instruction.offset - instruction.size
            }
            require(load.offset in flow.reachable)
            val visited = mutableSetOf<Long>()
            var cursor = load.offset + load.size
            fun take(operation: Operation, destination: Operand? = null, source: Operand? = null): Instruction {
                val instruction = flow.body.getValue(cursor)
                require(
                    visited.add(cursor) && instruction.operation == operation &&
                            (destination == null || instruction.destination == destination) &&
                            (source == null || instruction.source == source)
                ) { "Unsupported drawable size data flow" }
                cursor += instruction.size
                return instruction
            }

            fun branch(condition: Int): Long {
                val instruction = take(Operation.JCC)
                require(instruction.condition == condition)
                return (instruction.destination as? Immediate)?.value ?: error("Indirect drawable branch")
            }

            fun memory(operand: Operand?, base: Int, width: Int, local: Boolean = false): Long {
                val value = operand as? Memory ?: error("Missing drawable member")
                require(value.base == base && value.index == null && !value.relative && value.width == width)
                require(
                    if (local) value.displacement in -128..-width.toLong()
                    else value.displacement in 0..65536L - width
                )
                require(value.displacement % width == 0L)
                return value.displacement
            }

            fun member(register: Int, base: Int, width: Int) = memory(
                take(Operation.MOV, Register(register, width)).source, base, width
            )

            fun local(operand: Operand?) = memory(operand, 5, 4, local = true)

            take(Operation.TEST, Register(7, 8), Register(7, 8))
            val noDevice = branch(4)
            take(Operation.TEST, Register(0, 8), Register(0, 8))
            val noWindow = branch(4)
            val magic = memory(take(Operation.LEA, Register(1, 8)).source, 7, 8)
            val compare = take(Operation.CMP, source = Register(1, 8))
            require(memory(compare.destination, 0, 8) == 0L)
            val valid = branch(4)
            val wrongWindow = cursor

            // The invalid-object paths may report an SDL error and have no usable dimensions. They must
            // never reach either size callback or the raw fallback. No error-path output is authorized.
            cursor = valid
            val drawableSize = member(8, 7, 8)
            take(Operation.TEST, Register(8, 8), Register(8, 8))
            val callback = branch(5)
            val windowSize = member(8, 7, 8)
            require(windowSize != drawableSize)
            take(Operation.TEST, Register(8, 8), Register(8, 8))
            val fallback = branch(4)
            require(cursor == callback)
            // LEA's operand width describes an address, not the size of the int output at that address.
            fun output(register: Int): Long {
                val value = take(Operation.LEA, Register(register, 8)).source as? Memory
                    ?: error("Missing drawable output address")
                require(
                    value.base == 5 && value.index == null && !value.relative && value.width == 8 &&
                            value.displacement in -128..-4 && value.displacement % 4 == 0L
                )
                return value.displacement
            }

            val widthOutput = output(2)
            val heightOutput = output(1)
            require(widthOutput != heightOutput)
            take(Operation.MOV, Register(6, 8), Register(0, 8))
            take(Operation.CALL, Register(8, 8))
            val result = (take(Operation.JMP).destination as? Immediate)?.value
                ?: error("Missing drawable return path")

            cursor = fallback
            val width = member(1, 0, 4)
            require(local(take(Operation.MOV, source = Register(1, 4)).destination) == widthOutput)
            val height = member(0, 0, 4)
            require(
                width >= 8 && height >= 8 && width != height &&
                        local(take(Operation.MOV, source = Register(0, 4)).destination) == heightOutput
            )
            require(take(Operation.JMP).destination == Immediate(result))

            cursor = result
            require(local(take(Operation.MOV, Register(1, 4)).source) == widthOutput)
            require(local(take(Operation.MOV, Register(0, 4)).source) == heightOutput)
            take(Operation.SHL, Register(0, 8), Immediate(32))
            take(Operation.OR, Register(0, 8), Register(1, 8))
            val restore = take(Operation.ADD, Register(4, 8)).source as? Immediate
                ?: error("Missing drawable frame restoration")
            require(
                restore.value in 16..128 && restore.value % 16 == 0L &&
                        widthOutput >= -restore.value && heightOutput >= -restore.value
            )
            take(Operation.POP, Register(5, 8))
            take(Operation.RET)

            for (start in listOf(noDevice, noWindow, wrongWindow)) {
                val pending = ArrayDeque<Long>()
                val seen = mutableSetOf<Long>()
                pending.add(start)
                while (pending.isNotEmpty()) {
                    val site = pending.removeFirst()
                    require(site != valid && site != callback && site != fallback) {
                        "Invalid SDL object can reach drawable storage"
                    }
                    if (seen.add(site)) pending.addAll(flow.successors.getValue(site))
                }
            }
            return GraphicsDrawableLayout(magic, drawableSize, windowSize, width, height)
        }
    }
}
