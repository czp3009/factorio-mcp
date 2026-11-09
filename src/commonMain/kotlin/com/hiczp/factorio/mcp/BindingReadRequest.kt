package com.hiczp.factorio.mcp

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeIntLe

internal data class BindingReadRequest(val ids: Set<String>?, val search: String?, val offset: Int, val limit: Int?) {
    init {
        require(offset >= 0 && (limit == null || limit > 0))
    }

    fun payload(): ByteArray {
        val output = Buffer()
        output.writeIntLe(offset)
        output.writeIntLe(limit ?: Int.MAX_VALUE)
        output.writeIntLe(if (ids == null) 0 else 1)
        output.writeIntLe(ids?.size ?: 0)
        ids?.forEach { id ->
            val bytes = id.encodeToByteArray(throwOnInvalidSequence = true)
            output.writeIntLe(bytes.size)
            output.write(bytes)
        }
        val pattern = search.orEmpty()
        output.writeIntLe(pattern.length)
        pattern.forEach { character ->
            val codes = bindingCaseGroups[character.uppercaseChar().lowercaseChar()] ?: listOf(character.code)
            output.writeIntLe(codes.size)
            codes.forEach { output.writeIntLe(it) }
        }
        require(output.size <= Int.MAX_VALUE) { "Control query exceeds byte-array representation" }
        return output.readByteArray()
    }
}

internal data class BindingPage(val matchedCount: Int, val hasMore: Boolean, val presence: List<Boolean>)

// Char.equals(ignoreCase = true) compares uppercase characters and then their lowercase forms.
private val bindingCaseGroups by lazy {
    val groups = mutableMapOf<Char, MutableList<Int>>()
    for (code in 0..65535) {
        val character = code.toChar()
        val folded = character.uppercaseChar().lowercaseChar()
        if (character != folded) groups.getOrPut(folded) { mutableListOf(folded.code) }.add(code)
    }
    groups
}
