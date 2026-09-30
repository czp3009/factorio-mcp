package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Evidence for a single receiver-relative load, not permission to dereference an arbitrary object. */
internal data class NativeAccessor(val offset: Long, val width: Int, val mask: ULong, val shift: Int) {
    val maximum: ULong
        get() = mask shr shift

    fun withinObject(size: Long): NativeAccessor {
        require(size > 0 && offset >= 0 && offset <= size && width <= size - offset) {
            "Accessor load exceeds the independently established object bounds"
        }
        return this
    }

    fun extract(raw: ULong): ULong = (raw and mask) shr shift
}

/** Proves a restricted leaf accessor's System V receiver, load and integer return from its complete body. */
internal object SysVAccessors {
    private sealed interface Value
    private data class Receiver(val offset: Long = 0) : Value
    private data class Field(val accessor: NativeAccessor) : Value

    fun resolve(image: ElfImage, symbol: ElfImage.Symbol, boolean: Boolean = false): NativeAccessor {
        require(symbol.size in 1..256) { "Accessor function exceeds analysis bound" }
        EhFrames(image).function(symbol)
        return analyze(image.functionBytes(symbol, 256), boolean)
    }

    fun analyze(bytes: BinaryView, boolean: Boolean = false): NativeAccessor {
        val (value, loads) = evaluate(bytes)
        val result = (value as? Field)?.accessor ?: error("Accessor does not return a proven field in RAX")
        require(loads == 1 && result.maximum > 0u) { "Accessor returns no variable field bits" }
        if (boolean) require(result.maximum == 1uL) { "Accessor return is not a proven boolean" }
        return result
    }

    fun resolveAddress(image: ElfImage, symbol: ElfImage.Symbol, memberSize: Long, objectSize: Long): Long {
        require(symbol.size in 1..256) { "Address accessor function exceeds analysis bound" }
        EhFrames(image).function(symbol)
        return analyzeAddress(image.functionBytes(symbol, 256), memberSize, objectSize)
    }

    fun analyzeAddress(bytes: BinaryView, memberSize: Long, objectSize: Long): Long {
        val (value, loads) = evaluate(bytes)
        val offset = (value as? Receiver)?.offset ?: error("Accessor does not return a receiver-relative address")
        require(loads == 0 && memberSize > 0 && objectSize >= memberSize && offset in 0..objectSize - memberSize) {
            "Address accessor exceeds the independently established member/object bounds"
        }
        return offset
    }

    private fun evaluate(bytes: BinaryView): Pair<Value, Int> {
        require(bytes.size in 1..256) { "Accessor function exceeds analysis bound" }
        val body =
            X64Instructions(bytes).all().filter { it.operation != Operation.NOP && it.operation != Operation.ENDBR }
        require(body.isNotEmpty() && body.last().operation == Operation.RET) { "Accessor must end in a plain return" }
        var start = 0
        var end = body.lastIndex
        if (body.first().operation == Operation.PUSH) {
            require(
                body.size >= 5 && body[0].destination == Register(5, 8) &&
                        body[1].operation == Operation.MOV && body[1].destination == Register(5, 8) &&
                        body[1].source == Register(4, 8) && body[end - 1].operation == Operation.POP &&
                        body[end - 1].destination == Register(5, 8)
            ) { "Unsupported accessor frame" }
            start = 2
            end--
        }
        val values = mutableMapOf<Int, Value>(7 to Receiver())
        var loads = 0
        fun field(memory: Memory): Field {
            require(!memory.relative && memory.index == null && memory.base != null) { "Accessor load is not receiver-relative" }
            val receiver = values[memory.base] as? Receiver ?: error("Accessor receiver provenance is unknown")
            require(memory.displacement >= -receiver.offset && memory.displacement <= Int.MAX_VALUE - receiver.offset) {
                "Accessor displacement exceeds bound"
            }
            require(++loads == 1) { "Accessor must have exactly one object load" }
            return Field(NativeAccessor(receiver.offset + memory.displacement, memory.width, mask(memory.width), 0))
        }

        fun readable(register: Register): Value {
            val value = values[register.number] ?: error("Accessor reads an unknown register")
            when (value) {
                is Receiver -> require(register.width == 8) { "Accessor truncates its receiver" }
                is Field -> require(value.accessor.maximum <= mask(register.width)) { "Unsupported partial-register read" }
            }
            return value
        }
        for (instruction in body.subList(start, end)) {
            val target = instruction.destination as? Register ?: error("Accessor writes memory or changes control flow")
            require(target.number in setOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) { "Accessor changes a preserved register" }
            // A partial write must not leave unproven upper bits in the eventual integer return.
            if (target.width < 4) {
                val previous = values[target.number] as? Field ?: error("Unproven partial-register write")
                require(previous.accessor.maximum <= mask(target.width)) { "Partial write leaves unknown upper bits" }
            }
            val value = when (instruction.operation) {
                Operation.MOV, Operation.MOVZX -> when (val source = instruction.source) {
                    is Memory -> field(source)
                    is Register -> readable(source)
                    else -> error("Unsupported accessor move")
                }.also {
                    when (it) {
                        is Receiver -> require(target.width == 8 && instruction.operation == Operation.MOV)
                        is Field -> require(it.accessor.maximum <= mask(target.width)) { "Accessor move truncates a field" }
                    }
                }

                Operation.LEA -> {
                    val memory = instruction.source as? Memory ?: error("Accessor address has no memory expression")
                    val receiver = values[memory.base] as? Receiver ?: error("Accessor address provenance is unknown")
                    require(
                        target.width == 8 && !memory.relative && memory.index == null &&
                                memory.displacement >= -receiver.offset && memory.displacement <= Int.MAX_VALUE - receiver.offset
                    ) {
                        "Unsupported receiver address adjustment"
                    }
                    Receiver(receiver.offset + memory.displacement)
                }

                Operation.AND, Operation.SHR -> {
                    val previous =
                        (readable(target) as? Field)?.accessor ?: error("Accessor modifies a non-field value")
                    val immediate = instruction.source as? Immediate ?: error("Accessor transform is not constant")
                    if (instruction.operation == Operation.AND) {
                        Field(previous.copy(mask = previous.mask and (immediate.value.toULong() shl previous.shift)))
                    } else {
                        val count = immediate.value.toInt() and if (target.width == 8) 63 else 31
                        require(previous.shift + count < 64) { "Accessor shift exceeds field width" }
                        Field(previous.copy(shift = previous.shift + count))
                    }
                }

                else -> error("Unsupported accessor operation: ${instruction.operation}")
            }
            values[target.number] = value
        }
        return checkNotNull(values[0]) { "Accessor does not establish a return value" } to loads
    }

    private fun mask(width: Int): ULong = if (width == 8) ULong.MAX_VALUE else (1uL shl (width * 8)) - 1u
}
