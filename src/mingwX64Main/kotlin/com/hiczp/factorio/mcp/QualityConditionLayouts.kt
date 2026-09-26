@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.QualityConditionLayout

/** Shared item-filter provider; preserves the raw condition without interpreting its domain. */
internal class QualityConditionLayouts(
    types: DebugTypes,
    path: String,
    guid: ByteArray,
    age: Int,
    descriptors: List<ULong>,
    registries: List<ULong>,
) {
    private val type = descriptors.single()
    private val idType = "IDWithQualityFilter<ID<ItemPrototype,unsigned short> >"
    private val provider = "IDButtonProvider<$idType >"
    private val size = types.aggregateSize(idType).also { require(it in 1uL..64uL) }
    private val getter = run {
        require(types.aggregateSize(provider) == 8uL)
        require(types.directBaseOffset(provider, "PrototypeProvider") == 0u)
        // Caller/callee disassembly verifies RCX=adjusted provider, RDX=return storage,
        // RAX=the same storage. CodeView must also retain the C++ ReturnUDT flag.
        readPdbVirtualMethods(
            path,
            guid,
            age,
            provider,
            emptyMap(),
            aggregateSignatures = mapOf("getID" to idType),
            primaryBase = "PrototypeProvider",
        )
            .getValue("getID")
            .offset
    }
    private val condition =
        types.namedMember(
            idType,
            "qualityCondition",
            "QualityCondition",
            types.aggregateSize("QualityCondition"),
        )
    private val qualityType = "ID<QualityPrototype,unsigned char>"
    private val quality =
        condition +
                types.namedMember(
                    "QualityCondition",
                    "qualityID",
                    qualityType,
                    types.aggregateSize(qualityType),
                ) +
                types.byteMember(qualityType, "index", false)
    private val comparison =
        condition +
                types.namedMember(
                    "QualityCondition",
                    "comparison",
                    "Comparison",
                    types.aggregateSize("Comparison"),
                ) +
                types.namedMember("Comparison", "value", "Comparison::Enum", 1uL)
    private val comparisons =
        types.enumValues("Comparison::Enum").entries.associate { it.value to it.key }
    private val registryType = "std::vector<QualityPrototype *,std::allocator<QualityPrototype *> >"
    private val registry =
        registries.single().also {
            types.validateStaticMember(
                "PrototypeList<QualityPrototype>",
                "indexToPrototype",
                registryType,
            )
        }
    private val first =
        types.pointerPath(
            registryType,
            "_Mypair",
            "_Myval2",
            "_Myfirst",
            target = "QualityPrototype",
            indirections = 2,
        )
    private val last =
        types.pointerPath(
            registryType,
            "_Mypair",
            "_Myval2",
            "_Mylast",
            target = "QualityPrototype",
            indirections = 2,
        )
    private val string = "std::basic_string<char,std::char_traits<char>,std::allocator<char> >"
    private val name =
        types.namedMember("QualityPrototype", "name", string, types.aggregateSize(string))

    fun comparison(value: Int): String = comparisons[value] ?: "unknown_$value"

    fun write(target: QualityConditionLayout) {
        target.type = type
        target.size = size.toUInt()
        target.getter = getter
        target.quality = quality
        target.comparison = comparison
        target.registry = registry
        target.first = first
        target.last = last
        target.name = name
        target.supported = 1u
    }
}
