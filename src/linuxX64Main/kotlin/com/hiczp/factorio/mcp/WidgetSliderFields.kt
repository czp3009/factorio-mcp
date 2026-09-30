package com.hiczp.factorio.mcp

internal data class WidgetSliderFields(
    val size: Long,
    val value: Long,
    val minimum: Long,
    val maximum: Long,
    val step: Long
) {
    companion object {
        fun resolve(image: ElfImage): WidgetSliderFields {
            val size = SysVObjectSize.resolve(image, "4agui6Slider")
            val value = DoubleMemberAccessor.resolve(image, "_ZNK4agui6Slider8getValueEv", size)
            require(value == DoubleMemberAccessor.resolve(image, "_ZN4agui6Slider11setValueRawEd", size, true))
            val step = DoubleMemberAccessor.resolve(image, "_ZNK4agui6Slider12getValueStepEv", size)
            require(step == DoubleMemberAccessor.resolve(image, "_ZN4agui6Slider12setValueStepEd", size, true))
            val minimum = SliderRangeSetter.resolve(image, "_ZN4agui6Slider11setMinValueEd", size, 7)
            val maximum = SliderRangeSetter.resolve(image, "_ZN4agui6Slider11setMaxValueEd", size, 2)
            require(minimum.field == maximum.opposite && maximum.field == minimum.opposite)
            val offsets = listOf(value, step, minimum.field, maximum.field).sorted()
            require(offsets.zipWithNext().all { (left, right) -> right - left >= 8 })
            return WidgetSliderFields(size, value, minimum.field, maximum.field, step)
        }
    }
}
