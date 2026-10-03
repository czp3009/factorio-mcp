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
                    image.inlines.find(function, owner, setOf("operator==")).flatMap { instance ->
                        instance.ranges.map { range ->
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

    fun resolve(image: ElfImage): Long {
        val size = SysVObjectSize.resolve(image, "4agui11ProgressBar")
        val debug = image.inlines
        val owners =
            mapOf(
                "_ZNK4agui11ProgressBar15getBarRectangleEv" to "getBarRectangle",
                "_ZN4agui11ProgressBar14paintComponentERKNS_10PaintEventERKNS_5PointE" to
                    "paintComponent",
            )
        val fields =
            owners
                .flatMap { (name, owner) ->
                    val function = image.symbol(name)
                    val bytes = image.functionBytes(function, 4096)
                    debug.find(function, owner, setOf("operator==")).flatMap { instance ->
                        instance.ranges.map { range ->
                            val comparison =
                                MemberEquality.analyze(
                                    bytes,
                                    range.start - function.address,
                                    range.end - function.address,
                                    1,
                                )
                            instance.origin to
                                NativeAccessor(comparison.offset, 1, 0xffUL, 0)
                                    .withinObject(size)
                                    .offset
                        }
                    }
                }
                .distinct()
        return fields.singleOrNull()?.second
            ?: error("Progress direction comparisons disagree on the bounded member")
    }
}
