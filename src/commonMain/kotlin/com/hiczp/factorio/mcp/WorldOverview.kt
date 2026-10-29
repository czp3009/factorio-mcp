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
                        "group_by",
                        "aggregates",
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
    require(detail == "grid" || args.keys.none { it in setOf("group_by", "aggregates") }) {
        "group_by/aggregates require grid detail"
    }
    val groupBy = args["group_by"]?.let { value ->
        val array = requireNotNull(value as? JsonArray) { "group_by must be an array" }
        array.map { it.stringArgument() }.also { fields ->
            require(fields.distinct().size == fields.size && fields.all { it in entityFields }) {
                "group_by requires distinct entity fields"
            }
        }
    } ?: listOf("name", "type", "quality")
    val aggregates = args["aggregates"]?.let { value ->
        val array = requireNotNull(value as? JsonArray) { "aggregates must be an array" }
        require(array.distinct().size == array.size) { "aggregates requires distinct operations" }
        array.forEach { item ->
            val aggregate = requireNotNull(item as? JsonObject) { "An aggregate must be an object" }
            require(aggregate.keys == setOf("operation", "field")) { "An aggregate requires operation and field" }
            require(aggregate.getValue("operation").stringArgument() in setOf("sum", "min", "max")) {
                "Aggregate operation must be sum, min or max"
            }
            require(aggregate.getValue("field").stringArgument() in entityFields) { "Unsupported aggregate field" }
        }
        array
    } ?: JsonArray(emptyList())
    val fields =
        args["fields"]?.jsonArray?.map { it.stringArgument() }
            ?: if (detail == "entities")
                listOf("name", "type", "position", "unit_number", "direction", "quality")
            else emptyList()
    require(
        detail != "entities" ||
                fields.isNotEmpty() &&
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
        require(right > left && bottom > top) {
            "Overview area must have positive dimensions"
        }
    }
    args["surface"]?.let(::validateSurfaceSelector)
    val cell = args["cell_size"]?.intArgument()
    require(cell == null || cell > 0) { "cell_size must be positive" }
    val limit = args["entity_limit"]?.intArgument() ?: 64
    require(limit > 0) { "entity_limit must be positive" }
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
            if (detail == "grid") {
                putJsonArray("group_by") { groupBy.forEach { add(it) } }
                put("aggregates", aggregates)
            }
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
    putJsonObject("group_by") {
        put("type", "array")
        put("uniqueItems", true)
        putJsonArray("default") {
            add("name")
            add("type")
            add("quality")
        }
        putJsonObject("items") {
            put("type", "string")
            putJsonArray("enum") { entityFields.forEach { add(it) } }
        }
        put("description", "Grid only. Group by the original values of these fields within each cell; empty means one group per cell.")
    }
    putJsonObject("aggregates") {
        put("type", "array")
        put("uniqueItems", true)
        put("description", "Grid only. Numeric calculations requested explicitly; count is always included. Missing, failed, non-numeric and non-finite reads are excluded and counted.")
        putJsonObject("items") {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") {
                add("operation")
                add("field")
            }
            putJsonObject("properties") {
                putJsonObject("operation") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("sum")
                        add("min")
                        add("max")
                    }
                }
                putJsonObject("field") {
                    put("type", "string")
                    putJsonArray("enum") { entityFields.forEach { add(it) } }
                }
            }
        }
    }
    putJsonObject("fields") {
        put("type", "array")
        put("minItems", 1)
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
                                "World tile coordinates with positive dimensions. Omit to survey the current viewport; supply an area if viewport metadata is unavailable."
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
        put(
            "description",
            "Grid cell width/height in tiles. Defaults to at least 32, enlarged to keep at most 16 rows/columns. Smaller explicit cell_size values return more cells.",
        )
    }
    putJsonObject("entity_limit") {
        put("type", "integer")
        put("minimum", 1)
        put("default", 64)
        put(
            "description",
            "Maximum entity candidates per grid cell, or per whole area with detail:entities. Lookahead detects truncation; incomplete reads are explicit.",
        )
    }
}
