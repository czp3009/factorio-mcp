package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** The original Map byte incremented by permanent stopping and zero-tested at ordinary stopping's entry. */
internal object MapStopField {
    fun resolve(
        image: ElfImage, mapSize: Long, permanentName: String = "_ZN3Map15stopPermanentlyEb",
        stopName: String = "_ZN3Map4stopEb"
    ): Long {
        val permanent = image.symbol(permanentName)
        val stop = image.symbol(stopName)
        val flow = X64ControlFlow.resolve(image, permanent)
        val field = increment(flow, stop.address - permanent.address, mapSize)
        EhFrames(image).function(stop)
        val decoder = X64Instructions(image.functionBytes(stop, 512))
        val instructions = mutableListOf<X64Instructions.Instruction>()
        var end = 0L
        while (true) {
            require(end < minOf(stop.size, 512) && instructions.size < 128)
            val instruction = decoder.decode(end)
            if (instruction.operation == Operation.JCC) {
                require(
                    instruction.condition in listOf(4, 5) &&
                        (instruction.destination as? Immediate)?.value?.let { it in end + instruction.size until stop.size } == true)
                break
            }
            instructions += instruction
            end += instruction.size
        }
        val first = if (instructions.last().operation == Operation.TEST) instructions[instructions.lastIndex - 1]
        else instructions.last()
        verifyGetter(X64ControlFlow(instructions), listOf(DwarfRanges.Range(first.offset, end)), mapSize, field)
        return field
    }

    fun increment(flow: X64ControlFlow, stop: Long, mapSize: Long): Long {
        require(mapSize in 1..(64 * 1024 * 1024))
        val calls = flow.instructions.filter { it.operation == Operation.CALL && it.destination == Immediate(stop) }
        val call = calls.singleOrNull() ?: error("Permanent stopping has no unique stop call")
        val arguments = SysVArgumentFlow(flow)
        val locals = SysVLocalArgument(flow)
        // The aggregate result has private return storage in RDI; the original Map is forwarded in RSI.
        require(
            arguments.register(call.offset, 6) == SysVArgumentFlow.Reference(7) &&
                locals.registers(call.offset)[7]?.let { it < 0 } == true) {
            "Map::stop does not receive its original Map separately from return storage"
        }
        val increments = flow.instructions.filter { it.operation == Operation.INC }.mapNotNull { instruction ->
            val member = instruction.destination as? Memory ?: return@mapNotNull null
            val read = arguments.memory(instruction.offset, member) ?: return@mapNotNull null
            if (read.reference.argument != 7) return@mapNotNull null
            require(read.width == 1 && read.reference.offset in 0 until mapSize)
            require(flow.dominates(call.offset, instruction.offset)) {
                "Permanent stop increment can bypass the typed stop call"
            }
            read.reference.offset
        }
        return increments.singleOrNull() ?: error("Permanent stopping has no unique original-Map byte increment")
    }

    fun verifyGetter(flow: X64ControlFlow, ranges: List<DwarfRanges.Range>, mapSize: Long, field: Long) {
        require(field in 0 until mapSize && ranges.isNotEmpty())
        val end = flow.instructions.last().let { it.offset + it.size }
        val boundaries = flow.body.keys + end
        require(ranges.all { it.start in boundaries && it.end in boundaries && it.start < it.end })
        require(flow.instructions.none {
            it.operation in setOf(
                Operation.CALL,
                Operation.JMP,
                Operation.JCC,
                Operation.RET
            )
        }) {
            "Stopped getter is not an uninterrupted entry prefix"
        }
        val arguments = SysVArgumentFlow(flow)
        val body = flow.instructions.filter { instruction ->
            ranges.any {
                instruction.offset >= it.start && instruction.offset + instruction.size <= it.end
            } && instruction.operation !in setOf(Operation.NOP, Operation.ENDBR)
        }
        val load = body.first()
        val memory = if (body.size == 1) {
            require(load.operation == Operation.CMP && load.source == Immediate(0))
            load.destination as? Memory
        } else {
            require(
                body.size == 2 && load.operation == Operation.MOVZX && load.destination is Register &&
                        load.destination.width in listOf(4, 8)
            )
            val test = body[1]
            val low = Register(load.destination.number, 1)
            require(
                test.operation == Operation.TEST && test.destination == low && test.source == low &&
                        load.offset + load.size == test.offset
            )
            load.source as? Memory
        } ?: error("Stopped getter does not test a member")
        require(
            memory.width == 1 &&
                    arguments.memory(load.offset, memory) == SysVArgumentFlow.Read(
                SysVArgumentFlow.Reference(6, field),
                1
            )
        ) {
            "Stopped getter does not read the same original Map byte"
        }
    }
}
