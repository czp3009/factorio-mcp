@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FM_LINUX_EVENT_BYTES
import com.hiczp.factorio.mcp.linuxbridge.FM_LINUX_POINTER_NO_FIELD
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxPointerEventLayout
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxPointerStateLayout
import kotlinx.cinterop.get
import kotlinx.cinterop.set

/** Full keyboard/mouse-pump pointer variants. Direct-widget three-button mirroring remains a separate adapter. */
internal data class PointerEventMetadata(
    val payload: PointerEventPayloads,
    val cursor: PointerInputState,
    val masks: List<Long>,
    val emptyType: Long,
    val copies: Map<Long, PollEventCopies.Proof>,
    val constructionCopies: Map<Long, EventCopyCases.Proof>,
    val stateUpdates: Map<Long, InputEventUses.Proof>,
    val postUpdates: Map<Long, InputEventUses.Proof>,
    private val functions: List<ElfImage.Symbol>,
    private val readonly: List<ElfImage.ReadonlyRange>,
) {
    init {
        require(masks.size == 5 && masks.distinct().size == 5 && masks.all { it > 0 && it and (it - 1) == 0L })
        require(payload.cases.all { it.kind != emptyType })
        for (case in payload.cases) {
            val initialized = case.initialized(payload.header)
            require(initialized.containsAll(copies.getValue(case.kind).bytes))
            require(initialized.containsAll(constructionCopies.getValue(case.kind).reads))
            for (uses in listOf(stateUpdates, postUpdates))
                require(uses.getValue(case.kind).reads.all { read ->
                    (read.offset until read.offset + read.width).all { it in initialized }
                }) { "Native pointer handler reads bytes without established initialization" }
        }
    }

    fun verifyLoaded(image: ElfImage, process: ProcessHandle, bias: Long) = verify(image, bias, process::readMemory)

    internal fun verify(image: ElfImage, bias: Long, read: (Long, Int) -> ByteArray) {
        require(bias >= 0)
        fun compare(address: Long, size: Long, expected: BinaryView) {
            require(size in 1..16 * 1024 * 1024 && address > 0 && address <= Long.MAX_VALUE - bias - size)
            require(read(address + bias, size.toInt()).contentEquals(expected.bytes(0, size.toInt()))) {
                "Live pointer event evidence differs from the selected executable"
            }
        }
        for (function in functions)
            compare(function.address, function.size, image.functionBytes(function, function.size.toInt()))
        for (range in readonly)
            compare(range.address, range.size, image.virtualBytes(range.address, range.size))
    }

    fun writeTo(output: FmLinuxPointerEventLayout) {
        output.extent = payload.header.extent.toUInt()
        output.type = payload.header.type.toUInt()
        output.time = payload.header.time.toUInt()
        output.emptyType = emptyType.toUInt()
        payload.codes.forEachIndexed { index, code -> output.codes[index] = code.toUInt() }
        payload.cases.forEachIndexed { index, case ->
            val item = output.cases[index]
            item.kind = case.kind.toUInt()
            item.x = case.x?.toUInt() ?: FM_LINUX_POINTER_NO_FIELD
            item.y = case.y?.toUInt() ?: FM_LINUX_POINTER_NO_FIELD
            item.code = case.code?.toUInt() ?: FM_LINUX_POINTER_NO_FIELD
            item.wheel = case.wheel?.toUInt() ?: FM_LINUX_POINTER_NO_FIELD
            item.wheelY = case.wheelY?.toUInt() ?: FM_LINUX_POINTER_NO_FIELD
            for (offset in 0 until FM_LINUX_EVENT_BYTES) {
                item.defaults[offset] = (case.defaults[offset.toLong()] ?: 0).toUByte()
                item.initialized[offset] = if (offset.toLong() in case.defaults) 1u else 0u
            }
        }
    }

    fun writeTo(output: FmLinuxPointerStateLayout) {
        output.position = cursor.position.toUInt()
        output.inWindow = cursor.inWindow.toUInt()
        masks.forEachIndexed { index, mask -> output.masks[index] = mask.toUInt() }
    }

    companion object {
        fun resolve(image: ElfImage, keyboard: KeyboardEventMetadata, mouse: MouseStateMetadata): PointerEventMetadata {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val payload = PointerEventPayloads.resolve(image)
                    require(payload.header == keyboard.header && payload.header == mouse.conversion.header)
                    val cursor = PointerInputState.resolve(image, mouse.state, payload)
                    val cases = payload.cases.associateBy { it.kind }
                    val masks = listOf(SdlButtonAdmission.Button.LEFT, SdlButtonAdmission.Button.RIGHT,
                        SdlButtonAdmission.Button.MIDDLE, SdlButtonAdmission.Button.X1, SdlButtonAdmission.Button.X2)
                        .map { mouse.update.masks.getValue(it) }
                    for (case in payload.cases)
                        case.timeStore?.let { store ->
                            require(SdlEventTime.resolve(image, KeyboardEventPayload(emptyMap(), store)) == keyboard.clock)
                        }
                    val empty = (0 until 4).fold(0L) { value, byte ->
                        value or (keyboard.poll.defaults.getValue(payload.header.type + byte).toLong() shl (byte * 8))
                    }
                    PointerEventMetadata(payload, cursor, masks, empty,
                        PollEventCopies.resolve(image, keyboard.poll, payload.header, cases.keys),
                        EventCopyCases.resolve(image, payload.header, cases.mapValues { (_, case) ->
                            EventCopyCases.Case(case.required(payload.header), case.initialized(payload.header))
                        }), InputEventUses.resolve(image, payload.header, cases.mapValues { (_, case) -> case.initialized(payload.header) }),
                        InputEventUses.postUpdate(image, payload.header, cases.mapValues { (_, case) -> case.initialized(payload.header) }),
                        emptyList(), emptyList())
                }
            }
            return resolved.first.copy(functions = resolved.second, readonly = readonly)
        }
    }
}
