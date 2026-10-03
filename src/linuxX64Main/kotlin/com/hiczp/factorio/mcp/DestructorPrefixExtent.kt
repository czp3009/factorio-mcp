package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** A destructor's original-receiver reads before any call or branch prove a minimum prefix, never sizeof. */
internal object DestructorPrefixExtent {
    fun resolve(image: ElfImage, encodedType: String): Long {
        val identity = ItaniumType.resolve(image, encodedType)
        val table = image.symbol("_ZTV$encodedType")
        val words = image.pointers.words(table.address, (table.size / 8).toInt())
        val headers = mutableMapOf<Long, Long>()
        var index = 0
        while (index < words.size) {
            val word = words[index]
            if (word.relocation == null && word.raw in -65528L..0 && index + 1 < words.size &&
                runCatching { words[index + 1].pointer() == identity.typeInfo }.getOrDefault(false)) {
                val offset = -word.scalar()
                require(offset !in headers && (headers.isNotEmpty() || offset == 0L))
                headers[offset] = table.address + (index + 2) * 8L
                index += 2
            } else {
                require(headers.isNotEmpty())
                val entry = word.pointer()
                require(entry == 0L || entry in image.functionAddresses)
                index++
            }
        }
        val destructor = image.symbol("_ZN${encodedType}D2Ev")
        EhFrames(image).function(destructor)
        val extent = analyze(image.functionBytes(destructor, 32768), destructor.address, identity.addressPoint,
            headers.filterKeys { it != 0L })
        require(headers.keys.all { it <= extent - 8 }) { "Destructor prefix does not bound every subobject table" }
        return extent
    }

    fun analyze(bytes: BinaryView, address: Long, table: Long, secondary: Map<Long, Long> = emptyMap()): Long {
        require(bytes.size in 1..65536 && address > 0 && table > 0)
        require(secondary.all { (offset, pointer) -> offset in 8..65528 && pointer > 0 && pointer != table } &&
                secondary.values.distinct().size == secondary.size)
        val flow = X64ControlFlow(X64Instructions(bytes).all(8192))
        val arguments = SysVArgumentFlow(flow, includeStack = true)
        val addresses = MutableList<Long?>(16) { null }
        var installed = false
        val installedSecondary = mutableSetOf<Long>()
        var minimumExtent = 0L
        for (instruction in flow.instructions) {
            if (instruction.operation in listOf(Operation.CALL, Operation.JMP, Operation.JCC, Operation.RET)) break
            val destination = instruction.destination
            val source = instruction.source
            if (source is Memory && instruction.operation != Operation.LEA) {
                val read = arguments.memory(instruction.offset, source)
                    ?: error("Destructor prefix reads an unknown address")
                require(installed && read.reference.argument == 7 && read.reference.offset in 0..65528 &&
                        read.width in listOf(1, 2, 4, 8)) { "Destructor prefix load lacks a verified original receiver" }
                if (read.reference.offset >= 8 && secondary.keys.none {
                        read.reference.offset < it + 8 && it < read.reference.offset + read.width
                    }) minimumExtent = maxOf(minimumExtent, read.reference.offset + read.width)
            }
            if (destination is Memory && instruction.operation !in listOf(Operation.CMP, Operation.TEST)) {
                val write = arguments.memory(instruction.offset, destination)
                    ?: error("Destructor prefix writes an unknown address")
                when (write.reference.argument) {
                    4 -> require(write.reference.offset in -4096..-8 && write.width in listOf(1, 2, 4, 8))
                    7 -> {
                        require(write.width == 8 && instruction.operation == Operation.MOV && source is Register &&
                                source.width == 8) { "Destructor prefix changes unverified object storage" }
                        if (write.reference.offset == 0L) {
                            require(!installed && addresses[source.number] == table)
                            installed = true
                        } else {
                            require(installed && installedSecondary.add(write.reference.offset) &&
                                    secondary[write.reference.offset] == addresses[source.number] &&
                                    addresses[source.number] != null) { "Destructor installs an unverified secondary table" }
                        }
                    }
                    else -> error("Destructor prefix publishes its receiver or writes another object")
                }
            }
            if (destination is Register) {
                addresses[destination.number] = when {
                    instruction.operation == Operation.LEA && destination.width == 8 && source is Memory &&
                            source.relative && source.index == null -> address + instruction.offset + instruction.size + source.displacement
                    instruction.operation == Operation.MOV && destination.width == 8 && source is Register && source.width == 8 ->
                        addresses[source.number]
                    instruction.operation in listOf(Operation.CMP, Operation.TEST, Operation.PUSH) -> addresses[destination.number]
                    else -> null
                }
            }
            require(instruction.operation in listOf(Operation.MOV, Operation.MOVZX, Operation.LEA, Operation.PUSH,
                Operation.SUB, Operation.ADD, Operation.NOP, Operation.ENDBR, Operation.TEST, Operation.CMP)) {
                "Unsupported destructor prefix operation"
            }
        }
        require(installed && minimumExtent > 8) { "Destructor has no independent receiver member load before dispatch" }
        require(installedSecondary.all { it <= minimumExtent - 8 })
        return minimumExtent
    }
}
