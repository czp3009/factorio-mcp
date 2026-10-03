package com.hiczp.factorio.mcp

/** A primary Itanium ABI table established from local ELF symbols, RTTI and pointer relocations. */
internal class ItaniumVtable private constructor(val addressPoint: Long, private val entries: List<Long>) {
    data class Method(val addressPoint: Long, val slot: Int, val function: ElfImage.Symbol) {
        val entryAddress: Long
            get() = addressPoint + slot * 8L
    }

    fun method(image: ElfImage, symbol: String): Method {
        val function = image.symbol(symbol)
        image.functionBytes(function, 1)
        EhFrames(image).function(function)
        val slots = entries.indices.filter { entries[it] == function.address }
        require(slots.size == 1) { "Virtual method is missing or ambiguous in the primary table: $symbol" }
        return Method(addressPoint, slots.single(), function)
    }

    fun function(image: ElfImage, slot: Int): ElfImage.Symbol {
        val address = entries.getOrNull(slot) ?: error("Virtual slot exceeds the verified primary table")
        val function = image.function(address)
        EhFrames(image).function(function)
        return function
    }

    companion object {
        fun resolve(image: ElfImage, symbol: String, pointers: ElfPointers = image.pointers): ItaniumVtable {
            require(symbol.startsWith("_ZTV") && symbol.length > 4) { "Expected an Itanium vtable symbol" }
            val encodedType = symbol.removePrefix("_ZTV")
            val table = image.symbol(symbol)
            val type = image.symbol("_ZTI$encodedType")
            val name = image.symbol("_ZTS$encodedType")
            require(
                table.type == 1 && table.size in 24..65536 && table.size % 8 == 0L &&
                        type.type == 1 && type.size >= 16 && name.type == 1 && name.size in 2..4096
            ) {
                "Invalid ELF vtable/RTTI symbols"
            }
            val words = pointers.words(table.address, (table.size / 8).toInt())
            require(words[0].scalar() == 0L && words[1].pointer() == type.address) {
                "Expected an unambiguous primary vtable header"
            }
            require(
                pointers.words(type.address + 8, 1).single().pointer() == name.address &&
                        image.virtualBytes(name.address, name.size).string(0, name.size.toInt()) == encodedType
            ) {
                "Vtable RTTI does not identify the expected type"
            }
            val functions = words.drop(2).map { it.pointer() }
            val functionAddresses = image.functionAddresses
            for (address in functions) {
                // Secondary tables, virtual base offsets and external method relocations require separate proofs.
                require(address == 0L || address in functionAddresses) { "Unsupported vtable group or nonlocal function entry" }
                if (address != 0L) image.virtualBytes(address, 1, executable = true)
            }
            return ItaniumVtable(table.address + 16, functions)
        }
    }
}
