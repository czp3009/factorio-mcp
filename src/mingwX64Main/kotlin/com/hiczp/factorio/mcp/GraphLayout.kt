@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.FmUiGraphLayout
import com.hiczp.factorio.mcp.nativebridge.FmUiGraphVectorLayout

/** Existing Graph storage only. All member, container and element locations come from matching PDB types. */
internal class GraphLayout(private val types: DebugTypes, descriptors: Map<String, List<ULong>>) {
    private val owner = "agui::Graph"
    private val seriesType = "$owner::GraphSeries"
    private val string = "std::basic_string<char,std::char_traits<char>,std::allocator<char> >"
    private val type = descriptors.getValue("??_R0?AVGraph@agui@@@8").single()
    private val size = types.aggregateSize(owner).also { require(it in 8uL..16uL * 1024u * 1024u) }.toUInt()
    private val sampleCount = types.scalarMember(owner, "sampleCount", 4uL, 7u)
    private val minimum = types.scalarMember(owner, "minimumValue", 4uL, 8u)
    private val maximum = types.scalarMember(owner, "maximumValue", 4uL, 8u)
    init {
        require(types.directBaseOffset(owner, "agui::Widget") == 0u)
    }
    private data class Vector(val first: UInt, val last: UInt, val end: UInt, val stride: UInt) {
        fun write(v: FmUiGraphVectorLayout) {
            v.first = first
            v.last = last
            v.end = end
            v.stride = stride
        }
    }
    private fun vector(owner: String, field: String, element: String?): Vector {
        val vector = types.memberTypeName(owner, field)
        require(vector.startsWith("std::vector<"))
        val offset = types.namedMember(owner, field, vector, types.aggregateSize(vector))
        fun member(name: String) = offset + if (element == null)
            types.scalarPointerPath(vector, "_Mypair", "_Myval2", name, bytes = 4uL, basic = 8u)
        else types.pointerPath(vector, "_Mypair", "_Myval2", name, target = element, indirections = 1)
        val stride = if (element == null) 4u else types.aggregateSize(element).also { require(it in 1uL..65536uL) }.toUInt()
        return Vector(member("_Myfirst"), member("_Mylast"), member("_Myend"), stride)
    }
    private val series = vector(owner, "seriesData", seriesType)
    private val values = vector(seriesType, "values", null)
    private val seriesLabel = types.namedMember(seriesType, "label", string, types.aggregateSize(string))

    fun write(v: FmUiGraphLayout) {
        v.type = type
        v.supported = 1u
        v.size = size
        v.sampleCount = sampleCount
        v.minimum = minimum
        v.maximum = maximum
        series.write(v.series)
        values.write(v.values)
        v.seriesLabel = seriesLabel
    }
}
