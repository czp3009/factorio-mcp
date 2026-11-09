package com.hiczp.factorio.mcp

/** Small requested RTTI closure, retaining relocated pointers separately from ABI flag/offset words. */
internal class NativeTypeEvidence(private val image: ElfImage) {
    private val types = mutableMapOf<String, ItaniumClass>()
    private val pointers = mutableMapOf<Long, Long>()
    private val scalars = mutableMapOf<Long, Long>()

    fun type(encoded: String): ItaniumClass = types.getOrPut(encoded) {
        require(types.size < 256) { "Requested RTTI closure exceeds bound" }
        val value = ItaniumClass.resolve(image, encoded)
        val symbol = image.symbol("_ZTI$encoded")
        image.pointers.words(symbol.address, (symbol.size / 8).toInt()).forEachIndexed { index, word ->
            val address = symbol.address + index * 8L
            if (index < 2 || symbol.size == 24L || index >= 3 && index % 2 == 1) pointers[address] = word.pointer()
            else scalars[address] = word.scalar()
        }
        value
    }

    fun at(address: Long): ItaniumClass {
        val name = image.rttiByAddress[address]?.map { it.name }?.distinct()?.singleOrNull()
            ?: error("Requested native base has no unique RTTI identity")
        return type(name.removePrefix("_ZTI"))
    }

    fun evidence() = ElfEvidence(emptyList(), emptyList(), pointers.toMap(), scalars.toMap())
}
