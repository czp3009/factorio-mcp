package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal fun parseWorldOverview(args: JsonObject): WorldQuery {
    require(args.keys.all { it in setOf("area", "surface", "cell_size", "entity_limit") }) {
        "Unknown world overview argument"
    }
    require("surface" !in args || "area" in args) { "surface requires an explicit area" }
    args["area"]?.let {
        val area = it.jsonObject
        require(area.keys == setOf("left_top", "right_bottom")) {
            "area requires left_top and right_bottom"
        }
        fun point(value: JsonElement): Pair<Double, Double> {
            val point = value.jsonObject
            require(point.keys == setOf("x", "y")) { "Coordinates require x and y" }
            val x = point.getValue("x").doubleArgument()
            val y = point.getValue("y").doubleArgument()
            require(
                x.isFinite() &&
                        y.isFinite() &&
                        x in -1000000.0..1000000.0 &&
                        y in -1000000.0..1000000.0
            )
            return x to y
        }
        val (left, top) = point(area.getValue("left_top"))
        val (right, bottom) = point(area.getValue("right_bottom"))
        require(right > left && bottom > top && right - left <= 4096 && bottom - top <= 4096) {
            "Overview area must have positive dimensions no larger than 4096 tiles per side"
        }
    }
    args["surface"]?.let {
        val value = it.jsonPrimitive
        require(
            if (value.isString)
                value.content.isNotBlank() &&
                        value.content.length <= 256 &&
                        '\u0000' !in value.content
            else value.intOrNull?.let { index -> index > 0 } == true
        ) {
            "Invalid surface"
        }
    }
    val cell = args["cell_size"]?.intArgument()
    require(cell == null || cell in 1..4096) { "cell_size must be in 1..4096 tiles" }
    val limit = args["entity_limit"]?.intArgument() ?: 64
    require(limit in 1..512) { "entity_limit must be in 1..512 per cell" }
    return WorldQuery(
        buildJsonObject {
            putJsonObject("selection") {
                put("kind", "overview")
                args["area"]?.let { put("area", it) }
            }
            args["surface"]?.let { put("surface", it) }
            cell?.let { put("cell_size", it) }
            put("limit", limit)
            putJsonArray("fields") {}
        },
        includeViewport = true,
    )
}

internal fun worldOverviewSchema() = buildJsonObject {
    put(
        "area",
        JsonObject(
            worldQuerySchema()
                .getValue("selection")
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("area")
                .jsonObject +
                    ("description" to
                            JsonPrimitive(
                                "World tile coordinates with positive dimensions, at most 4096 tiles per side. Omit to survey the current viewport; supply an area if viewport metadata is unavailable or the viewport is too large."
                            ))
        ),
    )
    putJsonObject("surface") {
        putJsonArray("type") {
            add("string")
            add("integer")
        }
        put("description", "Explicit area only. Otherwise the actual viewport's surface is used.")
    }
    putJsonObject("cell_size") {
        put("type", "integer")
        put("minimum", 1)
        put("maximum", 4096)
        put(
            "description",
            "Grid cell width/height in tiles. Defaults to at least 32, enlarged to keep at most 16 rows/columns. Explicit granularity must yield at most 256 cells.",
        )
    }
    putJsonObject("entity_limit") {
        put("type", "integer")
        put("minimum", 1)
        put("maximum", 512)
        put("default", 64)
        put(
            "description",
            "Collision candidates per cell; total work is bounded to 4096 candidates. Truncated or unscanned cells are explicit.",
        )
    }
}
