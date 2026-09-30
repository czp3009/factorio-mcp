@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxPlayerLayout

/** Typed player references, native selection precedence and the unsigned Lua index field. */
internal data class PlayerLayout(
    val gameSize: Long,
    val playerSize: Long,
    val viewSize: Long,
    val gamePlayer: Long,
    val gameView: Long,
    val viewPlayer: Long,
    val index: PlayerIndex,
    val playerVtable: Long,
    val playerTypeInfo: Long,
    val viewVtable: Long,
    val viewTypeInfo: Long,
) {
    init {
        require(listOf(gameSize, playerSize, viewSize).all { it in 8..(64 * 1024 * 1024) })
        require(
            gamePlayer in 0..gameSize - 8 && gameView in 0..gameSize - 8 && gamePlayer != gameView &&
                    viewPlayer in 8..viewSize - 8 && listOf(gamePlayer, gameView, viewPlayer).all { it % 8 == 0L })
        require(index.width in listOf(1, 2) && index.offset in 8..playerSize - index.width)
        require(listOf(playerVtable, playerTypeInfo, viewVtable, viewTypeInfo).all {
            it >= 16 && it % 8 == 0L && it <= Long.MAX_VALUE - 16
        } && playerVtable != viewVtable && playerTypeInfo != viewTypeInfo)
    }

    fun writeTo(output: FmLinuxPlayerLayout, loadBias: Long) {
        require(
            loadBias >= 0 && loadBias % 8 == 0L &&
                    listOf(
                        playerVtable,
                        playerTypeInfo,
                        viewVtable,
                        viewTypeInfo
                    ).all { it <= Long.MAX_VALUE - 16 - loadBias })
        output.gameSize = gameSize.toUInt()
        output.playerSize = playerSize.toUInt()
        output.viewSize = viewSize.toUInt()
        output.gamePlayer = gamePlayer.toUInt()
        output.gameView = gameView.toUInt()
        output.viewPlayer = viewPlayer.toUInt()
        output.index = index.offset.toUInt()
        output.indexWidth = index.width.toUInt()
        output.playerVtable = (playerVtable + loadBias).toULong()
        output.playerTypeInfo = (playerTypeInfo + loadBias).toULong()
        output.viewVtable = (viewVtable + loadBias).toULong()
        output.viewTypeInfo = (viewTypeInfo + loadBias).toULong()
    }

    companion object {
        fun resolve(image: ElfImage, gameSize: Long, state: LuaStateLayout): PlayerLayout {
            val playerSize = SysVLineAllocation.resolve(
                image,
                "_ZN3Map8loadDataER15MapDeserialiserRK17GlobalModSettingsP16ProgressObserver",
                "_ZN6PlayerC2ER3MapR15MapDeserialiser"
            )
            val viewSize = SysVObjectSize.resolve(image, "8GameView")
            val direct = SysVArgumentMember.resolve(image, "_ZN4Game15connectToPlayerEP6Playerb", gameSize)
            val view = SysVMemberCalls.directPrefix(image, "_ZN4GameD2Ev", "_ZN8GameView9unloadGuiEv", gameSize)
            val indirect = SysVArgumentMember.resolve(
                image,
                "_ZN8GameViewC2ER4GameP6Player9NamedBoolI15IsSImulationTagEP18EngineFramebuffersS4_I17MuteWindSoundsTagE",
                viewSize,
                2
            )
            LocalPlayerSelection.verify(image, playerSize, direct, view, indirect)
            val playerType = ItaniumType.resolve(image, "6Player")
            val viewType = ItaniumType.resolve(image, "8GameView")
            return PlayerLayout(
                gameSize, playerSize, viewSize, direct, view, indirect,
                PlayerIndex.resolve(image, playerSize, state),
                playerType.addressPoint, playerType.typeInfo, viewType.addressPoint, viewType.typeInfo
            )
        }
    }
}
