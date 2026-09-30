package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Leaf double accessors are analyzed, never called, to establish a bounded original-receiver member. */
internal object DoubleMemberAccessor {
    fun resolve(image: ElfImage, name: String, size: Long, write: Boolean = false): Long {
        val function = image.symbol(name)
        EhFrames(image).function(function)
        return analyze(image.functionBytes(function, 256), size, write)
    }

    fun analyze(bytes: BinaryView, size: Long, write: Boolean = false): Long {
        require(bytes.size in 1..256)
        var body = X64Instructions(bytes).all().filter { it.operation !in setOf(Operation.NOP, Operation.ENDBR) }
        require(body.isNotEmpty() && body.last().operation == Operation.RET)
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
        val access = body.single()
        require(access.operation == Operation.SCALAR_MOV)
        val member = (if (write) access.destination else access.source) as? Memory
            ?: error("Double accessor has no member")
        require(
            (if (write) access.source else access.destination) == Register(16, 8) &&
                    member.width == 8 && member.base == 7 && member.index == null && !member.relative
        )
        return NativeAccessor(member.displacement, 8, ULong.MAX_VALUE, 0).withinObject(size).offset
    }
}
