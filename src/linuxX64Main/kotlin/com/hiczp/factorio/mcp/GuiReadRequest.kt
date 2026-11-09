@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.*
import kotlinx.cinterop.set

internal data class GuiReadRequest(
    val world: WorldQueryMetadata?,
    val registry: GuiRegistryLayout?,
    val arguments: ByteArray,
    val failure: String? = null,
) {
    fun payload(action: UiAction?) = RequestPayload(
        selectorPayload(action?.path.orEmpty()),
        if (world != null && registry != null) guiObservationLua.encodeToByteArray() else byteArrayOf(),
        if (world != null && registry != null) arguments else byteArrayOf(),
    )

    fun writeTo(shared: FmLinuxShared, loadBias: Long, payload: RequestPayload) {
        shared.guiEnabled = 0u
        if (world == null || registry == null) return
        world.writeTo(shared.worldConfig, loadBias)
        registry.writeTo(shared.guiRegistry)
        shared.query.source.bindRequest(shared.request, payload.source)
        shared.query.arguments.bindRequest(shared.request, payload.arguments)
        shared.query.includeViewport = 0u
        shared.guiEnabled = 1u
    }
}
