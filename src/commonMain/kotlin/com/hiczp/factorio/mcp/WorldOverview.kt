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
                        "cell_limit",
                        "start_cell",
                        "detail",
                        "group_by",
                        "aggregates",
                        "fields",
                        "include",
                        "reads",
                        "depth",
                        "name",
                        "type",
                        "filters",
                    )
        }
    ) {
        "Unknown world overview argument"
    }
    val detail = args["detail"]?.stringArgument() ?: "grid"
    require(detail in setOf("grid", "entities")) { "detail must be grid or entities" }
    require(detail == "entities" || args.keys.none { it in setOf("fields", "include", "reads") }) {
        "fields/include/reads require detail:entities; grid aggregation does not merge entity configuration"
    }
    require(detail == "grid" || args.keys.none { it in setOf("cell_size", "cell_limit", "start_cell") }) {
        "cell_size/cell_limit/start_cell require grid detail"
    }
    args["start_cell"]?.let { value ->
        require("area" in args && "cell_size" in args) { "start_cell requires the original area and explicit cell_size" }
        val cell = value.jsonObject
        require(cell.keys == setOf("row", "column")) { "start_cell requires row and column" }
        cell.values.forEach { require(it.intArgument() >= 0) { "Cell indices must be nonnegative" } }
    }
    require(detail == "grid" || args.keys.none { it in setOf("group_by", "aggregates") }) {
        "group_by/aggregates require grid detail"
    }
    val groupBy = args["group_by"]?.let { value ->
        val array = requireNotNull(value as? JsonArray) { "group_by must be an array" }
        array.map { it.stringArgument() }.also { fields ->
            require(fields.distinct().size == fields.size && fields.all { it.matches(nativeMemberPattern) }) {
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
            require(
                aggregate.getValue("field").stringArgument().matches(nativeMemberPattern)
            ) { "Invalid aggregate field" }
        }
        array
    } ?: JsonArray(emptyList())
    val fields =
        args["fields"]?.let(::nativeFields)
            ?: if (detail == "entities")
                listOf("name", "type", "position", "unit_number", "direction", "quality")
            else emptyList()
    args["include"]?.let(::validateEntityIncludes)
    for (key in listOf("name", "type")) args[key]?.let { validateEntityFilter(key, it) }
    args["filters"]?.let(::validateEntitySearchFilters)
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
    val cellLimit = args["cell_limit"]?.intArgument() ?: 256
    require(cellLimit > 0) { "cell_limit must be positive" }
    return WorldQuery(
        buildJsonObject {
            putJsonObject("selection") {
                put("kind", "overview")
                args["area"]?.let { put("area", it) }
                for (key in listOf("name", "type")) args[key]?.let { put(key, it) }
                args["filters"]?.let { put("filters", it) }
            }
            args["surface"]?.let { put("surface", it) }
            cell?.let { put("cell_size", it) }
            if (detail == "grid") {
                put("cell_limit", cellLimit)
                args["start_cell"]?.let { put("start_cell", it) }
            }
            put("limit", limit)
            put("detail", detail)
            if (detail == "grid") {
                putJsonArray("group_by") { groupBy.forEach { add(it) } }
                put("aggregates", aggregates)
            }
            args["include"]?.let { put("include", it) }
            args["reads"]?.let { put("reads", observationReads(it)) }
            put("depth", observationDepth(args["depth"]?.intArgument()))
            putJsonArray("fields") { fields.forEach { add(it) } }
        },
        includeViewport = true,
    )
}

internal fun worldOverviewSchema() = buildJsonObject {
    putJsonObject("reads") {
        put("\$ref", "#/\$defs/reads")
        put("description", "detail:entities only. Ordered relative reads for each returned entity; each read has its own projection and collection/text bounds.")
    }
    putJsonObject("depth") {
        put("type", "integer")
        put("minimum", 0)
        put("maximum", Int.MAX_VALUE)
        put("default", 3)
        put("description", "Plain-table preview depth for returned fields or grid grouping values, default 3, clamped to 65535. Native objects remain references; omitted deeper children are marked.")
    }
    putJsonObject("filters") {
        put("type", "object")
        put(
            "description",
            "Additional native EntitySearchFilters. area/position/radius/name/type/limit are rejected here; use area, name, type and entity_limit on this tool."
        )
    }
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
            "grid uses cell_size/cell_limit/start_cell, group_by and aggregates. entities uses fields/include/reads and applies entity_limit to the whole area. Options from the other mode are rejected.",
        )
    }
    put("include", JsonObject(entityIncludesSchema() + ("description" to JsonPrimitive(
        "detail:entities only. recipe returns the configured recipe and quality; fluids returns native amounts; filters returns indexed filters and native mode flags. Availability/errors are explicit."
    ))))
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
            put("pattern", "^[A-Za-z_][A-Za-z0-9_]*$")
        }
        put(
            "description",
            "Grid only. Group by the original values of these fields within each cell; empty means one group per cell."
        )
    }
    putJsonObject("aggregates") {
        put("type", "array")
        put("uniqueItems", true)
        put(
            "description",
            "Grid only. Numeric calculations requested explicitly; count is always included. Missing, failed, non-numeric and non-finite reads are excluded and counted."
        )
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
                    put("pattern", "^[A-Za-z_][A-Za-z0-9_]*$")
                }
            }
        }
    }
    putJsonObject("fields") {
        put("type", "array")
        put("minItems", 0)
        put("uniqueItems", true)
        putJsonObject("items") {
            put("type", "string")
            put("pattern", "^[A-Za-z_][A-Za-z0-9_]*$")
        }
        put(
            "description",
            "detail:entities only; same raw entity fields as world_query. Omit for identity, position, direction and quality.",
        )
    }
    put("area", buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") {
            add("left_top")
            add("right_bottom")
        }
        putJsonObject("properties") {
            put("left_top", worldPositionSchema())
            put(
                "right_bottom",
                worldPositionSchema()
            )
        }
        put("description", "World coordinates with positive dimensions. Omit to survey the current viewport.")
    })
    putJsonObject("surface") {
        putJsonArray("type") {
            add("string")
            add("integer")
        }
        put("minimum", 1)
        put("maximum", 4294967295L)
        put("minLength", 1)
        put("description", "Explicit area only. Otherwise the actual viewport's surface is used.")
    }
    putJsonObject("cell_size") {
        put("type", "integer")
        put("minimum", 1)
        put("maximum", Int.MAX_VALUE)
        put(
            "description",
            "Grid only. Cell width/height in tiles. Defaults to at least 32, enlarged to keep at most 16 rows/columns. Smaller explicit cell_size values return more cells.",
        )
    }
    putJsonObject("entity_limit") {
        put("type", "integer")
        put("minimum", 1)
        put("maximum", Int.MAX_VALUE)
        put("default", 64)
        put(
            "description",
            "Maximum entity candidates per grid cell, or per whole area with detail:entities. Lookahead detects truncation; incomplete reads are explicit.",
        )
    }
    putJsonObject("cell_limit") {
        put("type", "integer")
        put("minimum", 1)
        put("maximum", Int.MAX_VALUE)
        put("default", 256)
        put("description", "Grid only. Maximum returned cells; use next_cell to continue or increase for larger pages.")
    }
    putJsonObject("start_cell") {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") {
            add("row")
            add("column")
        }
        putJsonObject("properties") {
            for (name in listOf("row", "column")) putJsonObject(name) {
                put("type", "integer")
                put("minimum", 0)
                put("maximum", Int.MAX_VALUE)
            }
        }
        put(
            "description",
            "Grid only. Inclusive zero-based next_cell; supply the original area, surface and explicit cell_size. Each page is a fresh observation."
        )
    }
}
