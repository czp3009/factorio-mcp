package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Generic element providers, proven through inherited table groups without creating an optional Item. */
internal data class WidgetElementInterfaces(
    val widgetType: Long,
    val stackProvider: Long,
    val itemProvider: Long,
    val stackGetter: Int,
    val itemGetter: Int,
    val evidence: ElfEvidence,
) {
    companion object {
        fun resolve(image: ElfImage): WidgetElementInterfaces {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val pointers = mutableMapOf<Long, Long>()
                    val scalars = mutableMapOf<Long, Long>()
                    fun provider(element: String): Pair<Long, Int> {
                        val owner = "8IDButtonI19IDWithQualityFilterI2IDI13ItemPrototypetEE${element}E"
                        val name = "12ElemProviderI${element}E"
                        val extent = DestructorPrefixExtent.resolve(image, owner)
                        val table = ItaniumSubobjectVtable.resolve(image, owner, name, extent)
                        pointers.putAll(table.pointers)
                        scalars.putAll(table.scalars)
                        val type = ItaniumClass.resolve(image, name)
                        require(type.bases.isEmpty()) { "Element provider has unsupported inherited methods" }
                        val prefix = if (table.baseOffset == 0L) "_ZNK" else "_ZThn${table.baseOffset}_NK"
                        val getter = table.method(image, "${prefix}${owner}7getElemEv")
                        val bytes = image.functionBytes(getter.function, 256)
                        BooleanLeafReturn.analyze(bytes)
                        val values = SysVReceiverFlow(bytes, getter.function.address, extent - table.baseOffset)
                        val returns = values.instructions.filter { it.offset in values.reachable && it.operation == Operation.RET }
                        require(returns.isNotEmpty() && returns.all {
                            values.before(it.offset)[0] == SysVReceiverFlow.Constant(0)
                        }) { "Element getter does not return a complete null pointer" }
                        return type.typeInfo to getter.slot
                    }
                    val stack = provider("9ItemStack")
                    val item = provider("4Item")
                    val widget = ItaniumClass.resolve(image, "N4agui6WidgetE")
                    WidgetElementInterfaces(widget.typeInfo, stack.first, item.first, stack.second, item.second,
                        ElfEvidence(emptyList(), emptyList(), pointers, scalars))
                }
            }
            return resolved.first.copy(evidence = resolved.first.evidence.copy(functions = resolved.second, readonly = readonly))
        }
    }
}
