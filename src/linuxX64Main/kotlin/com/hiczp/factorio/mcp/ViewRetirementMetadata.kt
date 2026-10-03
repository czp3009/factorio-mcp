package com.hiczp.factorio.mcp

import platform.posix.PROT_READ
import platform.posix.PROT_WRITE

/** Loaded evidence for an unchanged native retirement entry. Installation/ownership stay with the resident. */
internal class ViewRetirementMetadata private constructor(
    val layout: ViewRetirementLayout,
    private val functions: List<ElfImage.Symbol>,
    private val readonly: List<ElfImage.ReadonlyRange>,
    private val words: List<Word>,
) {
    private data class Word(val address: Long, val value: Long, val pointer: Boolean)

    data class Entry(val address: Long, val original: Long, val protection: Int)

    fun verifyLoaded(image: ElfImage, process: ProcessHandle, bias: Long): Entry {
        verify(image, bias, process::readMemory)
        val entry = layout.method.entryAddress + bias
        val function = layout.method.function.address + bias
        val mappings = process.executableMappings()
        val data = mappings.singleOrNull { entry >= it.start && entry <= it.end - 8 }
            ?: error("Retirement table entry is not in the selected executable")
        require(data.readable && !data.executable && data.permissions[3] == 'p' && entry % 8 == 0L)
        require(mappings.any {
            it.readable && it.executable && function >= it.start &&
                    function <= it.end - layout.method.function.size
        }) { "Retirement function is not executable" }
        return Entry(entry, function, PROT_READ or if (data.writable) PROT_WRITE else 0)
    }

    internal fun verify(image: ElfImage, bias: Long, read: (Long, Int) -> ByteArray) {
        require(bias >= 0 && bias % 8 == 0L)
        fun compare(address: Long, expected: ByteArray) {
            require(
                expected.isNotEmpty() && expected.size <= 16 * 1024 * 1024 && address > 0 &&
                        address <= Long.MAX_VALUE - bias - expected.size
            )
            require(read(address + bias, expected.size).contentEquals(expected)) {
                "Live GameView retirement evidence differs from the selected executable"
            }
        }
        for (function in functions)
            compare(
                function.address,
                image.functionBytes(function, function.size.toInt()).bytes(0, function.size.toInt())
            )
        for (range in readonly)
            compare(range.address, image.virtualBytes(range.address, range.size).bytes(0, range.size.toInt()))
        for (word in words) {
            val value = if (word.pointer && word.value != 0L) {
                require(word.value > 0 && word.value <= Long.MAX_VALUE - bias)
                word.value + bias
            } else word.value
            compare(word.address, ByteArray(8) { (value ushr (it * 8)).toByte() })
        }
    }

    companion object {
        /** Sizes/member must already come from the selected image's independently verified input context. */
        fun resolve(image: ElfImage, gameSize: Long, viewSize: Long, member: Long): ViewRetirementMetadata {
            data class Resolved(val layout: ViewRetirementLayout, val words: List<Word>)
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val layout = ViewRetirementLayout.resolve(image, gameSize, viewSize, member)
                    val pointers = image.pointers
                    val words = mutableMapOf<Long, Word>()
                    fun capture(address: Long, count: Int, isPointer: (Int) -> Boolean) {
                        pointers.words(address, count).forEachIndexed { index, word ->
                            val pointer = isPointer(index)
                            val location = address + index * 8L
                            val observed = Word(location, if (pointer) word.pointer() else word.scalar(), pointer)
                            require(words.put(location, observed)?.let { it == observed } != false)
                        }
                    }

                    val table = image.symbol("_ZTV8GameView")
                    capture(table.address, (table.size / 8).toInt()) { it != 0 }
                    for (name in listOf("8GameView", "18GarbageCollectable")) {
                        val type = image.symbol("_ZTI$name")
                        // ItaniumClass has already verified the metaclass and the corresponding RTTI shape.
                        // Single inheritance has one base pointer; VMI has scalar flags/count and offset words.
                        capture(type.address, (type.size / 8).toInt()) {
                            it < 2 || type.size == 24L || it >= 3 && it % 2 == 1
                        }
                        val metaclass = pointers.words(type.address, 1).single().pointer()
                        capture(metaclass - 16, 2) { it == 1 }
                    }
                    Resolved(layout, words.values.toList())
                }
            }
            return ViewRetirementMetadata(resolved.first.layout, resolved.second, readonly, resolved.first.words)
        }
    }
}
