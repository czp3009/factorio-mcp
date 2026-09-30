@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxUiClickConfig
import kotlinx.cinterop.set

/** Both native representations of the same public mouse button, resolved from the selected image. */
internal data class UiClickMetadata(
    val gesture: MouseGestureMetadata,
    val state: MouseStateMetadata,
    val keyboard: KeyboardStateMetadata,
) {
    fun writeTo(output: FmLinuxUiClickConfig, loadBias: Long) {
        gesture.writeTo(output.gesture, loadBias)
        state.writeTo(output.state, loadBias)
        keyboard.writeTo(output.keyboard)
        listOf(SdlButtonAdmission.Button.LEFT, SdlButtonAdmission.Button.RIGHT, SdlButtonAdmission.Button.MIDDLE)
            .forEachIndexed { index, button ->
                output.codes[index] = checkNotNull(state.conversion.admission.table.value(button.sdkValue)).toUInt()
            }
    }

    companion object {
        fun resolve(image: ElfImage, process: ProcessHandle, loadBias: Long): UiClickMetadata {
            val gesture = MouseGestureMetadata.resolve(image).also { it.verifyLoaded(image, process, loadBias) }
            val state = MouseStateMetadata.resolve(image)
            val keyboard =
                KeyboardStateMetadata.resolve(image, state).also { it.verifyLoaded(image, process, loadBias) }
            return UiClickMetadata(gesture, state, keyboard)
        }
    }
}
