package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal data class WidgetGraphSeries(
    val index: Int,
    val label: String,
    val values: List<Double>,
    val total: Int = values.size,
    val offset: Int = 0,
)

/** Stored series data; axis labels remain ordinary child widgets and callback units are not inferred. */
internal data class WidgetGraph(
    val sampleCount: Long,
    val minimum: Double,
    val maximum: Double,
    val series: List<WidgetGraphSeries>,
    val collection: Int = 0,
    val total: Int = 0,
    val offset: Int = 0,
)

private fun Double.graphNumber(): JsonElement = if (isFinite()) JsonPrimitive(this) else buildJsonObject {
    put("value_type", "non_finite_number")
    put("value", when {
        this@graphNumber.isNaN() -> "nan"
        this@graphNumber > 0 -> "infinity"
        else -> "-infinity"
    })
}

private fun WidgetGraphSeries.json(): JsonObject = buildJsonObject {
    put("index", index)
    put("label", label)
    put("values", JsonArray(values.map { it.graphNumber() }))
}

internal fun WidgetGraph.json(): JsonObject = buildJsonObject {
    check(collection == 0) { "A graph collection cannot be returned as a complete property" }
    put("sample_count", sampleCount)
    put("minimum_value", minimum.graphNumber())
    put("maximum_value", maximum.graphNumber())
    put("index_base", 0)
    put("series", JsonArray(series.map { it.json() }))
}

internal fun WidgetGraph.collectionJson(): JsonObject {
    val values: List<JsonElement> = when (collection) {
        1 -> series.map { it.json() }
        2 -> series.single().values.map { it.graphNumber() }
        else -> error("Invalid graph collection")
    }
    require(total >= 0 && offset >= 0 && values.size <= total - minOf(offset, total))
    return buildJsonObject {
        put("value_type", "collection")
        put("index_base", 0)
        put("total", total)
        put("offset", offset)
        val next = offset.toLong() + values.size
        put("has_more", next < total)
        put("truncated", next < total)
        if (next < total) put("next_offset", next)
        putJsonArray("entries") { values.forEachIndexed { index, value -> addJsonObject {
            put("key", offset + index)
            put("value", value)
        } } }
    }
}

/** Validate the small structural path before sending its scalar selection to the resident. */
internal val UiCollection.graphCollection: Int
    get() {
        if (source != "graph") return 0
        val first = path.firstOrNull()?.jsonObject
        require(first == buildJsonObject { put("property", "series") }) { "Graph paths must select series" }
        if (path.size == 1) return 1
        require(path.size == 3 && path[1].jsonObject.keys == setOf("index") &&
            path[2].jsonObject == buildJsonObject { put("property", "values") }) {
            "Graph sample paths require series, a zero-based integer index and values"
        }
        require(path[1].jsonObject.getValue("index").intArgument() >= 0) { "Graph series index must be nonnegative" }
        return 2
    }

internal val UiCollection.graphSeries: Int
    get() = if (graphCollection == 2) path[1].jsonObject.getValue("index").intArgument() else 0
