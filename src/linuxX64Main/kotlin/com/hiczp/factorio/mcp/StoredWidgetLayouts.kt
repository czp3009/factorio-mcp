@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import com.hiczp.factorio.mcp.linuxbridge.FmUiStoredPrototypeLayout
import com.hiczp.factorio.mcp.linuxbridge.FmUiAchievementLayout

/** Retained references and card enums absent from the public script-GUI interface. */
internal data class StoredWidgetLayouts(
    val connectionType: Long, val connectionSize: Long, val connectionId: Long,
    val registry: Long, val first: Long, val last: Long,
    val cardType: Long, val cardSize: Long, val prototype: Long,
    val state: Long, val shownState: Long, val sidebar: Long,
    val evidence: ElfEvidence,
) {
    fun writeTo(connection: FmUiStoredPrototypeLayout, card: FmUiStoredPrototypeLayout, achievement: FmUiAchievementLayout, bias: Long) {
        require(bias >= 0 && maxOf(connectionType, cardType, registry) <= Long.MAX_VALUE - bias)
        connection.type = (connectionType + bias).toULong()
        connection.supported = 1u
        connection.size = connectionSize.toUInt()
        connection.field = connectionId.toUInt()
        connection.indirect = 1u
        connection.registry = (registry + bias).toULong()
        connection.first = first.toUInt()
        connection.last = last.toUInt()
        card.type = (cardType + bias).toULong()
        card.supported = 1u
        card.size = cardSize.toUInt()
        card.field = prototype.toUInt()
        achievement.type = card.type
        achievement.supported = 1u
        achievement.size = card.size
        achievement.state = state.toUInt()
        achievement.shownState = shownState.toUInt()
        achievement.inSidebar = sidebar.toUInt()
    }

    companion object {
        private fun originalInput(value: ScalarExpression.Value, width: Int): ScalarExpression.Input? {
            var current = value
            while (current is ScalarExpression.Narrow) {
                if (current.width < width) return null
                current = current.value
            }
            return (current as? ScalarExpression.Input)?.takeIf { it.field.width == width }
        }

        internal fun stateCopy(flow: X64ControlFlow, size: Long, baseSize: Long): Pair<Long, Long> {
            val arguments = SysVArgumentFlow(flow)
            val scalars = ScalarExpression(flow, mapOf(7 to size), arguments)
            fun input(site: Long, register: Register): Long? {
                val value = runCatching { originalInput(scalars.before(site, register), 4) }.getOrNull() ?: return null
                return value.field.reference.offset.takeIf {
                    value.field.width == 4 && value.field.reference.argument == 7 && it in baseSize..size - 4
                }
            }
            val pairs = flow.instructions.mapNotNull { instruction ->
                if (instruction.offset !in flow.reachable || instruction.operation != Operation.MOV) return@mapNotNull null
                val target = instruction.destination as? Memory ?: return@mapNotNull null
                val source = instruction.source as? Register ?: return@mapNotNull null
                if (target.width != 4 || source.width != 4) return@mapNotNull null
                val destination = arguments.memory(instruction.offset, target)?.reference ?: return@mapNotNull null
                if (destination.argument != 7 || destination.offset !in baseSize..size - 4) return@mapNotNull null
                val from = input(instruction.offset, source) ?: return@mapNotNull null
                if (from == destination.offset) return@mapNotNull null
                val compared = flow.instructions.any { compare ->
                    if (compare.offset !in flow.reachable || compare.operation != Operation.CMP) false else {
                        val operands = listOf(compare.destination, compare.source)
                        val memory = operands.filterIsInstance<Memory>().singleOrNull()
                        val register = operands.filterIsInstance<Register>().singleOrNull()
                        memory?.width == 4 && register?.width == 4 &&
                            arguments.memory(compare.offset, memory)?.reference == destination && input(compare.offset, register) == from
                    }
                }
                if (compared) from to destination.offset else null
            }.distinct()
            return pairs.singleOrNull() ?: error("Achievement card has no unique compared state/display-state copy")
        }

        private fun sidebar(image: ElfImage, description: ElfImage.Symbol, size: Long, prototype: Long): Long {
            val table = ItaniumVtable.resolve(image, "_ZTV20AchievementPrototype")
            val method = table.method(image, "_ZNK20AchievementPrototype33addToDescriptionWithPlayerContextER11DescriptionRK6Playerb")
            val flow = X64ControlFlow.resolve(image, description)
            val origins = OriginalPointerOrigins(flow)
            val scalars = ScalarExpression(flow, mapOf(7 to size))
            val fields = flow.instructions.mapNotNull { call ->
                val dispatch = call.destination as? Memory ?: return@mapNotNull null
                if (call.offset !in flow.reachable || call.operation != Operation.CALL || dispatch.relative || dispatch.index != null ||
                    dispatch.width != 8 || dispatch.displacement != method.slot * 8L) return@mapNotNull null
                val receiver = origins.register(call.offset, 7) as? OriginalPointerOrigins.Load ?: return@mapNotNull null
                if (receiver.base != OriginalPointerOrigins.Argument(7) || receiver.member != prototype) return@mapNotNull null
                val vtable = dispatch.base?.let { origins.register(call.offset, it) } as? OriginalPointerOrigins.Load ?: return@mapNotNull null
                if (vtable.base != receiver || vtable.member != 0L) return@mapNotNull null
                val value = runCatching { originalInput(scalars.before(call.offset, Register(1, 4)), 1) }.getOrNull() ?: return@mapNotNull null
                value.field.reference.offset.takeIf { value.field.reference.argument == 7 && value.field.width == 1 && it in 8 until size }
            }.distinct()
            return fields.singleOrNull() ?: error("Achievement description's original sidebar flag is unavailable")
        }

        private fun connection(image: ElfImage, size: Long, registry: ElfImage.Symbol): Pair<Long, Long> {
            val function = image.symbol("_ZNK14UniverseWidget21SpaceConnectionWidget15getSegmentCountEv")
            EhFrames(image).function(function)
            val bytes = image.functionBytes(function, 4096)
            val decoder = X64Instructions(bytes)
            val prefix = mutableListOf<Instruction>()
            var position = 0L
            // Stop at the typed indexed load, before unrelated geometry calculations or calls.
            repeat(64) {
                val instruction = decoder.decode(position)
                position += instruction.size
                require(instruction.operation !in setOf(Operation.CALL, Operation.RET, Operation.JMP, Operation.JCC)) {
                    "Connection prototype lookup leaves its bounded entry prefix"
                }
                prefix += instruction
                val source = instruction.source as? Memory
                if (instruction.operation == Operation.MOV && source?.width == 8 && source.index != null && source.base != null && source.scale == 8 && !source.relative) {
                    val flow = X64ControlFlow(prefix)
                    val origins = OriginalPointerOrigins(flow, function.address)
                    val base = origins.register(instruction.offset, source.base) as? OriginalPointerOrigins.Global
                        ?: error("Connection prototype lookup has no named registry provenance")
                    val member = base.address - registry.address
                    require(member in 0..registry.size - 8 && member % 8 == 0L && source.displacement == 0L)
                    val scalars = ScalarExpression(flow)
                    val definition = scalars.definition(instruction.offset, source.index)
                    val field = definition.source as? Memory ?: error("Connection ID is not a retained member")
                    require(definition.operation == Operation.MOVZX && field.width == 2)
                    val read = SysVArgumentFlow(flow).memory(definition.offset, field) ?: error("Connection ID has no original widget receiver")
                    require(read.reference.argument == 7 && read.reference.offset in 8..size - 2)
                    return read.reference.offset to member
                }
            }
            error("Connection typed registry lookup exceeds entry bound")
        }

        fun resolve(image: ElfImage, names: PrototypeNameLayout): StoredWidgetLayouts {
            val types = NativeTypeEvidence(image)
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val connectionType = types.type("N14UniverseWidget21SpaceConnectionWidgetE")
                    val cardType = types.type("15AchievementCard")
                    val connectionSize = SysVObjectSize.resolve(image, "14UniverseWidget21SpaceConnectionWidget")
                    val cardSize = SysVObjectSize.resolve(image, "15AchievementCard")
                    val frameSize = SysVObjectSize.resolve(image, "4agui5Frame")
                    require(cardType.directBase(types.type("N4agui5FrameE"), cardSize, frameSize) == 0L)
                    val constructor = "_ZN15AchievementCardC2ERK20AchievementPrototype10GuiContextbSt8optionalINSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEEESB_"
                    val prototype = SysVArgumentMember.resolve(image, constructor, cardSize)
                    val description = image.symbol("_ZN15AchievementCard17updateDescriptionEv")
                    val state = stateCopy(X64ControlFlow.resolve(image, description), cardSize, frameSize)
                    val sidebar = sidebar(image, description, cardSize, prototype)
                    val registry = image.symbol("_ZN13PrototypeListI24SpaceConnectionPrototypeE16indexToPrototypeE")
                    require(registry.type == 1 && registry.size in 16..4096)
                    val (id, first) = connection(image, connectionSize, registry)
                    val iteration = image.symbol("_ZZN13PrototypeListI24SpaceConnectionPrototypeE19getPrototypesOfTypeIS0_EERKSt6vectorIPKT_SaIS6_EEvENKUlvE_clEv")
                    val last = GlobalPointerIteration.analyze(X64ControlFlow.resolve(image, iteration), iteration.address, registry.address, registry.size, first)
                    val base = types.type("13PrototypeBase")
                    for (encoded in listOf("24SpaceConnectionPrototype", "20AchievementPrototype")) {
                        val type = types.type(encoded)
                        val size = SysVObjectSize.resolve(image, encoded)
                        require(type.baseOffset(base, size, names.minimumExtent, types::at) == 0L) {
                            "Retained prototype does not share its validated base prefix"
                        }
                    }
                    StoredWidgetLayouts(connectionType.typeInfo, connectionSize, id, registry.address, first, last,
                        cardType.typeInfo, cardSize, prototype, state.first, state.second, sidebar,
                        types.evidence())
                }
            }
            return resolved.first.copy(evidence = resolved.first.evidence.copy(functions = resolved.second, readonly = readonly))
        }
    }
}
