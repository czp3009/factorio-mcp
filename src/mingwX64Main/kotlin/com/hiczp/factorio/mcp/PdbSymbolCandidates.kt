package com.hiczp.factorio.mcp

/** Public-symbol aliases may identify one entry; distinct entries must never be chosen by enumeration order. */
internal class PdbSymbolCandidates(size: Int) {
    private val entries = List(size) { mutableSetOf<ULong>() }

    fun add(index: Int, address: ULong) {
        require(address != 0uL) { "PDB symbol has no loaded address" }
        entries[index].add(address)
    }

    fun count(index: Int): Int = entries[index].size

    fun addresses(): List<ULong> = entries.map { it.singleOrNull() ?: 0uL }
}
