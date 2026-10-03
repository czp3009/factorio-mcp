package com.hiczp.factorio.mcp

/** A nonvirtual base address point in a complete Itanium vtable group, connected to matching class RTTI. */
internal data class ItaniumSubobjectVtable(
    val addressPoint: Long,
    val baseOffset: Long,
    val entries: List<Long>,
    val pointers: Map<Long, Long>,
    val scalars: Map<Long, Long>,
) {
    fun method(image: ElfImage, name: String): ItaniumVtable.Method {
        val function = image.symbol(name)
        image.functionBytes(function, 1)
        EhFrames(image).function(function)
        val slot = entries.indices.singleOrNull { entries[it] == function.address }
            ?: error("Method is absent or ambiguous in the verified base table: $name")
        return ItaniumVtable.Method(addressPoint, slot, function)
    }

    companion object {
        fun resolve(image: ElfImage, concreteType: String, baseType: String, size: Long): ItaniumSubobjectVtable {
            require(size in 8..(16 * 1024 * 1024))
            return resolveTable(image, concreteType, baseType, size)
        }

        /** A typed interface vptr and dispatch only; this never authorizes complete-object member reads. */
        fun resolveInterface(image: ElfImage, concreteType: String, baseType: String): ItaniumSubobjectVtable =
            resolveTable(image, concreteType, baseType, null)

        private fun resolveTable(image: ElfImage, concreteType: String, baseType: String, objectSize: Long?): ItaniumSubobjectVtable {
            val loaded = mutableMapOf<Long, ItaniumClass>()
            fun load(address: Long): ItaniumClass = loaded.getOrPut(address) {
                val name = image.rttiByAddress[address]?.map { it.name }?.distinct()?.singleOrNull()
                    ?: error("Subobject has no unique matching RTTI symbol")
                ItaniumClass.resolve(image, name.removePrefix("_ZTI"))
            }
            val concrete = load(image.symbol("_ZTI$concreteType").address)
            val base = load(image.symbol("_ZTI$baseType").address)
            val offset = if (objectSize == null) concrete.baseDisplacement(base, ::load)
                else concrete.baseOffset(base, objectSize, 8, ::load)
            // Nonvirtual ancestry has no vbase/vcall words before an address point. Reject those layouts.
            val visited = mutableSetOf<Long>()
            fun validate(type: ItaniumClass) {
                require(visited.size < 256)
                if (!visited.add(type.typeInfo)) return
                require(type.bases.none { it.virtual }) { "Virtual ancestry requires a different table-group proof" }
                type.bases.forEach { validate(load(it.typeInfo)) }
            }
            validate(concrete)
            ItaniumType.resolve(image, concreteType)
            val table = image.symbol("_ZTV$concreteType")
            require(table.type == 1 && table.size in 24..65536 && table.size % 8 == 0L)
            val words = image.pointers.words(table.address, (table.size / 8).toInt())
            val size = objectSize ?: words.zipWithNext().mapNotNull { (word, next) ->
                if (word.relocation != null || word.raw > 0 ||
                    runCatching { next.pointer() != concrete.typeInfo }.getOrDefault(true)) return@mapNotNull null
                require(word.raw in -(16 * 1024 * 1024L - 8)..0) { "Interface table displacement exceeds bounds" }
                -word.raw + 8
            }.maxOrNull() ?: error("Interface vtable has no matching RTTI header")
            val result = analyze(table.address, concrete.typeInfo, offset, size, words, image.functionAddresses)
            val pointers = result.pointers.toMutableMap()
            val scalars = result.scalars.toMutableMap()
            for (address in loaded.keys) {
                val symbol = image.rttiByAddress.getValue(address).distinctBy { it.name }.single()
                image.pointers.words(address, (symbol.size / 8).toInt()).forEachIndexed { index, word ->
                    if (index < 2 || symbol.size == 24L || index >= 3 && index % 2 == 1)
                        pointers[address + index * 8L] = word.pointer()
                    else scalars[address + index * 8L] = word.scalar()
                }
            }
            return result.copy(pointers = pointers, scalars = scalars)
        }

        internal fun analyze(
            address: Long, type: Long, offset: Long, size: Long,
            words: List<ElfPointers.Word>, functions: Set<Long>,
        ): ItaniumSubobjectVtable {
            require(address > 0 && address % 8 == 0L && type > 0 && offset in 0..size - 8)
            require(words.size in 3..8192 && address <= Long.MAX_VALUE - words.size * 8L)
            val pointers = mutableMapOf<Long, Long>()
            val scalars = mutableMapOf<Long, Long>()
            val groups = mutableListOf<Pair<Int, Long>>()
            var index = 0
            while (index < words.size) {
                val word = words[index]
                val header = word.relocation == null && word.raw <= 0L && index + 1 < words.size &&
                        runCatching { words[index + 1].pointer() == type }.getOrDefault(false)
                if (header) {
                    val displacement = word.scalar()
                    require(displacement >= -(size - 8)) { "Subobject table exceeds the complete object" }
                    require(groups.none { it.second == displacement }) { "Duplicate subobject table displacement" }
                    require(groups.isNotEmpty() || displacement == 0L) { "Missing primary table" }
                    groups += index to displacement
                    scalars[address + index * 8L] = displacement
                    pointers[address + (index + 1) * 8L] = type
                    index += 2
                } else {
                    require(groups.isNotEmpty())
                    val function = word.pointer()
                    require(function == 0L || function in functions) { "Nonlocal or nonfunction virtual entry" }
                    pointers[address + index * 8L] = function
                    index++
                }
            }
            val selected = groups.indexOfFirst { it.second == -offset }
            require(selected >= 0) { "RTTI base has no corresponding table header" }
            val first = groups[selected].first + 2
            val end = groups.getOrNull(selected + 1)?.first ?: words.size
            require(end - first in 1..256)
            return ItaniumSubobjectVtable(address + first * 8L, offset,
                words.subList(first, end).map { it.pointer() }, pointers, scalars)
        }
    }
}
