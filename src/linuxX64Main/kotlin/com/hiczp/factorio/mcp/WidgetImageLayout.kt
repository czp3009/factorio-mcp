@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import com.hiczp.factorio.mcp.linuxbridge.FmUiImageLayout

/** Retained image identity, with a direct field read for static sprites only. */
internal data class WidgetImageLayout(
    val type: Long,
    val imageType: Long,
    val staticType: Long,
    val widgetSize: Long,
    val image: Long,
    val staticSize: Long,
    val sprite: Long,
    val evidence: ElfEvidence,
) {
    fun writeTo(output: FmUiImageLayout, bias: Long) {
        require(bias >= 0 && maxOf(type, imageType, staticType) <= Long.MAX_VALUE - bias)
        output.type = (type + bias).toULong()
        output.imageType = (imageType + bias).toULong()
        output.staticType = (staticType + bias).toULong()
        output.supported = 1u
        output.widgetSize = widgetSize.toUInt()
        output.image = image.toUInt()
        // Only the polymorphic base's ABI vfptr is read, not an assumed complete base layout.
        output.imageSize = 8u
        output.staticSize = staticSize.toUInt()
        output.sprite = sprite.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage): WidgetImageLayout {
            val pointers = mutableMapOf<Long, Long>()
            val scalars = mutableMapOf<Long, Long>()
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    fun type(encoded: String): ItaniumClass {
                        val result = ItaniumClass.resolve(image, encoded)
                        val symbol = image.symbol("_ZTI$encoded")
                        require(symbol.size in listOf(16L, 24L))
                        image.pointers.words(symbol.address, (symbol.size / 8).toInt()).forEachIndexed { index, word ->
                            pointers[symbol.address + index * 8L] = word.pointer()
                        }
                        return result
                    }
                    val widget = type("N4agui11ImageWidgetE")
                    val base = type("N4agui5ImageE")
                    val factorio = type("13FactorioImage")
                    val plain = type("8GuiImage")
                    val widgetSize = SysVObjectSize.resolve(image, "4agui11ImageWidget")
                    val staticSize = SysVObjectSize.resolve(image, "8GuiImage")
                    require(widgetSize in 8..16 * 1024 * 1024 && staticSize in 8..65536)
                    require(factorio.directBase(base, staticSize, 8) == 0L)
                    require(plain.directBase(factorio, staticSize, 8) == 0L)
                    val table = ItaniumVtable.resolve(image, "_ZTV8GuiImage")
                    val tableSymbol = image.symbol("_ZTV8GuiImage")
                    image.pointers.words(tableSymbol.address, (tableSymbol.size / 8).toInt()).forEachIndexed { index, word ->
                        if (index == 0) scalars[tableSymbol.address] = word.scalar()
                        else pointers[tableSymbol.address + index * 8L] = word.pointer()
                    }
                    val getter = table.method(image, "_ZNK8GuiImage9getSpriteEv")
                    val sprite = SysVAccessors.resolve(image, getter.function).withinObject(staticSize)
                    require(sprite.width == 8 && sprite.mask == ULong.MAX_VALUE && sprite.shift == 0)
                    val clear = image.symbol("_ZN4agui11ImageWidget5clearEv")
                    EhFrames(image).function(clear)
                    val member = ImagePointerMember.analyze(
                        X64ControlFlow.resolve(image, clear),
                        image.functionBytes(clear, 8192),
                        clear.address,
                        widgetSize,
                        table.method(image, "_ZN8GuiImageD0Ev").slot,
                    )
                    WidgetImageLayout(
                        widget.typeInfo, base.typeInfo, plain.typeInfo, widgetSize, member, staticSize, sprite.offset,
                        ElfEvidence(emptyList(), emptyList(), pointers, scalars),
                    )
                }
            }
            return resolved.first.copy(evidence = resolved.first.evidence.copy(functions = resolved.second, readonly = readonly))
        }
    }
}

/** The typed clear dispatches image deletion through, and resets, the same retained pointer. */
internal object ImagePointerMember {
    fun analyze(
        flow: X64ControlFlow,
        bytes: BinaryView,
        address: Long,
        size: Long,
        deleteSlot: Int,
    ): Long {
        require(size in 8..16 * 1024 * 1024)
        val origins = OriginalPointerOrigins(flow)
        val fields = flow.instructions.mapNotNull { instruction ->
            if (instruction.offset !in flow.reachable || instruction.operation != Operation.MOV ||
                instruction.source != Immediate(0)) return@mapNotNull null
            val target = instruction.destination as? Memory ?: return@mapNotNull null
            if (target.relative || target.index != null || target.width != 8) return@mapNotNull null
            val receiver = target.base?.let { origins.register(instruction.offset, it) } as? OriginalPointerOrigins.Argument
                ?: return@mapNotNull null
            if (receiver.register != 7) return@mapNotNull null
            (receiver.adjustment + target.displacement).also {
                require(it in 8..size - 8 && it % 8 == 0L) { "Image member exceeds its typed widget" }
            }
        }.distinct()
        val values = SysVReceiverFlow(bytes, address, size)
        val receivers = flow.instructions.filter {
            it.offset in flow.reachable && SysVVirtualCall.isCandidate(it, deleteSlot)
        }.mapNotNull { call -> SysVVirtualCall.receiver(call, values.borrowedCallValues(call.offset), deleteSlot) }
        require(receivers.isNotEmpty()) { "Image clear has no verified image deletion dispatch" }
        return receivers.map { receiver ->
            require(receiver is SysVReceiverFlow.Pointer && receiver.base == SysVReceiverFlow.Receiver() &&
                receiver.offset in fields) { "Image clear does not reset its deleted image pointer" }
            receiver.offset
        }.distinct().single()
    }
}
