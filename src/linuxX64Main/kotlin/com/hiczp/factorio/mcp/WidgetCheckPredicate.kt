package com.hiczp.factorio.mcp

/** Named inline predicate evidence; concrete widget type validation is still required before any read. */
internal data class WidgetCheckPredicate(val offset: Long, val checkedValue: Int) {
    fun withinObject(size: Long): WidgetCheckPredicate = apply {
        NativeAccessor(offset, 4, 0xffffffffUL, 0).withinObject(size)
    }

    companion object {
        fun resolve(image: ElfImage): WidgetCheckPredicate {
            val debug = image.inlines
            val fields = listOf("nextCheckState", "dispatchCheckChange").flatMap { owner ->
                val function = image.symbol("_ZN4agui12ToggleButton${owner.length}${owner}Ev")
                val bytes = image.functionBytes(function, 4096)
                debug.find(function, owner, setOf("isChecked")).flatMap { it.ranges }.map { range ->
                    analyze(bytes, range.start - function.address, range.end - function.address)
                }
            }.distinct()
            return fields.singleOrNull() ?: error("Named check predicates disagree on the native enum field")
        }

        fun analyze(bytes: BinaryView, start: Long, end: Long): WidgetCheckPredicate {
            val field = MemberEquality.analyze(bytes, start, end, 4)
            return WidgetCheckPredicate(field.offset, field.value)
        }
    }
}
