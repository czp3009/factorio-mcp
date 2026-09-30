package com.hiczp.factorio.mcp

/** Validates declaring types, field widths and member bounds before exposing a native offset. */
internal class DwarfTypes(private val info: DwarfInfo, requested: Set<String>) {
    data class Member(val offset: Long, val size: Long, val type: DwarfInfo.Entry)

    data class VirtualMethod(val slot: Long, val result: DwarfInfo.Entry?, val parameters: List<DwarfInfo.Entry>)

    private val candidates = info.findTypes(requested)
    private val children = mutableMapOf<Long, List<DwarfInfo.Entry>>()

    private fun children(type: DwarfInfo.Entry): List<DwarfInfo.Entry> =
        children.getOrPut(type.offset) { info.children(type) }

    private fun number(entry: DwarfInfo.Entry, attribute: Int): Long? =
        (info.attribute(entry, attribute) as? DwarfInfo.Value.Number)?.value

    private fun reference(entry: DwarfInfo.Entry, attribute: Int): DwarfInfo.Entry? =
        (info.attribute(entry, attribute) as? DwarfInfo.Value.Reference)?.let { info.entry(it.offset) }

    fun canonical(entry: DwarfInfo.Entry): DwarfInfo.Entry {
        var current = entry
        val seen = mutableSetOf<Long>()
        while (current.tag in setOf(0x16, 0x26, 0x35, 0x37, 0x47)) {
            require(seen.add(current.offset) && seen.size < 64) { "Cyclic DWARF type qualifiers" }
            current = reference(current, 0x49) ?: error("Missing DWARF qualified type")
        }
        return current
    }

    fun size(type: DwarfInfo.Entry): Long {
        val canonical = canonical(type)
        val size = number(canonical, 0x0b)
            ?: if (canonical.tag in setOf(0x0f, 0x10, 0x42)) 8L else error("Missing DWARF type size")
        require(size in 1..(64 * 1024 * 1024)) { "DWARF type size exceeds bound" }
        return size
    }

    private fun definitions(name: String): List<DwarfInfo.Entry> =
        candidates[name].orEmpty().map(::canonical).filter { number(it, 0x3c) != 1L && number(it, 0x0b) != null }
            .also { require(it.isNotEmpty()) { "Missing complete DWARF type: $name" } }

    fun size(name: String): Long = definitions(name).map(::size).distinct().singleOrNull()
        ?: error("Conflicting DWARF type sizes: $name")

    private fun constant(value: DwarfInfo.Value?, memberLocation: Boolean = false): Long {
        if (value is DwarfInfo.Value.Number) return value.value
        require(value is DwarfInfo.Value.Block) { "Missing constant DWARF location" }
        val cursor = value.bytes.cursor()
        val result = when (val operation = cursor.unsigned(1).toInt()) {
            0x10 -> cursor.uleb() // DW_OP_constu
            0x23 -> {
                require(memberLocation) { "DWARF vtable location depends on an external stack value" }
                cursor.uleb()
            }

            in 0x30..0x4f -> (operation - 0x30).toLong()
            else -> error("Nonconstant DWARF member/vtable location")
        }
        require(cursor.remaining == 0L && result >= 0) { "Unsupported DWARF location expression" }
        return result
    }

    private fun member(owner: DwarfInfo.Entry, name: String): Member {
        val matches = children(owner).filter { it.tag == 0x0d && info.name(it) == name }
        require(matches.size == 1) { "Missing or ambiguous declared DWARF member: $name" }
        val member = matches.single()
        require(info.attribute(member, 0x0d) == null && info.attribute(member, 0x6b) == null) {
            "Bitfield access is unsupported: $name"
        }
        val type = reference(member, 0x49) ?: error("Missing DWARF member type: $name")
        val offset = constant(info.attribute(member, 0x38), memberLocation = true)
        val length = size(type)
        val ownerSize = size(owner)
        require(offset >= 0 && offset <= ownerSize && length <= ownerSize - offset) {
            "DWARF member exceeds declaring type: $name"
        }
        return Member(offset, length, canonical(type))
    }

    private fun <T> validated(owner: String, member: String, validate: (Member) -> T): T {
        val values = definitions(owner).map { validate(member(it, member)) }.distinct()
        require(values.size == 1) { "Conflicting DWARF definitions: $owner::$member" }
        return values.single()
    }

    fun pointerMember(owner: String, member: String, pointee: String): Long = validated(owner, member) {
        require(it.type.tag == 0x0f && it.size == 8L) { "Expected pointer: $owner::$member" }
        val target = reference(it.type, 0x49)?.let(::canonical) ?: error("Missing DWARF pointer target")
        require(candidates[pointee].orEmpty().any { candidate -> canonical(candidate).offset == target.offset } &&
                target.tag in setOf(0x02, 0x13, 0x17)) {
            "Unexpected pointer target: $owner::$member"
        }
        it.offset
    }

    fun scalarMember(owner: String, member: String, bytes: Long, encoding: Long): Long = validated(owner, member) {
        require(it.type.tag == 0x24 && it.size == bytes && number(it.type, 0x3e) == encoding) {
            "Unexpected scalar representation: $owner::$member"
        }
        it.offset
    }

    fun namedMember(owner: String, member: String, expected: String, bytes: Long): Long = validated(owner, member) {
        require(info.name(it.type) == expected && it.size == bytes) { "Unexpected member type: $owner::$member" }
        it.offset
    }

    fun enumValue(owner: String, name: String): Long {
        val values = definitions(owner).map { type ->
            require(type.tag == 0x04) { "Expected DWARF enumeration: $owner" }
            val member = children(type).singleOrNull { it.tag == 0x28 && info.name(it) == name }
                ?: error("Missing or ambiguous DWARF enumerator: $owner::$name")
            number(member, 0x1c) ?: error("Missing DWARF enum constant")
        }.distinct()
        require(values.size == 1) { "Conflicting DWARF enum constant: $owner::$name" }
        return values.single()
    }

    /** The caller validates the signature and ABI; a name alone never authorizes dispatch. */
    fun virtualMethods(owner: String, name: String): List<VirtualMethod> = definitions(owner).map { type ->
        val method = children(type).singleOrNull { it.tag == 0x2e && info.name(it) == name }
            ?: error("Missing or overloaded DWARF virtual method: $owner::$name")
        require(number(method, 0x4c) in listOf(1L, 2L)) { "Expected virtual DWARF method: $owner::$name" }
        val slot = constant(info.attribute(method, 0x4d))
        require(slot in 0..4095) { "DWARF virtual method slot exceeds bound" }
        val parameters = children(method).filter { it.tag == 0x05 }.map {
            canonical(reference(it, 0x49) ?: error("Missing DWARF parameter type"))
        }
        VirtualMethod(slot, reference(method, 0x49)?.let(::canonical), parameters)
    }
}
