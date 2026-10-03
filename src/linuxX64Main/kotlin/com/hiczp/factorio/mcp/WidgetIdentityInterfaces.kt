package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Shared prototype-provider positions obtained from a concrete RTTI subobject and complete getter ABIs. */
internal data class WidgetIdentityInterfaces(
    val widgetType: Long,
    val providerType: Long,
    val prototype: Int,
    val quality: Int,
    val evidence: ElfEvidence,
) {
    companion object {
        fun resolve(image: ElfImage): WidgetIdentityInterfaces {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val destructor = image.symbol("_ZN12ItemGroupTabD0Ev")
                    EhFrames(image).function(destructor)
                    val size = SysVObjectSize.analyzeInlined(image.functionBytes(destructor, 256),
                        destructor.address, image.symbol("_ZdlPvm").address)
                    val table = ItaniumSubobjectVtable.resolve(image, "12ItemGroupTab", "17PrototypeProvider", size)
                    val provider = ItaniumClass.resolve(image, "17PrototypeProvider")
                    require(provider.bases.isEmpty()) { "Prototype interface has unsupported inherited entries" }
                    val widget = ItaniumClass.resolve(image, "N4agui6WidgetE")
                    val prefix = if (table.baseOffset == 0L) "_ZNK" else "_ZThn${table.baseOffset}_NK"
                    val prototype = table.method(image, "${prefix}12ItemGroupTab16getBasePrototypeEv")
                    val field = SysVAccessors.resolve(image, prototype.function).withinObject(size - table.baseOffset)
                    require(field.width == 8 && field.mask == ULong.MAX_VALUE && field.shift == 0)
                    val original = SysVAccessors.resolve(image,
                        image.symbol("_ZNK12ItemGroupTab16getBasePrototypeEv")).withinObject(size)
                    require(original == field.copy(offset = field.offset + table.baseOffset)) {
                        "Prototype getter and its adjusted receiver disagree"
                    }
                    val quality = table.method(image, "${prefix}12ItemGroupTab19getQualityPrototypeEv")
                    val bytes = image.functionBytes(quality.function, 256)
                    BooleanLeafReturn.analyze(bytes)
                    val flow = SysVReceiverFlow(bytes, quality.function.address, size - table.baseOffset)
                    val returns = flow.instructions.filter { it.offset in flow.reachable && it.operation == Operation.RET }
                    require(returns.isNotEmpty() && returns.all {
                        flow.before(it.offset)[0] == SysVReceiverFlow.Constant(0)
                    }) { "Quality getter does not return a complete null pointer" }
                    require(prototype.slot != quality.slot)
                    val pointers = table.pointers.toMutableMap()
                    val scalars = table.scalars.toMutableMap()
                    val widgetSymbol = image.symbol("_ZTIN4agui6WidgetE")
                    image.pointers.words(widgetSymbol.address, (widgetSymbol.size / 8).toInt()).forEachIndexed { index, word ->
                        if (index < 2 || widgetSymbol.size == 24L || index >= 3 && index % 2 == 1)
                            pointers[widgetSymbol.address + index * 8L] = word.pointer()
                        else scalars[widgetSymbol.address + index * 8L] = word.scalar()
                    }
                    WidgetIdentityInterfaces(widget.typeInfo, provider.typeInfo, prototype.slot, quality.slot,
                        ElfEvidence(emptyList(), emptyList(), pointers, scalars))
                }
            }
            return resolved.first.copy(evidence = resolved.first.evidence.copy(functions = resolved.second, readonly = readonly))
        }
    }
}
