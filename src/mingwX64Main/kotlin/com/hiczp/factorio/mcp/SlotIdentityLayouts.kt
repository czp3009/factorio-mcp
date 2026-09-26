@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.SlotIdentityLayout

/** One shared provider; both virtual positions and object members come from the target PDB. */
internal class SlotIdentityLayouts(
    types: DebugTypes,
    path: String,
    guid: ByteArray,
    age: Int,
    descriptors: List<ULong>,
) {
    private val type = descriptors.single()
    private val string = "std::basic_string<char,std::char_traits<char>,std::allocator<char> >"
    private val name =
        types.namedMember("PrototypeBase", "name", string, types.aggregateSize(string))
    private val qualityName =
        types.namedMember("QualityPrototype", "name", string, types.aggregateSize(string))
    private val methods =
        readPdbVirtualMethods(
            path,
            guid,
            age,
            "PrototypeProvider",
            emptyMap(),
            mapOf(
                "getBasePrototype" to "PrototypeBase",
                "getQualityPrototype" to "QualityPrototype",
            ),
        )

    fun write(target: SlotIdentityLayout) {
        target.type = type
        target.name = name
        target.qualityName = qualityName
        target.prototype = methods.getValue("getBasePrototype").offset
        target.quality = methods.getValue("getQualityPrototype").offset
        target.supported = 1u
    }
}
