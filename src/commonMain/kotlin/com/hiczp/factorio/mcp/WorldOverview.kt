package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal fun parseWorldOverview(args: JsonObject): WorldQuery {
    require(
        args.keys.all {
            it in
                    setOf(
                        "area",
                        "surface",
                        "cell_size",
                        "entity_limit",
                        "detail",
                        "fields",
                        "include",
                        "name",
                        "type",
                    )
        }
    ) {
        "Unknown world overview argument"
    }
    val detail = args["detail"]?.stringArgument() ?: "grid"
    require(detail in setOf("grid", "entities")) { "detail must be grid or entities" }
    require(detail == "entities" || args.keys.none { it in setOf("fields", "include") }) {
        "fields/include require detail:entities; grid aggregation does not merge entity configuration"
    }
    require(detail == "grid" || "cell_size" !in args) { "cell_size is only valid for grid detail" }
    val fields =
        args["fields"]?.jsonArray?.map { it.stringArgument() }
            ?: if (detail == "entities")
                listOf("name", "type", "position", "unit_number", "direction", "quality")
            else emptyList()
    require(
        detail != "entities" ||
                fields.size in 1..32 &&
                fields.distinct().size == fields.size &&
                fields.all { it in entityFields }
    ) {
        "Unsupported entity fields"
    }
    args["include"]?.let(::validateEntityIncludes)
    for (key in listOf("name", "type")) args[key]?.let { validateEntityFilter(key, it) }
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
    args["surface"]?.let(::validateSurfaceSelector)
    val cell = args["cell_size"]?.intArgument()
    require(cell == null || cell in 1..4096) { "cell_size must be in 1..4096 tiles" }
    val limit = args["entity_limit"]?.intArgument() ?: 64
    require(limit in 1..512) { "entity_limit must be in 1..512" }
    return WorldQuery(
        buildJsonObject {
            putJsonObject("selection") {
                put("kind", "overview")
                args["area"]?.let { put("area", it) }
                for (key in listOf("name", "type")) args[key]?.let { put(key, it) }
            }
            args["surface"]?.let { put("surface", it) }
            cell?.let { put("cell_size", it) }
            put("limit", limit)
            put("detail", detail)
            args["include"]?.let { put("include", it) }
            putJsonArray("fields") { fields.forEach { add(it) } }
        },
        includeViewport = true,
    )
}

internal fun worldOverviewSchema() = buildJsonObject {
    for (key in listOf("name", "type")) put(key, entityFilterSchema(key))
    putJsonObject("detail") {
        put("type", "string")
        put("default", "grid")
        putJsonArray("enum") {
            add("grid")
            add("entities")
        }
        put(
            "description",
            "grid aggregates counts per cell. entities returns individual identities/positions and requested fields/details; entity_limit then applies to the whole area.",
        )
    }
    put("include", entityIncludesSchema())
    putJsonObject("fields") {
        put("type", "array")
        put("minItems", 1)
        put("maxItems", 32)
        put("uniqueItems", true)
        putJsonObject("items") {
            put("type", "string")
            putJsonArray("enum") { entityFields.forEach { add(it) } }
        }
        put(
            "description",
            "detail:entities only; same raw entity fields as world_query. Omit for identity, position, direction and quality.",
        )
    }
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
            "Maximum entity candidates per grid cell (4096 total work bound), or per whole area with detail:entities. Lookahead detects truncation; incomplete reads are explicit.",
        )
    }
}
