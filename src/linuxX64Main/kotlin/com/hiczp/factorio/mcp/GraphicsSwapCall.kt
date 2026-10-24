package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * The game's presentation call with its native-window argument established from the typed getter.
 */
internal data class GraphicsSwapCall(val api: SdlDynamicApi, val caller: Long) {
    companion object {
        fun resolve(image: ElfImage, size: GraphicsFrameSize): GraphicsSwapCall {
            val api = SdlDynamicApi.resolve(image, "SDL_GL_SwapWindow", listOf(7))
            val function = image.symbol("_ZN23GraphicsInterfaceOpenGL11swapBuffersEv")
            require(function.size in 1..16384) { "Presentation caller exceeds complete-function bounds" }
            EhFrames(image).function(function)
            val body =
                X64Instructions(
                        image.functionBytes(function, 16384),
                        allowUnsignedWideMultiply = true,
                    )
                    .all(4096)
            val tables =
                X64JumpTables.resolve(body, function.address) { address, length ->
                    require(
                        image.sections.any {
                            it.flags and 3L == 2L &&
                                address >= it.address &&
                                length <= it.size &&
                                address - it.address <= it.size - length
                        }
                    )
                    image.virtualBytes(address, length)
                }
            val flow = X64ControlFlow(body, tables)
            val native =
                ItaniumVtable.resolve(image, "_ZTV9SDLWindow")
                    .method(image, "_ZN9SDLWindow9getWindowEv")
            val call =
                SdlDynamicApi.calls(flow, function.address, api.slot).singleOrNull()
                    ?: error("SDL presentation call is absent or ambiguous")
            require(
                windowMember(flow, call.offset, native.slot, size.graphicsSize) ==
                    size.graphicsWindow
            )
            return GraphicsSwapCall(api, function.address + call.offset + call.size)
        }

        /**
         * Shared by presentation and pixel-size observation; no call ordinal or adjacent setup is
         * assumed.
         */
        fun windowMember(
            flow: X64ControlFlow,
            call: Long,
            nativeSlot: Int,
            graphicsSize: Long,
        ): Long {
            val result =
                ReturnedPointerOrigins(flow).register(call, 7)
                    ?: error("SDL window does not originate from a native getter")
            require(result.members.isEmpty() && result.adjustment == 0L)
            val getter = flow.body.getValue(result.call)
            val target =
                getter.destination as? Memory ?: error("Native window getter is not virtual")
            require(
                getter.operation == Operation.CALL &&
                    !target.relative &&
                    target.index == null &&
                    target.width == 8 &&
                    target.displacement == nativeSlot * 8L
            )
            val origins = OriginalPointerOrigins(flow)
            val receiver =
                origins.register(getter.offset, 7) as? OriginalPointerOrigins.Load
                    ?: error("Native getter does not use a graphics member")
            val table =
                target.base?.let { origins.register(getter.offset, it) }
                    as? OriginalPointerOrigins.Load
            require(
                receiver.base == OriginalPointerOrigins.Argument(7) &&
                    receiver.member in 8..graphicsSize - 8 &&
                    receiver.member % 8 == 0L &&
                    table?.base == receiver &&
                    table.member == 0L
            )
            return receiver.member
        }
    }
}
