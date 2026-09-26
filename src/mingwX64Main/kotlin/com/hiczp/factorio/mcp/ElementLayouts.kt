@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.ElementLayout

/** Reads existing UI provider elements; never materializes a stack's optional Item object. */
internal class ElementLayouts(
    types: DebugTypes,
    private val path: String,
    private val guid: ByteArray,
    private val age: Int,
    private val descriptors: Map<String, List<ULong>>,
) {
    private fun descriptor(name: String) = descriptors.getValue(name).single()

    private val stackProvider = descriptor("??_R0?AV?\$ElemProvider@VItemStack@@@@@8")
    private val itemProvider = descriptor("??_R0?AV?\$ElemProvider@VItem@@@@@8")
    private val itemType = descriptor("??_R0?AVItem@@@8")
    private val toolType = descriptor("??_R0?AVTool@@@8")
    private val ammoType = descriptor("??_R0?AVAmmoItem@@@8")

    private fun getter(name: String) =
        readPdbVirtualMethods(
            path,
            guid,
            age,
            "ElemProvider<$name>",
            emptyMap(),
            mapOf("getElem" to name),
        )
            .getValue("getElem")
            .offset

    private val stackGetter = getter("ItemStack")
    private val itemGetter = getter("Item")
    private val stackItem = types.pointerMember("ItemStack", "item", "Item")
    private val count = types.scalarMember("ItemStack", "count", 4uL, 7u)
    private val health = types.scalarMember("Item", "health", 4uL, 8u)
    private val durability = types.scalarMember("Tool", "durabilityLeft", 8uL, 8u)
    private val magazine = types.scalarMember("AmmoItem", "magazineLeft", 4uL, 8u)

    fun write(target: ElementLayout) {
        target.stackProvider = stackProvider
        target.itemProvider = itemProvider
        target.itemType = itemType
        target.toolType = toolType
        target.ammoType = ammoType
        target.stackGetter = stackGetter
        target.itemGetter = itemGetter
        target.stackItem = stackItem
        target.count = count
        target.health = health
        target.durability = durability
        target.magazine = magazine
        target.supported = 1u
    }
}
