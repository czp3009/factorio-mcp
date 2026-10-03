package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Static evidence from a bounded native enum-to-string table. Does not execute Lua. */
internal object LuaEnumNames {
    data class Proof(
        val objectPointer: Long,
        val field: Long,
        val table: Long,
        val pointers: List<Long>,
        val names: List<String>,
    )

    data class Table(val objectPointer: Long, val field: Long, val address: Long, val count: Int)

    fun resolve(image: ElfImage, function: String, wrapperSize: Long, objectSize: Long): Proof {
        val entry = image.symbol(function)
        val push = image.symbol("lua_pushstring")
        EhFrames(image).function(push)
        val table =
            analyze(
                X64ControlFlow.resolve(image, entry),
                entry.address,
                push.address - entry.address,
                wrapperSize,
                objectSize,
            )
        val section =
            image.sections.singleOrNull {
                it.flags and 6L == 2L &&
                    table.address >= it.address &&
                    table.count * 8L <= it.size &&
                    table.address - it.address <= it.size - table.count * 8L
            } ?: error("Enum names table is not bounded allocated non-executable data")
        require(section.type == 1L)
        // RELRO pointer words are relocated, not ordinary immutable byte evidence. Consumers must
        // validate these pointers separately against the loaded selected executable before use.
        val pointers = image.pointers.words(table.address, table.count).map { it.pointer() }
        val names =
            pointers.map { address ->
                val storage =
                    image.sections.singleOrNull {
                        it.flags and 7L == 2L &&
                            address >= it.address &&
                            address - it.address < it.size
                    } ?: error("Enum name is not selected-executable immutable data")
                image
                    .virtualBytes(address, minOf(129L, storage.size - (address - storage.address)))
                    .string(0, 128)
                    .also { require(it.isNotEmpty()) }
            }
        return Proof(table.objectPointer, table.field, table.address, pointers, names)
    }

    fun analyze(
        flow: X64ControlFlow,
        address: Long,
        push: Long,
        wrapperSize: Long,
        objectSize: Long,
    ): Table {
        require(address >= 0 && wrapperSize in 8..4096 && objectSize in 1..4096)
        val arguments = SysVArgumentFlow(flow)
        val definitions = ScalarExpression(flow)
        val calls =
            flow.instructions.filter {
                it.offset in flow.reachable &&
                    it.operation == Operation.CALL &&
                    it.destination == Immediate(push)
            }
        val call = calls.singleOrNull() ?: error("Enum reader has no unique native string push")
        require(arguments.register(call.offset, 7) == SysVArgumentFlow.Reference(6)) {
            "Enum reader does not forward its original Lua state"
        }
        fun pointerDefinition(site: Long, register: Int, depth: Int = 0): Instruction {
            require(depth < 32) { "Enum pointer forwarding exceeds its bound" }
            val instruction = definitions.definition(site, register)
            require(instruction.destination == Register(register, 8)) {
                "Enum pointer is truncated"
            }
            if (instruction.operation == Operation.MOV && instruction.source is Register) {
                require(instruction.source.width == 8)
                return pointerDefinition(instruction.offset, instruction.source.number, depth + 1)
            }
            return instruction
        }
        val load = pointerDefinition(call.offset, 6)
        val memory = load.source as? Memory ?: error("Enum result is not a pointer-table load")
        require(
            load.operation == Operation.MOV &&
                memory.width == 8 &&
                !memory.relative &&
                memory.base != null &&
                memory.index != null &&
                memory.base != memory.index &&
                memory.scale == 8 &&
                memory.displacement == 0L
        )
        val base = pointerDefinition(load.offset, memory.base)
        val location =
            base.source as? Memory ?: error("Enum table has no selected-executable address")
        require(
            base.operation == Operation.LEA &&
                location.relative &&
                location.base == null &&
                location.index == null
        )
        require(address <= Long.MAX_VALUE - base.offset - base.size)
        val pc = address + base.offset + base.size
        require(location.displacement >= -pc && location.displacement <= Long.MAX_VALUE - pc)
        val table = pc + location.displacement
        require(table > 0 && table % 8 == 0L)

        fun byteSource(site: Long, register: Int, depth: Int = 0): Instruction {
            require(depth < 32) { "Enum index forwarding exceeds its bound" }
            val instruction = definitions.definition(site, register)
            val target =
                instruction.destination as? Register ?: error("Enum index is not a register")
            if (instruction.operation == Operation.MOV && instruction.source is Register) {
                require(target.width in listOf(4, 8) && instruction.source.width == target.width)
                return byteSource(instruction.offset, instruction.source.number, depth + 1)
            }
            require(
                instruction.operation == Operation.MOVZX &&
                    target.width in listOf(4, 8) &&
                    (instruction.source as? Memory)?.width == 1
            ) {
                "Enum index is not an unchanged zero-extended byte"
            }
            return instruction
        }
        val source = byteSource(load.offset, memory.index)
        val field = source.source as Memory
        require(
            !field.relative &&
                field.index == null &&
                field.base != null &&
                field.displacement in 0 until objectSize
        )
        val values = ConstructorValues(flow.reaching(source.offset), emptyMap())
        val objectPointer =
            values.register(source.offset, field.base) as? ConstructorValues.Load
                ?: error("Enum member does not come from the original wrapper")
        require(
            objectPointer.base == ConstructorValues.Argument(7) &&
                objectPointer.member in 0..wrapperSize - 8
        )
        val objectLoad = flow.body.getValue(objectPointer.site)
        require(
            objectLoad.operation == Operation.MOV &&
                (objectLoad.source as? Memory)?.width == 8 &&
                arguments.source(objectLoad.offset) ==
                    SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, objectPointer.member), 8)
        )

        fun reaches(start: Long, omit: Pair<Long, Long>? = null): Boolean {
            val pending = ArrayDeque<Long>()
            val seen = mutableSetOf<Long>()
            pending.add(start)
            while (pending.isNotEmpty()) {
                val site = pending.removeFirst()
                if (site == load.offset) return true
                if (seen.add(site))
                    pending.addAll(flow.successors.getValue(site).filter { (site to it) != omit })
            }
            return false
        }
        val bounds =
            flow.instructions
                .mapNotNull { branch ->
                    if (
                        branch.offset !in flow.reachable ||
                            branch.operation != Operation.JCC ||
                            branch.condition !in setOf(3, 7)
                    )
                        return@mapNotNull null
                    val comparison =
                        runCatching { definitions.flagDefinition(branch.offset) }.getOrNull()
                            ?: return@mapNotNull null
                    val index = comparison.destination as? Register ?: return@mapNotNull null
                    val maximum = (comparison.source as? Immediate)?.value ?: return@mapNotNull null
                    if (
                        comparison.operation != Operation.CMP ||
                            index.width !in listOf(4, 8) ||
                            maximum !in 0..255
                    )
                        return@mapNotNull null
                    if (
                        runCatching { byteSource(comparison.offset, index.number) }.getOrNull() !=
                            source
                    )
                        return@mapNotNull null
                    val count = maximum + if (branch.condition == 7) 1 else 0
                    if (count !in 1..256) return@mapNotNull null
                    val safe = branch.offset + branch.size
                    val failure =
                        (branch.destination as? Immediate)?.value ?: return@mapNotNull null
                    if (
                        safe !in flow.body ||
                            failure !in flow.body ||
                            reaches(failure) ||
                            !reaches(safe) ||
                            reaches(0, branch.offset to safe)
                    )
                        return@mapNotNull null
                    count.toInt()
                }
                .distinct()
        val count =
            bounds.singleOrNull() ?: error("Enum table has no unique dominating unsigned bound")
        // A normal return must execute the proven push. Error/throw exits provide no enum result.
        val pending = ArrayDeque<Long>()
        val seen = mutableSetOf<Long>()
        pending.add(0)
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (!seen.add(site) || site == call.offset) continue
            require(flow.body.getValue(site).operation != Operation.RET) {
                "Enum reader returns without its string push"
            }
            pending.addAll(flow.successors.getValue(site))
        }
        return Table(objectPointer.member, field.displacement, table, count)
    }
}
