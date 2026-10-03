@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxInputContextConfig
import kotlinx.cinterop.set

/** Fresh world/input/clock observations. Does not establish a hook, dispatch ABI or cross-frame lifetime. */
internal data class InputContextMetadata(
    val world: WorldLayout,
    val script: LuaScriptLayout,
    val context: LuaContextLayout,
    val source: InputSourceLayout,
    val gameSource: Long,
    val sourceType: ItaniumType,
    val playerType: ItaniumType,
    val viewType: ItaniumType,
    val handlerType: ItaniumType,
    val handlerSize: Long,
    val handlers: List<Long>,
    val tick: ScriptTickField,
    val stop: Long,
    val pause: InputPauseField,
    val evaluationCaller: Long,
    val forwarded: ForwardedInputSource,
    private val functions: List<ElfImage.Symbol> = emptyList(),
    private val readonly: List<ElfImage.ReadonlyRange> = emptyList(),
    private val pointers: Map<Long, Long> = emptyMap(),
) {
    fun writeTo(output: FmLinuxInputContextConfig, bias: Long) {
        require(world.gameSize == source.gameSize && world.contextSize == context.size && handlers.size in 1..64)
        world.writeTo(output.world, bias)
        context.writeTo(output.script, script, bias)
        fun address(value: Long): ULong {
            require(bias >= 0 && bias % 8 == 0L && value > 0 && value <= Long.MAX_VALUE - 16 - bias)
            return (value + bias).toULong()
        }

        val input = output.input
        input.sourceVtable = address(sourceType.addressPoint)
        input.sourceTypeInfo = address(sourceType.typeInfo)
        input.playerVtable = address(playerType.addressPoint)
        input.playerTypeInfo = address(playerType.typeInfo)
        input.viewVtable = address(viewType.addressPoint)
        input.viewTypeInfo = address(viewType.typeInfo)
        input.handlerVtable = address(handlerType.addressPoint)
        input.handlerTypeInfo = address(handlerType.typeInfo)
        input.sourceSize = source.sourceSize.toUInt()
        input.playerSize = source.playerSize.toUInt()
        input.mapSize = source.mapSize.toUInt()
        input.viewSize = source.viewSize.toUInt()
        input.handlerSize = handlerSize.toUInt()
        input.globalSource = source.globalSource.toUInt()
        input.sourcePlayer = source.sourcePlayer.toUInt()
        input.playerMap = source.playerMap.toUInt()
        input.mapGame = source.mapGame.toUInt()
        input.gameView = source.gameView.toUInt()
        input.gameSource = gameSource.toUInt()
        input.viewPlayer = source.viewPlayer.toUInt()
        input.scriptMap = tick.scriptMap.toUInt()
        input.mapTick = tick.mapTick.toUInt()
        input.mapStop = stop.toUInt()
        input.mapPaused = pause.mapPaused.toUInt()
        input.handlerMap = pause.handlerMap.toUInt()
        input.handlerSource = pause.handlerSource.toUInt()
        input.handlerCount = handlers.size.toUInt()
        input.forwardedVtable = address(forwarded.type.addressPoint)
        input.forwardedTypeInfo = address(forwarded.type.typeInfo)
        input.forwardedSize = forwarded.size.toUInt()
        input.forwardedSource = forwarded.source.toUInt()
        handlers.forEachIndexed { index, member -> input.handlers[index] = member.toUInt() }
    }

    fun verifyLoaded(image: ElfImage, process: ProcessHandle, bias: Long) = verify(image, bias, process::readMemory)

    internal fun verify(image: ElfImage, bias: Long, read: (Long, Int) -> ByteArray) {
        require(bias >= 0)
        fun compare(address: Long, expected: ByteArray) {
            require(
                expected.isNotEmpty() && expected.size <= 16 * 1024 * 1024 &&
                        address > 0 && address <= Long.MAX_VALUE - bias - expected.size
            )
            require(read(address + bias, expected.size).contentEquals(expected)) {
                "Live input context evidence differs from the selected executable"
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

    companion object {
        fun resolve(image: ElfImage): InputContextMetadata {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val world = WorldLayout.resolve(image)
                    val script = LuaScriptLayout.resolve(image)
                    val context = LuaContextLayout.resolve(image, script.size)
                    val source = InputSourceLayout.resolve(image, world.global, world.globalSize)
                    val gameSource = EventSenderStateCalls.sourceDispatch(image, source).gameSource
                    val forwarded = ForwardedInputSource.resolve(image, source.evaluation.slot)
                    require(world.gameSize == source.gameSize && world.contextSize == context.size)
                    val types = listOf(
                        "17PlayerInputSource", "6Player", "8GameView", "17GameActionHandler",
                        "10LuaContext", "13LuaGameScript", "11InputSource", "19NetworkInputHandler"
                    ).associateWith { ItaniumType.resolve(image, it) }
                    val handlerType = types.getValue("17GameActionHandler")
                    val handlerSize = SysVObjectSize.resolve(image, "17GameActionHandler")
                    val pause = InputPauseField.resolve(image, source.mapSize)
                    val evaluationCaller = EvaluationCallSite.resolve(
                        image, handlerSize, pause.handlerSource,
                        source.evaluation.slot
                    )
                    val handlers = TypedMemberStores.resolve(
                        image,
                        "_ZN4GameC2ER3MapR8Scenario8LoadType9InputTypeP11InputSource", source.gameSize, handlerType
                    )
                    val pointers = image.pointers
                    val words = mutableMapOf<Long, Long>()
                    for (type in types.values) {
                        words[type.addressPoint - 16] = 0
                        words[type.addressPoint - 8] = type.typeInfo
                        words[type.typeInfo + 8] = pointers.words(type.typeInfo + 8, 1).single().pointer()
                    }
                    // Complete primary tables consulted for method-slot derivation. Multiple-table groups used
                    // only for concrete identity above do not imply any callable secondary slots.
                    for (name in listOf("17PlayerInputSource", "11InputSource", "10LuaContext", "13LuaGameScript", "19NetworkInputHandler")) {
                        val table = image.symbol("_ZTV$name")
                        ItaniumVtable.resolve(image, table.name)
                        pointers.words(table.address, (table.size / 8).toInt()).forEachIndexed { index, word ->
                            words[table.address + index * 8L] = if (index == 0) word.scalar() else word.pointer()
                        }
                    }
                    InputContextMetadata(
                        world, script, context, source, gameSource, types.getValue("17PlayerInputSource"),
                        types.getValue("6Player"), types.getValue("8GameView"), handlerType,
                        handlerSize, handlers,
                        ScriptTickField.resolve(image, script.size, source.mapSize),
                        MapStopField.resolve(image, source.mapSize), pause, evaluationCaller, forwarded,
                        pointers = words
                    )
                }
            }
            return resolved.first.copy(functions = resolved.second, readonly = readonly)
        }
    }
}
