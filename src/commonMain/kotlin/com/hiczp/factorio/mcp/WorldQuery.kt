package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal data class WorldQuery(val arguments: JsonObject, val includeViewport: Boolean = false)

internal val entityFields =
    setOf(
        "name",
        "type",
        "position",
        "surface",
        "force",
        "direction",
        "orientation",
        "quality",
        "unit_number",
        "health",
        "max_health",
        "status",
        "bounding_box",
        "selection_box",
        "amount",
        "energy",
        "temperature",
        "active",
        "operable",
        "destructible",
        "minable",
        "rotatable",
        "ghost_name",
        "ghost_type",
        "crafting_progress",
        "products_finished",
        "train",
        "speed",
        "selected_gun_index",
        "driver_is_gunner",
    )
private val tileFields = setOf("name", "position", "surface", "hidden_tile", "double_hidden_tile")
internal val playerFields =
    setOf(
        "index",
        "name",
        "position",
        "surface",
        "force",
        "controller_type",
        "physical_position",
        "physical_surface",
        "zoom",
        "character",
        "cursor_stack",
        "cursor_ghost",
        "cursor_record",
        "cursor_stack_temporary",
        "hand_location",
        "blueprint_to_setup",
        "crafting_queue",
        "crafting_queue_size",
        "selected",
        "reach_distance",
        "build_distance",
        "resource_reach_distance",
        "physical_controller_type",
        "vehicle",
        "physical_vehicle",
        "driving",
        "connected",
        "last_online",
        "online_time",
        "afk_time",
        "color",
        "tag",
    )

private val forceFields =
    setOf(
        "name",
        "index",
        "current_research",
        "previous_research",
        "research_queue",
        "research_progress",
        "research_enabled",
    )

internal val localEntityKinds = setOf("character", "vehicle", "physical_vehicle")

internal fun validateSurfaceSelector(value: JsonElement) {
    val surface = value as? JsonPrimitive ?: error("surface must be a name or positive index")
    require(
        if (surface.isString)
            surface.content.isNotBlank() &&
                    surface.content.length <= 256 &&
                    '\u0000' !in surface.content
        else surface.intOrNull?.let { it > 0 } == true
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
    require(args.keys.all { it in setOf("selection", "fields", "limit", "surface", "include") }) {
        "Unknown world query argument"
    }
    val selection = args.getValue("selection").jsonObject
    require(
        selection.keys.all {
            it in
                    setOf("kind", "position", "area", "radius", "unit_number", "name", "type", "player")
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
        require(selection.keys.all { it in setOf("kind", "player") } && "surface" !in args) {
            "Player reference selections take kind and optional player, without surface"
        }
        selection["player"]?.let(::validatePlayerSelector)
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
        require(right > left && bottom > top && right - left <= 128 && bottom - top <= 128) {
            "Area must have positive dimensions no larger than 128 tiles per side"
        }
    }
    selection["radius"]?.let {
        val radius = it.doubleArgument()
        require("position" in selection && radius.isFinite() && radius in 0.0..64.0) {
            "radius requires position and must be in 0..64"
        }
    }
    selection["unit_number"]?.let {
        require(spatial == 1 || selection.keys == setOf("kind", "unit_number")) {
            "unit_number filters require position or area"
        }
        val number = it.jsonPrimitive
        require(!number.isString && number.longOrNull?.let { n -> n in 1..4294967295L } == true) {
            "Invalid unit_number"
        }
    }
    for (field in listOf("name", "type")) selection[field]?.let { validateEntityFilter(field, it) }
    args["surface"]?.let(::validateSurfaceSelector)
    val limit = args["limit"]?.intArgument() ?: 128
    require(limit in 1..512) { "limit must be in 1..512" }
    val allowed =
        when (kind) {
            "entities",
            "character",
            "vehicle",
            "physical_vehicle" -> entityFields

            "tiles" -> tileFields
            "force" -> forceFields
            else -> playerFields
        }
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
    val fields = args["fields"]?.jsonArray?.map { it.stringArgument() } ?: defaults
    require(
        fields.size in 1..32 &&
                fields.distinct().size == fields.size &&
                fields.all { it in allowed }
    ) {
        "fields must contain 1..32 distinct supported properties for $kind: ${allowed.joinToString()}"
    }
    return WorldQuery(
        buildJsonObject {
            args.forEach { (name, value) -> put(name, value) }
            put("limit", limit)
            putJsonArray("fields") { fields.forEach { add(it) } }
        }
    )
}

internal fun worldQuerySchema(): JsonObject = buildJsonObject {
    put("include", entityIncludesSchema())
    putJsonObject("selection") {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject("kind") {
                put("type", "string")
                putJsonArray("enum") {
                    add("entities")
                    add("tiles")
                    add("player")
                    add("players")
                    add("inspect")
                    add("force")
                    localEntityKinds.forEach { add(it) }
                    relatedWorldKinds.forEach { add(it) }
                }
            }
            put("position", worldPositionSchema())
            put("player", playerSelectorSchema())
            putJsonObject("target") {
                put("type", "object")
                put(
                    "description",
                    "inspect only: game; entities/player/character/vehicle/physical_vehicle/force selectors; surface with optional name; planet/recipe/technology with name; prototype with type and name. An entity target must match exactly one object. Recipe/technology may specify player for the force.",
                )
            }
            put("path", inspectionPathSchema())
            putJsonObject("connected") {
                put("type", "boolean")
                put(
                    "description",
                    "players only: filter online/offline state; omit to include all current-world players.",
                )
            }
            putJsonObject("indices") {
                put("type", "array")
                put("minItems", 1)
                put("maxItems", 128)
                put("uniqueItems", true)
                putJsonObject("items") {
                    put("type", "integer")
                    put("minimum", 1)
                }
                put(
                    "description",
                    "players only: exact API indices. Combines with names and connected filters; results sort by player index.",
                )
            }
            putJsonObject("area") {
                put("type", "object")
                put("additionalProperties", false)
                putJsonArray("required") {
                    add("left_top")
                    add("right_bottom")
                }
                putJsonObject("properties") {
                    put("left_top", worldPositionSchema())
                    put("right_bottom", worldPositionSchema())
                }
            }
            putJsonObject("radius") {
                put("type", "number")
                put("minimum", 0)
                put("maximum", 64)
            }
            putJsonObject("unit_number") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", 4294967295L)
                put(
                    "description",
                    "Direct lookup requires the prototype's get-by-unit-number flag. Otherwise combine with position/area to filter a bounded scan (at most 4096 candidates); unresolved direct lookup is an error, not proof the entity is absent.",
                )
            }
            for (name in listOf("name", "type")) putJsonObject(name) {
                entityFilterSchema(name).forEach { (key, value) -> put(key, value) }
                put(
                    "description",
                    if (name == "name")
                        "entities only: exact internal prototype name(s). Entries are OR; name and type combine with AND."
                    else
                        "entities: native type(s), e.g. transport-belt, underground-belt, splitter, mining-drill. Entries are OR; name and type combine with AND. prototypes: one catalog type listed in selection's description.",
                )
            }
            relatedSelectionProperties().forEach { (name, value) -> put(name, value) }
        }
        put(
            "description",
            "entities: position/area uses collision geometry; position+radius uses centers; unit_number uses the world's index. tiles: one position or area. Coordinates are tiles, areas at most 128x128. player|character|vehicle|physical_vehicle|force accepts optional player; absent entity references return availability:nil. players enumerates/filter players. inspect requires target and optional path. inventories discovers inventory names; inventory reads slots. quickbar reads slots/screen_pages. prototypes requires type:item|recipe|entity|fluid|technology|quality|item_group|item_subgroup. recipes/technologies read local-force objects. Catalogs accept names/search and recipe relations.",
        )
        putJsonArray("required") { add("kind") }
    }
    putJsonObject("fields") {
        put("type", "array")
        put("minItems", 1)
        put("maxItems", 64)
        put("uniqueItems", true)
        putJsonObject("items") { put("type", "string") }
        put(
            "description",
            "Omit for defaults; inspect values defaults to all readable attributes and accepts up to 64 names discovered with members; other kinds accept up to 32. quickbar/inventories reject fields. Entities/references: ${entityFields.joinToString()}. Tiles: ${tileFields.joinToString()}. Player/players: ${playerFields.joinToString()}. Force: ${forceFields.joinToString()}. ${relatedFieldsDescription()}",
        )
    }
    putJsonObject("mode") {
        put("type", "string")
        putJsonArray("enum") {
            add("values")
            add("members")
            add("entries")
        }
        put("default", "values")
        put(
            "description",
            "inspect only. values reads a page of native attributes (all readable names when fields omitted); members discovers attribute/query metadata without reading their values; entries pages a table or indexable object. Related objects remain references; continue via path. Errors, nil and bounded previews are explicit.",
        )
    }
    putJsonObject("surface") {
        putJsonArray("type") {
            add("string")
            add("integer")
        }
        put(
            "description",
            "Spatial queries or spatial inventory owners only: surface name or positive index; defaults to the local player's current controller surface, which may differ from the character's physical surface.",
        )
    }
    putJsonObject("limit") {
        put("type", "integer")
        put("minimum", 1)
        put("maximum", 512)
        put(
            "description",
            "Spatial/player default 128, max 512. Inventory/catalog/players/inspect default 64; inventory slots max 512, players/catalogs max 128, inspect max 256. quickbar rejects limit.",
        )
    }
    putJsonObject("offset") {
        put("type", "integer")
        put("minimum", 0)
        put("maximum", 65536)
        put("default", 0)
        put(
            "description",
            "Inventory/catalog/players/inspect only: zero-based page offset. Inventory slot indices remain one-based. Each page is a fresh observation.",
        )
    }
}

/** Lua encodes empty tables as objects, even for API fields whose contract is an array. */
internal fun decodeWorldQuery(text: String): JsonObject {
    fun array(value: JsonElement): JsonArray =
        if (value is JsonObject && value.isEmpty()) JsonArray(emptyList()) else value.jsonArray

    val result = Json.parseToJsonElement(text).jsonObject
    val arrays =
        setOf(
            "crafting_queue",
            "ingredients",
            "products",
            "additional_categories",
            "surface_conditions",
            "items_to_place_this",
            "effects",
            "research_unit_ingredients",
            "research_queue",
            "subgroups",
        )
    val objects =
        array(result.getValue("objects")).map { value ->
            val original = value.jsonObject
            val objectValue =
                JsonObject(
                    original.mapValues { (key, item) ->
                        if (
                            result["observation"]?.jsonPrimitive?.content == "object_inspection" &&
                            key in setOf("members", "entries")
                        )
                            array(item)
                        else if (key == "details")
                            JsonObject(
                                item.jsonObject.mapValues { (name, detail) ->
                                    if (name == "filters")
                                        JsonObject(
                                            detail.jsonObject.mapValues { (field, data) ->
                                                if (field == "slots") array(data) else data
                                            }
                                        )
                                    else detail
                                }
                            )
                        else item
                    }
                )
            val attributes = objectValue["attributes"]?.jsonObject
            if ("entity_groups" in objectValue)
                JsonObject(
                    objectValue +
                            ("entity_groups" to
                                    JsonArray(
                                        array(objectValue.getValue("entity_groups")).map { entry ->
                                            val group = entry.jsonObject
                                            JsonObject(group + ("aggregates" to array(group.getValue("aggregates"))))
                                        }
                                    ))
                )
            else if (
                attributes == null ||
                result["observation"]?.jsonPrimitive?.content == "object_inspection"
            )
                objectValue
            else
                JsonObject(
                    objectValue +
                            ("attributes" to
                                    JsonObject(
                                        attributes.mapValues { (key, item) ->
                                            if (key in arrays) array(item) else item
                                        }
                                    ))
                )
        }
    return JsonObject(
        result +
                ("objects" to JsonArray(objects)) +
                result["missing_names"]?.let { mapOf("missing_names" to array(it)) }.orEmpty() +
                result["active_pages"]?.let { mapOf("active_pages" to array(it)) }.orEmpty() +
                result["path"]?.let { mapOf("path" to array(it)) }.orEmpty()
    )
}

private fun worldPositionSchema() = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    putJsonArray("required") {
        add("x")
        add("y")
    }
    putJsonObject("properties") {
        for (name in listOf("x", "y")) putJsonObject(name) {
            put("type", "number")
            put("minimum", -1000000)
            put("maximum", 1000000)
        }
    }
}
