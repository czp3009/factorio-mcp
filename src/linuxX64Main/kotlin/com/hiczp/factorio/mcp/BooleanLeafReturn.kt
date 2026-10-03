package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Complete bounded constant leaf: no hidden return storage, object reads, writes or other arguments. */
internal object BooleanLeafReturn {
    fun analyze(bytes: BinaryView) {
        require(bytes.size in 1..256)
        val body = X64Instructions(bytes).all(128).filter { it.operation !in setOf(Operation.NOP, Operation.ENDBR) }
        require(body.isNotEmpty() && body.last().operation == Operation.RET)
        var start = 0
        var end = body.lastIndex
        if (body.first().operation == Operation.PUSH) {
            require(body.size >= 5 && body[0].destination == Register(5, 8) &&
                    body[1].operation == Operation.MOV && body[1].destination == Register(5, 8) &&
                    body[1].source == Register(4, 8) && body[end - 1].operation == Operation.POP &&
                    body[end - 1].destination == Register(5, 8)) { "Unsupported boolean leaf frame" }
            start = 2
            end--
        }
        val values = mutableMapOf<Int, Long>()
        for (instruction in body.subList(start, end)) {
            val destination = instruction.destination as? Register ?: error("Boolean leaf changes control flow or memory")
            require(destination.number in setOf(0, 1, 2, 6, 7, 8, 9, 10, 11) && destination.width in setOf(1, 4, 8))
            val value = when (instruction.operation) {
                Operation.MOV -> when (val source = instruction.source) {
                    is Immediate -> source.value
                    is Register -> values[source.number] ?: error("Boolean leaf reads an unknown register")
                    else -> error("Boolean leaf reads memory")
                }
                Operation.XOR -> {
                    require(instruction.source == destination)
                    0L
                }
                else -> error("Unsupported boolean leaf instruction")
            }
            require(value in 0..1)
            values[destination.number] = value
        }
        require(values[0]?.let { it in 0L..1L } == true) { "Boolean leaf does not return a proven AL value" }
    }
}
