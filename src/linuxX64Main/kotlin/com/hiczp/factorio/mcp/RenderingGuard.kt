package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Proves the complete native suppression prefix, including its null-settings branch. */
internal data class RenderingGuard(val minimized: Long, val suppressed: Long, val settings: Long, val halt: Long) {
    companion object {
        fun analyze(bytes: BinaryView, address: Long, global: Long, headless: Long,
                    globalSize: Long, settingsSize: Long, window: Long, flushSlot: Int): RenderingGuard {
            val decoder = X64Instructions(bytes)
            val body = mutableListOf<Instruction>()
            var offset = 0L
            while (true) {
                require(offset < minOf(bytes.size, 512)) { "Rendering guard exceeds prefix bound" }
                val instruction = decoder.decode(offset)
                body += instruction
                offset += instruction.size
                if (instruction.operation in setOf(Operation.CALL, Operation.JMP) && instruction.destination !is Immediate) break
                require(instruction.operation != Operation.CALL) { "Rendering guard calls an unproven helper" }
            }
            return analyze(body, address, global, headless, globalSize, settingsSize, window, flushSlot)
        }

        fun analyze(body: List<Instruction>, address: Long, global: Long, headless: Long,
                    globalSize: Long, settingsSize: Long, window: Long, flushSlot: Int): RenderingGuard {
            // Only the dispatch's inputs matter. A tail call is terminal, not a switch edge.
            val terminal = body.last()
            require(terminal.operation in setOf(Operation.CALL, Operation.JMP))
            val flow = X64ControlFlow(body.dropLast(1) + terminal.copy(operation = Operation.CALL))
            val origins = OriginalPointerOrigins(flow, address)
            val receiver = origins.register(terminal.offset, 7) as? OriginalPointerOrigins.Load
                ?: error("Suppression does not use the original window")
            require(receiver.base == OriginalPointerOrigins.Argument(7) && receiver.member == window)
            val table = when (val target = terminal.destination) {
                is Memory -> {
                    require(target.width == 8 && target.index == null && !target.relative && target.displacement == flushSlot * 8L)
                    target.base?.let { origins.register(terminal.offset, it) }
                }
                is Register -> {
                    require(target.width == 8)
                    val entry = origins.register(terminal.offset, target.number) as? OriginalPointerOrigins.Load
                        ?: error("Rendering suppression has an unproven virtual entry")
                    require(entry.member == flushSlot * 8L)
                    entry.base
                }
                else -> error("Rendering suppression is not a virtual dispatch")
            } as? OriginalPointerOrigins.Load
            require(table?.base == receiver && table.member == 0L)
            val flags = mutableSetOf<Long>()
            val settingsFields = mutableSetOf<Pair<Long, Long>>()
            for (instruction in body) {
                val reads = when (instruction.operation) {
                    Operation.CMP, Operation.TEST -> listOfNotNull(instruction.destination as? Memory, instruction.source as? Memory)
                    Operation.MOV, Operation.MOVZX -> listOfNotNull(instruction.source as? Memory)
                    else -> emptyList()
                }
                for (memory in reads) {
                    if (memory.width != 1 || memory.relative) continue
                    require(memory.index == null)
                    when (val root = memory.base?.let { origins.register(instruction.offset, it) }) {
                        is OriginalPointerOrigins.Argument -> {
                            require(root.register == 7 && root.adjustment == 0L && memory.displacement in 0 until globalSize)
                            flags += memory.displacement
                        }
                        is OriginalPointerOrigins.Load -> {
                            val source = root.base as? OriginalPointerOrigins.Global
                            require(source?.address == global && root.member in 0..globalSize - 8 && root.member % 8 == 0L &&
                                memory.displacement in 0 until settingsSize)
                            settingsFields += root.member to memory.displacement
                        }
                        else -> error("Rendering flag has unknown receiver provenance")
                    }
                }
            }
            require(flags.size == 2 && settingsFields.size == 1) { "Ambiguous rendering condition fields" }
            val (settings, halt) = settingsFields.single()
            val candidates = flags.map { minimized -> RenderingGuard(minimized, flags.single { it != minimized }, settings, halt) }
            return candidates.singleOrNull { candidate ->
                (0 until 32).all { bits ->
                    val h = bits and 1 != 0
                    val s = bits and 2 != 0
                    val m = bits and 4 != 0
                    val present = bits and 8 != 0
                    val haltValue = bits and 16 != 0
                    evaluate(body, address, global, headless, window, flushSlot, candidate, h, s, m, present, haltValue) ==
                        (h || s || (m && present && haltValue))
                }
            } ?: error("Native rendering branches do not implement the complete suppression condition")
        }

        private fun evaluate(body: List<Instruction>, address: Long, global: Long, headless: Long, window: Long, flushSlot: Int,
                             layout: RenderingGuard, h: Boolean, s: Boolean, m: Boolean, present: Boolean, halt: Boolean): Boolean {
            // Synthetic identities are used only to prove instructions; they never address the game.
            val root = -0x10000000L
            val settings = -0x20000000L
            val owner = -0x30000000L
            val registers = MutableList<Long?>(16) { null }
            registers[7] = root
            registers[4] = -0x40000000L
            val stack = mutableMapOf<Long, Long?>()
            val instructions = body.associateBy { it.offset }
            var site = 0L
            var equal: Boolean? = null
            val seen = mutableSetOf<Long>()
            while (site in instructions) {
                require(seen.add(site)) { "Cyclic rendering predicate" }
                val i = instructions.getValue(site)
                if (i == body.last()) return true
                val next = site + i.size
                fun location(v: Memory): Long {
                    require(v.index == null)
                    return (if (v.relative) address + next else checkNotNull(registers[checkNotNull(v.base)])) + v.displacement
                }
                fun read(v: Operand?): Long = when (v) {
                    is Register -> checkNotNull(registers[v.number]) { "Rendering predicate uses an unproven register" }
                    is Immediate -> v.value
                    is Memory -> {
                        val position = location(v)
                        when (position to v.width) {
                            global to 8 -> root
                            headless to 1 -> if (h) 1 else 0
                            root + layout.minimized to 1 -> if (m) 1 else 0
                            root + layout.suppressed to 1 -> if (s) 1 else 0
                            root + layout.settings to 8 -> if (present) settings else 0
                            settings + layout.halt to 1 -> if (halt) 1 else 0
                            root + window to 8 -> owner
                            owner to 8 -> -0x50000000L
                            -0x50000000L + flushSlot * 8L to 8 -> -0x60000000L
                            else -> error("Rendering predicate reads an unrelated or incorrectly sized field")
                        }
                    }
                    else -> error("Unknown rendering operand")
                }
                fun write(v: Operand?, value: Long?) {
                    require(v is Register && v.number in 0..15 && v.width in setOf(4, 8))
                    registers[v.number] = if (v.width == 4) value?.and(0xffffffffL) else value
                }
                site = next
                when (i.operation) {
                    Operation.NOP, Operation.ENDBR -> Unit
                    Operation.PUSH -> {
                        val saved = i.destination as? Register ?: error("Rendering prefix saves a nonregister value")
                        require(saved.width == 8)
                        val slot = checkNotNull(registers[4]) - 8
                        registers[4] = slot
                        stack[slot] = registers[saved.number]
                    }
                    Operation.POP -> {
                        val slot = checkNotNull(registers[4])
                        write(i.destination, stack.getValue(slot))
                        registers[4] = slot + 8
                    }
                    Operation.MOV, Operation.MOVZX -> write(i.destination, read(i.source))
                    Operation.LEA -> write(i.destination, location(i.source as Memory))
                    Operation.SUB, Operation.ADD -> {
                        require(i.destination == Register(4, 8) && i.source is Immediate)
                        registers[4] = checkNotNull(registers[4]) + read(i.source) * if (i.operation == Operation.ADD) 1 else -1
                        equal = null
                    }
                    Operation.CMP -> equal = read(i.destination) == read(i.source)
                    Operation.TEST -> equal = read(i.destination) and read(i.source) == 0L
                    Operation.JCC -> {
                        require(i.condition in setOf(4, 5))
                        if (checkNotNull(equal) == (i.condition == 4)) site = (i.destination as Immediate).value
                    }
                    Operation.JMP -> site = (i.destination as Immediate).value
                    else -> error("Unsupported rendering predicate instruction: ${i.operation}")
                }
            }
            require(site >= body.last().offset + body.last().size && site < 512) { "Rendering guard escapes its verified prefix" }
            return false
        }
    }
}
