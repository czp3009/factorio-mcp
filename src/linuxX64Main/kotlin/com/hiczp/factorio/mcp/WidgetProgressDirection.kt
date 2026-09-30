package com.hiczp.factorio.mcp

/** Cross-checked named direction comparisons; preserves the raw enum rather than assigning direction names. */
internal object WidgetProgressDirection {
    fun resolve(image: ElfImage): Long {
        val size = SysVObjectSize.resolve(image, "4agui11ProgressBar")
        val debug = DwarfInlines(image)
        val owners = mapOf(
            "_ZNK4agui11ProgressBar15getBarRectangleEv" to "getBarRectangle",
            "_ZN4agui11ProgressBar14paintComponentERKNS_10PaintEventERKNS_5PointE" to "paintComponent",
        )
        val fields = owners.flatMap { (name, owner) ->
            val function = image.symbol(name)
            val bytes = image.functionBytes(function, 4096)
            debug.find(function, owner, setOf("operator==")).flatMap { instance ->
                instance.ranges.map { range ->
                    val comparison = MemberEquality.analyze(
                        bytes, range.start - function.address,
                        range.end - function.address, 1
                    )
                    instance.origin to NativeAccessor(comparison.offset, 1, 0xffUL, 0).withinObject(size).offset
                }
            }
        }.distinct()
        return fields.singleOrNull()?.second ?: error("Progress direction comparisons disagree on the bounded member")
    }
}
