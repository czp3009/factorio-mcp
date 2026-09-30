package com.hiczp.factorio.mcp

/** Raw progress members. The text gate is distinct from the style-dependent reservation expression. */
internal data class WidgetProgressFields(val size: Long, val value: Long, val direction: Long, val hasText: Long) {
    companion object {
        fun resolve(image: ElfImage): WidgetProgressFields {
            val size = SysVObjectSize.resolve(image, "4agui11ProgressBar")
            val direction = WidgetProgressDirection.resolve(image)
            val value = WidgetProgressValue.resolve(image)
            val debug = DwarfInlines(image)
            val text = mapOf(
                "_ZNK4agui11ProgressBar15getBarRectangleEv" to "getBarRectangle",
                "_ZN4agui11ProgressBar14paintComponentERKNS_10PaintEventERKNS_5PointE" to "paintComponent",
            ).flatMap { (name, owner) ->
                val function = image.symbol(name)
                val bytes = image.functionBytes(function, 4096)
                debug.find(function, owner, setOf("reserveSpaceForText")).map { instance ->
                    instance.origin to InlineBooleanMember.analyze(bytes, instance.ranges.map {
                        DwarfRanges.Range(it.start - function.address, it.end - function.address)
                    }, size)
                }
            }.distinct().singleOrNull()?.second ?: error("Progress text gates disagree on their own boolean")
            require(direction != text && listOf(direction, text).all { it < value || it >= value + 8 })
            return WidgetProgressFields(size, value, direction, text)
        }
    }
}
