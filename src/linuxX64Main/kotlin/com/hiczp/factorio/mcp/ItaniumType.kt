package com.hiczp.factorio.mcp

/** Primary RTTI identity only; secondary tables in the group do not establish callable virtual slots. */
internal data class ItaniumType(val addressPoint: Long, val typeInfo: Long) {
    companion object {
        fun resolve(image: ElfImage, encodedType: String): ItaniumType {
            require(encodedType.isNotEmpty())
            val table = image.symbol("_ZTV$encodedType")
            val type = image.symbol("_ZTI$encodedType")
            val name = image.symbol("_ZTS$encodedType")
            require(
                table.type == 1 && table.size in 24..65536 && table.size % 8 == 0L &&
                        type.type == 1 && type.size >= 16 && name.type == 1 && name.size in 2..4096
            )
            val pointers = ElfPointers(image)
            val header = pointers.words(table.address, 2)
            require(
                header[0].scalar() == 0L && header[1].pointer() == type.address &&
                        pointers.words(type.address + 8, 1).single().pointer() == name.address &&
                        image.virtualBytes(name.address, name.size).string(0, name.size.toInt()) == encodedType
            ) {
                "Primary RTTI header does not identify the expected type"
            }
            return ItaniumType(table.address + 16, type.address)
        }
    }
}
