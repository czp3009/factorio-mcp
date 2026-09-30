package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Original Player/action parameters, index write and unchanged normal-return/tail frame. */
internal object ChatSubmitArguments {
    fun verify(image: ElfImage, player: PlayerLayout, size: Long, type: Long, payload: Long, stringSize: Long) {
        val entry = image.symbol("_ZNK6Player15sendToListenersEO11InputAction")
        EhFrames(image).function(entry)
        analyze(image.functionBytes(entry, 4096), player.playerSize, player.index, size, type, payload, stringSize)
    }

    fun analyze(
        bytes: BinaryView, playerSize: Long, index: PlayerIndex, size: Long,
        type: Long, payload: Long, stringSize: Long
    ) {
        require(
            playerSize in 8..4096 && index.width in listOf(1, 2) && index.offset in 8..playerSize - index.width &&
                    size in 2..4096 && type in 0..size - 2 && payload in 0..size - stringSize && stringSize > 0
        )
        val instructions = X64Instructions(bytes).all(1024)
        val tails = instructions.filter { it.operation == Operation.JMP && it.destination !is Immediate }
        require(tails.all { it.destination is Register && it.destination.width == 8 })
        val exits = instructions.filter { it.operation == Operation.RET || it in tails }.map { it.offset }.toSet()
        require(exits.isNotEmpty())
        val flow = X64ControlFlow(instructions.map {
            if (it in tails) it.copy(operation = Operation.RET, destination = null) else it
        })
        val arguments = SysVArgumentFlow(flow)
        val scalars = ScalarExpression(flow, mapOf(7 to playerSize))
        val writes = instructions.filter {
            it.offset in flow.reachable && it.destination is Memory &&
                    it.operation !in listOf(Operation.CMP, Operation.TEST, Operation.BIT_TEST)
        }
        val store = writes.singleOrNull() ?: error("Chat submission does not have one bounded action index write")
        val field = arguments.memory(store.offset, store.destination as Memory)
            ?: error("Submission writes through an unknown object")
        require(
            store.operation == Operation.MOV && field.reference.argument == 6 && field.width == index.width &&
                    field.reference.offset in 0..size - field.width &&
                    (field.reference.offset + field.width <= type || field.reference.offset >= type + 2) &&
                    (field.reference.offset + field.width <= payload || field.reference.offset >= payload + stringSize)
        )
        val source = store.source as? Register ?: error("Submission index is not a player value")
        var value = scalars.before(store.offset, source)
        while (value is ScalarExpression.Narrow) {
            require(value.width >= index.width)
            value = value.value
        }
        require(
            value is ScalarExpression.Input && value.field ==
                    SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, index.offset), index.width)
        ) {
            "Submission does not copy the original player's native index"
        }
        val permitted = setOf(
            Operation.MOV, Operation.MOVZX, Operation.LEA, Operation.CMP, Operation.TEST,
            Operation.BIT_TEST, Operation.SUB, Operation.SAR, Operation.SHR, Operation.JCC, Operation.JMP,
            Operation.RET, Operation.NOP, Operation.ENDBR
        )
        val saved = setOf(3, 4, 5, 12, 13, 14, 15)
        for (exit in exits.filter { it in flow.reachable }) {
            val ancestors = flow.reaching(exit)
            for (instruction in instructions.filter { it.offset in ancestors.reachable }) {
                require(instruction.operation in permitted) { "Submission exit has unsupported frame effects" }
                if (instruction.operation !in setOf(
                        Operation.CMP, Operation.TEST, Operation.BIT_TEST,
                        Operation.JCC, Operation.JMP, Operation.RET
                    )
                ) {
                    require((instruction.destination as? Register)?.number !in saved) {
                        "Submission exit changes its original frame or saved registers"
                    }
                }
            }
            require(arguments.register(exit, 6) == SysVArgumentFlow.Reference(6)) {
                "Submission does not preserve its original action argument"
            }
        }
    }
}
