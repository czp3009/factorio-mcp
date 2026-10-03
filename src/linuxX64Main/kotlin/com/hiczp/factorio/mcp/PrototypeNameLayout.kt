package com.hiczp.factorio.mcp

/** Raw prototype names share their proven base string; the bound is a minimum readable prefix, not sizeof. */
internal data class PrototypeNameLayout(
    val offset: Long,
    val minimumExtent: Long,
    val qualityBase: Long,
    val qualitySize: Long,
    val string: NativeStringLayout,
    val evidence: ElfEvidence,
) {
    companion object {
        fun resolve(image: ElfImage): PrototypeNameLayout {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val pointers = mutableMapOf<Long, Long>()
                    val scalars = mutableMapOf<Long, Long>()
                    val types = mutableMapOf<Long, ItaniumClass>()
                    fun type(encoded: String): ItaniumClass {
                        val result = ItaniumClass.resolve(image, encoded)
                        types[result.typeInfo] = result
                        val symbol = image.symbol("_ZTI$encoded")
                        image.pointers.words(symbol.address, (symbol.size / 8).toInt()).forEachIndexed { index, word ->
                            if (index < 2 || symbol.size == 24L || index >= 3 && index % 2 == 1)
                                pointers[symbol.address + index * 8L] = word.pointer()
                            else scalars[symbol.address + index * 8L] = word.scalar()
                        }
                        return result
                    }
                    val prototype = type("13PrototypeBase")
                    val table = ItaniumType.resolve(image, "13PrototypeBase")
                    val tableSymbol = image.symbol("_ZTV13PrototypeBase")
                    scalars[tableSymbol.address] = 0
                    pointers[tableSymbol.address + 8] = prototype.typeInfo
                    val destructor = image.symbol("_ZN13PrototypeBaseD2Ev")
                    EhFrames(image).function(destructor)
                    val minimumExtent = DestructorPrefixExtent.analyze(image.functionBytes(destructor, 65536),
                        destructor.address, table.addressPoint)
                    val string = NativeStringLayout.resolve(image)
                    ManualRecordAbi.resolve(image, string)
                    val values = image.symbol("_ZNK13PrototypeBase9addValuesER24PrototypeExplorerWidgets")
                    EhFrames(image).function(values)
                    val flow = X64ControlFlow.resolve(image, values)
                    val consumer = image.symbol(NamedMemberRecord.CONSUMER)
                    EhFrames(image).function(consumer)
                    val name = NamedMemberRecord.analyze(flow, consumer.address - values.address, string,
                        minimumExtent, setOf("name")).getValue("name")
                    require(name.width == string.size) { "Prototype name is not the verified native string width" }
                    val quality = type("16QualityPrototype")
                    val qualityDestructor = image.symbol("_ZN16QualityPrototypeD0Ev")
                    EhFrames(image).function(qualityDestructor)
                    val qualitySize = SysVObjectSize.analyzeInlined(image.functionBytes(qualityDestructor, 65536),
                        qualityDestructor.address, image.symbol("_ZdlPvm").address)
                    val qualityBase = quality.baseOffset(prototype, qualitySize, minimumExtent) { address ->
                        types[address] ?: type(image.rttiByAddress[address]?.map { it.name }?.distinct()?.singleOrNull()
                            ?.removePrefix("_ZTI") ?: error("Quality base has no unique RTTI name"))
                    }
                    PrototypeNameLayout(name.offset, minimumExtent, qualityBase, qualitySize, string,
                        ElfEvidence(emptyList(), emptyList(), pointers, scalars))
                }
            }
            return resolved.first.copy(evidence = resolved.first.evidence.copy(functions = resolved.second, readonly = readonly))
        }
    }
}
