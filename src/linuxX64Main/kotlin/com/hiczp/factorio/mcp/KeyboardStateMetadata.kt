@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxKeyStateLayout
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxKeyboardStateConfig
import kotlinx.cinterop.set

/** Keyboard evidence supplementing the shared InputState owner and scalar Event header used by mouse input. */
internal data class KeyboardStateMetadata(
    val owner: MouseStateMetadata,
    val update: InputStateKeyUpdate,
    val post: Map<Long, KeyPostUpdate.Proof>,
    val map: KeyMapLayout,
    val modifiers: Map<String, ModifierKey.Proof>,
    val uses: Map<Long, InputEventUses.Proof>,
    private val functions: List<ElfImage.Symbol>,
    private val readonly: List<ElfImage.ReadonlyRange>,
) {
    fun verifyLoaded(image: ElfImage, process: ProcessHandle, loadBias: Long) {
        owner.verifyLoaded(image, process, loadBias)
        verify(image, loadBias, process::readMemory)
    }

    internal fun verify(image: ElfImage, loadBias: Long, read: (Long, Int) -> ByteArray) {
        require(loadBias >= 0)
        fun compare(address: Long, size: Long, expected: BinaryView) {
            require(size in 1..(16 * 1024 * 1024) && address >= 0 && address <= Long.MAX_VALUE - loadBias - size)
            require(read(address + loadBias, size.toInt()).contentEquals(expected.bytes(0, size.toInt()))) {
                "Live keyboard-state evidence differs from the selected executable"
            }
        }
        for (function in functions)
            compare(function.address, function.size, image.functionBytes(function, function.size.toInt()))
        for (range in readonly)
            compare(range.address, range.size, image.virtualBytes(range.address, range.size))
    }

    fun writeTo(output: FmLinuxKeyStateLayout) {
        output.map = update.map.toUInt()
        output.begin = map.begin.toUInt()
        output.end = map.end.toUInt()
        output.stride = map.stride.toUInt()
        output.key = map.key.toUInt()
        output.value = map.value.toUInt()
        output.valueSize = update.requiredValueSize.toUInt()
        output.held = update.held.toUInt()
        output.clear = modifiers.values.flatMap { it.clear }.distinct().single().toUInt()
    }

    fun writeTo(output: FmLinuxKeyboardStateConfig) {
        writeTo(output.layout)
        output.eventCode = update.code.toUInt()
        output.press = update.press.kind.toUInt()
        output.release = update.release.kind.toUInt()
        listOf("control", "shift", "alt").forEachIndexed { index, name ->
            output.codes[index] = modifiers.getValue(name).code.toUInt()
        }
    }

    companion object {
        fun resolve(image: ElfImage, owner: MouseStateMetadata): KeyboardStateMetadata {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val header = owner.conversion.header
                    val update = InputStateKeyUpdate.resolve(image, owner.state.size, header)
                    val post = KeyPostUpdate.resolve(image, header, update)
                    val modifiers = ModifierKey.resolve(image, update, post)
                    require(modifiers.values.flatMap { it.clear }.distinct().size == 1)
                    KeyboardStateMetadata(
                        owner, update, post, KeyMapLayout.resolve(image, owner.state.size, update), modifiers,
                        InputEventUses.resolve(
                            image,
                            header,
                            update.code,
                            setOf(update.press.kind, update.release.kind)
                        ),
                        emptyList(), emptyList()
                    )
                }
            }
            return resolved.first.copy(functions = resolved.second, readonly = readonly)
        }
    }
}
