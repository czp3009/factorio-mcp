@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.*
import kotlinx.cinterop.*
import platform.posix.memset
import platform.windows.HANDLE
import platform.windows.LocalFree

/** DbgHelp type metadata supplies every game member location and its representation. */
internal class DebugTypes(private val process: HANDLE, private val base: ULong) {
    data class Member(val offset: UInt, val type: UInt)

    // Debug metadata is immutable for this resolution session; never cache live game objects.
    private val types = mutableMapOf<String, UInt>()
    private val lengths = mutableMapOf<UInt, ULong>()
    private val names = mutableMapOf<UInt, String?>()
    private val childLists = mutableMapOf<UInt, List<UInt>>()

    private fun uint(type: UInt, query: IMAGEHLP_SYMBOL_TYPE_INFO): UInt = memScoped {
        val value = alloc<UIntVar>()
        check(SymGetTypeInfo(process, base, type, query, value.ptr) != 0) {
            "Missing PDB type property: $query"
        }
        value.value
    }

    private fun length(type: UInt): ULong = memScoped {
        lengths[type]?.let {
            return it
        }
        val value = alloc<ULongVar>()
        check(
            SymGetTypeInfo(
                process,
                base,
                type,
                IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_LENGTH,
                value.ptr,
            ) != 0
        ) {
            "Missing PDB type length"
        }
        value.value.also { lengths[type] = it }
    }

    private fun name(type: UInt): String? = memScoped {
        if (names.containsKey(type)) return names[type]
        val value = alloc<CPointerVar<UShortVar>>()
        if (
            SymGetTypeInfo(
                process,
                base,
                type,
                IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_SYMNAME,
                value.ptr,
            ) == 0
        )
            return null.also { names[type] = null }
        try {
            value.value?.toKString().also { names[type] = it }
        } finally {
            LocalFree(value.value)
        }
    }

    private fun type(name: String): UInt = memScoped {
        types[name]?.let {
            return it
        }
        val bytes = allocArray<ByteVar>(sizeOf<SYMBOL_INFO>() + 1024)
        memset(bytes, 0, (sizeOf<SYMBOL_INFO>() + 1024).toULong())
        val info = bytes.reinterpret<SYMBOL_INFO>()
        info.pointed.SizeOfStruct = sizeOf<SYMBOL_INFO>().toUInt()
        info.pointed.MaxNameLen = 1024u
        check(SymGetTypeFromName(process, base, name, info) != 0) { "Missing PDB type: $name" }
        info.pointed.TypeIndex.also { types[name] = it }
    }

    private fun children(ownerType: UInt): List<UInt> = memScoped {
        childLists[ownerType]?.let {
            return it
        }
        val count = uint(ownerType, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_CHILDRENCOUNT)
        check(count < 65536u) { "PDB child count exceeds bound" }
        if (count == 0u) return emptyList<UInt>().also { childLists[ownerType] = it }
        val children = allocArray<UIntVar>(count.toInt() + 2)
        children[0] = count
        children[1] = 0u
        check(
            SymGetTypeInfo(
                process,
                base,
                ownerType,
                IMAGEHLP_SYMBOL_TYPE_INFO.TI_FINDCHILDREN,
                children,
            ) != 0
        ) {
            "Cannot enumerate PDB members"
        }
        (0 until count.toInt()).map { children[it + 2] }.also { childLists[ownerType] = it }
    }

    private fun findMember(ownerType: UInt, member: String, depth: Int = 0): Member? {
        check(depth < 16) { "PDB inheritance exceeds bound" }
        val children = children(ownerType)
        val matches = children.filter { name(it) == member }
        if (matches.isEmpty()) {
            val inherited =
                children
                    .filter { uint(it, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_SYMTAG) == 18u }
                    .mapNotNull { baseType ->
                        check(
                            uint(baseType, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_VIRTUALBASECLASS) == 0u
                        ) {
                            "Virtual PDB base requires runtime adjustment: ${name(ownerType)}"
                        }
                        val base = uint(baseType, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_TYPEID)
                        val offset = uint(baseType, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_OFFSET)
                        findMember(base, member, depth + 1)?.let {
                            check(
                                offset.toULong() + it.offset + length(it.type) <= length(ownerType)
                            )
                            val combined = offset.toULong() + it.offset
                            check(combined <= UInt.MAX_VALUE.toULong())
                            Member(combined.toUInt(), it.type)
                        }
                    }
            check(inherited.size <= 1) {
                "Expected one inherited PDB member: ${name(ownerType)}::$member"
            }
            return inherited.singleOrNull()
        }
        check(matches.size == 1) { "Expected one PDB member: ${name(ownerType)}::$member" }
        val id = matches.single()
        val offset = uint(id, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_OFFSET)
        val memberType = uint(id, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_TYPEID)
        check(offset.toULong() + length(memberType) <= length(ownerType)) {
            "PDB member exceeds its declaring type"
        }
        return Member(offset, memberType)
    }

    private fun member(ownerType: UInt, member: String): Member =
        checkNotNull(findMember(ownerType, member)) {
            "Missing PDB member: ${name(ownerType)}::$member"
        }

    fun member(owner: String, member: String): Member = member(type(owner), member)

    fun aggregateSize(owner: String): ULong = length(type(owner))

    fun memberTypeName(owner: String, field: String): String =
        checkNotNull(name(member(owner, field).type)) { "Unnamed member type: $owner::$field" }

    fun byteArrayMember(owner: String, field: String, minimum: UInt): UInt {
        val value = member(owner, field)
        check(uint(value.type, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_SYMTAG) == 15u)
        val element = uint(value.type, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_TYPEID)
        check(length(element) == 1uL && length(value.type) >= minimum)
        return value.offset
    }

    fun validateStaticMember(owner: String, member: String, expected: String) {
        val field = children(type(owner)).single { name(it) == member }
        check(
            uint(field, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_SYMTAG) == 7u &&
                    uint(field, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_DATAKIND) == 8u
        ) {
            "Expected a static PDB member: $owner::$member"
        }
        val value = uint(field, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_TYPEID)
        check(name(value) == expected && length(value) == aggregateSize(expected)) {
            "Unexpected static PDB member type: $owner::$member"
        }
    }

    fun directBaseOffset(owner: String, expected: String): UInt {
        val ownerType = type(owner)
        val base =
            children(ownerType).single {
                uint(it, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_SYMTAG) == 18u && name(it) == expected
            }
        check(uint(base, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_VIRTUALBASECLASS) == 0u) {
            "Virtual PDB base requires runtime adjustment: $owner::$expected"
        }
        val baseType = uint(base, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_TYPEID)
        check(name(baseType) == expected)
        val offset = uint(base, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_OFFSET)
        check(offset.toULong() + length(baseType) <= length(ownerType)) {
            "PDB base exceeds its declaring type: $owner::$expected"
        }
        return offset
    }

    fun path(owner: String, vararg names: String, target: String, bytes: ULong): UInt {
        var current = type(owner)
        var offset = 0uL
        names.forEach {
            val field = member(current, it)
            offset += field.offset
            current = field.type
        }
        check(name(current) == target && length(current) == bytes) {
            "Unexpected PDB field path: $owner::${names.joinToString("::")}"
        }
        check(offset <= UInt.MAX_VALUE.toULong() && offset + bytes <= aggregateSize(owner))
        return offset.toUInt()
    }

    fun scalarPath(owner: String, vararg names: String, target: UInt, bytes: ULong): UInt {
        var current = type(owner)
        var offset = 0uL
        names.forEach {
            val field = member(current, it)
            offset += field.offset
            current = field.type
        }
        check(
            uint(current, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_SYMTAG) == 16u &&
                    uint(current, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_BASETYPE) == target &&
                    length(current) == bytes
        )
        check(offset <= UInt.MAX_VALUE.toULong() && offset + bytes <= aggregateSize(owner))
        return offset.toUInt()
    }

    fun pointerPath(owner: String, vararg names: String, target: String, indirections: Int): UInt {
        require(names.isNotEmpty() && indirections > 0)
        var current = type(owner)
        var offset = 0uL
        names.forEach {
            val field = member(current, it)
            offset += field.offset
            current = field.type
        }
        repeat(indirections) {
            check(
                uint(current, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_SYMTAG) == 14u &&
                        length(current) == 8uL
            )
            current = uint(current, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_TYPEID)
        }
        check(name(current) == target) { "Unexpected PDB pointer path: $owner" }
        check(offset <= UInt.MAX_VALUE.toULong() && offset + 8uL <= aggregateSize(owner))
        return offset.toUInt()
    }

    fun enumValues(owner: String): Map<String, Int> = memScoped {
        val id = type(owner)
        check(uint(id, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_SYMTAG) == 12u) {
            "Expected PDB enum: $owner"
        }
        children(id).associate { child ->
            val value = alloc<IntVar>()
            check(fm_type_value(process, base, child, value.ptr) != 0) {
                "Unsupported PDB enum: $owner::${name(child)}"
            }
            checkNotNull(name(child)) to value.value
        }
    }

    fun pointerMember(owner: String, member: String, target: String): UInt {
        val field = member(owner, member)
        // SymTagPointerType is part of the Windows debugging API, not a game layout constant.
        check(
            uint(field.type, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_SYMTAG) == 14u &&
                    length(field.type) == 8uL
        ) {
            "Expected a pointer/reference: $owner::$member"
        }
        check(name(uint(field.type, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_TYPEID)) == target) {
            "Unexpected pointee: $owner::$member"
        }
        return field.offset
    }

    fun byteMember(owner: String, member: String, boolean: Boolean): UInt {
        val field = member(owner, member)
        check(
            length(field.type) == 1uL &&
                    uint(field.type, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_SYMTAG) == 16u
        ) {
            "Expected a scalar byte: $owner::$member"
        }
        check(
            uint(field.type, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_BASETYPE) == if (boolean) 10u else 7u
        ) {
            "Unexpected scalar representation: $owner::$member"
        }
        return field.offset
    }

    fun size(owner: String): UInt =
        length(type(owner))
            .also { check(it in 1uL..256uL) { "Unsupported event storage size: $owner" } }
            .toUInt()

    fun plainMembers(owner: String, expected: Set<String>) = memScoped {
        val id = type(owner)
        val count = uint(id, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_CHILDRENCOUNT)
        check(count < 4096u) { "Event type exceeds bound" }
        val children = allocArray<UIntVar>(count.toInt() + 2)
        children[0] = count
        children[1] = 0u
        check(
            SymGetTypeInfo(
                process,
                base,
                id,
                IMAGEHLP_SYMBOL_TYPE_INFO.TI_FINDCHILDREN,
                children,
            ) != 0
        )
        val fields = mutableSetOf<String>()
        for (index in 0 until count.toInt()) {
            val child = children[index + 2]
            val tag = uint(child, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_SYMTAG)
            // SymTagBaseClass/VTable imply construction beyond the verified plain event adapter.
            check(tag != 18u && tag != 25u) {
                "Event type requires unsupported construction: $owner"
            }
            if (tag == 7u && uint(child, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_DATAKIND) == 7u) {
                fields += checkNotNull(name(child))
            }
        }
        check(fields == expected) { "Event members changed: $owner" }
    }

    fun scalarMember(owner: String, member: String, bytes: ULong, baseType: UInt): UInt {
        val field = member(owner, member)
        check(
            length(field.type) == bytes &&
                    uint(field.type, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_SYMTAG) == 16u &&
                    uint(field.type, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_BASETYPE) == baseType
        ) {
            "Unexpected scalar type: $owner::$member"
        }
        return field.offset
    }

    fun namedMember(owner: String, member: String, expected: String, bytes: ULong): UInt {
        val field = member(owner, member)
        check(name(field.type) == expected && length(field.type) == bytes) {
            "Unexpected named type: $owner::$member"
        }
        return field.offset
    }

    fun enumValue(owner: String, entry: String): UInt = memScoped {
        val id = type(owner)
        check(uint(id, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_SYMTAG) in setOf(11u, 12u)) {
            "Expected PDB enum or class: $owner"
        }
        val count = uint(id, IMAGEHLP_SYMBOL_TYPE_INFO.TI_GET_CHILDRENCOUNT)
        check(count < 4096u) { "Enum exceeds bound" }
        val children = allocArray<UIntVar>(count.toInt() + 2)
        children[0] = count
        children[1] = 0u
        check(
            SymGetTypeInfo(
                process,
                base,
                id,
                IMAGEHLP_SYMBOL_TYPE_INFO.TI_FINDCHILDREN,
                children,
            ) != 0
        )
        val child = (0 until count.toInt()).map { children[it + 2] }.single { name(it) == entry }
        val value = alloc<IntVar>()
        check(fm_type_value(process, base, child, value.ptr) != 0) {
            "Unsupported enum value: $owner::$entry"
        }
        check(value.value >= 0)
        value.value.toUInt()
    }
}
