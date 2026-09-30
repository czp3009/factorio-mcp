@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxUiTextConfig
import kotlinx.cinterop.set

/** Native text editing events, lifetime references and typed dispatch entries from the selected executable. */
internal data class UiTextMetadata(
    val core: Core,
    val capture: UiCaptureMetadata,
    val modal: UiModalMetadata,
) {
    data class Core(
        val fields: KeyEventFields,
        val defaults: Map<Long, Int>,
        val selectAll: KeyInputConversion.Value,
        val backspace: KeyInputConversion.Value,
        val clock: InputClockBinding,
        val cast: Long,
        val widgetType: Long,
        val textBoxType: Long,
        val focus: Long,
        val keyDown: Long,
        val functions: List<ElfImage.Symbol> = emptyList(),
        val readonly: List<ElfImage.ReadonlyRange> = emptyList(),
    )

    init {
        require(
            core.clock.guiSize == capture.guiSize && capture.guiSize == modal.guiSize &&
                    capture.widgetSize == modal.widgetSize && capture.widgetTargetable == modal.widgetTargetable
        )
        require(core.selectAll.extended == core.backspace.extended)
        require(
            core.fields.construction.extent in 1..256 &&
                    core.defaults.all { (offset, value) -> offset in 0 until core.fields.construction.extent && value in 0..255 })
    }

    fun verifyLoaded(image: ElfImage, process: ProcessHandle, bias: Long) = verify(image, bias, process::readMemory)

    internal fun verify(image: ElfImage, bias: Long, read: (Long, Int) -> ByteArray) {
        require(bias >= 0)
        fun compare(address: Long, bytes: ByteArray) {
            require(
                bytes.isNotEmpty() && bytes.size <= 16 * 1024 * 1024 && address > 0 &&
                        address <= Long.MAX_VALUE - bias - bytes.size
            )
            require(read(address + bias, bytes.size).contentEquals(bytes)) {
                "Live text editing evidence differs from the selected executable"
            }
        }
        for (function in core.functions) {
            require(function.size in 1..16 * 1024 * 1024)
            compare(
                function.address,
                image.functionBytes(function, function.size.toInt()).bytes(0, function.size.toInt())
            )
        }
        for (range in core.readonly) {
            require(range.size in 1..16 * 1024 * 1024)
            compare(range.address, image.virtualBytes(range.address, range.size).bytes(0, range.size.toInt()))
        }
        capture.verify(image, bias, read)
        modal.verify(image, bias, read)
    }

    fun writeTo(output: FmLinuxUiTextConfig, bias: Long) {
        fun address(value: Long): ULong {
            require(bias >= 0 && value > 0 && value < Long.MAX_VALUE - bias)
            return (value + bias).toULong()
        }

        val fields = core.fields
        val event = output.event
        event.extent = fields.construction.extent.toUInt()
        event.key = fields.key.toUInt()
        event.extended = fields.extended.toUInt()
        event.character = fields.character.toUInt()
        event.control = fields.control.toUInt()
        event.source = fields.source.toUInt()
        event.time = fields.time.toUInt()
        event.none = (0 until 4).fold(0u) { value, byte ->
            value or (core.defaults.getValue(fields.key + byte).toUInt() shl (byte * 8))
        }
        event.selectAll = core.selectAll.key.toUInt()
        event.backspace = core.backspace.key.toUInt()
        event.extendedNone = core.selectAll.extended.toUInt()
        require(setOf(event.none, event.selectAll, event.backspace).size == 3)
        for (offset in 0 until 256) event.defaults[offset] = (core.defaults[offset.toLong()] ?: 0).toUByte()
        core.clock.writeTo(output.clock, bias)
        capture.writeTo(output.capture, bias)
        modal.writeTo(output.modal)
        output.dynamicCast = address(core.cast)
        output.widgetType = address(core.widgetType)
        output.textBoxType = address(core.textBoxType)
        output.focus = address(core.focus)
        output.keyDown = address(core.keyDown)
    }

    companion object {
        fun resolve(image: ElfImage): UiTextMetadata {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val widgetSize = SysVObjectSize.resolve(image, "4agui6Widget")
                    val focus = WidgetFocusEntry.verify(image, widgetSize)
                    val textBoxSize = SysVObjectSize.resolve(image, "4agui7TextBox")
                    val table = ItaniumVtable.resolve(image, "_ZTVN4agui7TextBoxE")
                    val handler = table.method(image, "_ZN4agui7TextBox14handleKeyboardERKNS_8KeyEventE")
                    val keyDown = image.symbol("_ZN4agui7TextBox7keyDownERKNS_8KeyEventE")
                    WidgetEventDispatch.verify(image, keyDown, textBoxSize, handler)
                    val fields = KeyEventFields.resolve(image)
                    val defaults = fields.defaults(image)
                    val clock = InputClockBinding.resolve(image)
                    val input = KeyInputFields.resolve(image, fields, clock.member)
                    // SDL_Keycode uses ASCII for these documented printable/control keys. The values
                    // enter the game's named keycode conversion and its actual queued output fields.
                    val keys = KeyInputConversion.resolve(image, input, setOf(8, 97))
                    val cast = image.symbol("__dynamic_cast")
                    EhFrames(image).function(cast)
                    image.functionBytes(cast, 4096)
                    Core(
                        fields, defaults, keys.getValue(97), keys.getValue(8), clock, cast.address,
                        ItaniumClass.resolve(image, "N4agui6WidgetE").typeInfo,
                        ItaniumClass.resolve(image, "N4agui7TextBoxE").typeInfo, focus.address, keyDown.address
                    )
                }
            }
            return UiTextMetadata(
                resolved.first.copy(functions = resolved.second, readonly = readonly),
                UiCaptureMetadata.resolve(image), UiModalMetadata.resolve(image)
            )
        }
    }
}
