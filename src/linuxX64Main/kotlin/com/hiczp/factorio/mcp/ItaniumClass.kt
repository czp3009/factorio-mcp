package com.hiczp.factorio.mcp

/**
 * Class RTTI follows https://itanium-cxx-abi.github.io/cxx-abi/abi.html#rtti-layout.
 * Constants below describe ABI fields; game base offsets come only from matching RTTI.
 */
internal data class ItaniumClass(val typeInfo: Long, val bases: List<Base>) {
    data class Base(val typeInfo: Long, val offset: Long, val public: Boolean, val virtual: Boolean)

    fun directBase(base: ItaniumClass, objectSize: Long, baseExtent: Long): Long {
        require(objectSize > 0 && baseExtent in 1..objectSize)
        val entry = bases.singleOrNull { it.typeInfo == base.typeInfo }
            ?: error("Required direct base is missing or ambiguous")
        require(entry.public && !entry.virtual && entry.offset in 0..objectSize - baseExtent) {
            "Direct base is not public, nonvirtual and within independently established object bounds"
        }
        return entry.offset
    }

    /** Resolves one public nonvirtual path; ambiguous, cyclic and unsupported inheritance is rejected. */
    fun baseOffset(
        base: ItaniumClass, objectSize: Long, baseExtent: Long,
        load: (Long) -> ItaniumClass
    ): Long {
        require(objectSize > 0 && baseExtent in 1..objectSize)
        return displacement(base, objectSize, load).also {
            require(it <= objectSize - baseExtent) { "Base field exceeds concrete object bounds" }
        }
    }

    /** RTTI association only. This displacement does not establish complete-object storage bounds. */
    fun baseDisplacement(base: ItaniumClass, load: (Long) -> ItaniumClass): Long =
        displacement(base, 64 * 1024 * 1024L, load)

    private fun displacement(base: ItaniumClass, objectSize: Long, load: (Long) -> ItaniumClass): Long {
        val matches = mutableListOf<Long>()
        var visited = 0
        fun visit(type: ItaniumClass, offset: Long, path: Set<Long>) {
            require(++visited <= 256 && path.size < 32 && type.typeInfo !in path) { "Class ancestry exceeds its acyclic bound" }
            require(offset in 0 until objectSize)
            if (type.typeInfo == base.typeInfo) {
                matches += offset
                return
            }
            for (entry in type.bases) {
                require(entry.public && !entry.virtual && entry.offset in 0 until objectSize - offset) {
                    "Class ancestry requires unsupported access, virtual bases or offsets"
                }
                val parent = load(entry.typeInfo)
                require(parent.typeInfo == entry.typeInfo)
                visit(parent, offset + entry.offset, path + type.typeInfo)
            }
        }
        visit(this, 0, emptySet())
        return matches.singleOrNull() ?: error("Required base is absent or ambiguous")
    }

    companion object {
        /** Relocation references are candidates only; each ancestry edge is checked against decoded RTTI. */
        fun descendants(image: ElfImage, encodedType: String, limit: Int = 64): List<String> {
            require(limit in 1..1024)
            val pointers = image.pointers
            val root = resolve(image, encodedType, pointers)
            val types = image.rttiSymbols.filter { it.size >= 16 }.distinctBy { it.name }
            require(types.size <= 65536)
            val pending = ArrayDeque<Long>()
            val found = linkedMapOf<Long, String>()
            val inspected = mutableMapOf<Long, ItaniumClass>()
            pending.add(root.typeInfo)
            while (pending.isNotEmpty()) {
                val parent = pending.removeFirst()
                val references = if (image.positionIndependent) pointers.relativeReferences(parent) else null
                val candidates = types.filter { type ->
                    references == null || references.any {
                        it >= type.address + 16 && it <= type.address + type.size - 8
                    }
                }
                for (symbol in candidates) {
                    val name = symbol.name.removePrefix("_ZTI")
                    val child = inspected.getOrPut(symbol.address) { resolve(image, name, pointers) }
                    if (child.bases.none { it.typeInfo == parent }) continue
                    require(child.typeInfo != root.typeInfo) { "Cyclic class descendants" }
                    if (child.typeInfo !in found) {
                        found[child.typeInfo] = name
                        require(found.size <= limit) { "Class descendants exceed the selected capacity" }
                        pending.add(child.typeInfo)
                    }
                }
            }
            return found.values.toList()
        }

        private val kinds = listOf(
            "N10__cxxabiv117__class_type_infoE",
            "N10__cxxabiv120__si_class_type_infoE", "N10__cxxabiv121__vmi_class_type_infoE"
        )

        fun resolve(image: ElfImage, encodedType: String, pointers: ElfPointers = image.pointers): ItaniumClass {
            require(encodedType.isNotEmpty())
            val type = image.symbol("_ZTI$encodedType")
            val name = image.symbol("_ZTS$encodedType")
            require(
                type.type == 1 && type.size in 16..(24 + 64 * 16) && type.size % 8 == 0L &&
                        name.type == 1 && name.size in 2..4096
            ) { "Invalid class RTTI symbols" }
            val words = pointers.words(type.address, (type.size / 8).toInt())
            require(
                words[1].pointer() == name.address &&
                        image.virtualBytes(name.address, name.size).string(0, name.size.toInt()) == encodedType
            ) {
                "Class RTTI name differs from the requested type"
            }
            val addressPoint = words[0].pointer()
            val tables = kinds.mapNotNull { image.findSymbol("_ZTV$it") }
            val kind = tables.singleOrNull { it.type == 1 && it.size >= 24 && it.address + 16 == addressPoint }
                ?.name?.removePrefix("_ZTV") ?: error("Class RTTI uses an unsupported or nonlocal metaclass")
            val table = image.symbol("_ZTV$kind")
            val header = pointers.words(table.address, 2)
            require(header[0].scalar() == 0L && header[1].pointer() == image.symbol("_ZTI$kind").address) {
                "Class RTTI metaclass lacks a primary vtable header"
            }
            val bases = when (kind) {
                kinds[0] -> {
                    require(type.size == 16L)
                    emptyList()
                }

                kinds[1] -> {
                    require(type.size == 24L)
                    listOf(Base(words[2].pointer(), 0, public = true, virtual = false))
                }

                else -> {
                    require(type.size >= 24)
                    val fields = words[2].scalar().toULong()
                    val flags = fields and 0xffffffffu
                    val count = fields shr 32
                    require(flags and 3uL.inv() == 0uL && count in 1uL..64uL && type.size == 24L + count.toLong() * 16) {
                        "Invalid class RTTI flags or base count"
                    }
                    List(count.toInt()) { index ->
                        val offsetFlags = words[4 + index * 2].scalar()
                        require(offsetFlags and 0xfcL == 0L) { "Unknown base-class flags" }
                        Base(
                            words[3 + index * 2].pointer(), offsetFlags shr 8,
                            public = offsetFlags and 2L != 0L, virtual = offsetFlags and 1L != 0L
                        )
                    }
                }
            }
            val knownTypes = image.typeInfoAddresses
            require(bases.all { it.typeInfo != type.address && it.typeInfo in knownTypes }) {
                "Class RTTI has a self or unresolved base reference"
            }
            require(bases.map { it.typeInfo }.distinct().size == bases.size) { "Class RTTI repeats a direct base" }
            return ItaniumClass(type.address, bases)
        }
    }
}
