package com.hiczp.factorio.mcp

/** Reference-event fields cross-checked against the game's named constructor and text editing accessors. */
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
        get() = construction.assignments.single { !it.floating && it.width == 8 && it.constant == 0L }.offset

    fun defaults(image: ElfImage): Map<Long, Int> {
        val fields = construction.assignments.filter { it.offset !in setOf(extended, source) }
            .map { InlineArgumentFields.Field(it.offset, it.width) }
        return MemberDefaults.resolve(
            image, image.symbol("_ZN4agui3GuiC2Ev"),
            SysVObjectSize.resolve(image, "4agui3Gui"), construction.member, fields
        )
    }

    companion object {
        fun resolve(image: ElfImage): KeyEventFields {
            val debug = image.inlines
            val function = image.symbol("_ZN4agui7TextBox23textInputHandleKeyEventERKNS_8KeyEventE")
            val fields = InlineArgumentFields.resolve(
                image, function, "textInputHandleKeyEvent", 6, 256,
                mapOf("getKey" to 4, "getExtendedKey" to 4, "getUnichar" to 4, "metaOrControl" to 1), debug
            )
            val construction = InlineMemberConstruction.resolve(
                image, image.symbol("_ZN4agui3Gui5logicEb"),
                "logic", "setKeyEvent",
                image.symbol("_ZN4agui6Widget30_dispatchKeyboardListenerEventENS_8KeyEvent17KeyboardEventEnumERKS1_"),
                SysVObjectSize.resolve(image, "4agui3Gui"), 2, debug
            )
            return crossCheck(construction, fields)
        }

        fun crossCheck(
            construction: InlineMemberConstruction.Proof,
            fields: Map<String, InlineArgumentFields.Field>
        ): KeyEventFields {
            fun field(name: String, width: Int): Long {
                val field = fields.getValue(name)
                require(field.width == width && construction.assignments.any {
                    it.offset == field.offset && it.width == width && !it.floating && it.constant == null
                }) { "Named key getter disagrees with native event construction" }
                return field.offset
            }

            val result = KeyEventFields(
                construction, field("getKey", 4), field("getExtendedKey", 4),
                field("getUnichar", 4), field("metaOrControl", 1)
            )
            require(listOf(result.key, result.extended, result.character, result.control).distinct().size == 4)
            return result
        }
    }
}
