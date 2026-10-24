package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Synthetic inlined lookup with a typed insertion fallback; offsets deliberately differ from the
 * game.
 */
internal class KeySearchFixture(val stride: Long = 64, val map: Long = 16) {
    val header = EventHeader(80, 4, 16)
    val code = 40L
    val eventModifiers = 56L
    val stateModifiers = 104L
    val stateSize = 128L
    val begin = 8L
    val end = 24L
    val key = 4L
    val held = 12L
    val insertion = 1000L
    val table: X64JumpTables.Table
    val flow: X64ControlFlow

    init {
        val body = mutableListOf<Instruction>()
        val labels = mutableMapOf<String, Long>()
        val branches = mutableMapOf<Long, String>()
        fun label(name: String) {
            labels[name] = body.size.toLong()
        }
        fun add(
            operation: Operation,
            target: Operand? = null,
            source: Operand? = null,
            condition: Int? = null,
        ) {
            body += Instruction(body.size.toLong(), 1, operation, target, source, condition)
        }
        fun branch(name: String, condition: Int? = null) {
            branches[body.size.toLong()] = name
            add(
                if (condition == null) Operation.JMP else Operation.JCC,
                Immediate(0),
                condition = condition,
            )
        }
        add(Operation.PUSH, Register(5, 8))
        add(Operation.PUSH, Register(14, 8))
        add(Operation.PUSH, Register(12, 8))
        add(Operation.MOV, Register(14, 8), Register(7, 8))
        add(Operation.MOV, Register(12, 8), Register(6, 8))
        add(Operation.MOV, Register(0, 4), Memory(6, null, 1, header.type, 4))
        add(Operation.SUB, Register(0, 4), Immediate(5))
        add(Operation.CMP, Register(0, 4), Immediate(1))
        val guard = body.size.toLong()
        branch("return", 7)
        val jump = body.size.toLong()
        add(Operation.JMP, Register(0, 8))
        for ((name, down) in listOf("press" to true, "release" to false)) {
            label(name)
            add(Operation.MOV, Register(2, 4), Memory(12, null, 1, code, 4))
            add(Operation.MOV, Register(0, 8), Memory(14, null, 1, map + begin, 8))
            add(Operation.MOV, Register(1, 8), Memory(14, null, 1, map + end, 8))
            add(Operation.MOV, Register(9, 8), Register(1, 8))
            add(Operation.SUB, Register(9, 8), Register(0, 8))
            add(Operation.SAR, Register(9, 8), Immediate(if (stride == 64L) 6 else 5))
            add(Operation.CMP, Register(9, 8), Immediate(10))
            branch("${name}_missing", 3)
            add(Operation.CMP, Register(0, 8), Register(1, 8))
            branch("${name}_missing", 4)
            label("${name}_loop")
            add(Operation.CMP, Memory(0, null, 1, key, 4), Register(2, 4))
            branch("${name}_found", 13)
            add(Operation.ADD, Register(0, 8), Immediate(stride))
            add(Operation.CMP, Register(0, 8), Register(1, 8))
            branch("${name}_loop", 5)
            branch("${name}_missing")
            label("${name}_found")
            add(Operation.CMP, Register(2, 4), Memory(0, null, 1, key, 4))
            branch("${name}_held", 13)
            label("${name}_missing")
            add(Operation.LEA, Register(7, 8), Memory(14, null, 1, map, 8))
            add(Operation.CALL, Immediate(insertion))
            label("${name}_held")
            add(Operation.MOV, Memory(0, null, 1, held, 1), Immediate(if (down) 1 else 0))
            if (!down) {
                add(Operation.MOV, Memory(0, null, 1, 16, 8), Immediate(0))
                add(Operation.MOV, Memory(0, null, 1, 24, 1), Immediate(0))
            }
            branch("modifiers")
        }
        label("modifiers")
        add(Operation.MOVZX, Register(0, 4), Memory(12, null, 1, eventModifiers, 2))
        add(Operation.MOV, Memory(14, null, 1, stateModifiers, 2), Register(0, 2))
        label("return")
        add(Operation.POP, Register(12, 8))
        add(Operation.POP, Register(14, 8))
        add(Operation.POP, Register(5, 8))
        add(Operation.RET)
        table =
            X64JumpTables.Table(
                jump,
                guard,
                Register(0, 4),
                2000,
                listOf(labels.getValue("press"), labels.getValue("release")),
            )
        flow =
            X64ControlFlow(
                body.map { instruction ->
                    branches[instruction.offset]?.let {
                        instruction.copy(destination = Immediate(labels.getValue(it)))
                    } ?: instruction
                },
                listOf(table),
            )
    }

    fun update(selected: X64ControlFlow = flow) =
        InputStateKeyUpdate.analyze(selected, table, insertion, stateSize, header)

    fun replace(instruction: Instruction) =
        X64ControlFlow(
            flow.instructions.map { if (it.offset == instruction.offset) instruction else it },
            listOf(table),
        )
}
