package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Native empty returned-string storage and original LocalisedString receiver at its guarded mode dispatch. */
internal data class LocalisedRawCall(val function: Long, val output: Int, val receiver: Int, val mode: Long) {
    companion object {
        fun resolve(image: ElfImage, string: NativeStringLayout, text: LocalisedTextLayout): LocalisedRawCall {
            val function = image.symbol("_ZNK15LocalisedString7str_rawB5cxx11Ev")
            EhFrames(image).function(function)
            val bytes = image.functionBytes(function, 32768)
            val prefix = prefix(bytes)
            val end = prefix.last().let { it.offset + it.size }
            val ranges = DwarfInlines(image).find(function, "str_raw", setOf("basic_string"))
                .flatMap { it.ranges }.map { DwarfRanges.Range(it.start - function.address, it.end - function.address) }
                .filter { it.start >= 0 && it.end <= end }
            return analyze(X64ControlFlow(prefix), function.address, ranges, string, text.size)
        }

        private fun prefix(bytes: BinaryView): List<X64Instructions.Instruction> {
            val result = mutableListOf<X64Instructions.Instruction>()
            var offset = 0L
            val decoder = X64Instructions(bytes)
            repeat(128) {
                require(offset < bytes.size && offset < 512)
                val instruction = decoder.decode(offset)
                if (instruction.operation == Operation.JCC) {
                    require(instruction.condition == 7 && (instruction.destination as? Immediate)?.value?.let {
                        it >= offset + instruction.size && it < bytes.size
                    } == true) { "Raw text mode lacks a forward unsigned bound" }
                    return result
                }
                require(instruction.operation !in listOf(Operation.CALL, Operation.JMP, Operation.RET)) {
                    "Raw text storage and receiver are not established before control transfer"
                }
                result += instruction
                offset += instruction.size
            }
            error("Raw text prefix exceeds analysis bound")
        }

        fun analyze(
            flow: X64ControlFlow, address: Long, ranges: List<DwarfRanges.Range>,
            string: NativeStringLayout, textSize: Long
        ): LocalisedRawCall {
            require(
                address > 0 && textSize in string.size..4096 && flow.instructions.size in 2..128 &&
                        flow.instructions.none {
                            it.operation in listOf(
                                Operation.CALL,
                                Operation.JCC,
                                Operation.JMP,
                                Operation.RET
                            )
                        })
            // Optimized inline ranges may include saved-register pushes and private frame allocation.
            // Trim only that leading stack setup; all actual constructor operations remain checked.
            val constructors = ranges.map { range ->
                var start = range.start
                while (start < range.end) {
                    val instruction = flow.body.getValue(start)
                    val saved = instruction.destination as? Register
                    val amount = instruction.source as? Immediate
                    val setup = instruction.operation == Operation.PUSH && saved?.width == 8 &&
                            saved.number in listOf(3, 5, 12, 13, 14, 15) ||
                            instruction.operation == Operation.SUB && instruction.destination == Register(4, 8) &&
                            amount?.value in 1L..4096L || instruction.operation == Operation.MOV &&
                            instruction.destination == Register(5, 8) && instruction.source == Register(4, 8)
                    if (!setup) break
                    start += instruction.size
                }
                require(start < range.end)
                DwarfRanges.Range(start, range.end)
            }
            val output = EmptyStringOutput.analyze(flow, string, constructors)
            require(output.argument == 7) { "Raw text does not construct its hidden output in RDI" }
            val values = SysVArgumentFlow(flow)
            val compare = flow.instructions.last()
            val load = flow.instructions[flow.instructions.lastIndex - 1]
            val register = load.destination as? Register ?: error("Raw text mode has no scalar register")
            val memory = load.source as? Memory ?: error("Raw text mode has no receiver member")
            require(
                load.operation == Operation.MOVZX && register.width == 4 && memory.width == 1 &&
                    values.memory(
                        load.offset,
                        memory
                    )?.reference?.let { it.argument == 6 && it.offset in 0 until textSize } == true)
            require(
                compare.operation == Operation.CMP && compare.destination == register.copy(width = 8) &&
                        (compare.source as? Immediate)?.value in 0L..255L
            )
            for (instruction in flow.instructions) {
                val target = instruction.destination as? Memory ?: continue
                if (instruction.operation in listOf(Operation.CMP, Operation.TEST, Operation.NOP)) continue
                val reference = values.memory(instruction.offset, target)?.reference ?: continue
                require(reference.argument != 6) { "Raw text prefix mutates its original receiver" }
            }
            require(output.constructors.all { it < load.offset })
            return LocalisedRawCall(
                address, output.argument, 6,
                checkNotNull(values.memory(load.offset, memory)).reference.offset
            )
        }
    }
}
