package com.hiczp.factorio.mcp

/** Each supported virtual text accessor returns a separately bounded string member by reference. */
internal data class WidgetTextLayout(
    val slot: Int,
    val data: Long,
    val length: Long,
    val getters: List<Getter>,
) {
    data class Getter(val function: ElfImage.Symbol, val offset: Long, val objectSize: Long)

    companion object {
        private const val METHOD = "7getTextB5cxx11Ev"
        private const val STRING = "_ZNKSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEE"

        fun resolve(
            image: ElfImage,
            namespace: String = "4agui",
            base: String = "4agui6Widget",
            dataFunction: String = "${STRING}4dataEv",
            sizeFunction: String = "${STRING}4sizeEv",
        ): WidgetTextLayout {
            fun member(name: String): Long {
                val accessor = SysVAccessors.resolve(image, image.symbol(name))
                require(
                    accessor.width == 8 && accessor.mask == ULong.MAX_VALUE && accessor.shift == 0 &&
                            accessor.offset in 0..4096
                ) { "Unsupported native string accessor" }
                return accessor.offset
            }

            val data = member(dataFunction)
            val length = member(sizeFunction)
            require(data + 8 <= length || length + 8 <= data) { "String pointer and length overlap" }
            val extent = maxOf(data, length) + 8
            val slot = ItaniumVtable.resolve(image, "_ZTVN${base}E")
                .method(image, "_ZNK$base$METHOD").slot
            val getters = image.symbols().filter {
                it.type == 2 && it.name.startsWith("_ZNK$namespace") && it.name.endsWith(METHOD)
            }.distinctBy { it.name }.map { function ->
                val owner = function.name.removePrefix("_ZNK").removeSuffix(METHOD)
                val size = SysVObjectSize.resolve(image, owner)
                require(ItaniumVtable.resolve(image, "_ZTVN${owner}E").method(image, function.name).slot == slot) {
                    "Text override changes its primary virtual slot"
                }
                val offset = SysVAccessors.resolveAddress(image, function, extent, size)
                Getter(function, offset, size)
            }.toList()
            require(getters.size in 1..64 && getters.map { it.function.address }.distinct().size == getters.size) {
                "Missing, ambiguous or excessive native text accessors"
            }
            return WidgetTextLayout(slot, data, length, getters)
        }
    }
}
