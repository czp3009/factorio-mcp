@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.*
import kotlinx.cinterop.*

/** Validated keyboard/mouse registry fields and vocabularies from the selected executable. */
internal data class ControlLayouts(
    val size: Long,
    val registry: ElfImage.Symbol,
    val guard: Long,
    val loading: Long,
    val names: NamedPointerRegistry,
    val slots: ControlSlots,
    val modifiers: ControlModifiers,
    val linked: Long,
    val prototype: ControlPrototype,
    val gui: NativeAccessor,
    val usage: ControlUsage,
    val emptyKind: Int,
    val mouseKind: Int,
    val mouseNames: Map<Int, String>,
    val wheel: ControlWheelKinds,
    private val functions: List<ElfImage.Symbol> = emptyList(),
    private val readonly: List<ElfImage.ReadonlyRange> = emptyList(),
    private val pointers: Map<Long, Long> = emptyMap(),
) {
    init {
        require(size in 16..4096 && slots.values.size == 2 && gui.width == 1 && gui.maximum == 1UL)
        require(setOf(emptyKind, mouseKind, wheel.kind, slots.keyboardType.toInt()).size == 4)
    }

    fun verifyLoaded(image: ElfImage, process: ProcessHandle, bias: Long) = verify(image, bias, process::readMemory)

    internal fun verify(image: ElfImage, bias: Long, read: (Long, Int) -> ByteArray) {
        require(bias >= 0)
        fun compare(address: Long, bytes: ByteArray) {
            require(
                bytes.isNotEmpty() && bytes.size <= 16 * 1024 * 1024 &&
                        address > 0 && address <= Long.MAX_VALUE - bias - bytes.size
            )
            require(read(address + bias, bytes.size).contentEquals(bytes)) {
                "Live control binding evidence differs from the selected executable"
            }
        }
        for (function in functions)
            compare(
                function.address,
                image.functionBytes(function, function.size.toInt()).bytes(0, function.size.toInt())
            )
        for (range in readonly)
            compare(range.address, image.virtualBytes(range.address, range.size).bytes(0, range.size.toInt()))
        for ((address, target) in pointers) {
            require(target >= 0 && target <= Long.MAX_VALUE - bias)
            val expected = if (target == 0L) 0L else target + bias
            compare(address, ByteArray(8) { (expected ushr (it * 8)).toByte() })
        }
    }

    fun writeTo(output: FmLinuxControlsLayout, bias: Long) {
        fun address(value: Long): ULong {
            require(bias >= 0 && value > 0 && value <= Long.MAX_VALUE - bias)
            return (value + bias).toULong()
        }
        output.registry = address(registry.address)
        output.guard = address(guard)
        output.loading = address(loading)
        output.prototypeVtable = address(prototype.vtable)
        output.registrySize = registry.size.toUInt()
        output.begin = names.begin.toUInt()
        output.end = names.end.toUInt()
        output.size = size.toUInt()
        output.nameData = names.nameData.toUInt()
        output.nameLength = names.nameSize.toUInt()
        output.linked = linked.toUInt()
        output.custom = prototype.member.toUInt()
        output.gui = gui.offset.toUInt()
        output.guiMask = gui.mask.toUInt()
        output.usage = usage.field.toUInt()
        slots.values.forEachIndexed { index, value -> output.slots[index] = value.toUInt() }
        output.type = slots.type.toUInt()
        output.code = slots.code.toUInt()
        output.modifiers = modifiers.field.toUInt()
        output.prototypeSize = prototype.size.toUInt()
        output.enabled = prototype.enabled.toUInt()
        output.spectating = prototype.spectating.toUInt()
        output.cutscene = prototype.cutscene.toUInt()
    }

    fun read(source: FmLinuxControlsSnapshot, frame: Long): GameSnapshot {
        require(
            source.count <= FM_LINUX_MAX_CONTROLS.toUInt() && source.count <= source.registryCount &&
                    source.registryCount <= 16384u && source.truncated <= 1u &&
                    (source.count < source.registryCount) == (source.truncated != 0u)
        )
        fun identity(value: CPointer<ByteVar>): String {
            val bytes = value.readBytes(FM_LINUX_CONTROL_ID_BYTES)
            val end = bytes.indexOf(0)
            require(end >= 0) { "Resident control identity is not terminated" }
            return bytes.decodeToString(0, end, throwOnInvalidSequence = true)
        }

        fun bindings(values: CPointer<FmLinuxBinding>): List<BindingSnapshot> = List(2) { index ->
            val binding = values[index]
            val code = binding.code.toInt()
            val flags = binding.modifiers.toInt()
            require(binding.type <= 255u && binding.modifiers <= 255u)
            val type = when (binding.type.toInt()) {
                emptyKind -> "Nothing"
                slots.keyboardType.toInt() -> "Keyboard"
                mouseKind -> "MouseButton"
                wheel.kind -> "MouseWheel"
                else -> "Unknown(${binding.type})"
            }
            BindingSnapshot(
                listOf("keyboard_mouse_primary", "keyboard_mouse_secondary")[index], type,
                when (type) {
                    "Keyboard" -> SdlScancodes.names[binding.code]
                    "MouseButton" -> mouseNames[code]
                    "MouseWheel" -> wheel.names[code]
                    else -> null
                },
                code,
                listOf(modifiers.control to "control", modifiers.shift to "shift", modifiers.alt to "alt")
                    .filter { (mask, _) -> flags and mask != 0 }.map { it.second },
                flags,
            )
        }

        val controls = List(source.count.toInt()) { index ->
            val row = source.controls[index]
            require(listOf(row.custom, row.enabled, row.spectating, row.cutscene, row.gui).all { it <= 1u })
            ControlSnapshot(
                identity(row.id).also { require(it.isNotEmpty()) },
                identity(row.linked).takeIf { it.isNotEmpty() },
                identity(row.owner).also { require(it.isNotEmpty()) }, row.custom != 0u,
                if (row.custom != 0u) row.enabled != 0u else null,
                if (row.custom != 0u) row.spectating != 0u else null,
                if (row.custom != 0u) row.cutscene != 0u else null, row.gui != 0u,
                row.usage, bindings(row.bindings), bindings(row.effective)
            )
        }
        require(controls.map { it.id }.distinct().size == controls.size) { "Duplicate control identities" }
        return GameSnapshot(
            "unknown", true, frame, truncated = source.truncated != 0u,
            controls = controls, registryCount = source.registryCount.toInt()
        )
    }

    companion object {
        fun resolve(image: ElfImage): ControlLayouts {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val size = VectorElementSize.resolve(
                        image,
                        "_ZNSt6vectorI12ControlInputSaIS0_EED2Ev",
                        "_ZN12ControlInputD2Ev"
                    )
                    val registry = image.symbol("_ZZN12ControlInput19getControlInputListEvE16controlInputList")
                    val guard = image.symbol("_ZGVZN12ControlInput19getControlInputListEvE16controlInputList")
                    val loading = image.symbol("_ZN12ControlInput19loadingCustomInputsE")
                    require(loading.type == 1 && loading.size == 1L)
                    val names = NamedPointerRegistry.resolve(
                        image,
                        "_ZN12ControlInput16findControlInputESt17basic_string_viewIcSt11char_traitsIcEE",
                        registry.name, guard.name, size
                    )
                    val debug = DwarfInlines(image)
                    val slots = ControlSlots.resolve(image, size, debug)
                    val extent = slots.values.minOf { size - it }
                    val modifiers = ControlModifiers.resolve(image, extent, debug)
                    val linked = listOf(
                        "_ZNK12ControlInput11triggeredByERK5EventPPK17ControlInputValuej",
                        "_ZNK12ControlInput8isActiveEb9NamedBoolI11GuiCheckTagEbS0_I17CheckModifiersTagE"
                    )
                        .map { LinkedReceiverPrefix.resolve(image, it, size).member }.distinct().single()
                    val prototype = ControlPrototype.resolve(image, size, debug)
                    val gui = ControlGuiFlag.resolve(image, size)
                    val usage = ControlUsage.resolve(image, size)
                    val string = NativeStringLayout.resolve(image)
                    val empty = ControlEmptyKind.resolve(image, extent, slots.type, slots.code, string, debug)
                    val mouse = ControlMouseKind.resolve(image, extent, slots.type, slots.code)
                    val wheels = ControlWheelKinds.resolve(image, extent, slots.type, slots.code, string)
                    val mouseNames = ControlMouseCodes.resolve(image, extent, slots.type, slots.code, mouse)
                    val header = EventHeader.resolve(image)
                    val keyboard = InputStateKeyUpdate.resolve(
                        image,
                        SysVObjectSize.resolveDeleter(image, "_ZN13SimpleDeleterI10InputStateEclEPS0_"),
                        header
                    )
                    ControlEventCode.resolve(
                        image, extent, slots.type, slots.code, slots.keyboardType.toInt(),
                        header.extent.toLong(), keyboard.code
                    )
                    val table = image.symbol("_ZTV20CustomInputPrototype")
                    val pointers = ElfPointers(image)
                    val words = pointers.words(table.address, (table.size / 8).toInt())
                        .mapIndexed { index, word -> table.address + index * 8L to if (index == 0) word.scalar() else word.pointer() }
                        .toMap()
                    val type = image.symbol("_ZTI20CustomInputPrototype")
                    val typeName = type.address + 8
                    ControlLayouts(
                        size, registry, guard.address, loading.address, names, slots, modifiers, linked,
                        prototype, gui, usage, empty, mouse, mouseNames, wheels,
                        pointers = words + (typeName to pointers.words(typeName, 1).single().pointer())
                    )
                }
            }
            return resolved.first.copy(functions = resolved.second, readonly = readonly)
        }
    }
}
