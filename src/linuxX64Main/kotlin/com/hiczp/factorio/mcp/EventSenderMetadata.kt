@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxEventReceiverLayout

/** Selected sender structure and loaded-code evidence. Does not authorize calling the discovered entries:
 * event payload, entry ABI, execution phase and live receiver lifetimes are independent requirements.
 */
internal data class EventSenderMetadata(
    val state: InputStateLayout,
    val source: InputSourceLayout,
    val sourceDispatch: EventSenderStateCalls.SourceDispatch,
    val guiDispatch: EventSenderStateCalls.GuiDispatch,
    val orders: Map<Long, EventSenderStateCalls.UpdateOrder>,
    val sourceEvent: ItaniumVtable.Method,
    val guiLogic: ItaniumVtable.Method,
    val guiEvent: ElfImage.Symbol,
    val update: ElfImage.Symbol,
    val postUpdate: ElfImage.Symbol,
    val guiInstance: Long,
    val guiSize: Long,
    val guiType: ItaniumType,
    val handlerSize: Long,
    val handlerType: ItaniumType,
    private val functions: List<ElfImage.Symbol>,
    private val readonly: List<ElfImage.ReadonlyRange>,
    private val pointers: Map<Long, Long>,
    private val scalars: Map<Long, Long>,
) {
    fun writeTo(output: FmLinuxEventReceiverLayout, bias: Long) {
        fun address(value: Long): ULong {
            require(bias >= 0 && bias % 8 == 0L && value > 0 && value <= Long.MAX_VALUE - bias)
            return (value + bias).toULong()
        }
        output.global = address(state.global)
        output.globalSize = state.globalSize.toUInt()
        output.stateMember = state.member.toUInt()
        output.stateSize = state.size.toUInt()
        output.guiInstance = address(guiInstance)
        output.guiSize = guiSize.toUInt()
        output.guiHandler = guiDispatch.handlerMember.toUInt()
        output.guiVtable = address(guiType.addressPoint)
        output.guiTypeInfo = address(guiType.typeInfo)
        output.handlerSize = handlerSize.toUInt()
        output.handlerVtable = address(handlerType.addressPoint)
        output.handlerTypeInfo = address(handlerType.typeInfo)
    }

    fun verifyLoaded(image: ElfImage, process: ProcessHandle, bias: Long) = verify(image, bias, process::readMemory)

    internal fun verify(image: ElfImage, bias: Long, read: (Long, Int) -> ByteArray) {
        require(bias >= 0 && bias % 8 == 0L)
        fun relocated(target: Long): Long {
            require(target >= 0 && target <= Long.MAX_VALUE - bias)
            return if (target == 0L) 0 else target + bias
        }

        fun compare(address: Long, expected: ByteArray) {
            require(
                expected.isNotEmpty() && expected.size <= 16 * 1024 * 1024 &&
                        address > 0 && address <= Long.MAX_VALUE - bias - expected.size
            )
            require(read(address + bias, expected.size).contentEquals(expected)) {
                "Live event sender evidence differs from the selected executable"
            }
        }

        fun word(value: Long) = ByteArray(8) { (value ushr (it * 8)).toByte() }
        for (function in functions)
            compare(
                function.address,
                image.functionBytes(function, function.size.toInt()).bytes(0, function.size.toInt())
            )
        for (range in readonly) {
            val expected = image.virtualBytes(range.address, range.size).bytes(0, range.size.toInt())
            // A non-PIE table may itself reside in a readonly section. Do not compare its pointer words as
            // unrelocated scalars when validating a loaded image at a different base.
            for ((address, target) in pointers) {
                val bytes = word(relocated(target))
                for (index in bytes.indices) {
                    val offset = address + index - range.address
                    if (offset in 0 until range.size) expected[offset.toInt()] = bytes[index]
                }
            }
            compare(range.address, expected)
        }
        for ((address, target) in pointers) compare(address, word(relocated(target)))
        for ((address, value) in scalars) compare(address, word(value))
    }

    companion object {
        fun resolve(image: ElfImage): EventSenderMetadata {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val state = InputStateLayout.resolve(image, MouseInputLayout.resolve(image))
                    val source = InputSourceLayout.resolve(image, state.global, state.globalSize)
                    val stateCalls = EventSenderStateCalls.resolve(image, state, source.mapSize)
                    val dispatch = EventSenderStateCalls.sourceDispatch(image, source)
                    val gui = EventSenderStateCalls.guiDispatch(image, source.mapSize)
                    val header = EventHeader.resolve(image)
                    val keyboard = InputStateKeyUpdate.resolve(image, state.size, header)
                    val sender = image.symbol("_ZN16InputEventSender9sendEventER3MapRK5Event")
                    val orders = EventSenderStateCalls.updateOrder(
                        X64ControlFlow.resolve(image, sender),
                        stateCalls, dispatch, gui, header, setOf(keyboard.press.kind, keyboard.release.kind)
                    )
                    val pointers = mutableMapOf<Long, Long>()
                    val scalars = mutableMapOf<Long, Long>()
                    val reader = image.pointers
                    fun table(type: String): ItaniumVtable {
                        val symbol = image.symbol("_ZTV$type")
                        val table = ItaniumVtable.resolve(image, symbol.name)
                        reader.words(symbol.address, (symbol.size / 8).toInt()).forEachIndexed { index, word ->
                            val address = symbol.address + index * 8L
                            if (index == 0) scalars[address] = word.scalar() else pointers[address] = word.pointer()
                        }
                        val name = image.symbol("_ZTI$type").address + 8
                        pointers[name] = reader.words(name, 1).single().pointer()
                        return table
                    }

                    val event = table("17PlayerInputSource")
                        .method(image, "_ZN17PlayerInputSource12processEventERK5Event")
                    require(event.addressPoint == source.evaluation.addressPoint)
                    val logic = table("N4agui3GuiE").method(image, "_ZN4agui3Gui5logicEb")
                    val handlerType = ItaniumType.resolve(image, "16InputHandlerAgui")
                    scalars[handlerType.addressPoint - 16] = 0
                    pointers[handlerType.addressPoint - 8] = handlerType.typeInfo
                    pointers[handlerType.typeInfo + 8] = reader.words(handlerType.typeInfo + 8, 1).single().pointer()
                    val guiType = ItaniumType(logic.addressPoint, image.symbol("_ZTIN4agui3GuiE").address)
                    EventSenderMetadata(
                        state, source, dispatch, gui, orders, event, logic,
                        image.symbol("_ZN16InputHandlerAgui12processEventERK5Event"),
                        image.symbol("_ZN10InputState6updateERK5Event"),
                        image.symbol("_ZN10InputState10postUpdateERK5Event"),
                        image.symbol("_ZN4agui3Gui8instanceE").address, SysVObjectSize.resolve(image, "4agui3Gui"),
                        guiType, SysVObjectSize.resolve(image, "16InputHandlerAgui"), handlerType,
                        emptyList(), emptyList(), pointers, scalars
                    )
                }
            }
            return resolved.first.copy(functions = resolved.second, readonly = readonly)
        }
    }
}
