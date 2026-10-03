@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.*
import kotlinx.cinterop.get

/** Native loading and simulation flags, independent of Lua and local-player availability. */
internal data class GameStateMetadata(
    val world: WorldReferences,
    val mapSize: Long,
    val map: Long,
    val paused: Long,
    val stopped: Long,
    val loading: LoadingPredicate,
    private val evidence: ElfEvidence,
) {
    fun verifyLoaded(image: ElfImage, process: ProcessHandle, bias: Long) =
        evidence.verify(image, bias, process::readMemory)

    fun writeTo(output: FmLinuxGameStateConfig, bias: Long) {
        fun address(value: Long): ULong {
            require(bias >= 0 && bias % 8 == 0L && value > 0 && value <= Long.MAX_VALUE - 16 - bias)
            return (value + bias).toULong()
        }

        fun predicate(target: FmLinuxVirtualBoolean, table: ItaniumSubobjectVtable, method: ItaniumVtable.Method) {
            target.table = address(table.addressPoint)
            target.typeInfo = address(table.pointers.getValue(table.addressPoint - 8))
            target.function = address(method.function.address)
            target.adjustment = table.baseOffset.toUInt()
            target.slot = method.slot.toUInt()
        }

        require(loading.states.size in 1..FM_LINUX_LOADING_STATES.toInt())
        require(loading.multiplayer.size in 1..FM_LINUX_MULTIPLAYER_BINDINGS.toInt())
        output.global = address(world.global)
        output.globalSize = world.globalSize.toUInt()
        output.scenarioSize = world.scenarioSize.toUInt()
        output.gameSize = world.gameSize.toUInt()
        output.mapSize = mapSize.toUInt()
        output.scenario = world.scenario.toUInt()
        output.game = world.game.toUInt()
        output.map = map.toUInt()
        output.paused = paused.toUInt()
        output.stopped = stopped.toUInt()
        output.appManager = loading.manager.pointer.toUInt()
        output.appManagerSize = loading.manager.size.toUInt()
        output.statesBegin = loading.begin.toUInt()
        output.statesEnd = loading.end.toUInt()
        output.stateCount = loading.states.size.toUInt()
        loading.states.forEachIndexed { index, state -> predicate(output.states[index], state.table, state.method) }
        output.managerCount = loading.multiplayer.size.toUInt()
        loading.multiplayer.forEachIndexed { index, manager ->
            val target = output.managers[index]
            target.primary = address(manager.primary.addressPoint)
            target.member = manager.member.toUInt()
            target.size = manager.size.toUInt()
            predicate(target.predicate, manager.table, manager.method)
        }
    }

    companion object {
        fun resolve(image: ElfImage): GameStateMetadata {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val world = WorldReferences.resolve(image)
                    val mapSize = SysVOwnedObjectSize.resolve(image,
                        "_ZNSt10unique_ptrI3MapSt14default_deleteIS0_EED2Ev", "_ZN3MapD2Ev").size
                    val map = SysVArgumentMember.resolve(image,
                        "_ZN4GameC2ER3MapR8Scenario8LoadType9InputTypeP11InputSource", world.gameSize)
                    val pause = InputPauseField.resolve(image, mapSize).mapPaused
                    val stop = MapStopField.resolve(image, mapSize)
                    val loading = LoadingPredicate.resolve(image, world.global, world.globalSize)
                    val tables = loading.states.map { it.table } + loading.multiplayer.map { it.table }
                    val pointers = tables.flatMap { it.pointers.entries }.associate { it.toPair() }
                    val scalars = tables.flatMap { it.scalars.entries }.associate { it.toPair() }
                    GameStateMetadata(world, mapSize, map, pause, stop, loading,
                        ElfEvidence(emptyList(), emptyList(), pointers, scalars))
                }
            }
            return resolved.first.copy(evidence = resolved.first.evidence.copy(functions = resolved.second, readonly = readonly))
        }
    }
}
