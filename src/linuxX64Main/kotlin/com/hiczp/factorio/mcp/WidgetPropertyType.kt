package com.hiczp.factorio.mcp

/** Concrete primary identity and public nonvirtual base displacement for bounded property reads. */
internal data class WidgetPropertyType(val table: Long, val size: Long, val baseOffset: Long) {
    companion object {
        fun destructorOwner(type: String): String {
            require(type.isNotEmpty())
            return if (type.startsWith("N")) {
                require(type.endsWith("E"))
                type.drop(1).dropLast(1)
            } else type
        }

        fun resolve(image: ElfImage, concreteType: String, baseType: String, extent: Long): WidgetPropertyType {
            val size = SysVObjectSize.resolve(image, destructorOwner(concreteType))
            val pointers = image.pointers
            val names = image.rttiByAddress
            val cache = mutableMapOf<Long, ItaniumClass>()
            fun load(address: Long): ItaniumClass = cache.getOrPut(address) {
                val name = names[address]?.map { it.name }?.distinct()?.singleOrNull()
                    ?: error("Property ancestry has an ambiguous RTTI symbol")
                ItaniumClass.resolve(image, name.removePrefix("_ZTI"), pointers)
            }

            val concrete = load(image.symbol("_ZTI$concreteType").address)
            val base = load(image.symbol("_ZTI$baseType").address)
            val offset = concrete.baseOffset(base, size, extent, ::load)
            val identity = ItaniumType.resolve(image, concreteType)
            require(identity.typeInfo == concrete.typeInfo)
            return WidgetPropertyType(identity.addressPoint, size, offset)
        }
    }
}
