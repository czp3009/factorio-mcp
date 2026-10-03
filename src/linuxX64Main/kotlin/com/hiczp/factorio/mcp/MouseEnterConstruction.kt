@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxMouseEventLayout
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxMouseGestureLayout

/** Native enter construction retains the actual previous GUI target; it is not an invented zero default. */
internal data class MouseEnterConstruction(
    val template: MouseEventConstruction.Template,
    val guiSize: Long,
    val guiPrevious: Long,
    val widgetTargetable: Long,
    val literals: List<LocalAggregate.LiteralRange>,
    private val functions: List<ElfImage.Symbol> = emptyList(),
) {
    fun verifyLoaded(image: ElfImage, process: ProcessHandle, loadBias: Long) {
        verify(image, loadBias, process::readMemory)
    }

    internal fun verify(image: ElfImage, loadBias: Long, read: (Long, Int) -> ByteArray) {
        require(loadBias >= 0)
        fun compare(address: Long, expected: ByteArray) {
            require(
                expected.size in 1..(16 * 1024 * 1024) && address >= 0 &&
                        address <= Long.MAX_VALUE - loadBias - expected.size
            )
            require(read(address + loadBias, expected.size).contentEquals(expected)) {
                "Live enter-construction evidence differs from the selected executable"
            }
        }
        for (function in functions) {
            require(function.size in 1..(16 * 1024 * 1024))
            compare(
                function.address,
                image.functionBytes(function, function.size.toInt()).bytes(0, function.size.toInt())
            )
        }
        for (literal in literals) compare(literal.address, literal.bytes.map { it.toByte() }.toByteArray())
    }

    /** Add the independently resolved previous-widget field after writing the common event fields. */
    fun writeTo(event: FmLinuxMouseEventLayout, gesture: FmLinuxMouseGestureLayout) {
        val previous = checkNotNull(template.previous)
        require(previous >= 0 && previous <= event.size.toLong() - 8 && template.type in 0..UInt.MAX_VALUE.toLong())
        require(guiPrevious in 0..guiSize - 8 && widgetTargetable in 0..UInt.MAX_VALUE.toLong())
        event.hasPrevious = 1U
        event.previous = previous.toUInt()
        gesture.enterType = template.type.toUInt()
        gesture.previousTarget = guiPrevious.toUInt()
        gesture.widgetTargetable = widgetTargetable.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage, fields: MouseEventFields): MouseEnterConstruction {
            val (resolved, functions) = image.withFunctionEvidence { resolveLayout(image, fields) }
            return resolved.copy(functions = functions)
        }

        private fun resolveLayout(image: ElfImage, fields: MouseEventFields): MouseEnterConstruction {
            val function = image.symbol("_ZN4agui3Gui29orderToUpdateWidgetUnderMouseEv")
            val flow = X64ControlFlow.resolve(image, function)
            val constructors = image.inlines.find(function, "orderToUpdateWidgetUnderMouse", setOf("MouseEvent"))
            val widgetSize = SysVObjectSize.resolve(image, "4agui6Widget")
            val guiSize = SysVObjectSize.resolve(image, "4agui3Gui")
            val targetable = SysVTargeterRelease.resolve(
                image,
                "_ZN4agui19GenericTargeterBase8attachToEPNS_17GenericTargetableE"
            ).targetableExtent
            val base = ItaniumClass.resolve(image, "N4agui6WidgetE").directBase(
                ItaniumClass.resolve(image, "N4agui17GenericTargetableE"), widgetSize, targetable
            )
            val table = ItaniumVtable.resolve(image, "_ZTVN4agui6WidgetE")
            val entries = listOf("18dispatchMouseEnter" to "10mouseEnter", "18dispatchMouseLeave" to "10mouseLeave")
                .associate { (entry, method) ->
                    val symbol = image.symbol("_ZN4agui6Widget${entry}ERKNS_10MouseEventE")
                    WidgetEventDispatch.verify(
                        image, symbol, widgetSize,
                        table.method(image, "_ZN4agui6Widget${method}ERKNS_10MouseEventE")
                    )
                    entry to symbol.address
                }
            val calls = flow.instructions.filter {
                it.operation == Operation.CALL &&
                        (it.destination as? Immediate)?.value?.plus(function.address) in entries.values
            }
            val values = ConstructorValues(flow, calls.associate {
                it.offset to listOf(ConstructorValues.Borrow(6, fields.extent))
            })
            val aggregate = LocalAggregate.resolve(image, function)
            val enterCalls = calls.filter {
                (it.destination as Immediate).value + function.address == entries.getValue("18dispatchMouseEnter")
            }
            require(enterCalls.isNotEmpty()) { "No native enter construction site" }
            val results = enterCalls.map { call ->
                val constructor =
                    constructors.filter { it.ranges.maxOf { range -> range.end } <= function.address + call.offset }
                        .maxByOrNull { it.ranges.maxOf { range -> range.end } }
                        ?: error("Enter dispatch has no preceding inline constructor")
                val proof = aggregate.constructedAt(call.offset, fields.extent, constructor.ranges.map {
                    DwarfRanges.Range(it.start - function.address, it.end - function.address)
                })
                val candidates =
                    proof.written.filter { it.width == 8 && it.offset != fields.source }.mapNotNull { field ->
                        val value =
                            values.field(call.offset, fields.extent, field.offset) as? ConstructorValues.Nullable
                                ?: return@mapNotNull null
                        val load = value.base as? ConstructorValues.Load ?: return@mapNotNull null
                        val gui = load.base as? ConstructorValues.Argument ?: return@mapNotNull null
                        if (gui.register != 7 || value.adjustment != -base) return@mapNotNull null
                        val member = gui.adjustment + load.member
                        require(member >= 0 && member <= guiSize - 8 && member % 8 == 0L)
                        field.offset to member
                    }
                val (previous, member) = candidates.distinct().singleOrNull()
                    ?: error(
                        "Enter event has no unique nullable previous GUI widget at ${call.offset}: " +
                            proof.written.filter { it.width == 8 }.associate { field ->
                                field.offset to values.field(call.offset, fields.extent, field.offset)
                            })
                MouseEnterConstruction(
                    MouseEventConstruction.crossCheck(fields, proof, previous), guiSize,
                    member, base, proof.literals
                )
            }
            val combined = results.map { it.copy(literals = emptyList()) }.distinct().singleOrNull()
                ?: error("Native enter construction sites disagree")
            return combined.copy(literals = results.flatMap { it.literals }.distinct())
        }
    }
}
