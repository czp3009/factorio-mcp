package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal data class WorldQuery(val arguments: JsonObject, val includeViewport: Boolean = false)

internal val localEntityKinds = setOf("character", "vehicle", "physical_vehicle")

internal fun validateSurfaceSelector(value: JsonElement) {
    val surface = value as? JsonPrimitive ?: error("surface must be a name or positive index")
    require(
        if (surface.isString)
            surface.content.isNotEmpty() &&
                    '\u0000' !in surface.content
        else surface.longOrNull?.let { it in 1..4294967295L } == true
    ) {
        "surface must be a name or positive index"
    }
}

internal fun parseWorldQuery(args: JsonObject): WorldQuery {
    if (args["selection"]?.jsonObject?.get("kind")?.stringArgument() == "inspect")
        return parseObjectInspection(args)
    if (args["selection"]?.jsonObject?.get("kind")?.stringArgument() == "players")
        return parsePlayersQuery(args)
    if (args["selection"]?.jsonObject?.get("kind")?.stringArgument() in relatedWorldKinds)
        return parseRelatedWorldQuery(args)
    require(args.keys.all { it in setOf("selection", "fields", "limit", "surface", "include", "depth", "reads") }) {
        "Unknown world query argument"
    }
    val selection = args.getValue("selection").jsonObject
    require(
        selection.keys.all {
            it in
                    setOf(
                        "kind",
                        "position",
                        "area",
                        "radius",
                        "unit_number",
                        "name",
                        "type",
                        "player",
                        "force",
                        "filters"
                    )
        }
    ) {
        "Unknown selection field"
    }
    val kind = selection.getValue("kind").stringArgument()
    args["include"]?.let {
        require(kind == "entities" || kind in localEntityKinds) {
            "include requires an entity selection"
        }
        validateEntityIncludes(it)
    }
    require(kind in setOf("entities", "tiles", "player", "force") + localEntityKinds) {
        "Unsupported world selection kind"
    }
    val spatial = listOf("position", "area").count { it in selection }
    if (kind in setOf("player", "force") + localEntityKinds) {
        require(selection.keys.all {
            it in setOf(
                "kind",
                "player"
            ) || kind == "force" && it == "force"
        } && "surface" !in args) {
            "Player reference selections take kind and optional player, without surface"
        }
        selection["player"]?.let(::validatePlayerSelector)
        selection["force"]?.let(::validateSurfaceSelector)
        require(selection.keys.count { it in setOf("force", "player") } <= 1) { "Select force or player, not both" }
    } else if (kind == "entities") {
        require(spatial <= 1 && (spatial == 1 || "unit_number" in selection)) {
            "Select position or area, optionally filtered by unit_number; or use a directly indexed unit_number"
        }
    } else {
        require(spatial == 1) { "Specify exactly one of position or area" }
    }
    require("player" !in selection || kind in setOf("player", "force") + localEntityKinds) {
        "player requires a player reference selection"
    }
    require("force" !in selection || kind == "force") { "Use filters.force for entity filters" }
    require("filters" !in selection || kind == "entities") { "filters requires entities" }
    selection["filters"]?.let(::validateEntitySearchFilters)
    if (kind != "entities")
        require(selection.keys.none { it in setOf("radius", "unit_number", "name", "type") }) {
            "Entity filters are only valid for entities"
        }
    fun point(value: JsonElement): Pair<Double, Double> {
        val point = value.jsonObject
        require(point.keys == setOf("x", "y")) { "Coordinates require x and y" }
        val x = point.getValue("x").doubleArgument()
        val y = point.getValue("y").doubleArgument()
        require(
            x.isFinite() &&
                    y.isFinite() &&
                    x in -1_000_000.0..1_000_000.0 &&
                    y in -1_000_000.0..1_000_000.0
        ) {
            "Coordinates must be finite and within +/-1000000 tiles"
        }
        return x to y
    }
    selection["position"]?.let(::point)
    selection["area"]?.let {
        val area = it.jsonObject
        require(area.keys == setOf("left_top", "right_bottom")) {
            "area requires left_top and right_bottom"
        }
        val (left, top) = point(area.getValue("left_top"))
        val (right, bottom) = point(area.getValue("right_bottom"))
        require(right > left && bottom > top) {
            "Area must have positive dimensions"
        }
    }
    selection["radius"]?.let {
        val radius = it.doubleArgument()
        require("position" in selection && radius.isFinite() && radius >= 0.0) {
            "radius requires position and must be finite and nonnegative"
        }
    }
    selection["unit_number"]?.let {
        require(spatial == 1 || selection.keys == setOf("kind", "unit_number")) {
            "unit_number filters require position or area"
        }
        val number = it.jsonPrimitive
        val identifier = number.content.toULongOrNull()
        require(!number.isString && identifier != null && identifier > 0uL) {
            "Invalid unit_number"
        }
        // The game Lua number ABI is a double; never round an entity identity during admission.
        val discardedBits = (64 - identifier.countLeadingZeroBits() - 53).coerceAtLeast(0)
        require(identifier and ((1uL shl discardedBits) - 1uL) == 0uL) {
            "unit_number must be exactly representable by the game's Lua number type"
        }
    }
    for (field in listOf("name", "type")) selection[field]?.let { validateEntityFilter(field, it) }
    args["surface"]?.let(::validateSurfaceSelector)
    val single = kind in setOf("player", "force") + localEntityKinds ||
            kind == "entities" && "unit_number" in selection || kind == "tiles" && "position" in selection
    require(!single || "limit" !in args) { "A single-object selection does not accept limit" }
    val limit = if (single) null else args["limit"]?.intArgument() ?: 128
    require(limit == null || limit > 0) { "limit must be positive" }
    val depth = observationDepth(args["depth"]?.intArgument())
    val defaults =
        when (kind) {
            "entities",
            "character",
            "vehicle",
            "physical_vehicle" ->
                listOf(
                    "name",
                    "type",
                    "position",
                    "unit_number",
                    "quality",
                    "force",
                    "health",
                    "status",
                )

            "tiles" -> listOf("name", "position", "hidden_tile", "double_hidden_tile")
            "force" ->
                listOf(
                    "name",
                    "current_research",
                    "research_queue",
                    "research_progress",
                    "research_enabled",
                )

            else ->
                listOf(
                    "index",
                    "name",
                    "controller_type",
                    "position",
                    "physical_position",
                    "cursor_stack",
                    "cursor_ghost",
                    "cursor_record",
                    "cursor_stack_temporary",
                    "hand_location",
                    "crafting_queue",
                )
        }
    val fields = args["fields"]?.let(::nativeFields) ?: defaults
    return WorldQuery(
        buildJsonObject {
            args.forEach { (name, value) -> put(name, value) }
            limit?.let { put("limit", it) }
            put("depth", depth)
            args["reads"]?.let { put("reads", observationReads(it)) }
            putJsonArray("fields") { fields.forEach { add(it) } }
        }
    )
}

/** Normalize MCP-owned envelope arrays, preserving the game's attribute/value table encoding. */
internal fun decodeWorldQuery(text: String, request: JsonObject? = null): JsonObject {
    fun array(value: JsonElement): JsonArray =
        if (value is JsonObject && value.isEmpty()) JsonArray(emptyList()) else value.jsonArray

    fun path(value: JsonElement): JsonArray = JsonArray(array(value).map { raw ->
        JsonObject(raw.jsonObject.filterKeys { it != "argument_count" })
    })

    val reads = request?.get("reads")?.jsonArray

    fun observation(value: JsonObject, selectedPath: JsonElement? = null): JsonObject = buildJsonObject {
        value.forEach { (key, item) -> put(key, item) }
        for (key in listOf("members", "reads")) value[key]?.let {
            put(key, JsonArray(array(it).mapIndexed { index, child ->
                if (key == "reads") observation(
                    child.jsonObject,
                    reads?.getOrNull(index)?.jsonObject?.get("path")
                ) else child
            }))
        }
        value["entries"]?.let {
            put("entries", JsonArray(array(it).map { entry ->
                val item = entry.jsonObject
                if ("reads" in item) observation(item) else item
            }))
        }
        value["details"]?.jsonObject?.get("filters")?.jsonObject?.let { filters ->
            put(
                "details", JsonObject(
                    value.getValue("details").jsonObject +
                            ("filters" to JsonObject(filters + ("slots" to array(filters.getValue("slots")))))
                )
            )
        }
        value["entity_groups"]?.let {
            put("entity_groups", JsonArray(array(it).map { raw ->
                val group = raw.jsonObject
                JsonObject(group + ("aggregates" to array(group.getValue("aggregates"))))
            }))
        }
        (selectedPath ?: value["path"])?.let { put("path", path(it)) }
    }

    val result = Json.parseToJsonElement(text).jsonObject
    return buildJsonObject {
        result.forEach { (key, value) -> put(key, value) }
        val selectedPath = request?.get("inspection")?.jsonObject?.get("path")
        put("objects", JsonArray(array(result.getValue("objects")).map { observation(it.jsonObject) }))
        for (key in listOf("missing_names", "missing_indices", "active_pages")) result[key]?.let { put(key, array(it)) }
        (selectedPath ?: result["path"])?.let { put("path", path(it)) }
        if (result["selection"]?.jsonObject?.get("kind")?.jsonPrimitive?.content == "overview")
            put("has_more", "next_cell" in result)
    }
}
