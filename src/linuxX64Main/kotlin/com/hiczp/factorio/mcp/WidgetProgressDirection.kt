package com.hiczp.factorio.mcp

/** Receiver-bounded direction comparisons and the same typed source operation's native names. */
internal object WidgetProgressDirection {
    data class Named(
        val field: Long,
        val names: Map<Int, String>,
        val source: DwarfSourceLines.Source,
    )

    fun named(image: ElfImage): Named {
        val direction = LuaDirectionNames.resolve(image)
        val size = SysVObjectSize.resolve(image, "4agui11ProgressBar")
        val owners =
            mapOf(
                "_ZNK4agui11ProgressBar15getBarRectangleEv" to "getBarRectangle",
                "_ZN4agui11ProgressBar14paintComponentERKNS_10PaintEventERKNS_5PointE" to
                    "paintComponent",
            )
        val members =
            owners
                .flatMap { (name, owner) ->
                    val function = image.symbol(name)
                    val bytes = image.functionBytes(function, 4096)
                    val instructions = X64Instructions(bytes).all(2048)
                    image.inlines.find(function, owner, setOf("operator==")).flatMap { instance ->
                        instance.ranges.mapNotNull { range ->
                            val site =
                                instructions.firstOrNull {
                                    it.operation == X64Instructions.Operation.CMP &&
                                        function.address + it.offset >= range.start &&
                                        function.address + it.offset + it.size <= range.end
                                } ?: return@mapNotNull null
                            if (
                                image.inlines.source(instance, function.address + site.offset) !=
                                    direction.source
                            )
                                return@mapNotNull null
                            val comparison =
                                MemberEquality.locate(
                                    bytes,
                                    range.start - function.address,
                                    range.end - function.address,
                                    1,
                                )
                            val source =
                                image.inlines.source(
                                    instance,
                                    function.address + comparison.instruction,
                                )
                            require(
                                source == direction.source &&
                                    comparison.member.value in direction.names
                            ) {
                                "Progress comparison lacks the typed direction operation's source identity"
                            }
                            NativeAccessor(comparison.member.offset, 1, 0xffUL, 0)
                                .withinObject(size)
                                .offset
                        }
                    }
                }
                .distinct()
        val field = members.singleOrNull() ?: error("Typed progress direction comparisons disagree")
        return Named(field, direction.names, direction.source)
    }

    fun resolve(image: ElfImage): Long = named(image).field
}
