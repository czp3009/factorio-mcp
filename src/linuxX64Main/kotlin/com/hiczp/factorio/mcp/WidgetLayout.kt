@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxMemberFlag
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxUiLayout as NativeWidgetLayout

/** Verified structural members only. Optional widget properties require their own native proofs. */
internal data class WidgetLayout(
    val guiSize: Long,
    val widgetSize: Long,
    val root: Long,
    val ranges: List<WidgetChildren.Range>,
    val enabled: NativeAccessor,
    val destroying: NativeAccessor,
    val visible: NativeAccessor,
    val hiddenBySearch: NativeAccessor,
    val render: WidgetRenderFlag,
    val text: WidgetTextLayout,
    val toggle: WidgetToggleLayout,
    val checks: List<WidgetCheckLayout>,
    val sliders: List<WidgetSliderLayout>,
    val progress: List<WidgetProgressLayout>,
    val dropdowns: List<WidgetDropdownLayout>,
    val rectangle: ElfImage.Symbol,
    val rectangleAbi: SysVRectangleAbi.Proof,
    private val functions: List<ElfImage.Symbol>,
) {
    fun verifyLoaded(image: ElfImage, process: ProcessHandle, loadBias: Long) {
        render.verify(image, loadBias, process::readMemory)
        for (function in functions) {
            require(function.size in 1..(16 * 1024 * 1024)) { "Widget function exceeds verification bounds" }
            val bytes = image.functionBytes(function, function.size.toInt())
            require(
                process.readMemory(loadBias + function.address, function.size.toInt())
                    .contentEquals(bytes.bytes(0, bytes.size.toInt()))
            ) {
                "Live widget function differs from the selected executable: ${function.name}"
            }
        }
    }

    fun writeTo(output: NativeWidgetLayout, loadBias: Long) {
        output.guiSize = guiSize.toUInt()
        output.widgetSize = widgetSize.toUInt()
        output.root = root.toUInt()
        output.rangeCount = ranges.size.toUInt()
        ranges.forEachIndexed { index, range ->
            output.ranges[index].begin = range.begin.toUInt()
            output.ranges[index].end = range.end.toUInt()
        }
        fun NativeAccessor.writeTo(flag: FmLinuxMemberFlag) {
            flag.offset = offset.toUInt()
            flag.width = width.toUInt()
            flag.mask = mask
            flag.shift = shift.toUInt()
        }
        enabled.writeTo(output.enabled)
        destroying.writeTo(output.destroying)
        visible.writeTo(output.visible)
        hiddenBySearch.writeTo(output.hiddenBySearch)
        render.field.writeTo(output.renderEnabled)
        output.text.slot = text.slot.toUInt()
        output.text.data = text.data.toUInt()
        output.text.length = text.length.toUInt()
        output.text.count = text.getters.size.toUInt()
        text.getters.forEachIndexed { index, getter ->
            output.text.getters[index].function = (loadBias + getter.function.address).toULong()
            output.text.getters[index].offset = getter.offset.toUInt()
            output.text.getters[index].objectSize = getter.objectSize.toUInt()
        }
        output.rectangle.function = (loadBias + rectangle.address).toULong()
        output.rectangle.parent = rectangleAbi.parent.toUInt()
        output.toggle.function = (loadBias + toggle.function.address).toULong()
        output.toggle.slot = toggle.slot.toUInt()
        output.toggle.offset = toggle.offset.toUInt()
        output.toggle.modeOffset = toggle.modeOffset.toUInt()
        output.toggle.objectSize = toggle.size.toUInt()
        output.sliderCount = sliders.size.toUInt()
        sliders.forEachIndexed { index, slider ->
            output.sliders[index].table = (loadBias + slider.table).toULong()
            output.sliders[index].objectSize = slider.size.toUInt()
            output.sliders[index].value = slider.value.toUInt()
            output.sliders[index].minimum = slider.minimum.toUInt()
            output.sliders[index].maximum = slider.maximum.toUInt()
            output.sliders[index].step = slider.step.toUInt()
        }
        output.progressCount = progress.size.toUInt()
        progress.forEachIndexed { index, item ->
            output.progress[index].table = (loadBias + item.table).toULong()
            output.progress[index].objectSize = item.size.toUInt()
            output.progress[index].value = item.value.toUInt()
            output.progress[index].direction = item.direction.toUInt()
            output.progress[index].hasText = item.hasText.toUInt()
        }
        output.dropdownCount = dropdowns.size.toUInt()
        dropdowns.forEachIndexed { index, item ->
            output.dropdowns[index].table = (loadBias + item.table).toULong()
            output.dropdowns[index].objectSize = item.size.toUInt()
            output.dropdowns[index].selected = item.selected.toUInt()
            output.dropdowns[index].first = item.first.toUInt()
            output.dropdowns[index].last = item.last.toUInt()
            output.dropdowns[index].stride = item.stride.toUInt()
            output.dropdowns[index].button = item.button.toUInt()
        }
        output.checkCount = checks.size.toUInt()
        checks.forEachIndexed { index, check ->
            output.checks[index].table = (loadBias + check.table).toULong()
            output.checks[index].offset = check.stateOffset.toUInt()
            output.checks[index].objectSize = check.size.toUInt()
        }
        output.toggle.tableCount = toggle.tables.size.toUInt()
        toggle.tables.forEachIndexed { index, table -> output.toggle.tables[index] = (loadBias + table).toULong() }
    }

    companion object {
        fun resolve(image: ElfImage): WidgetLayout {
            val guiSize = SysVObjectSize.resolve(image, "4agui3Gui")
            val widgetSize = SysVObjectSize.resolve(image, "4agui6Widget")
            val root = SysVCallReceiver.resolve(
                image, "_ZN4agui3Gui3addEPNS_6WidgetE",
                "_ZN4agui6Widget3addEPS0_", guiSize
            )
            val logicRoot = SysVCallReceiver.resolve(
                image, "_ZN4agui3Gui5logicEb",
                "_ZN4agui12TopContainer23processTriggersToResizeEv", guiSize
            )
            require(root == logicRoot) { "GUI logic and widget insertion disagree on their root receiver" }
            fun flag(name: String) =
                SysVAccessors.resolve(image, image.symbol(name), boolean = true).withinObject(widgetSize)

            val rectangle = image.symbol("_ZNK4agui6Widget20getAbsoluteRectangleEv")
            val rectangleAbi = SysVRectangleAbi.resolve(
                image, rectangle, widgetSize,
                ItaniumVtable.resolve(image, "_ZTVN4agui6WidgetE")
            )
            val text = WidgetTextLayout.resolve(image)
            val toggle = WidgetToggleLayout.resolve(image)
            val checkPredicate = WidgetCheckPredicate.resolve(image)
            val checkTypes = ItaniumClass.descendants(image, "N4agui12ToggleButtonE")
            require(checkTypes.isNotEmpty()) { "No concrete check widget layouts were found" }
            val checks = checkTypes.map { WidgetCheckLayout.resolve(image, it, checkPredicate) }
            val sliderFields = WidgetSliderFields.resolve(image)
            val sliderTypes = listOf("N4agui6SliderE") + ItaniumClass.descendants(image, "N4agui6SliderE")
            require(sliderTypes.size <= 64)
            val sliders = sliderTypes.map { WidgetSliderLayout.resolve(image, it, sliderFields) }
            val progressFields = WidgetProgressFields.resolve(image)
            val progressTypes = listOf("N4agui11ProgressBarE") + ItaniumClass.descendants(image, "N4agui11ProgressBarE")
            require(progressTypes.size <= 64)
            val progress = progressTypes.map { WidgetProgressLayout.resolve(image, it, progressFields) }
            val dropdownFields = WidgetDropdownFields.resolve(image)
            val dropdownTypes = listOf("N4agui8DropDownE") + ItaniumClass.descendants(image, "N4agui8DropDownE")
            require(dropdownTypes.size <= 64)
            val dropdowns = dropdownTypes.map { WidgetDropdownLayout.resolve(image, it, dropdownFields) }
            val visible = SysVFlagMutation.resolveBoolean(image, "_ZN4agui6Widget10setVisibleEb", widgetSize)
            val hiddenBySearch =
                SysVFlagMutation.resolveConstant(image, "_ZN4agui6Widget12hideBySearchEv", widgetSize, true)
            require(
                hiddenBySearch == SysVFlagMutation.resolveConstant(
                    image,
                    "_ZN4agui6Widget12showBySearchEv", widgetSize, false
                )
            ) { "Search visibility setters disagree on their flag" }
            require(visible != hiddenBySearch) { "Native visibility flags overlap" }
            val render = WidgetRenderFlag.resolve(image, widgetSize)
            require(listOf(visible, hiddenBySearch, render.field).map { it.offset * 8 + it.shift }
                .distinct().size == 3) {
                "Native render and visibility flags overlap"
            }
            val functions = mutableListOf(rectangle)
            functions += image.symbol("_ZN4agui12ToggleButton14nextCheckStateEv")
            functions += image.symbol("_ZN4agui12ToggleButton19dispatchCheckChangeEv")
            (checkTypes + sliderTypes + progressTypes + dropdownTypes).forEach {
                functions += image.symbol("_ZN${WidgetPropertyType.destructorOwner(it)}D0Ev")
            }
            for (name in listOf(
                "_ZNK4agui8DropDown16getSelectedIndexEv", "_ZNK4agui8DropDown9getItemAtB5cxx11Ei",
                "_ZNK4agui7ListBox12getItemCountEv", "_ZNK4agui7ListBox9getItemAtB5cxx11Ei",
                "_ZN4agui7ListBoxD0Ev"
            )) functions += image.symbol(name)
            for (name in listOf(
                "_ZNK4agui11ProgressBar15getBarRectangleEv",
                "_ZN4agui11ProgressBar14paintComponentERKNS_10PaintEventERKNS_5PointE",
                "_ZN17CustomProgressBar12createWidgetEv"
            )) functions += image.symbol(name)
            for (name in listOf(
                "_ZNK4agui6Slider8getValueEv", "_ZN4agui6Slider11setValueRawEd",
                "_ZNK4agui6Slider12getValueStepEv", "_ZN4agui6Slider12setValueStepEd",
                "_ZN4agui6Slider11setMinValueEd", "_ZN4agui6Slider11setMaxValueEd"
            )) functions += image.symbol(name)
            functions += toggle.function
            functions += toggle.modeFunction
            functions += image.symbol("_ZN4agui6ButtonD0Ev")
            for (name in listOf(
                "_ZN4agui3GuiD0Ev", "_ZN4agui6WidgetD0Ev", "_ZN4agui3Gui3addEPNS_6WidgetE",
                "_ZN4agui3Gui5logicEb", "_ZN4agui6Widget15callRecursivelyERKSt8functionIFvPS0_EE",
                "_ZNK4agui6Widget9isEnabledEv", "_ZNK4agui6Widget23isFlaggedForDestructionEv",
                "_ZN4agui6Widget10setVisibleEb", "_ZN4agui6Widget12hideBySearchEv", "_ZN4agui6Widget12showBySearchEv",
                "_ZNKSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEE4dataEv",
                "_ZNKSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEE4sizeEv"
            )) functions += image.symbol(name)
            for (getter in text.getters) {
                functions += getter.function
                val owner = getter.function.name.removePrefix("_ZNK").removeSuffix("7getTextB5cxx11Ev")
                functions += image.symbol("_ZN${owner}D0Ev")
            }
            return WidgetLayout(
                guiSize, widgetSize, root,
                WidgetChildren.resolve(image, "_ZN4agui6Widget15callRecursivelyERKSt8functionIFvPS0_EE", widgetSize),
                flag("_ZNK4agui6Widget9isEnabledEv"), flag("_ZNK4agui6Widget23isFlaggedForDestructionEv"),
                visible, hiddenBySearch, render, text, toggle, checks, sliders, progress, dropdowns,
                rectangle, rectangleAbi, functions.distinct()
            )
        }
    }
}
