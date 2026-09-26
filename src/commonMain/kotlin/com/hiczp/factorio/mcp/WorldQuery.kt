package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal data class WorldQuery(val arguments: JsonObject, val includeViewport: Boolean = false)

private val entityFields =
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
private val playerFields =
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

internal fun parseWorldQuery(args: JsonObject): WorldQuery {
    if (args["selection"]?.jsonObject?.get("kind")?.stringArgument() in relatedWorldKinds)
        return parseRelatedWorldQuery(args)
    require(args.keys.all { it in setOf("selection", "fields", "limit", "surface") }) {
        "Unknown world query argument"
    }
    val selection = args.getValue("selection").jsonObject
    require(
        selection.keys.all {
            it in setOf("kind", "position", "area", "radius", "unit_number", "name", "type")
        }
    ) {
        "Unknown selection field"
    }
    val kind = selection.getValue("kind").stringArgument()
    require(kind in setOf("entities", "tiles", "player", "force") + localEntityKinds) {
        "Unsupported world selection kind"
    }
    val spatial = listOf("position", "area").count { it in selection }
    if (kind in setOf("player", "force") + localEntityKinds) {
        require(selection.keys == setOf("kind") && "surface" !in args) {
            "Local selections take only kind and do not accept surface"
        }
    } else if (kind == "entities") {
        require(spatial <= 1 && (spatial == 1 || "unit_number" in selection)) {
            "Select position or area, optionally filtered by unit_number; or use a directly indexed unit_number"
        }
    } else {
        require(spatial == 1) { "Specify exactly one of position or area" }
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
    for (field in listOf("name", "type")) selection[field]?.let {
        val values = if (it is JsonArray) it else JsonArray(listOf(it))
        require(values.size in 1..64) { "$field requires 1..64 names" }
        values.forEach { value ->
            require(
                value.stringArgument().let { text ->
                    text.isNotEmpty() && text.length <= 256 && '\u0000' !in text
                }
            ) {
                "Invalid $field"
            }
        }
    }
    args["surface"]?.let {
        val value = it.jsonPrimitive
        require(
            if (value.isString)
                value.content.isNotBlank() &&
                        value.content.length <= 256 &&
                        '\u0000' !in value.content
            else value.intOrNull?.let { n -> n > 0 } == true
        ) {
            "surface must be a name or positive index"
        }
    }
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
                    add("force")
                    localEntityKinds.forEach { add(it) }
                    relatedWorldKinds.forEach { add(it) }
                }
            }
            put("position", worldPositionSchema())
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
                put(
                    "description",
                    if (name == "name") "entities only: exact internal prototype name(s)."
                    else
                        "entities: exact native entity type(s). prototypes: one catalog type listed in selection's description.",
                )
                putJsonArray("oneOf") {
                    addJsonObject { put("type", "string") }
                    addJsonObject {
                        put("type", "array")
                        put("minItems", 1)
                        put("maxItems", 64)
                        putJsonObject("items") { put("type", "string") }
                    }
                }
            }
            relatedSelectionProperties().forEach { (name, value) -> put(name, value) }
        }
        put(
            "description",
            "entities: position/area uses collision geometry; position+radius uses entity centers; unit_number uses the current world's index. tiles: exactly one position or area. Coordinates are world tile units; areas must have positive dimensions up to 128x128. player|character|vehicle|physical_vehicle|force: only kind, no coordinates; absent entity references return availability:nil. inventories discovers an owner's inventory names; inventory reads slots. quickbar reads explicit slots/screen_pages. prototypes requires one type:item|recipe|entity|fluid|technology|quality|item_group|item_subgroup. recipes/technologies read current-force objects. Catalogs accept names/search and recipe relation filters where applicable.",
        )
        putJsonArray("required") { add("kind") }
    }
    putJsonObject("fields") {
        put("type", "array")
        put("minItems", 1)
        put("maxItems", 32)
        put("uniqueItems", true)
        putJsonObject("items") { put("type", "string") }
        put(
            "description",
            "Select a small subset; omission uses kind-specific defaults. quickbar and inventories do not accept fields. Entities and local entity references: ${entityFields.joinToString()}. Tiles: ${tileFields.joinToString()}. Player: ${playerFields.joinToString()}. Force: ${forceFields.joinToString()}. ${relatedFieldsDescription()}",
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
            "Spatial/local default 128, max 512. Inventory/catalog queries default 64; inventory slots max 512, other related queries max 128. quickbar does not accept limit.",
        )
    }
    putJsonObject("offset") {
        put("type", "integer")
        put("minimum", 0)
        put("maximum", 65536)
        put("default", 0)
        put(
            "description",
            "Inventory/catalog queries only (not quickbar): zero-based page offset. Inventory slot indices remain one-based. Each page is a fresh observation.",
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
            val objectValue = value.jsonObject
            val attributes = objectValue["attributes"]?.jsonObject
            if ("entity_groups" in objectValue)
                JsonObject(
                    objectValue + ("entity_groups" to array(objectValue.getValue("entity_groups")))
                )
            else if (attributes == null) objectValue
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
                result["active_pages"]?.let { mapOf("active_pages" to array(it)) }.orEmpty()
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
