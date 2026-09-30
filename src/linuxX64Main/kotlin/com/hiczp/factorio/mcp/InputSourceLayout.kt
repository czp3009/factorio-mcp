package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Structural input evaluation owner. Hook installation, thread/phase admission and tick/pause reads are separate. */
internal data class InputSourceLayout(
    val globalSource: Long,
    val sourceSize: Long,
    val sourcePlayer: Long,
    val playerSize: Long,
    val playerMap: Long,
    val mapSize: Long,
    val mapGame: Long,
    val gameSize: Long,
    val gameView: Long,
    val viewSize: Long,
    val viewPlayer: Long,
    val evaluation: ItaniumVtable.Method,
) {
    companion object {
        fun resolve(image: ElfImage, global: Long, globalSize: Long): InputSourceLayout {
            val sourceSize = SysVObjectSize.resolve(image, "17PlayerInputSource")
            val setter = image.symbol("_ZN17PlayerInputSource15connectToPlayerEP6Player")
            val sourcePlayer = SysVArgumentMember.resolve(image, setter.name, sourceSize)
            val caller = image.symbol("_ZN4Game15connectToPlayerEP6Playerb")
            val flow = X64ControlFlow.resolve(image, caller)
            val calls = flow.instructions.filter {
                it.operation == Operation.CALL &&
                        it.destination == Immediate(setter.address - caller.address)
            }
            require(calls.size == 1)
            val call = calls.single().offset
            require(SysVArgumentFlow(flow).register(call, 6) == SysVArgumentFlow.Reference(6)) {
                "Game does not forward its original Player to its input source"
            }
            val globalSource = GlobalPointerLoad(flow, caller.address, global, globalSize).at(call, 7)
            val playerSize = SysVLineAllocation.resolve(
                image,
                "_ZN3Map8loadDataER15MapDeserialiserRK17GlobalModSettingsP16ProgressObserver",
                "_ZN6PlayerC2ER3MapR15MapDeserialiser"
            )
            val playerMap = SysVArgumentMember.resolve(image, "_ZN6PlayerC2ER3MapR15MapDeserialiser", playerSize)
            fun owned(type: String) = SysVOwnedObjectSize.resolve(
                image,
                "_ZNSt10unique_ptrI${type}St14default_deleteIS0_EED2Ev", "_ZN${type}D2Ev"
            ).size

            val mapSize = owned("3Map")
            val gameSize = owned("4Game")
            val gameView = SysVMemberCalls.directPrefix(image, "_ZN4GameD2Ev", "_ZN8GameView9unloadGuiEv", gameSize)
            val viewSize = SysVObjectSize.resolve(image, "8GameView")
            val viewPlayer = SysVArgumentMember.resolve(
                image,
                "_ZN8GameViewC2ER4GameP6Player9NamedBoolI15IsSImulationTagEP18EngineFramebuffersS4_I17MuteWindSoundsTagE",
                viewSize,
                2
            )
            val evaluation = ItaniumVtable.resolve(image, "_ZTV17PlayerInputSource")
                .method(image, "_ZN17PlayerInputSource16sendStateChangesEv")
            val mapGame = InputOwnerPrefix.analyze(
                image.functionBytes(evaluation.function, 512), evaluation.function.size,
                listOf(sourceSize, playerSize, mapSize, gameSize, viewSize), sourcePlayer, playerMap,
                listOf(gameView, viewPlayer)
            )
            return InputSourceLayout(
                globalSource, sourceSize, sourcePlayer, playerSize, playerMap,
                mapSize, mapGame, gameSize, gameView, viewSize, viewPlayer, evaluation
            )
        }
    }
}
