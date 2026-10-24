package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Pointer
import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import com.hiczp.factorio.mcp.X64Instructions.*

/** Null-terminated receiver forwarding in a bounded entry prefix. Never follows a live chain. */
internal object LinkedReceiverPrefix {
    data class Proof(val member: Long, val end: Long, val receiver: Int)

    fun resolve(image: ElfImage, function: String, extent: Long): Proof {
        val entry = image.symbol(function)
        EhFrames(image).function(entry)
        return analyze(image.functionBytes(entry, 512), entry.address, extent)
    }

    fun analyze(bytes: BinaryView, address: Long, extent: Long): Proof {
        require(bytes.size in 1..512 && extent in 8..16 * 1024 * 1024)
        val decoder = X64Instructions(bytes)
        val instructions = mutableListOf<X64Instructions.Instruction>()
        var site = 0L
        repeat(128) {
            require(site < bytes.size) { "Linked receiver prefix leaves its function" }
            val instruction = decoder.decode(site)
            instructions += instruction
            site += instruction.size
            require(
                instruction.operation !in
                    setOf(Operation.CALL, Operation.JMP, Operation.RET, Operation.POP)
            ) {
                "Linked receiver traversal is not an entry prefix"
            }
            val target = (instruction.destination as? Immediate)?.value
            if (
                instruction.operation != Operation.JCC ||
                    target == null ||
                    target >= instruction.offset
            )
                return@repeat
            require(instruction.condition == 5 && target >= 0)
            val body =
                instructions.filter {
                    it.offset in target until site && it.operation != Operation.NOP
                }
            require(body.size == 4 && body.first().offset == target && body.last() == instruction) {
                "Linked receiver loop has unsupported effects"
            }
            val (copy, load, test) = body
            val cursor =
                load.destination as? Register
                    ?: error("Linked receiver loop has no advancing register")
            val member =
                load.source as? Memory
                    ?: error("Linked receiver loop does not read a pointer member")
            val retained =
                copy.destination as? Register
                    ?: error("Linked receiver loop does not retain its nonnull receiver")
            require(
                copy.operation == Operation.MOV &&
                    copy.source == cursor &&
                    retained.width == 8 &&
                    cursor.width == 8 &&
                    retained.number !in setOf(4, 5, cursor.number) &&
                    cursor.number !in setOf(4, 5)
            )
            require(
                load.operation == Operation.MOV &&
                    member.width == 8 &&
                    !member.relative &&
                    member.index == null &&
                    member.base == cursor.number &&
                    member.displacement in 0..extent - 8 &&
                    member.displacement % 8 == 0L
            )
            fun nullTest(candidate: X64Instructions.Instruction): Boolean =
                candidate.operation == Operation.TEST &&
                    candidate.destination == cursor &&
                    candidate.source == cursor ||
                    candidate.operation == Operation.CMP &&
                        candidate.destination == cursor &&
                        candidate.source == Immediate(0)
            require(nullTest(test)) {
                "Linked receiver backedge does not test the next pointer for null"
            }
            val prefix = SysVReceiverFlow(bytes.slice(0, copy.offset + copy.size), address, extent)
            val values = prefix.before(copy.offset)
            val guards = instructions.filter { it.operation == Operation.JCC && it.offset < target }
            when (val initial = values[cursor.number]) {
                Receiver() ->
                    require(guards.isEmpty()) { "Linked receiver entry has an unrelated branch" }
                is Pointer -> {
                    require(initial.base == Receiver() && initial.offset == member.displacement)
                    val guard =
                        guards.singleOrNull()
                            ?: error("Linked receiver entry has no unique null guard")
                    val guardTest = instructions.single { it.offset + it.size == guard.offset }
                    require(guard.condition == 4 && nullTest(guardTest))
                    require(prefix.before(guardTest.offset)[cursor.number] == initial)
                    val nullStart = (guard.destination as Immediate).value
                    if (nullStart == site) {
                        require(values[retained.number] == Receiver()) {
                            "Linked receiver entry does not preserve the original root"
                        }
                    } else {
                        site =
                            joinedReceiver(
                                decoder,
                                bytes.size,
                                site,
                                nullStart,
                                retained,
                                prefix.before(guardTest.offset),
                            )
                    }
                }

                else -> error("Linked receiver loop is not rooted in the original receiver")
            }
            return Proof(member.displacement, site, retained.number)
        }
        error("Linked receiver traversal exceeds its prefix bound")
    }

    /** Two straight exits may assign the retained root only on the initial-null path. */
    private fun joinedReceiver(
        decoder: X64Instructions,
        bound: Long,
        nonnull: Long,
        nullable: Long,
        retained: Register,
        originals: List<SysVReceiverFlow.Value>,
    ): Long {
        require(nullable > nonnull && nullable < bound)
        var site = nonnull
        var join: Long? = null
        fun scalar(instruction: Instruction) {
            require(instruction.operation in listOf(Operation.MOV, Operation.XOR, Operation.NOP)) {
                "Linked receiver exit has an unsupported effect"
            }
            if (instruction.operation != Operation.NOP) {
                val destination =
                    instruction.destination as? Register
                        ?: error("Linked receiver exit writes memory")
                require(
                    destination.number !in listOf(4, 5) &&
                        (instruction.source is Register || instruction.source is Immediate)
                )
            }
        }
        repeat(32) {
            if (join != null) return@repeat
            require(site < nullable)
            val instruction = decoder.decode(site)
            site += instruction.size
            require(site <= nullable)
            if (instruction.operation == Operation.JMP) {
                join =
                    (instruction.destination as? Immediate)?.value
                        ?: error("Linked receiver exit has an indirect jump")
            } else {
                scalar(instruction)
                require((instruction.destination as? Register)?.number != retained.number)
            }
        }
        val end = checkNotNull(join)
        require(end > nullable && end < bound && end - nullable <= 128)
        val body = mutableListOf<Instruction>()
        site = nullable
        while (site < end) {
            val instruction = decoder.decode(site)
            scalar(instruction)
            body += instruction.copy(offset = site - nullable)
            site += instruction.size
        }
        require(site == end)
        body += Instruction(end - nullable, 1, Operation.NOP)
        val roots =
            originals
                .mapIndexedNotNull { register, value ->
                    if (value == Receiver()) register to SysVArgumentFlow.Reference(7) else null
                }
                .toMap()
        val flow = SysVArgumentFlow(X64ControlFlow(body), entryReferences = roots)
        require(flow.register(end - nullable, retained.number) == SysVArgumentFlow.Reference(7)) {
            "Initial-null path does not retain the original root"
        }
        return end
    }
}
