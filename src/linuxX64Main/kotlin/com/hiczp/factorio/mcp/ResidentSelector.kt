@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FM_LINUX_UI_PATH
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxUiSelector
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.get
import kotlinx.cinterop.set

/** Writes only typed selector data while the caller owns the resident command payload. */
internal fun FmLinuxUiSelector.writePath(path: List<UiStep>?) {
    count = 0u
    if (path.isNullOrEmpty()) return
    require(path.size in 1..FM_LINUX_UI_PATH)
    fun text(destination: CPointer<ByteVar>, capacity: Int, value: String?) {
        val bytes = value.orEmpty().encodeToByteArray()
        require(bytes.size < capacity && '\u0000' !in value.orEmpty()) { "Selector string exceeds native bounds" }
        bytes.forEachIndexed { index, byte -> destination[index] = byte }
        destination[bytes.size] = 0
    }
    path.forEachIndexed { index, step ->
        require(step.position >= 0)
        val output = this.path[index]
        output.child = if (step.child) 1 else 0
        output.position = step.position
        output.enabled = step.enabled?.let { if (it) 1 else 0 } ?: -1
        output.hasText = if (step.text == null) 0u else 1u
        output.hasVisible = if (step.visible == null) 0u else 1u
        output.visible = if (step.visible == true) 1u else 0u
        text(output.type, 160, step.type)
        text(output.text, 1024, step.text)
        text(output.prototypeName, 256, step.prototype?.name)
        text(output.prototypeType, 160, step.prototype?.nativeType)
    }
    count = path.size.toUInt()
}
