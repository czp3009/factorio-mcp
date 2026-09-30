package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Callback member and allocation extent from the selected SDL factory; live ownership is checked separately. */
internal data class SdlDeviceAllocation(val size: Long, val member: Long, val allocator: Long, val backend: Long) {
    companion object {
        fun resolve(image: ElfImage, factoryName: String, backendName: String): SdlDeviceAllocation {
            val factory = image.symbol(factoryName)
            val backend = image.symbol(backendName)
            image.functionBytes(backend, 1)
            val allocator = image.symbol("real_calloc")
            for (function in listOf(factory, backend, allocator)) EhFrames(image).function(function)
            val wrapper = X64Instructions(image.functionBytes(allocator, 64)).all(32)
                .filter { it.operation !in listOf(Operation.NOP, Operation.ENDBR) }
            val body = if (wrapper.size == 4) {
                require(
                    wrapper[0].operation == Operation.PUSH && wrapper[0].destination == Register(5, 8) &&
                            wrapper[1].operation == Operation.MOV && wrapper[1].destination == Register(5, 8) &&
                            wrapper[1].source == Register(4, 8) && wrapper[2].operation == Operation.POP &&
                            wrapper[2].destination == Register(5, 8)
                )
                wrapper.drop(3)
            } else wrapper
            require(body.size == 1 && body.single().operation == Operation.JMP)
            val relative = (body.single().destination as? Immediate)?.value ?: error("Indirect calloc wrapper")
            require(
                relative >= -allocator.address && relative <= Long.MAX_VALUE - allocator.address &&
                        image.importedFunction(allocator.address + relative) == "calloc"
            )
            val table = image.symbol("s_mem")
            require(table.type == 1 && table.size in 8..4096 && table.size % 8 == 0L)
            val slots =
                ElfPointers(image).words(table.address, (table.size / 8).toInt()).mapIndexedNotNull { index, word ->
                    if ((word.relocation ?: word.raw.takeUnless { word.positionIndependent }) == allocator.address)
                        table.address + index * 8 else null
                }
            val slot = slots.singleOrNull() ?: error("Missing or ambiguous SDL calloc entry")
            return analyze(X64ControlFlow.resolve(image, factory), factory.address, slot, backend.address)
        }

        fun analyze(flow: X64ControlFlow, address: Long, allocator: Long, backend: Long): SdlDeviceAllocation {
            require(
                address > 0 && address <= Long.MAX_VALUE - 65536 && allocator > 0 &&
                        allocator % 8 == 0L && backend > 0
            )
            val definitions = ScalarExpression(flow)
            fun global(site: Long, memory: Memory): Long {
                require(memory.relative && memory.base == null && memory.index == null && memory.width == 8)
                val next = address + site + flow.body.getValue(site).size
                require(memory.displacement >= -next && memory.displacement <= Long.MAX_VALUE - next)
                return next + memory.displacement
            }

            fun origin(site: Long, register: Int, depth: Int = 0): X64Instructions.Instruction {
                require(depth < 32)
                val definition = definitions.definition(site, register)
                if (definition.operation == Operation.MOV && definition.destination == Register(register, 8) &&
                    definition.source is Register
                ) {
                    require(definition.source.width == 8)
                    return origin(definition.offset, definition.source.number, depth + 1)
                }
                require(definition.operation != Operation.CALL || register == 0) {
                    "Only RAX establishes the allocator result"
                }
                return definition
            }

            val stores = flow.instructions.filter { instruction ->
                val target = instruction.destination as? Memory
                val source = instruction.source as? Register
                if (instruction.offset !in flow.reachable || instruction.operation != Operation.MOV ||
                    target == null || source?.width != 8 || target.width != 8
                ) return@filter false
                val definition = runCatching { origin(instruction.offset, source.number) }.getOrNull()
                    ?: return@filter false
                val reference = definition.source as? Memory ?: return@filter false
                definition.operation == Operation.LEA && reference.relative &&
                        global(definition.offset, reference) == backend
            }
            require(stores.size == 1) { "Missing or ambiguous backend callback store" }
            val store = stores.single()
            val target = store.destination as Memory
            require(
                !target.relative && target.index == null && target.base != null &&
                        target.displacement >= 0 && target.displacement % 8 == 0L
            )
            val allocation = origin(store.offset, target.base)
            val entry = allocation.destination as? Memory ?: error("Device has no indirect allocator result")
            require(allocation.operation == Operation.CALL && global(allocation.offset, entry) == allocator)
            fun constant(register: Int): Long {
                val definition = origin(allocation.offset, register)
                val destination = definition.destination as? Register ?: error("Missing allocation argument")
                val value = (definition.source as? Immediate)?.value ?: error("Dynamic SDL allocation size")
                require(definition.operation == Operation.MOV && destination.width in listOf(4, 8)) {
                    "Allocation argument leaves unproven upper bits"
                }
                return if (destination.width == 4) value and 0xffffffffL else value
            }

            val count = constant(7)
            val width = constant(6)
            require(count in 1..65536 && width in 1..65536 && count <= 65536 / width)
            val size = count * width
            require(size >= 8 && target.displacement <= size - 8) { "Backend slot exceeds its device allocation" }
            return SdlDeviceAllocation(size, target.displacement, allocator, backend)
        }
    }
}
