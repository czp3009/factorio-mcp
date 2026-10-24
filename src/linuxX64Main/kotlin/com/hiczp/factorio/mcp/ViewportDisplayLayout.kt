package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Pointer
import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * The normal renderer's current framebuffer, independently associated with its typed owner and
 * bounds.
 */
internal data class ViewportDisplayLayout(
    val renderer: Long,
    val rendererSize: Long,
    val framebufferReference: Long,
    val width: ItaniumVtable.Method,
    val height: ItaniumVtable.Method,
) {
    companion object {
        fun resolve(image: ElfImage, viewSize: Long): ViewportDisplayLayout {
            val rendererSize =
                SysVOwnedObjectSize.resolve(
                        image,
                        "_ZN30GarbageCollectableGameRenderer19CollectableRendererD2Ev",
                        "_ZN12GameRendererD2Ev",
                        ownerPrimaryTable =
                            "_ZTVN30GarbageCollectableGameRenderer19CollectableRendererE",
                    )
                    .size
            val renderer =
                SysVMemberCalls.direct(image, "_ZN8GameViewD2Ev", "_ZN12GameRendererD2Ev", viewSize)
            val table = ItaniumVtable.resolve(image, "_ZTV11Framebuffer")
            val width = table.method(image, "_ZNK11Framebuffer5widthEv")
            val height = table.method(image, "_ZNK11Framebuffer6heightEv")
            // Follow the projection's actual framebuffer reads; the display helper can be inlined
            // away.
            val function = image.symbol("_ZNK8GameView17getScreenPositionE11MapPosition")
            EhFrames(image).function(function)
            val field =
                analyze(
                    image.functionBytes(function, 4096),
                    function.address,
                    viewSize,
                    renderer,
                    rendererSize,
                    width.slot,
                    height.slot,
                )
            return ViewportDisplayLayout(renderer, rendererSize, field, width, height)
        }

        fun analyze(
            bytes: BinaryView,
            address: Long,
            viewSize: Long,
            renderer: Long,
            rendererSize: Long,
            widthSlot: Int,
            heightSlot: Int,
        ): Long {
            require(
                renderer in 0..viewSize - 8 &&
                    renderer % 8 == 0L &&
                    rendererSize in 8..64 * 1024 * 1024
            )
            require(widthSlot in 0..8191 && heightSlot in 0..8191 && widthSlot != heightSlot)
            val flow = SysVReceiverFlow(bytes, address, viewSize)
            fun reference(slot: Int): Long {
                val calls =
                    flow.instructions.filter { instruction ->
                        val target = instruction.destination as? Memory
                        instruction.operation == Operation.CALL &&
                            target != null &&
                            target.width == 8 &&
                            !target.relative &&
                            target.index == null &&
                            target.displacement == slot * 8L
                    }
                val fields =
                    calls
                        .mapNotNull { call ->
                            val registers = flow.call(call.offset)
                            val receiver = registers[7] as? Pointer ?: return@mapNotNull null
                            val reference = receiver.base as? Pointer ?: return@mapNotNull null
                            val owner = reference.base as? Pointer ?: return@mapNotNull null
                            val target = call.destination as Memory
                            val dispatchTable = target.base?.let { registers[it] } as? Pointer
                            if (
                                receiver.offset != 0L ||
                                    owner.base != Receiver() ||
                                    owner.offset != renderer ||
                                    reference.offset !in 0..rendererSize - 8 ||
                                    reference.offset % 8 != 0L ||
                                    dispatchTable?.base != receiver ||
                                    dispatchTable.offset != 0L
                            )
                                return@mapNotNull null
                            reference.offset
                        }
                        .distinct()
                return fields.singleOrNull()
                    ?: error(
                        "Framebuffer virtual dispatch has no unique bounded GameRenderer reference"
                    )
            }
            val width = reference(widthSlot)
            require(reference(heightSlot) == width) {
                "Framebuffer dimensions use different references"
            }
            return width
        }
    }
}
