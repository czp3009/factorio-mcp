@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxMouseStateConfig
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxMouseStateLayout
import kotlinx.cinterop.set

/** Resolved adapter data. Calling entries still requires loaded-image evidence and complete gesture admission. */
internal data class MouseStateMetadata(
    val state: InputStateLayout,
    val conversion: SdlButtonConversion,
    val update: InputStateMouseUpdate,
    val postPaths: Map<Long, List<Long>>,
    val copyCases: Map<Long, EventCopyCases.Proof>,
    val updateUses: Map<Long, InputEventUses.Proof>,
    val updateEntry: Long,
    val postEntry: Long,
    private val functions: List<ElfImage.Symbol>,
    private val readonly: List<ElfImage.ReadonlyRange>,
) {
    fun verifyLoaded(image: ElfImage, process: ProcessHandle, loadBias: Long) {
        verify(image, loadBias, process::readMemory)
    }

    internal fun verify(image: ElfImage, loadBias: Long, read: (Long, Int) -> ByteArray) {
        require(loadBias >= 0)
        fun compare(address: Long, size: Long, expected: BinaryView) {
            require(size in 1..(16 * 1024 * 1024) && address >= 0 && address <= Long.MAX_VALUE - loadBias - size)
            require(read(address + loadBias, size.toInt()).contentEquals(expected.bytes(0, size.toInt()))) {
                "Live mouse-state evidence differs from the selected executable"
            }
        }
        for (function in functions)
            compare(function.address, function.size, image.functionBytes(function, function.size.toInt()))
        for (range in readonly)
            compare(range.address, range.size, image.virtualBytes(range.address, range.size))
    }

    fun writeTo(output: FmLinuxMouseStateLayout, loadBias: Long) {
        require(loadBias >= 0 && state.global <= Long.MAX_VALUE - 8 - loadBias)
        output.global = (state.global + loadBias).toULong()
        output.globalSize = state.globalSize.toUInt()
        output.stateMember = state.member.toUInt()
        output.stateSize = state.size.toUInt()
        output.heldMask = update.member.toUInt()
        output.eventSize = conversion.header.extent.toUInt()
        output.eventType = conversion.header.type.toUInt()
        output.eventTime = conversion.header.time.toUInt()
        output.eventCode = conversion.payload.code.toUInt()
        output.press = conversion.kinds.getValue(SdlButtonAdmission.Transition.PRESS).toUInt()
        output.release = conversion.kinds.getValue(SdlButtonAdmission.Transition.RELEASE).toUInt()
        SdlButtonAdmission.Button.entries.forEachIndexed { index, button ->
            output.codes[index] = checkNotNull(conversion.admission.table.value(button.sdkValue)).toUInt()
            output.masks[index] = update.masks.getValue(button).toUInt()
        }
    }

    fun writeTo(output: FmLinuxMouseStateConfig, loadBias: Long) {
        require(
            loadBias >= 0 && updateEntry > 0 && postEntry > 0 &&
                    updateEntry < Long.MAX_VALUE - loadBias && postEntry < Long.MAX_VALUE - loadBias
        )
        writeTo(output.layout, loadBias)
        output.update = (updateEntry + loadBias).toULong()
        output.postUpdate = (postEntry + loadBias).toULong()
    }

    companion object {
        fun resolve(image: ElfImage): MouseStateMetadata {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val conversion = SdlButtonConversion.resolve(image)
                    val state = InputStateLayout.resolve(image, MouseInputLayout.resolve(image))
                    MouseStateMetadata(
                        state, conversion, InputStateMouseUpdate.resolve(image, state, conversion),
                        MousePostUpdate.resolve(image, conversion), EventCopyCases.resolve(image, conversion),
                        InputEventUses.resolve(image, conversion),
                        image.symbol("_ZN10InputState6updateERK5Event").address,
                        image.symbol("_ZN10InputState10postUpdateERK5Event").address,
                        emptyList(), emptyList()
                    )
                }
            }
            return resolved.first.copy(functions = resolved.second, readonly = readonly)
        }
    }
}
