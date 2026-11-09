@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.FmUiAchievementLayout
import com.hiczp.factorio.mcp.nativebridge.FmUiStoredPrototypeLayout

/** Native UI references absent from LuaGuiElement and the shared PrototypeProvider interface. */
internal class StoredWidgetLayouts(
    private val types: DebugTypes,
    descriptors: Map<String, List<ULong>>,
    registries: List<ULong>,
) {
    private val connection = "UniverseWidget::SpaceConnectionWidget"
    private val card = "AchievementCard"
    private val id = "ID<SpaceConnectionPrototype,unsigned short>"
    private val string = "std::basic_string<char,std::char_traits<char>,std::allocator<char> >"
    private val connectionType = descriptors.getValue("??_R0?AVSpaceConnectionWidget@UniverseWidget@@@8").single()
    private val cardType = descriptors.getValue("??_R0?AVAchievementCard@@@8").single()
    private val registryType = "std::vector<SpaceConnectionPrototype *,std::allocator<SpaceConnectionPrototype *> >"
    private val registry = registries.single().also {
        types.validateStaticMember("PrototypeList<SpaceConnectionPrototype>", "indexToPrototype", registryType)
    }
    private val connectionId = types.namedMember(connection, "connectionID", id, types.aggregateSize(id)) +
        types.scalarMember(id, "index", 2uL, 7u)
    private val prototype = types.pointerMember(card, "achievementPrototype", "AchievementPrototype")
    private val state = types.namedMember(card, "state", "AchievementState", 4uL)
    private val shownState = types.namedMember(card, "shownState", "AchievementState", 4uL)
    private val sidebar = types.byteMember(card, "inSidebar", true)
    private val states = types.enumValues("AchievementState")
    private val connectionSize = size(connection)
    private val cardSize = size(card)
    private val first = types.pointerPath(registryType, "_Mypair", "_Myval2", "_Myfirst", target = "SpaceConnectionPrototype", indirections = 2)
    private val last = types.pointerPath(registryType, "_Mypair", "_Myval2", "_Mylast", target = "SpaceConnectionPrototype", indirections = 2)
    private val connectionName = types.namedMember("SpaceConnectionPrototype", "name", string, types.aggregateSize(string))
    private val cardName = types.namedMember("AchievementPrototype", "name", string, types.aggregateSize(string))

    init {
        require(states.isNotEmpty())
    }

    private fun size(owner: String) = types.aggregateSize(owner).also { require(it in 8uL..16uL * 1024u * 1024u) }.toUInt()

    fun write(connectionOut: FmUiStoredPrototypeLayout, cardOut: FmUiStoredPrototypeLayout, achievement: FmUiAchievementLayout) {
        connectionOut.type = connectionType
        connectionOut.registry = registry
        connectionOut.supported = 1u
        connectionOut.size = connectionSize
        connectionOut.field = connectionId
        connectionOut.indirect = 1u
        connectionOut.first = first
        connectionOut.last = last
        connectionOut.name = connectionName
        cardOut.type = cardType
        cardOut.supported = 1u
        cardOut.size = cardSize
        cardOut.field = prototype
        cardOut.name = cardName
        achievement.type = cardType
        achievement.supported = 1u
        achievement.size = cardSize
        achievement.state = state
        achievement.shownState = shownState
        achievement.inSidebar = sidebar
    }
}
