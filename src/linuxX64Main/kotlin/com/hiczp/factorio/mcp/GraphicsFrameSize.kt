package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation

/**
 * SDL pixel-size API and reciprocal object members; no borrowed graphics/window instance is
 * retained.
 */
internal data class GraphicsFrameSize(
    val global: Long,
    val globalSize: Long,
    val globalWindow: Long,
    val pixels: SdlDynamicApi,
    val graphicsTable: Long,
    val graphicsSize: Long,
    val graphicsWindow: Long,
    val windowTable: Long,
    val windowSize: Long,
    val windowGraphics: Long,
    val nativeWindow: Long,
) {
    companion object {
        fun resolve(image: ElfImage): GraphicsFrameSize {
            val global = image.symbol("global")
            require(global.type == 1 && global.size == 8L)
            val globalSize =
                SysVGlobalAllocation.resolve(
                        image,
                        "_ZN9MainTasks6createERK13ParsedOptions",
                        "_ZN13GlobalContextC2ERKN10Filesystem4PathES3_",
                        "global",
                    )
                    .size
            // nextEvent's hidden result precedes its Window argument. Reuse the existing verified
            // native poll dispatch instead of inferring argument positions from the symbol
            // spelling.
            val poll = EventPollCall.resolve(image, EventHeader.resolve(image))
            val pumpBytes = image.functionBytes(poll.pump, 32768).slice(0, poll.pumpReturnOffset)
            val pump = X64ControlFlow(X64Instructions(pumpBytes).all(2048))
            val pumpCall = pump.instructions.last()
            require(pumpCall.operation == Operation.CALL)
            val globalWindow =
                GlobalPointerLoad(pump, poll.pump.address, global.address, globalSize)
                    .at(pumpCall.offset, 6)
            val graphics = ItaniumVtable.resolve(image, "_ZTV23GraphicsInterfaceOpenGL")
            val window = ItaniumVtable.resolve(image, "_ZTV9SDLWindow")
            val graphicsSize = SysVObjectSize.resolve(image, "23GraphicsInterfaceOpenGL")
            val windowSize = SysVObjectSize.resolve(image, "9SDLWindow")
            require(
                ItaniumClass.resolve(image, "23GraphicsInterfaceOpenGL")
                    .directBase(
                        ItaniumClass.resolve(image, "17GraphicsInterface"),
                        graphicsSize,
                        8,
                    ) == 0L
            )
            require(
                ItaniumClass.resolve(image, "9SDLWindow")
                    .directBase(ItaniumClass.resolve(image, "6Window"), windowSize, 8) == 0L
            )
            val native = window.method(image, "_ZN9SDLWindow9getWindowEv")
            val nativePointer =
                SysVAccessors.resolve(image, native.function).withinObject(windowSize)
            require(
                nativePointer.width == 8 &&
                    nativePointer.mask == ULong.MAX_VALUE &&
                    nativePointer.shift == 0
            )
            val getter = image.symbol("_ZN23GraphicsInterfaceOpenGL25getDefaultFramebufferSizeEv")
            require(getter.size in 1..1024)
            EhFrames(image).function(getter)
            val getterBytes = image.functionBytes(getter, 1024)
            val instructions = X64Instructions(getterBytes).all(256)
            val pixels = SdlDynamicApi.resolve(image, "SDL_GetWindowSizeInPixels", listOf(7, 6, 2))
            val flow = X64ControlFlow(instructions)
            val call =
                SdlDynamicApi.calls(flow, getter.address, pixels.slot).singleOrNull()
                    ?: error("Framebuffer getter has no unique SDL pixel-size call")
            val graphicsWindow =
                GraphicsSwapCall.windowMember(flow, call.offset, native.slot, graphicsSize)
            val locals = SysVLocalArgument(flow)
            val width = locals.argument(call.offset, 6, 4)
            val height = locals.argument(call.offset, 2, 4)
            require(width + 4 <= height || height + 4 <= width) { "SDL dimensions alias" }
            val swap = graphics.method(image, "_ZN23GraphicsInterfaceOpenGL11swapBuffersEv")
            val windowSwap = window.method(image, "_ZNK9SDLWindow4swapEv")
            require(windowSwap.function.size in 1..1024)
            val swapBytes = image.functionBytes(windowSwap.function, 1024)
            val swapBody = X64Instructions(swapBytes).all(256)
            val dispatch =
                swapBody.single { instruction ->
                    val target = instruction.destination as? X64Instructions.Memory
                    instruction.operation == Operation.CALL &&
                        target != null &&
                        !target.relative &&
                        target.index == null &&
                        target.width == 8 &&
                        target.displacement == swap.slot * 8L
                }
            // Only a type/member association is needed here. Establish it on the straight entry
            // path; the unrelated post-presentation virtual tail does not define this member.
            require(
                swapBody
                    .takeWhile { it.offset < dispatch.offset }
                    .none {
                        it.operation in
                            setOf(Operation.JMP, Operation.JCC, Operation.RET, Operation.TRAP)
                    }
            )
            val windowGraphics =
                SysVMemberCalls.virtualAt(
                    swapBytes.slice(0, dispatch.offset + dispatch.size),
                    windowSwap.function.address,
                    swap.slot,
                    windowSize,
                    dispatch.offset,
                )
            require(windowGraphics != nativePointer.offset)
            return GraphicsFrameSize(
                global.address,
                globalSize,
                globalWindow,
                pixels,
                graphics.addressPoint,
                graphicsSize,
                graphicsWindow,
                window.addressPoint,
                windowSize,
                windowGraphics,
                nativePointer.offset,
            )
        }
    }
}
