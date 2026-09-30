package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Connects GLAD storage to an exact SDK lookup name, including the loader's null/error assignments. */
internal object GlLoaderBinding {
    fun resolve(image: ElfImage, names: List<String>, deviceSize: Long): Map<String, Long> {
        require(names.isNotEmpty() && names.size <= 32 && names.distinct().size == names.size)
        val loader = image.symbol("gladLoadGLLoader")
        require(loader.size in 1..524288)
        EhFrames(image).function(loader)
        val instructions = X64Instructions(image.functionBytes(loader, 524288)).all(65536)
        val device = image.symbol("_this")
        require(device.type == 1 && device.size == 8L)
        val error = image.symbol("SDL_SetError")
        image.functionBytes(error, 1)
        return names.associateWith { name ->
            require(name.startsWith("gl") && name.length in 3..64)
            val slot = image.symbol("glad_$name")
            require(slot.type == 1 && slot.size == 8L && slot.address % 8 == 0L)
            analyze(instructions, loader.address, device.address, deviceSize, slot.address, error.address, name) {
                image.virtualBytes(it, name.length + 1L).string(0, name.length + 1)
            }
            slot.address
        }
    }

    fun analyze(
        body: List<Instruction>, address: Long, device: Long, deviceSize: Long,
        storage: Long, error: Long, name: String, readName: (Long) -> String
    ) {
        require(
            body.isNotEmpty() && body.size <= 65536 && body.first().offset == 0L &&
                    body.zipWithNext().all { (a, b) -> a.offset + a.size == b.offset })
        val end = body.last().let { it.offset + it.size }
        require(
            end in 1..524288 && address > 0 && address <= Long.MAX_VALUE - end &&
                    device > 0 && storage > 0 && deviceSize in 8..65536
        )
        val byOffset = body.associateBy { it.offset }
        fun global(instruction: Instruction, memory: Memory): Long {
            require(memory.relative && memory.base == null && memory.index == null)
            val next = address + instruction.offset + instruction.size
            require(memory.displacement >= -next && memory.displacement <= Long.MAX_VALUE - next)
            return next + memory.displacement
        }

        val branches = body.filter { it.operation in listOf(Operation.JMP, Operation.JCC) }.map {
            it.offset to ((it.destination as? Immediate)?.value ?: error("Indirect GL loader branch"))
        }
        val stores = body.filter { instruction ->
            val memory = instruction.destination as? Memory ?: return@filter false
            if (!memory.relative || instruction.operation in listOf(Operation.CMP, Operation.TEST, Operation.CALL))
                return@filter false
            val target = global(instruction, memory)
            if (target >= storage + 8 || target + memory.width <= storage) return@filter false
            require(
                target == storage && memory.width == 8 && instruction.operation == Operation.MOV &&
                        instruction.source == Register(3, 8)
            ) { "Unsupported GL slot assignment" }
            true
        }
        require(stores.size in 1..16) { "Missing or excessive GL loader assignments" }
        for (store in stores) {
            val candidate = body.withIndex().filter { (index, instruction) ->
                index >= 11 && instruction.operation == Operation.JMP && instruction.destination == Immediate(store.offset) &&
                        body[index - 1].let {
                            it.operation == Operation.MOV && it.destination == Register(3, 8) &&
                                    it.source == Register(0, 8)
                        } &&
                        body[index - 2].let { it.operation == Operation.CALL && it.destination == Register(0, 8) }
            }.singleOrNull() ?: error("GL slot has no unique lookup-result assignment")
            val path = body.subList(candidate.index - 11, candidate.index + 1)
            fun requireAt(
                index: Int, operation: Operation, destination: X64Instructions.Operand?,
                source: X64Instructions.Operand? = null
            ) {
                val instruction = path[index]
                require(
                    instruction.operation == operation && (destination == null || instruction.destination == destination) &&
                            (source == null || instruction.source == source)
                ) { "Unsupported SDK lookup path" }
            }
            requireAt(0, Operation.MOV, Register(7, 8))
            val root = path[0].source as? Memory ?: error("Missing SDL device load")
            require(root.width == 8 && global(path[0], root) == device)
            requireAt(1, Operation.TEST, Register(7, 8), Register(7, 8))
            requireAt(3, Operation.MOV, Register(0, 8))
            val resolver = path[3].source as? Memory ?: error("Missing native SDK resolver")
            require(
                !resolver.relative && resolver.base == 7 && resolver.index == null && resolver.width == 8 &&
                        resolver.displacement in 0..deviceSize - 8 && resolver.displacement % 8 == 0L
            )
            requireAt(4, Operation.TEST, Register(0, 8), Register(0, 8))
            requireAt(6, Operation.CMP, null, Immediate(0))
            val loaded = path[6].destination as? Memory ?: error("Missing SDL loaded-driver gate")
            require(
                !loaded.relative && loaded.base == 7 && loaded.index == null && loaded.width == 4 &&
                        loaded.displacement in 0..deviceSize - 4 && loaded.displacement % 4 == 0L
            )
            requireAt(8, Operation.LEA, Register(6, 8))
            val text = path[8].source as? Memory ?: error("Missing SDK name")
            require(
                text.width == 8 && readName(
                    global(
                        path[8],
                        text
                    )
                ) == name
            ) { "GL storage uses a different SDK name" }
            val start = path.first().offset
            val failureStart = candidate.value.offset + candidate.value.size
            require(failureStart <= store.offset && store.offset - start <= 512)
            for ((from, to) in branches) require(from in start..store.offset || to !in start + 1..store.offset) {
                "Another loader path enters the SDK assignment"
            }
            for (index in listOf(2, 5, 7)) {
                requireAt(index, Operation.JCC, null)
                require(path[index].condition == 4)
                var cursor = (path[index].destination as? Immediate)?.value ?: error("Indirect SDK null branch")
                require(cursor in failureStart..store.offset)
                val visited = mutableSetOf<Long>()
                var zero = false
                while (cursor != store.offset) {
                    require(cursor in failureStart until store.offset && visited.add(cursor) && visited.size <= 32)
                    val instruction = byOffset[cursor] ?: error("SDK error path enters an instruction")
                    cursor += instruction.size
                    when (instruction.operation) {
                        Operation.LEA, Operation.MOV -> require((instruction.destination as? Register)?.let {
                            it.number in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) && it.width in listOf(4, 8)
                        } == true)

                        Operation.XOR -> {
                            require(instruction.destination == instruction.source)
                            when (instruction.destination) {
                                Register(3, 4), Register(3, 8) -> zero = true
                                Register(0, 4), Register(0, 8) -> Unit
                                else -> error("Unknown SDK error-path mutation")
                            }
                        }

                        Operation.CALL -> require(instruction.destination == Immediate(error - address))
                        Operation.JMP -> cursor = (instruction.destination as? Immediate)?.value
                            ?: error("Indirect SDK error continuation")

                        else -> error("Unsupported SDK error path")
                    }
                }
                require(zero) { "Failed SDK lookup does not clear the function pointer" }
            }
        }
    }
}
