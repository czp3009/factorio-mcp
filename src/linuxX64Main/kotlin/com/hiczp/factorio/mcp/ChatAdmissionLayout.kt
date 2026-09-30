@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxChatAdmissionConfig

/** Bounded configuration for fresh native chat admission. Loaded evidence must be checked separately. */
internal data class ChatAdmissionLayout(
    val world: WorldLayout,
    val player: PlayerLayout,
    val gate: ChatSubmissionGate,
    val mapSize: Long,
    val handlerSize: Long,
    val handler: ItaniumType,
) {
    init {
        require(
            world.gameSize == player.gameSize && mapSize in 8..(64 * 1024 * 1024) &&
                    handlerSize in 8..(64 * 1024 * 1024)
        )
        fun member(offset: Long, extent: Long) = offset >= 0 && offset <= extent - 8 && offset % 8 == 0L
        require(
            member(gate.playerMap, player.playerSize) && gate.playerMap >= 8 &&
                    member(gate.mapGame, mapSize) && member(gate.gameHandler, world.gameSize) &&
                    gate.gameHandler !in listOf(player.gamePlayer, player.gameView) &&
                    gate.handlerField in 8..handlerSize - 4 && gate.excluded in 0..UInt.MAX_VALUE.toLong()
        )
        require(
            handler.addressPoint in 16..Long.MAX_VALUE - 8 && handler.typeInfo in 8..Long.MAX_VALUE - 16 &&
                    handler.addressPoint % 8 == 0L && handler.typeInfo % 8 == 0L
        )
    }

    fun writeTo(output: FmLinuxChatAdmissionConfig, bias: Long) {
        require(
            bias >= 0 && bias % 8 == 0L && handler.addressPoint <= Long.MAX_VALUE - 8 - bias &&
                    handler.typeInfo <= Long.MAX_VALUE - 16 - bias
        )
        world.writeTo(output.world, bias)
        player.writeTo(output.player, bias)
        output.handlerVtable = (handler.addressPoint + bias).toULong()
        output.handlerTypeInfo = (handler.typeInfo + bias).toULong()
        output.mapSize = mapSize.toUInt()
        output.handlerSize = handlerSize.toUInt()
        output.playerMap = gate.playerMap.toUInt()
        output.mapGame = gate.mapGame.toUInt()
        output.gameHandler = gate.gameHandler.toUInt()
        output.handlerField = gate.handlerField.toUInt()
        output.excluded = gate.excluded.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage): ChatAdmissionLayout {
            val world = WorldLayout.resolve(image)
            val player = PlayerLayout.resolve(image, world.gameSize, LuaStateLayout.resolve(image))
            val mapSize = SysVOwnedObjectSize.resolve(
                image,
                "_ZNSt10unique_ptrI3MapSt14default_deleteIS0_EED2Ev", "_ZN3MapD2Ev"
            ).size
            val gate = ChatSubmissionGate.resolve(image)
            require(
                gate.playerMap == SysVArgumentMember.resolve(
                    image,
                    "_ZN6PlayerC2ER3MapR15MapDeserialiser", player.playerSize
                )
            ) {
                "Chat guard's Map differs from the native player constructor"
            }
            return ChatAdmissionLayout(
                world, player, gate, mapSize,
                SysVObjectSize.resolve(image, "17GameActionHandler"), ItaniumType.resolve(image, "17GameActionHandler")
            )
        }
    }
}
