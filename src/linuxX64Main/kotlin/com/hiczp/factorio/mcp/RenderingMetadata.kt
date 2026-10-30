@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import com.hiczp.factorio.mcp.linuxbridge.FmRenderingState
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxRenderingSetting

internal data class RenderingMetadata(
    val headless: Long, val globalSize: Long, val settingsSize: Long, val table: Long,
    val guard: RenderingGuard,
    val configTable: Long, val configSize: Long, val configMember: Long, val nameData: Long, val nameLength: Long,
) {
    fun write(output: FmRenderingState, setting: FmLinuxRenderingSetting, global: Long, bias: Long) {
        fun address(value: Long): ULong {
            require(value > 0 && bias >= 0 && value <= Long.MAX_VALUE - bias)
            return (value + bias).toULong()
        }
        output.global = address(global)
        output.headless = address(headless)
        output.settingsTable = address(table)
        output.globalSize = globalSize.toUInt()
        output.settingsSize = settingsSize.toUInt()
        output.minimized = guard.minimized.toUInt()
        output.suppressed = guard.suppressed.toUInt()
        output.settings = guard.settings.toUInt()
        output.halt = guard.halt.toUInt()
        setting.table = address(configTable)
        setting.size = configSize.toUInt()
        setting.member = configMember.toUInt()
        setting.nameData = nameData.toUInt()
        setting.nameLength = nameLength.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage, control: RenderingControlMetadata): RenderingMetadata {
            val settingsSize = control.settingsSize
            val table = ItaniumVtable.resolve(image, "_ZTV16GraphicsSettings").addressPoint
            val guard = control.guard

            // A named bool setter ties the guard's byte to the current value, not its default.
            val setter = image.symbol("_ZN17StorageConfigItemIbE8setValueEb")
            EhFrames(image).function(setter)
            val setterFlow = X64ControlFlow(X64Instructions(image.functionBytes(setter, 512)).all())
            val setterArguments = SysVArgumentFlow(setterFlow)
            val stores = setterFlow.instructions.filter {
                it.operation == Operation.MOV && it.destination is Memory && it.destination.width == 1 &&
                    it.source == Register(6, 1) && setterArguments.register(it.offset, 6) == SysVArgumentFlow.Reference(6)
            }
            val value = stores.map {
                val field = checkNotNull(setterArguments.memory(it.offset, it.destination as Memory))
                require(field.reference.argument == 7 && field.width == 1)
                field.reference.offset
            }.distinct().single()
            val configSize = SysVDeletingTailSize.resolve(image, image.symbol("_ZN16SimpleConfigItemIbED0Ev"))
            require(value in 8 until configSize && guard.halt >= value)
            val member = guard.halt - value
            require(member in 8..settingsSize - configSize)
            val configTable = ItaniumVtable.resolve(image, "_ZTV16SimpleConfigItemIbE").addressPoint
            require(ItaniumClass.resolve(image, "16SimpleConfigItemIbE")
                .directBase(ItaniumClass.resolve(image, "17StorageConfigItemIbE"), configSize, 8) == 0L)
            require(ItaniumClass.resolve(image, "17StorageConfigItemIbE")
                .directBase(ItaniumClass.resolve(image, "14ConfigItemBase"), configSize, 8) == 0L)
            val string = NativeStringLayout.resolve(image)
            val constructor = image.symbol("_ZN14ConfigItemBaseC2EP14ConfigItemListRKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEE14ConfigItemTypeS9_")
            EhFrames(image).function(constructor)
            val body = X64Instructions(image.functionBytes(constructor, 4096), allowAtomicExchangeAdd = true).all(1024)
            val flow = X64ControlFlow(body)
            val name = settingName(flow, constructor.address, string.data, string.length, string.local, string.size, configSize)
            // The live typed item supplies its exact key. This avoids analyzing the unrelated
            // settings constructor and never caches the settings object across frontend callbacks.
            return RenderingMetadata(control.headless, control.window.globalSize, settingsSize, table, guard,
                configTable, configSize, member, name + string.data, name + string.length)
        }

        internal fun settingName(flow: X64ControlFlow, address: Long, dataOffset: Long, lengthOffset: Long,
                                 localOffset: Long, stringSize: Long, size: Long): Long {
            require(lengthOffset in 0..stringSize - 8 && dataOffset in 0..stringSize - 8 &&
                localOffset in 0 until stringSize && stringSize in 8..size)
            val origins = OriginalPointerOrigins(flow, address)
            val stores = flow.instructions.filter {
                it.offset in flow.reachable && it.operation == Operation.MOV &&
                    (it.source as? Register)?.width == 8 && (it.destination as? Memory)?.let { memory ->
                        memory.width == 8 && !memory.relative && memory.index == null
                    } == true
            }
            // Capacity can also receive the input length. Require the independent inline-data
            // initialization to identify a complete string, rather than picking a store ordinal.
            val strings = stores.mapNotNull { instruction ->
                val destination = instruction.destination as Memory
                val source = origins.register(instruction.offset, (instruction.source as Register).number)
                    as? OriginalPointerOrigins.Argument ?: return@mapNotNull null
                val receiver = destination.base?.let { origins.register(instruction.offset, it) }
                    as? OriginalPointerOrigins.Argument ?: return@mapNotNull null
                if (source.register != 7 || receiver.register != 7) return@mapNotNull null
                val member = receiver.adjustment + destination.displacement - dataOffset
                member.takeIf { source.adjustment == member + localOffset }
            }.toSet()
            val names = stores.mapNotNull { instruction ->
                if (instruction.offset !in flow.reachable) return@mapNotNull null
                val destination = instruction.destination as? Memory ?: return@mapNotNull null
                val source = instruction.source as? Register ?: return@mapNotNull null
                if (instruction.operation != Operation.MOV || source.width != 8 || destination.width != 8 ||
                    destination.relative || destination.index != null) return@mapNotNull null
                val length = origins.register(instruction.offset, source.number) as? OriginalPointerOrigins.Load
                    ?: return@mapNotNull null
                if (length.base != OriginalPointerOrigins.Argument(2) || length.member != lengthOffset)
                    return@mapNotNull null
                val receiver = destination.base?.let { origins.register(instruction.offset, it) }
                    as? OriginalPointerOrigins.Argument ?: return@mapNotNull null
                require(receiver.register == 7)
                receiver.adjustment + destination.displacement - lengthOffset
            }.distinct().filter { it in strings }
            val name = names.singleOrNull() ?: error("Config name lacks one complete string initialization and length copy")
            require(name in 8..size - stringSize)
            return name
        }
    }
}
