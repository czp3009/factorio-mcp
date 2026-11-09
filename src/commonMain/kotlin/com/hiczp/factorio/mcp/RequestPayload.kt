package com.hiczp.factorio.mcp

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeIntLe

internal data class RequestSlice(val offset: Int, val size: Int)

/** Immutable command bytes; platform adapters bind these offsets only to their owned resident allocation. */
internal class RequestPayload(selector: ByteArray = byteArrayOf(), source: ByteArray = byteArrayOf(), arguments: ByteArray = byteArrayOf()) {
    val selector = RequestSlice(0, selector.size)
    val source = RequestSlice(selector.size, source.size)
    val arguments: RequestSlice
    val bytes: ByteArray

    init {
        val sourceEnd = selector.size.toLong() + source.size
        val end = sourceEnd + arguments.size
        require(end <= Int.MAX_VALUE) { "Request payload exceeds byte-array representation" }
        this.arguments = RequestSlice(sourceEnd.toInt(), arguments.size)
        bytes = ByteArray(end.toInt())
        selector.copyInto(bytes)
        source.copyInto(bytes, selector.size)
        arguments.copyInto(bytes, sourceEnd.toInt())
    }
}

/** Length-delimited UTF-8 predicates, independent of native struct padding and platform ABI. */
internal fun selectorPayload(path: List<UiStep>): ByteArray {
    if (path.isEmpty()) return byteArrayOf()
    val output = Buffer()
    output.writeIntLe(path.size)
    for (step in path) {
        require(step.position >= 0)
        output.writeIntLe(if (step.child) 1 else 0)
        output.writeIntLe(step.position)
        output.writeIntLe(step.enabled?.let { if (it) 1 else 0 } ?: -1)
        output.writeIntLe(step.visible?.let { if (it) 1 else 0 } ?: -1)
        output.writeIntLe(if (step.gui == null) 0 else 1)
        for (value in listOf(step.type, step.text, step.prototype?.name, step.prototype?.nativeType)) {
            if (value == null) output.writeIntLe(-1)
            else {
                require('\u0000' !in value) { "Selector predicate contains NUL" }
                val bytes = value.encodeToByteArray()
                output.writeIntLe(bytes.size)
                output.write(bytes)
            }
        }
        require(output.size <= Int.MAX_VALUE) { "Selector payload exceeds byte-array representation" }
    }
    return output.readByteArray()
}
