package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Selects the native action case and proves both inline and heap cleanup without invoking game code. */
internal object ChatActionDestruction {
    fun verify(image: ElfImage, action: ChatActionCall, type: Long, payload: Long, string: NativeStringLayout) {
        val entry = image.symbol("_ZN11InputAction12destroyValueEv")
        val deallocate = image.symbol("_ZdlPvm")
        EhFrames(image).function(deallocate)
        val tables = X64JumpTables.resolve(image, entry)
        val flow = X64ControlFlow(X64Instructions(image.functionBytes(entry, 4096)).all(1024), tables)
        analyze(flow, tables, entry.address, deallocate.address, action.size, action.type, type, payload, string)
    }

    fun analyze(
        flow: X64ControlFlow, tables: List<X64JumpTables.Table>, address: Long, deallocate: Long,
        size: Long, kind: Int, type: Long, payload: Long, string: NativeStringLayout
    ) {
        require(
            size in 2..4096 && kind in 0..65535 && type in 0..size - 2 &&
                    payload in 0..size - string.size
        )
        val table = tables.singleOrNull() ?: error("Action destructor has no unique discriminator table")
        val scalars = ScalarExpression(flow, mapOf(7 to size))
        fun read(input: ScalarExpression.Input): Long {
            require(input.field == SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, type), 2)) {
                "Action cleanup switch depends on an unrelated input"
            }
            return kind.toLong()
        }
        require(ScalarExpression.evaluate(scalars.branch(table.guard), ::read) == 0L) {
            "Chat action falls outside the native cleanup switch"
        }
        val index = ScalarExpression.evaluate(scalars.before(table.guard, table.index), ::read)
        require(index >= 0 && index < table.targets.size)
        val target = table.targets[index.toInt()]
        require(target > table.jump)
        val specialized = flow.instructions.map { instruction ->
            if (instruction.offset > table.jump) return@map instruction
            if (instruction.offset == table.jump)
                return@map instruction.copy(operation = Operation.JMP, destination = Immediate(target))
            when (instruction.operation) {
                Operation.PUSH -> {
                    require(instruction.destination is Register && instruction.destination.width == 8)
                    return@map instruction
                }

                Operation.MOV -> if (instruction.destination == Register(5, 8) && instruction.source == Register(4, 8))
                    return@map instruction

                Operation.JCC -> require(instruction.offset == table.guard)
                Operation.CMP -> require(instruction.destination is Register && instruction.source is Immediate)
                Operation.MOVZX, Operation.ADD, Operation.SUB, Operation.LEA, Operation.MOVSX -> {
                    val register = instruction.destination as? Register ?: error("Action switch writes memory")
                    require(register.number in listOf(0, 1)) { "Action switch changes a receiver or saved register" }
                }

                Operation.NOP, Operation.ENDBR -> Unit
                else -> error("Unsupported action cleanup switch prefix")
            }
            require(instruction.operation != Operation.MOV) { "Action switch has an unsupported move" }
            if (instruction.offset < table.guard && instruction.source is Memory) {
                require(
                    instruction.operation == Operation.MOVZX &&
                            SysVArgumentFlow(flow).source(instruction.offset) ==
                            SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, type), 2)
                )
            }
            instruction.copy(operation = Operation.NOP, destination = null, source = null)
        }
        StringStorageProof.sizedDestructor(
            specialized, address, deallocate,
            payload + string.data, payload + string.local, size
        )
    }
}
