package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Leaf element count from two original-receiver pointers and an exact power-of-two element extent. */
internal data class VectorCountAccessor(val first: Long, val last: Long, val stride: Long) {
    companion object {
        fun resolve(image: ElfImage, name: String, size: Long): VectorCountAccessor {
            val function = image.symbol(name)
            EhFrames(image).function(function)
            return analyze(image.functionBytes(function, 256), size)
        }

        fun analyze(bytes: BinaryView, size: Long): VectorCountAccessor {
            require(bytes.size in 1..256 && size >= 16)
            var body = X64Instructions(bytes).all().filter { it.operation !in listOf(Operation.NOP, Operation.ENDBR) }
            require(body.lastOrNull()?.operation == Operation.RET)
            body = body.dropLast(1)
            if (body.firstOrNull()?.operation == Operation.PUSH) {
                require(
                    body.size >= 4 && body[0].destination == Register(5, 8) &&
                            body[1].operation == Operation.MOV && body[1].destination == Register(5, 8) &&
                            body[1].source == Register(4, 8) && body.last().operation == Operation.POP &&
                            body.last().destination == Register(5, 8)
                )
                body = body.drop(2).dropLast(1)
            }
            require(body.size == 3)
            val (load, subtract, shift) = body
            require(
                load.operation == Operation.MOV && load.destination == Register(0, 8) &&
                        subtract.operation == Operation.SUB && subtract.destination == Register(0, 8) &&
                        shift.operation in listOf(Operation.SHR, Operation.SAR) && shift.destination == Register(0, 8)
            )
            fun member(source: X64Instructions.Operand?): Long {
                val memory = source as? Memory ?: error("Count accessor does not use an original-receiver pointer")
                require(memory.width == 8 && memory.base == 7 && memory.index == null && !memory.relative)
                return NativeAccessor(memory.displacement, 8, ULong.MAX_VALUE, 0).withinObject(size).offset
            }

            val first = member(subtract.source)
            val last = member(load.source)
            require(first + 8 <= last || last + 8 <= first)
            val bits = (shift.source as? Immediate)?.value ?: error("Count divisor is not constant")
            require(bits in 3..12)
            return VectorCountAccessor(first, last, 1L shl bits.toInt())
        }
    }
}
