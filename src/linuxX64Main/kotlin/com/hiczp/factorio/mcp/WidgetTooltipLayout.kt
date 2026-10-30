@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxTooltipLayout

internal data class WidgetTooltipLayout(
    val base: Long,
    val plain: Long,
    val size: Long,
    val creator: Long,
    val title: Long,
    val text: Long,
    val string: NativeStringLayout,
    val evidence: ElfEvidence,
) {
    fun writeTo(target: FmLinuxTooltipLayout, bias: Long) {
        target.baseType = (base + bias).toULong()
        target.plainType = (plain + bias).toULong()
        target.plainSize = size.toUInt()
        target.creator = creator.toUInt()
        target.title = title.toUInt()
        target.text = text.toUInt()
        target.stringSize = string.size.toUInt()
        target.stringData = string.data.toUInt()
        target.stringLength = string.length.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage, widgetSize: Long): WidgetTooltipLayout {
            val pointers = mutableMapOf<Long, Long>()
            val scalars = mutableMapOf<Long, Long>()
            val (resolved, readonly) =
                image.withReadonlyEvidence {
                    image.withFunctionEvidence {
                        fun type(encoded: String): ItaniumClass {
                            val result = ItaniumClass.resolve(image, encoded)
                            val symbol = image.symbol("_ZTI$encoded")
                            require(symbol.size in listOf(16L, 24L))
                            image.pointers
                                .words(symbol.address, (symbol.size / 8).toInt())
                                .forEachIndexed { i, word ->
                                    pointers[symbol.address + i * 8L] = word.pointer()
                                }
                            return result
                        }
                        val base = type("N4agui18ToolTipCreatorBaseE")
                        val plain = type("N4agui19PlainToolTipCreatorE")
                        val table = ItaniumType.resolve(image, "N4agui19PlainToolTipCreatorE")
                        val tableSymbol = image.symbol("_ZTVN4agui19PlainToolTipCreatorE")
                        image.pointers
                            .words(tableSymbol.address, (tableSymbol.size / 8).toInt())
                            .forEachIndexed { index, word ->
                                if (index == 0) scalars[tableSymbol.address] = word.scalar()
                                else pointers[tableSymbol.address + index * 8L] = word.pointer()
                            }
                        val size = SysVObjectSize.resolve(image, "4agui19PlainToolTipCreator")
                        plain.directBase(base, size, 8)
                        val string = NativeStringLayout.resolve(image)
                        val entry =
                            image.symbol(
                                "_ZN4agui6Widget10setToolTipERKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEE"
                            )
                        val flow = X64ControlFlow.resolve(image, entry)
                        val cast = image.symbol("__dynamic_cast")
                        val casts =
                            flow.instructions.filter {
                                it.offset in flow.reachable &&
                                    it.operation == Operation.CALL &&
                                    it.destination == Immediate(cast.address - entry.address)
                            }
                        val origins = OriginalPointerOrigins(flow)
                        val definitions = ScalarExpression(flow)
                        fun address(site: Long, register: Int, depth: Int = 0): Long {
                            require(depth < 32)
                            val instruction = definitions.definition(site, register)
                            require(instruction.destination == Register(register, 8))
                            val source = instruction.source
                            if (
                                instruction.operation == Operation.MOV &&
                                    source is Register &&
                                    source.width == 8
                            )
                                return address(instruction.offset, source.number, depth + 1)
                            require(
                                instruction.operation == Operation.LEA &&
                                    source is Memory &&
                                    source.relative &&
                                    source.base == null &&
                                    source.index == null
                            )
                            return entry.address +
                                instruction.offset +
                                instruction.size +
                                source.displacement
                        }
                        val call =
                            casts.single {
                                address(it.offset, 6) == base.typeInfo &&
                                    address(it.offset, 2) == plain.typeInfo
                            }
                        val provider =
                            origins.register(call.offset, 7) as? OriginalPointerOrigins.Load
                                ?: error("Tooltip cast has no stored provider")
                        require(
                            provider.base == OriginalPointerOrigins.Argument(7) &&
                                provider.member in 0..widgetSize - 8
                        )
                        val returned = ReturnedPointerOrigins(flow)
                        // The one-string setter compares its stored title with the supplied string
                        // and requires an empty body. Trace those values, not declaration order.
                        val comparisons =
                            flow.instructions.filter {
                                it.offset in flow.reachable &&
                                    it.operation == Operation.CALL &&
                                    (it.destination as? Immediate)?.value?.let { target ->
                                        image.importedFunction(entry.address + target) in
                                            setOf("bcmp", "memcmp")
                                    } == true
                            }
                        val fields =
                            TooltipStringFields.analyze(
                                flow,
                                call.offset,
                                comparisons.map { it.offset }.toSet(),
                                size,
                                string,
                            )
                        val allocation =
                            NativeAllocationResult.find(
                                flow,
                                image.symbol("_Znwm").address - entry.address,
                                size,
                            )
                        require(
                            flow.instructions.any { instruction ->
                                val destination = instruction.destination as? Memory
                                val source = instruction.source as? Register
                                instruction.offset in flow.reachable &&
                                    instruction.operation == Operation.MOV &&
                                    destination?.width == 8 &&
                                    source?.width == 8 &&
                                    returned.address(instruction.offset, destination) ==
                                        ReturnedPointerOrigins.Value(allocation.offset) &&
                                    runCatching { address(instruction.offset, source.number) }
                                        .getOrNull() == table.addressPoint
                            }
                        ) {
                            "Tooltip storage lacks its matching plain-provider vtable"
                        }
                        fields.verifyInitialization(flow, allocation.offset, string)
                        WidgetTooltipLayout(
                            base.typeInfo,
                            plain.typeInfo,
                            size,
                            provider.member,
                            fields.title,
                            fields.text,
                            string,
                            ElfEvidence(emptyList(), emptyList(), emptyMap(), emptyMap()),
                        )
                    }
                }
            return resolved.first.copy(
                evidence = ElfEvidence(resolved.second, readonly, pointers, scalars)
            )
        }
    }
}
