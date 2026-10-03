package com.hiczp.factorio.mcp

/** Value member derived from a named setter on a freshly constructed native ProgressBar; never invokes it. */
internal object WidgetProgressValue {
    fun resolve(image: ElfImage): Long {
        val size = SysVObjectSize.resolve(image, "4agui11ProgressBar")
        val identity = ItaniumType.resolve(image, "N4agui11ProgressBarE")
        val function = image.symbol("_ZN17CustomProgressBar12createWidgetEv")
        val allocator = image.symbol("_Znwm")
        EhFrames(image).function(allocator)
        val instances = image.inlines.find(function, "createWidget", setOf("setValue"))
        val offsets = instances.map { instance ->
            AllocatedInlineDoubleMember.analyze(
                image.functionBytes(function, 4096), function.address,
                allocator.address, size, identity.addressPoint,
                instance.ranges.map { DwarfRanges.Range(it.start - function.address, it.end - function.address) })
        }.distinct()
        return offsets.singleOrNull() ?: error("Progress setters disagree on their value member")
    }
}
