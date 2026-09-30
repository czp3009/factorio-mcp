@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FM_LINUX_EVENT_BYTES
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxEventClock
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxKeyboardEventLayout
import kotlinx.cinterop.set

/** Loaded-code evidence for scalar Event construction and the exact native poll callers. Does not install a hook. */
internal data class KeyboardEventMetadata(
    val header: EventHeader,
    val poll: EventPollCall,
    val update: InputStateKeyUpdate,
    val payload: KeyboardEventPayload,
    val copies: Map<Long, PollEventCopies.Proof>,
    val constructionCopies: Map<Long, EventCopyCases.Proof>,
    val stateUpdates: Map<Long, InputEventUses.Proof>,
    val postUpdates: Map<Long, InputEventUses.Proof>,
    val clock: SdlEventTime,
    private val functions: List<ElfImage.Symbol>,
    private val readonly: List<ElfImage.ReadonlyRange>,
) {
    init {
        require(
            header.extent in 16..FM_LINUX_EVENT_BYTES &&
                    payload.defaults.all { (offset, value) -> offset in 0 until header.extent && value in 0..255 })
        val initialized = payload.defaults.keys + (header.type until header.type + 4) +
                (header.time until header.time + 8) + (update.code until update.code + 4)
        require(
            copies.keys == setOf(update.press.kind, update.release.kind) &&
                    copies.values.all { initialized.containsAll(it.bytes) })
        require(
            constructionCopies.keys == copies.keys &&
                    constructionCopies.values.all { initialized.containsAll(it.reads) && initialized.containsAll(it.bytes) }) {
            "Native keyboard copy reads bytes without established initialization"
        }
        for (uses in listOf(stateUpdates, postUpdates)) {
            require(uses.keys == copies.keys && uses.values.all { proof ->
                proof.possibleArguments.isEmpty() && proof.reads.all { read ->
                    (read.offset until read.offset + read.width).all { it in initialized }
                }
            }) { "Keyboard state handler reads uninitialized bytes or exposes an unverified event argument" }
        }
        require((header.time until header.time + 8).all { poll.defaults[it] == 0 })
    }

    fun verifyLoaded(image: ElfImage, process: ProcessHandle, bias: Long) = verify(image, bias, process::readMemory)

    internal fun verify(image: ElfImage, bias: Long, read: (Long, Int) -> ByteArray) {
        require(bias >= 0)
        fun compare(address: Long, size: Long, expected: BinaryView) {
            require(size in 1..16 * 1024 * 1024 && address > 0 && address <= Long.MAX_VALUE - bias - size)
            require(read(address + bias, size.toInt()).contentEquals(expected.bytes(0, size.toInt()))) {
                "Live keyboard event evidence differs from the selected executable"
            }
        }
        for (function in functions)
            compare(function.address, function.size, image.functionBytes(function, function.size.toInt()))
        for (range in readonly)
            compare(range.address, range.size, image.virtualBytes(range.address, range.size))
    }

    fun writeTo(output: FmLinuxKeyboardEventLayout) {
        output.extent = header.extent.toUInt()
        output.type = header.type.toUInt()
        output.time = header.time.toUInt()
        output.code = update.code.toUInt()
        output.press = update.press.kind.toUInt()
        output.release = update.release.kind.toUInt()
        output.emptyType = (0 until 4).fold(0u) { value, byte ->
            value or (poll.defaults.getValue(header.type + byte).toUInt() shl (byte * 8))
        }
        require(output.emptyType != output.press && output.emptyType != output.release)
        for (offset in 0 until FM_LINUX_EVENT_BYTES) {
            output.defaults[offset] = (payload.defaults[offset.toLong()] ?: 0).toUByte()
            output.initialized[offset] = if (offset.toLong() in payload.defaults) 1u else 0u
        }
    }

    fun writeTo(output: FmLinuxEventClock, bias: Long) {
        require(bias >= 0 && clock.ticks.address > 0 && clock.ticks.address <= Long.MAX_VALUE - bias)
        output.ticks = (clock.ticks.address + bias).toULong()
        output.divisor = clock.divisor
    }

    companion object {
        fun resolve(image: ElfImage): KeyboardEventMetadata {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val header = EventHeader.resolve(image)
                    val poll = EventPollCall.resolve(image, header)
                    val size = SysVObjectSize.resolveDeleter(image, "_ZN13SimpleDeleterI10InputStateEclEPS0_")
                    val update = InputStateKeyUpdate.resolve(image, size, header)
                    val payload = KeyboardEventPayload.resolve(image, header, update)
                    KeyboardEventMetadata(
                        header, poll, update, payload,
                        PollEventCopies.resolve(image, poll, header, setOf(update.press.kind, update.release.kind)),
                        EventCopyCases.resolve(
                            image,
                            header,
                            update.code,
                            setOf(update.press.kind, update.release.kind)
                        ),
                        InputEventUses.resolve(
                            image,
                            header,
                            update.code,
                            setOf(update.press.kind, update.release.kind)
                        ),
                        InputEventUses.postUpdate(image, header, update),
                        SdlEventTime.resolve(image, payload), emptyList(), emptyList()
                    )
                }
            }
            return resolved.first.copy(functions = resolved.second, readonly = readonly)
        }
    }
}
