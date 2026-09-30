package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Reciprocal neighbour links from the native unlink operation. Never invokes it. */
internal object NativeListLinks {
    private sealed interface Value
    private data object Node : Value
    private data class Link(val offset: Long) : Value

    fun resolve(image: ElfImage, node: NativeListNodeLayout): Long {
        val function = image.symbol("_ZNSt8__detail15_List_node_base9_M_unhookEv")
        EhFrames(image).function(function)
        return analyze(image.functionBytes(function, 256), node)
    }

    fun analyze(bytes: BinaryView, node: NativeListNodeLayout): Long {
        require(bytes.size in 1..256 && node.value in 16..4096 && node.next in 0..node.value - 8)
        val registers = MutableList<Value?>(16) { null }
        registers[7] = Node
        val reads = mutableListOf<Long>()
        val stores = mutableListOf<Triple<Long, Long, Long>>()
        val instructions = X64Instructions(bytes).all()
        require(instructions.last().operation == Operation.RET)
        for (instruction in instructions.dropLast(1)) {
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.MOV -> {
                    val target = instruction.destination
                    val source = instruction.source
                    if (target is Register) {
                        require(target.width == 8 && target.number in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11))
                        registers[target.number] = when (source) {
                            is Register -> {
                                require(source.width == 8)
                                registers[source.number] ?: error("List unlink copies an unproven register")
                            }

                            is Memory -> {
                                require(
                                    source.width == 8 && !source.relative && source.index == null &&
                                        source.base?.let { registers[it] } == Node && stores.isEmpty())
                                require(source.displacement in 0..node.value - 8 && source.displacement % 8 == 0L)
                                reads += source.displacement
                                Link(source.displacement)
                            }

                            else -> error("List unlink loads an unrelated value")
                        }
                    } else {
                        require(
                            target is Memory && source is Register && target.width == 8 && source.width == 8 &&
                                    !target.relative && target.index == null
                        )
                        val owner = target.base?.let { registers[it] } as? Link
                            ?: error("List unlink writes an unrelated object")
                        val value =
                            registers[source.number] as? Link ?: error("List unlink stores an unrelated pointer")
                        stores += Triple(owner.offset, target.displacement, value.offset)
                    }
                }

                else -> error("Unsupported list unlink instruction: ${instruction.operation}")
            }
        }
        require(reads.size == 2 && reads.distinct().size == 2 && node.next in reads && stores.size == 2)
        val previous = reads.single { it != node.next }
        require(
            stores.toSet() == setOf(
                Triple(previous, node.next, node.next),
                Triple(node.next, previous, previous)
            )
        ) {
            "List unlink does not connect each neighbour to its reciprocal link"
        }
        return previous
    }
}
