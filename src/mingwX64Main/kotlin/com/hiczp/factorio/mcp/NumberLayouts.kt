@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.NumberLayout

/** One shared interface, with virtual positions read from the selected PDB on every resolution. */
internal class NumberLayouts(path: String, guid: ByteArray, age: Int, descriptors: List<ULong>) {
    private val type = descriptors.single()
    private val methods =
        readPdbVirtualMethods(
            path,
            guid,
            age,
            "ButtonNumber",
            mapOf(
                "getCount" to 0x41u,
                "shouldDrawNumber" to 0x30u,
                "shouldShowZero" to 0x30u,
                "isUnknown" to 0x30u,
                "isInfinite" to 0x30u,
            ),
        )

    fun write(target: NumberLayout) {
        target.type = type
        target.count = methods.getValue("getCount").offset
        target.draw = methods.getValue("shouldDrawNumber").offset
        target.zero = methods.getValue("shouldShowZero").offset
        target.unknown = methods.getValue("isUnknown").offset
        target.infinite = methods.getValue("isInfinite").offset
        target.supported = 1u
    }
}
