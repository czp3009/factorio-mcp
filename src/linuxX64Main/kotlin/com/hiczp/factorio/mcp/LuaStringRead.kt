package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** ABI evidence from the string-result path with a non-null size output, not a Lua conversion implementation. */
internal data class LuaStringResult(
    val tag: Long, val tagWidth: Int, val mask: Long, val type: Long,
    val pointer: Long, val length: Long, val data: Long
)

internal object SysVLuaStringRead {
    private sealed interface Value
    private data class Input(val register: Int, val width: Int = 8) : Value
    private data class Stack(val offset: Long) : Value
    private data object Indexed : Value
    private data class Field(val base: Value, val offset: Long, val width: Int) : Value
    private data class Masked(val field: Field, val mask: Long) : Value
    private data class Data(val pointer: Field, val offset: Long) : Value
    private data object Unknown : Value
    private data class Tag(val field: Masked, val type: Long)

    fun resolve(image: ElfImage, valueSize: Long): LuaStringResult {
        val entry = image.symbol("lua_tolstring")
        val index = image.symbol("index2addr")
        val frames = EhFrames(image)
        frames.function(entry)
        frames.function(index)
        return analyze(image.functionBytes(entry, 4096), entry.address, index.address, valueSize)
    }

    fun analyze(bytes: BinaryView, address: Long, index: Long, valueSize: Long): LuaStringResult {
        require(bytes.size in 1..4096 && address >= 0 && address <= Long.MAX_VALUE - bytes.size && index >= 0)
        require(valueSize in 8..128)
        val registers = MutableList<Value>(16) { Input(it) }
        registers[6] = Input(6, 4)
        registers[4] = Stack(0)
        val saved = mutableMapOf<Long, Value>()
        var indexed = false
        var selected: Tag? = null
        var comparison: Tag? = null
        var sizeTest = false
        var length: Field? = null
        fun top() = (registers[4] as? Stack)?.offset ?: error("Unproven string-result frame")
        fun read(source: X64Instructions.Operand?): Value = when (source) {
            is Register -> {
                val value = registers[source.number]
                require(
                    source.width == 8 || value is Input && value.width == source.width ||
                            value is Field && source.width >= value.width || value is Masked && source.width == 4
                ) {
                    "String-result instruction truncates argument provenance"
                }
                value
            }

            is Memory -> {
                require(!source.relative && source.index == null && source.base != null)
                val base = registers[source.base]
                when (base) {
                    Indexed -> require(
                        source.width in listOf(
                            1,
                            4,
                            8
                        ) && source.displacement in 0..valueSize - source.width
                    )

                    is Field -> require(
                        base.base == Indexed && base.width == 8 && source.width == 8 &&
                                source.displacement in 0..4096
                    )

                    else -> error("String result reads an unproven object")
                }
                Field(base, source.displacement, source.width)
            }

            else -> error("Unsupported string-result source")
        }

        fun write(target: X64Instructions.Operand?, value: Value) {
            when (target) {
                is Register -> {
                    require(
                        target.number != 4 && (target.width == 8 || value is Input && value.width == target.width ||
                                value is Field && target.width >= value.width || value is Masked && target.width == 4)
                    )
                    registers[target.number] = value
                }

                is Memory -> {
                    require(
                        selected != null && length == null && !target.relative && target.index == null &&
                            target.base?.let { registers[it] } == Input(2) && target.displacement == 0L && target.width == 8)
                    val field = value as? Field ?: error("String size does not come from a native member")
                    require(field.width == 8 && field.base is Field && field.base.base == Indexed && field.base.width == 8)
                    length = field
                }

                else -> error("Unsupported string-result destination")
            }
        }

        val decoder = X64Instructions(bytes)
        val visited = mutableSetOf<Long>()
        var position = 0L
        repeat(128) {
            require(position in 0 until bytes.size && visited.add(position)) { "String-result path loops or escapes" }
            val instruction = decoder.decode(position)
            position += instruction.size
            val previous = comparison
            val testedSize = sizeTest
            comparison = null
            sizeTest = false
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    require(!indexed && instruction.destination is Register && instruction.destination.width == 8)
                    val value = read(instruction.destination)
                    val next = top() - 8
                    require(next >= -16384)
                    registers[4] = Stack(next)
                    require(saved.put(next, value) == null)
                }

                Operation.POP -> {
                    require(
                        indexed && instruction.destination is Register && instruction.destination.width == 8 &&
                                instruction.destination.number != 4
                    )
                    val offset = top()
                    registers[instruction.destination.number] =
                        saved.remove(offset) ?: error("String result restores an unsaved register")
                    registers[4] = Stack(offset + 8)
                }

                Operation.MOV, Operation.MOVZX -> write(instruction.destination, read(instruction.source))
                Operation.LEA -> {
                    val source = instruction.source as? Memory ?: error("Invalid string data address")
                    val pointer =
                        source.base?.let { registers[it] } as? Field ?: error("String data lacks a native object")
                    require(
                        selected != null && !source.relative && source.index == null && pointer.base == Indexed &&
                                pointer.width == 8 && source.displacement in 8..4096 && instruction.destination is Register &&
                                instruction.destination.width == 8
                    )
                    write(instruction.destination, Data(pointer, source.displacement))
                }

                Operation.SUB, Operation.ADD -> {
                    val target = instruction.destination as? Register ?: error("String result modifies memory")
                    val amount =
                        (instruction.source as? Immediate)?.value ?: error("Variable string-result displacement")
                    require(target.width == 8)
                    if (target.number == 4) {
                        require(
                            amount in 0..16384 && amount % 8 == 0L &&
                                    (instruction.operation == Operation.ADD) == indexed
                        )
                        val next = top() + if (indexed) amount else -amount
                        require(next in -16384..0)
                        registers[4] = Stack(next)
                        saved.keys.removeAll { it < next }
                    } else {
                        val pointer = read(target) as? Field ?: error("String data address lacks an object pointer")
                        require(
                            selected != null && instruction.operation == Operation.ADD && pointer.base == Indexed &&
                                    pointer.width == 8 && amount in 8..4096
                        )
                        registers[target.number] = Data(pointer, amount)
                    }
                }

                Operation.CALL -> {
                    val relative = (instruction.destination as? Immediate)?.value ?: error("Indirect index lookup")
                    require(
                        !indexed && relative >= -address && relative <= Long.MAX_VALUE - address &&
                                address + relative == index && registers[7] == Input(7) && registers[6] == Input(
                            6,
                            4
                        ) &&
                                (8 + top()) % 16 == 0L
                    ) { "Lua string lookup does not forward state and int index" }
                    for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) registers[register] = Unknown
                    registers[0] = Indexed
                    indexed = true
                }

                Operation.AND -> {
                    val field =
                        read(instruction.destination) as? Field ?: error("String tag mask lacks a native member")
                    val mask = (instruction.source as? Immediate)?.value ?: error("Variable string tag mask")
                    require(
                        indexed && selected == null && field.base == Indexed && field.width in listOf(
                            1,
                            4
                        ) && mask in 1..255
                    )
                    write(instruction.destination, Masked(field, mask))
                }

                Operation.CMP -> {
                    val tag = read(instruction.destination) as? Masked ?: error("String branch lacks a masked tag")
                    val type = (instruction.source as? Immediate)?.value ?: error("Variable string type")
                    require(selected == null && type in 1..255 && type and tag.mask == type)
                    comparison = Tag(tag, type)
                }

                Operation.TEST -> {
                    require(
                        selected != null && read(instruction.destination) == Input(2) && read(instruction.source) == Input(
                            2
                        )
                    )
                    sizeTest = true // The query adapter always supplies a real size_t output pointer.
                }

                Operation.JCC -> {
                    val target = (instruction.destination as? Immediate)?.value ?: error("Indirect string branch")
                    require(target in 0 until bytes.size && instruction.condition in listOf(4, 5))
                    if (previous != null) {
                        require(selected == null)
                        selected = previous
                        if (instruction.condition == 4) position = target
                    } else {
                        require(testedSize)
                        if (instruction.condition == 5) position = target
                    }
                }

                Operation.JMP -> {
                    val target = (instruction.destination as? Immediate)?.value ?: error("Indirect string jump")
                    require(target in 0 until bytes.size)
                    position = target
                }

                Operation.RET -> {
                    val tag = checkNotNull(selected)
                    val output = checkNotNull(length)
                    val result = registers[0] as? Data ?: error("String result is not the native data address")
                    require(
                        output.base == result.pointer && output.offset + 8 <= result.offset &&
                                (tag.field.field.offset + tag.field.field.width <= result.pointer.offset ||
                                        result.pointer.offset + 8 <= tag.field.field.offset) &&
                                top() == 0L && saved.isEmpty() &&
                                listOf(3, 5, 12, 13, 14, 15).all { registers[it] == Input(it) }) {
                        "String result disagrees on object identity, member bounds or restored registers"
                    }
                    return LuaStringResult(
                        tag.field.field.offset, tag.field.field.width, tag.field.mask, tag.type,
                        result.pointer.offset, output.offset, result.offset
                    )
                }

                else -> error("Unsupported string-result path operation: ${instruction.operation}")
            }
        }
        error("String-result path exceeds instruction bound")
    }
}
