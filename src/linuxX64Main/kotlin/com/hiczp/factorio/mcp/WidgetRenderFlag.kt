package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** A named inline predicate on the same Widget receiver used by a verified virtual method position. */
internal data class WidgetRenderFlag(
    val field: NativeAccessor,
    private val functions: List<ElfImage.Symbol>,
    private val readonly: List<ElfImage.ReadonlyRange>,
) {
    fun verify(image: ElfImage, bias: Long, read: (Long, Int) -> ByteArray) {
        require(bias >= 0)
        fun compare(address: Long, size: Long, expected: BinaryView) {
            require(size in 1..16 * 1024 * 1024 && address > 0 && address <= Long.MAX_VALUE - bias - size)
            require(read(address + bias, size.toInt()).contentEquals(expected.bytes(0, size.toInt()))) {
                "Live widget render evidence differs from the selected executable"
            }
        }
        for (function in functions)
            compare(function.address, function.size, image.functionBytes(function, function.size.toInt()))
        for (range in readonly)
            compare(range.address, range.size, image.virtualBytes(range.address, range.size))
    }

    companion object {
        fun resolve(image: ElfImage, widgetSize: Long): WidgetRenderFlag {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val function =
                        image.symbol("_ZN4agui6Widget30recursivePaintChildrenInternalEbPNS_8GraphicsERKNS_5PointE")
                    val predicates =
                        DwarfInlines(image).find(function, "recursivePaintChildrenInternal", setOf("shouldRender"))
                    val method = ItaniumVtable.resolve(image, "_ZTVN4agui6WidgetE")
                        .method(image, "_ZNK4agui6Widget11getLocationEv")
                    val bytes = image.functionBytes(function, 32768)
                    require(bytes.size == function.size)
                    val fields = predicates.flatMap { it.ranges }.map { range ->
                        analyze(
                            bytes,
                            range.start - function.address,
                            range.end - function.address,
                            widgetSize,
                            method.slot
                        )
                    }.distinct()
                    fields.singleOrNull() ?: error("Named render predicates disagree on their widget flag")
                }
            }
            return WidgetRenderFlag(resolved.first, resolved.second, readonly)
        }

        /** Decode only the debug-identified predicate and its bounded direct fallthrough to typed Widget dispatch. */
        fun analyze(bytes: BinaryView, start: Long, end: Long, widgetSize: Long, slot: Int): NativeAccessor {
            require(start >= 0 && end > start && end < bytes.size && bytes.size <= 32768 && slot in 0..4096)
            val decoder = X64Instructions(bytes)
            val test = decoder.decode(start)
            val member = test.destination as? Memory ?: error("Render predicate does not test a member")
            val bits = (test.source as? Immediate)?.value?.toULong() ?: error("Render predicate has no constant mask")
            require(
                test.operation == Operation.TEST && start + test.size == end && !member.relative &&
                        member.index == null && member.base != null && member.width in listOf(1, 2, 4) &&
                        bits != 0UL && bits and (bits - 1UL) == 0UL && bits < (1UL shl (member.width * 8))
            ) {
                "Render predicate is not one receiver-relative flag test"
            }
            NativeAccessor(member.displacement, member.width, bits, bits.countTrailingZeroBits()).withinObject(
                widgetSize
            )
            val branch = decoder.decode(end)
            val skip = (branch.destination as? Immediate)?.value ?: error("Render predicate has no direct false branch")
            require(branch.operation == Operation.JCC && branch.condition == 4 && skip in end + branch.size until bytes.size) {
                "Render predicate does not skip its widget dispatch when clear"
            }
            // The predicate's receiver must reach RDI unchanged and supply the primary virtual table used by the call.
            // This proves a layout association, not a new adapter call into getLocation or the paint implementation.
            val receivers = mutableSetOf(member.base)
            val tables = mutableSetOf<Int>()
            var cursor = end + branch.size
            repeat(8) {
                require(cursor < skip && cursor - end <= 64)
                val instruction = decoder.decode(cursor)
                cursor += instruction.size
                when (instruction.operation) {
                    Operation.NOP -> Unit
                    Operation.MOV -> {
                        val target =
                            instruction.destination as? Register ?: error("Render dispatch prefix mutates memory")
                        require(target.width == 8 && target.number != 4)
                        val source = instruction.source
                        val receiver = source is Register && source.width == 8 && source.number in receivers
                        val table = source is Register && source.width == 8 && source.number in tables ||
                                source is Memory && source.width == 8 && !source.relative && source.index == null &&
                                source.base in receivers && source.displacement == 0L
                        receivers.remove(target.number)
                        tables.remove(target.number)
                        if (receiver) receivers.add(target.number)
                        if (table) tables.add(target.number)
                    }

                    Operation.CALL -> {
                        val call = instruction.destination as? Memory ?: error("Render dispatch is not virtual")
                        require(
                            7 in receivers && call.width == 8 && !call.relative && call.index == null &&
                                    call.base in tables && call.displacement == slot * 8L && cursor <= skip
                        ) {
                            "Render predicate and typed virtual dispatch do not share the same Widget receiver"
                        }
                        val bit = bits.countTrailingZeroBits()
                        return NativeAccessor(member.displacement + bit / 8, 1, 1UL shl (bit % 8), bit % 8)
                    }

                    else -> error("Render predicate has an unsupported dispatch prefix")
                }
            }
            error("Render predicate has no bounded typed widget dispatch")
        }
    }
}
