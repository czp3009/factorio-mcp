package com.hiczp.factorio.mcp

/** Instruction boundaries from DWARF 4/5 line rows. File names/line numbers are not used as layout evidence. */
internal class DwarfLines(private val section: BinaryView) {
    fun addresses(begin: Long, end: Long): Set<Long> {
        require(begin >= 0 && end > begin)
        val selected = mutableSetOf<Long>()
        val input = section.cursor()
        var units = 0
        while (input.remaining > 0) {
            require(++units <= 65536)
            var length = input.unsigned(4)
            val width = if (length == 0xffffffffL) 8 else 4
            if (width == 8) length = input.unsigned(8) else require(length < 0xfffffff0L)
            require(length > 0 && length <= input.remaining)
            val unit = input.block(length).cursor()
            val version = unit.unsigned(2)
            require(version in 4..5) { "Unsupported DWARF line version: $version" }
            if (version == 5L) require(unit.unsigned(1) == 8L && unit.unsigned(1) == 0L) {
                "Segmented/non-x64 line addresses are unsupported"
            }
            val headerLength = unit.unsigned(width)
            require(headerLength > 0 && headerLength <= unit.remaining)
            val header = unit.block(headerLength).cursor()
            val instructionSize = header.unsigned(1)
            require(instructionSize in 1..16 && header.unsigned(1) == 1L) { "VLIW line tables are unsupported" }
            require(header.unsigned(1) in 0..1)
            header.unsigned(1) // Signed line base does not change machine addresses.
            val lineRange = header.unsigned(1)
            val opcodeBase = header.unsigned(1)
            require(lineRange > 0 && opcodeBase in 13..255)
            val operandCounts = List((opcodeBase - 1).toInt()) { header.unsigned(1) }
            require(operandCounts.take(12) == listOf(0L, 1L, 1L, 1L, 1L, 0L, 0L, 0L, 1L, 0L, 0L, 1L)) {
                "DWARF standard line opcodes have incompatible operand counts"
            }
            // The rest of this length-delimited header describes directories/files, which are irrelevant here.
            var address: Long? = null
            var steps = 0
            fun advance(operations: Long) {
                val current = checkNotNull(address) { "Line program advances before setting an address" }
                require(operations >= 0 && operations <= (Long.MAX_VALUE - current) / instructionSize)
                address = current + operations * instructionSize
            }

            fun emit() {
                val current = checkNotNull(address) { "Line row has no address" }
                if (current in begin until end) {
                    selected += current
                    require(selected.size <= 65536) { "Too many instruction boundaries in the selected range" }
                }
            }
            while (unit.remaining > 0) {
                require(++steps <= 64 * 1024 * 1024) { "DWARF line program exceeds operation bound" }
                val opcode = unit.unsigned(1)
                when {
                    opcode == 0L -> {
                        val size = unit.uleb()
                        require(size > 0 && size <= unit.remaining)
                        val extended = unit.block(size).cursor()
                        when (val operation = extended.unsigned(1)) {
                            1L -> {
                                require(extended.remaining == 0L && address != null)
                                address = null
                            }

                            2L -> {
                                require(extended.remaining == 8L)
                                val next = extended.unsigned(8)
                                require(next >= 0 && (address == null || next >= checkNotNull(address)))
                                address = next
                            }

                            3L -> Unit // Define-file payload cannot change address state.
                            4L -> {
                                require(extended.uleb() >= 0 && extended.remaining == 0L)
                            }

                            else -> error("Unsupported extended DWARF line opcode: $operation")
                        }
                    }

                    opcode >= opcodeBase -> {
                        advance((opcode - opcodeBase) / lineRange)
                        emit()
                    }

                    else -> when (opcode) {
                        1L -> emit()
                        2L -> advance(unit.uleb())
                        3L -> unit.sleb()
                        4L, 5L, 12L -> require(unit.uleb() >= 0)
                        6L, 7L, 10L, 11L -> Unit
                        8L -> advance((255 - opcodeBase) / lineRange)
                        9L -> {
                            val current = checkNotNull(address)
                            val delta = unit.unsigned(2)
                            require(delta <= Long.MAX_VALUE - current)
                            address = current + delta
                        }

                        else -> error("Unsupported standard DWARF line opcode: $opcode")
                    }
                }
            }
            require(address == null) { "DWARF line sequence is unterminated" }
        }
        return selected
    }
}
