package com.hiczp.factorio.mcp

/** Reference-event fields cross-checked against native construction and text editing consumers. */
internal data class KeyEventFields(
    val construction: InlineMemberConstruction.Proof,
    val key: Long,
    val extended: Long,
    val character: Long,
    val control: Long,
) {
    val time: Long
        get() = construction.assignments.single { it.floating && it.width == 8 }.offset

    val source: Long
        get() =
            construction.assignments
                .single { !it.floating && it.width == 8 && it.constant == 0L }
                .offset

    fun defaults(image: ElfImage): Map<Long, Int> {
        val fields =
            construction.assignments
                .filter { it.offset !in setOf(extended, source) }
                .map { NativeField(it.offset, it.width) }
        return MemberDefaults.resolve(
            image,
            image.symbol("_ZN4agui3GuiC2Ev"),
            SysVObjectSize.resolve(image, "4agui3Gui"),
            construction.member,
            fields,
        )
    }

    companion object {
        fun resolve(image: ElfImage): KeyEventFields {
            val debug = image.inlines
            val fields = KeyEventReads.resolve(image)
            val construction =
                InlineMemberConstruction.resolve(
                    image,
                    image.symbol("_ZN4agui3Gui5logicEb"),
                    "logic",
                    "setKeyEvent",
                    image.symbol(
                        "_ZN4agui6Widget30_dispatchKeyboardListenerEventENS_8KeyEvent17KeyboardEventEnumERKS1_"
                    ),
                    SysVObjectSize.resolve(image, "4agui3Gui"),
                    2,
                    debug,
                )
            return crossCheck(construction, fields)
        }

        fun crossCheck(
            construction: InlineMemberConstruction.Proof,
            fields: Map<String, NativeField>,
        ): KeyEventFields {
            fun field(name: String, width: Int): Long {
                val field = fields.getValue(name)
                require(
                    field.width == width &&
                        construction.assignments.any {
                            it.offset == field.offset &&
                                it.width == width &&
                                !it.floating &&
                                it.constant == null
                        }
                ) {
                    "Native key consumer disagrees with event construction"
                }
                return field.offset
            }

            val result =
                KeyEventFields(
                    construction,
                    field("key", 4),
                    field("extended", 4),
                    field("character", 4),
                    field("control", 1),
                )
            require(
                listOf(result.key, result.extended, result.character, result.control)
                    .distinct()
                    .size == 4
            )
            return result
        }
    }
}
