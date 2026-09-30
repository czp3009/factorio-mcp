@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxFrameApiConfig
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxFrameApiEntry

/** Snapshot of proven GLAD slots. The resident must revalidate these values at the render callback. */
internal object FrameApiBinding {
    val names =
        listOf("glGetIntegerv", "glBindFramebuffer", "glBindBuffer", "glPixelStorei", "glReadPixels", "glGetError")

    data class Entry(val storage: Long, val function: Long)

    fun writeTo(entries: Map<String, Entry>, output: FmLinuxFrameApiConfig) {
        require(entries.keys == names.toSet())
        require(entries.values.map { it.storage }.distinct().size == names.size)
        require(entries.values.all { it.storage > 0 && it.storage % 8 == 0L && it.function > 0 })
        fun entry(name: String, target: FmLinuxFrameApiEntry) {
            val source = entries.getValue(name)
            target.storage = source.storage.toULong()
            target.function = source.function.toULong()
        }
        entry("glGetIntegerv", output.getInteger)
        entry("glBindFramebuffer", output.bindFramebuffer)
        entry("glBindBuffer", output.bindBuffer)
        entry("glPixelStorei", output.pixelStore)
        entry("glReadPixels", output.readPixels)
        entry("glGetError", output.getError)
    }

    fun bind(
        slots: Map<String, Long>, bias: Long, mappings: List<ProcMapping>,
        read: (Long, Int) -> ByteArray
    ): Map<String, Entry> {
        require(slots.keys == names.toSet() && slots.values.distinct().size == names.size)
        require(bias >= 0 && bias % 8 == 0L)
        return names.associateWith { name ->
            val slot = slots.getValue(name)
            require(slot > 0 && slot % 8 == 0L && slot <= Long.MAX_VALUE - bias - 8)
            val storage = slot + bias
            require(mappings.any { it.readable && storage >= it.start && storage <= it.end - 8 }) {
                "OpenGL loader slot is not readable: $name"
            }
            val bytes = read(storage, 8)
            require(bytes.size == 8)
            val function = BinaryView(bytes).unsigned(0, 8)
            require(function > 0 && mappings.any { it.executable && function >= it.start && function < it.end }) {
                "OpenGL loader entry is not executable: $name"
            }
            Entry(storage, function)
        }
    }
}
