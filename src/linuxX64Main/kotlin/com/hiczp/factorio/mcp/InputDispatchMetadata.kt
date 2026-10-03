@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxInputDispatchConfig

/** Typed keyboard/mouse event construction and the ordinary native pump entry, independent of task scheduling. */
internal data class InputDispatchMetadata(
    val event: KeyboardEventMetadata,
    val keys: KeyboardStateMetadata,
    val pointer: PointerEventMetadata,
    val call: ScalarPumpCall.Proof,
    val capture: EntryScalarSpill.Proof,
    private val functions: List<ElfImage.Symbol>,
) {
    fun verifyLoaded(image: ElfImage, process: ProcessHandle, bias: Long) = verify(image, bias, process::readMemory)

    internal fun verify(image: ElfImage, bias: Long, read: (Long, Int) -> ByteArray) {
        event.verify(image, bias, read)
        keys.owner.verify(image, bias, read)
        keys.verify(image, bias, read)
        verifyAdditional(image, bias, read)
    }

    /** Existing keyboard event/state evidence must already be verified and retained by this attachment. */
    internal fun verifyAdditional(image: ElfImage, bias: Long, read: (Long, Int) -> ByteArray) {
        pointer.verify(image, bias, read)
        for (function in functions) {
            require(bias >= 0 && function.address > 0 && function.size in 1..32768 &&
                    function.address <= Long.MAX_VALUE - bias - function.size)
            val expected = image.functionBytes(function, function.size.toInt())
            require(read(function.address + bias, function.size.toInt()).contentEquals(expected.bytes(0, expected.size.toInt()))) {
                "Live input pump ABI evidence differs from the selected executable"
            }
        }
    }

    fun writeTo(output: FmLinuxInputDispatchConfig, protection: Int, bias: Long) {
        event.poll.writeTo(output.site, event.header, protection, bias)
        keys.owner.writeTo(output.owner, bias)
        keys.writeTo(output.keys)
        event.writeTo(output.keyboard)
        event.writeTo(output.clock, bias)
        pointer.writeTo(output.pointer)
        pointer.writeTo(output.pointerState)
        require(bias >= 0 && event.poll.pump.address > 0 && event.poll.pump.address <= Long.MAX_VALUE - bias)
        output.pump = (event.poll.pump.address + bias).toULong()
        output.pumpArgument = call.value.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage, event: KeyboardEventMetadata, keys: KeyboardStateMetadata): InputDispatchMetadata {
            require(event.header == keys.owner.conversion.header && event.update == keys.update)
            val pointer = PointerEventMetadata.resolve(image, event, keys.owner)
            val (proof, functions) = image.withFunctionEvidence {
                val caller = image.symbol("_ZN8MainLoop10prePrepareEv")
                val pump = event.poll.pump
                val call = ScalarPumpCall.resolve(image, caller, pump)
                require(call.value == 0) { "The ordinary frontend pump call does not use the verified scalar argument" }
                val bytes = image.functionBytes(pump, 32768)
                val capture = EntryScalarSpill.inspect(bytes.slice(0, minOf(bytes.size, 512)), X64Instructions.Register(7, 4))
                call to capture
            }
            return InputDispatchMetadata(event, keys, pointer, proof.first, proof.second, functions)
        }
    }
}
