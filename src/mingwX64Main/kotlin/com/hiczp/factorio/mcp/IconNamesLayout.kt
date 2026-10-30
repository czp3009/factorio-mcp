@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.FmIconNamesLayout

/** The game resolves resource identity; the adapter preserves its returned registration key. */
internal class IconNamesLayout(
    types: DebugTypes,
    entries: List<ULong>,
    image: PeImage,
    module: ProcessModule,
    read: (Int, Int) -> ByteArray,
) {
    private val lookup = entries.single()
    private val registry =
        types.pointerPath(
            "GlobalContext",
            "utilitySprites",
            "value",
            target = "UtilitySprites",
            indirections = 1,
        )
    private val string = types.memberTypeName("LocalisedString", "key")
    private val size = types.aggregateSize(string).also { require(it in 1uL..256uL) }

    init {
        require(string == "std::basic_string<char,std::char_traits<char>,std::allocator<char> >")
        require(lookup >= module.base && lookup - module.base < module.size)
        val rva = (lookup - module.base).toLong()
        val end = image.functionEnd(rva)
        require(end - rva in 1..65536)
        val bytes = image.readonlyBytes(rva.toInt(), (end - rva).toInt())
        require(read(rva.toInt(), bytes.size).contentEquals(bytes)) {
            "Loaded sprite-name lookup differs from the selected executable"
        }
        SpriteNameAbi.verify(
            BinaryView(bytes),
            1,
            2,
            8,
            types.aggregateSize("UtilitySprites").toLong(),
            size.toLong(),
            setOf(0, 1, 2, 8, 9, 10, 11) + (16..21),
        )
    }

    fun write(target: FmIconNamesLayout) {
        target.lookup = lookup
        target.registry = registry
        target.stringSize = size.toUInt()
    }
}
