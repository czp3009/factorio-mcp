package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Guarded byte-to-scalar lookup evidence. The input object's SDK/type contract is established separately. */
internal object GuardedByteTable {
    data class Proof(
        val load: Long, val inputLoad: Long, val input: SysVArgumentFlow.Read, val address: Long,
        val values: List<Long>, val indices: List<Int?>
    ) {
        fun value(byte: Int): Long? {
            require(byte in 0..255)
            return indices[byte]?.let { values[it] }
        }
    }

    fun resolve(image: ElfImage, function: ElfImage.Symbol): List<Proof> =
        analyze(X64ControlFlow.resolve(image, function), function.address) { address, size ->
            require(image.sections.any { section ->
                section.flags and 3L == 2L && address >= section.address &&
                        size <= section.size && address - section.address <= section.size - size
            }) {
                "Scalar lookup table is not allocated read-only data"
            }
            image.virtualBytes(address, size)
        }

    fun analyze(flow: X64ControlFlow, address: Long, read: (Long, Long) -> BinaryView): List<Proof> {
        require(address >= 0 && address <= Long.MAX_VALUE - flow.instructions.last().offset - 15)
        val arguments = SysVArgumentFlow(flow)
        fun previous(position: Long): Instruction {
            var cursor = position
            repeat(16) {
                val predecessor = flow.predecessors[cursor]?.singleOrNull()
                    ?: error("Scalar lookup has an intervening entry edge")
                require(predecessor < cursor)
                val instruction = flow.body.getValue(predecessor)
                require(predecessor + instruction.size == cursor)
                cursor = predecessor
                if (instruction.operation !in listOf(Operation.NOP, Operation.ENDBR)) return instruction
            }
            error("Scalar lookup padding exceeds bounds")
        }

        val result = mutableListOf<Proof>()
        for (load in flow.instructions) {
            if (load.offset !in flow.reachable || load.operation != Operation.MOV) continue
            val output = load.destination as? Register ?: continue
            val entry = load.source as? Memory ?: continue
            if (output.width != 4 || entry.width != 4 || entry.relative || entry.base == null || entry.index == null ||
                entry.base == entry.index || entry.scale != 4 || entry.displacement != 0L
            ) continue
            val proof = try {
                val base = previous(load.offset)
                require(base.operation == Operation.LEA && base.destination == Register(entry.base, 8))
                val location = base.source as? Memory ?: error("Lookup base is not an address")
                require(location.relative && location.base == null && location.index == null)
                val extension = previous(base.offset)
                require(extension.operation == Operation.MOVZX && extension.destination == Register(entry.index, 4))
                val index = extension.source as? Register ?: error("Lookup index is not a byte register")
                require(index.width == 1)
                var guard = previous(extension.offset)
                var intervening = 0
                while (guard.operation == Operation.PUSH && guard.destination is Register && guard.destination.width == 8 &&
                    index.number != 4 ||
                    guard.operation == Operation.MOV && guard.destination is Register && guard.destination.width == 8 &&
                    guard.destination.number != index.number && guard.source is Register && guard.source.width == 8
                ) {
                    require(++intervening <= 16)
                    guard = previous(guard.offset)
                }
                require(guard.operation == Operation.JCC && guard.condition == 7)
                val rejected = (guard.destination as? Immediate)?.value ?: error("Indirect lookup guard")
                require(rejected in flow.body && rejected !in guard.offset + guard.size..load.offset)
                val comparison = previous(guard.offset)
                require(comparison.operation == Operation.CMP && comparison.destination == index)
                val maximum = (comparison.source as? Immediate)?.value ?: error("Lookup bound is not constant")
                require(maximum in 0..255)
                val transforms = mutableListOf<Instruction>()
                var cursor = comparison.offset
                var input: SysVArgumentFlow.Read? = null
                var inputLoad = -1L
                repeat(16) {
                    if (input != null) return@repeat
                    val instruction = previous(cursor)
                    val target =
                        instruction.destination as? Register ?: error("Lookup index has an unsupported definition")
                    if (target.number != index.number && instruction.operation in listOf(
                            Operation.MOV,
                            Operation.MOVZX, Operation.LEA
                        )
                    ) {
                        cursor = instruction.offset
                        return@repeat
                    }
                    require(target.number == index.number && target.width in listOf(1, 4))
                    if (instruction.operation == Operation.MOVZX && target.width == 4 &&
                        (instruction.source as? Memory)?.width == 1
                    ) {
                        input = arguments.source(instruction.offset)
                            ?: error("Lookup input has no original argument provenance")
                        inputLoad = instruction.offset
                    } else {
                        require(
                            instruction.operation in listOf(
                                Operation.ADD, Operation.SUB, Operation.INC,
                                Operation.DEC, Operation.AND, Operation.OR, Operation.XOR
                            )
                        )
                        require(
                            instruction.operation in listOf(
                                Operation.INC,
                                Operation.DEC
                            ) || instruction.source is Immediate
                        )
                        transforms += instruction
                    }
                    cursor = instruction.offset
                }
                val source = input ?: error("Lookup byte source exceeds analysis bound")
                require(source.width == 1)
                val indices = (0..255).map { byte ->
                    var value = byte.toLong()
                    for (instruction in transforms.asReversed()) {
                        val amount = (instruction.source as? Immediate)?.value ?: 1L
                        value = when (instruction.operation) {
                            Operation.ADD, Operation.INC -> value + amount
                            Operation.SUB, Operation.DEC -> value - amount
                            Operation.AND -> value and amount
                            Operation.OR -> value or amount
                            Operation.XOR -> value xor amount
                            else -> error("Unsupported byte index transform")
                        } and 255
                    }
                    value.toInt().takeIf { value <= maximum }
                }
                val next = address + base.offset + base.size
                require(location.displacement >= -next && location.displacement <= Long.MAX_VALUE - next)
                val table = next + location.displacement
                // This resolver records locations only. SDK/type bounds must precede any live input access.
                Proof(load.offset, inputLoad, source, table, emptyList(), indices) to maximum.toInt() + 1
            } catch (_: IllegalArgumentException) {
                continue // This indexed read is not an accepted guarded byte table.
            } catch (_: IllegalStateException) {
                continue
            }
            val (metadata, count) = proof
            val bytes = read(metadata.address, count * 4L)
            require(bytes.size == count * 4L)
            result += metadata.copy(values = List(count) { bytes.unsigned(it * 4L, 4) })
        }
        require(result.isNotEmpty()) { "No bounded byte-to-scalar lookup was established" }
        return result
    }
}
