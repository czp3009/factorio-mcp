package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Native getter ABI and reciprocal object members; no borrowed graphics/window instance is retained. */
internal data class GraphicsFrameSize(
    val global: Long,
    val globalSize: Long,
    val globalWindow: Long,
    val getter: ElfImage.Symbol,
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
            val globalSize = SysVGlobalAllocation.resolve(
                image, "_ZN9MainTasks6createERK13ParsedOptions",
                "_ZN13GlobalContextC2ERKN10Filesystem4PathES3_", "global"
            ).size
            // nextEvent's hidden result precedes its Window argument. Reuse the existing verified
            // native poll dispatch instead of inferring argument positions from the symbol spelling.
            val poll = EventPollCall.resolve(image, EventHeader.resolve(image))
            val pumpBytes = image.functionBytes(poll.pump, 32768).slice(0, poll.pumpReturnOffset)
            val pump = X64ControlFlow(X64Instructions(pumpBytes).all(2048))
            val pumpCall = pump.instructions.last()
            require(pumpCall.operation == Operation.CALL)
            val globalWindow = GlobalPointerLoad(pump, poll.pump.address, global.address, globalSize)
                .at(pumpCall.offset, 6)
            val graphics = ItaniumVtable.resolve(image, "_ZTV23GraphicsInterfaceOpenGL")
            val window = ItaniumVtable.resolve(image, "_ZTV9SDLWindow")
            val graphicsSize = SysVObjectSize.resolve(image, "23GraphicsInterfaceOpenGL")
            val windowSize = SysVObjectSize.resolve(image, "9SDLWindow")
            require(
                ItaniumClass.resolve(image, "23GraphicsInterfaceOpenGL").directBase(
                    ItaniumClass.resolve(image, "17GraphicsInterface"), graphicsSize, 8
                ) == 0L
            )
            require(
                ItaniumClass.resolve(image, "9SDLWindow").directBase(
                    ItaniumClass.resolve(image, "6Window"), windowSize, 8
                ) == 0L
            )
            val native = window.method(image, "_ZN9SDLWindow9getWindowEv")
            val nativePointer = SysVAccessors.resolve(image, native.function).withinObject(windowSize)
            require(nativePointer.width == 8 && nativePointer.mask == ULong.MAX_VALUE && nativePointer.shift == 0)
            val getter = image.symbol("_ZN23GraphicsInterfaceOpenGL25getDefaultFramebufferSizeEv")
            EhFrames(image).function(getter)
            val getterBytes = image.functionBytes(getter, 1024)
            val instructions = X64Instructions(getterBytes).all(256)
            val call = instructions.first { it.operation == Operation.CALL }
            val graphicsWindow = SysVMemberCalls.virtualAt(
                getterBytes, getter.address,
                native.slot, graphicsSize, call.offset
            )
            // The normal getter path starts with this proven Window receiver and its native pointer return.
            // Its suffix establishes the scalar return convention, including platform size callbacks.
            GraphicsDrawableLayout.resolve(image)
            val swap = graphics.method(image, "_ZN23GraphicsInterfaceOpenGL11swapBuffersEv")
            val windowSwap = window.method(image, "_ZNK9SDLWindow4swapEv")
            val swapBytes = image.functionBytes(windowSwap.function, 1024)
            val prefix = X64Instructions(swapBytes).all(256).takeWhile { it.operation != Operation.CALL }
            require(prefix.isNotEmpty() && prefix.none {
                it.operation in listOf(
                    Operation.JMP,
                    Operation.JCC,
                    Operation.RET
                )
            })
            val swapCallOffset = prefix.last().let { it.offset + it.size }
            val swapCall = X64Instructions(swapBytes).decode(swapCallOffset)
            require(swapCall.operation == Operation.CALL)
            val windowGraphics = SysVMemberCalls.virtualAt(
                swapBytes.slice(0, swapCall.offset + swapCall.size),
                windowSwap.function.address, swap.slot, windowSize, swapCall.offset
            )
            require(windowGraphics != nativePointer.offset)
            return GraphicsFrameSize(
                global.address, globalSize, globalWindow, getter,
                graphics.addressPoint, graphicsSize, graphicsWindow,
                window.addressPoint, windowSize, windowGraphics, nativePointer.offset
            )
        }
    }
}
