package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal val relatedWorldKinds =
    setOf("inventories", "inventory", "prototypes", "recipes", "technologies", "quickbar")

private val prototypeBaseFields =
    listOf(
        "name",
        "type",
        "order",
        "hidden",
        "localised_name",
        "localised_description",
        "group",
        "subgroup",
        "hidden_in_factoriopedia",
        "factoriopedia_description",
        "factoriopedia_alternative",
    )

internal val prototypeFields =
    mapOf(
        "item" to
                prototypeBaseFields +
                listOf("stack_size", "place_result", "fuel_value", "fuel_category", "spoil_result"),
        "recipe" to
                prototypeBaseFields +
                listOf(
                    "enabled",
                    "category",
                    "additional_categories",
                    "energy",
                    "ingredients",
                    "products",
                    "main_product",
                    "surface_conditions",
                    "allow_decomposition",
                ),
        "entity" to
                prototypeBaseFields +
                listOf(
                    "items_to_place_this",
                    "selection_box",
                    "collision_box",
                    "tile_width",
                    "tile_height",
                    "crafting_categories",
                    "resource_categories",
                    "mining_speed",
                    "mineable_properties",
                    "fixed_recipe",
                ),
        "fluid" to
                prototypeBaseFields +
                listOf("default_temperature", "max_temperature", "fuel_value", "heat_capacity"),
        "technology" to
                prototypeBaseFields +
                listOf(
                    "enabled",
                    "level",
                    "max_level",
                    "prerequisites",
                    "effects",
                    "research_unit_count",
                    "research_unit_count_formula",
                    "research_unit_energy",
                    "research_unit_ingredients",
                    "research_trigger",
                ),
        "quality" to
                (prototypeBaseFields - "factoriopedia_alternative") +
                listOf("level", "next", "next_probability", "default_multiplier"),
        "item_group" to listOf("name", "type", "order", "localised_name", "subgroups"),
        "item_subgroup" to
                listOf("name", "type", "order", "order_in_recipe", "localised_name", "group"),
    )

private val recipeFields =
    listOf(
        "name",
        "enabled",
        "hidden",
        "category",
        "additional_categories",
        "energy",
        "ingredients",
        "products",
        "force",
        "prototype",
        "productivity_bonus",
        "localised_name",
        "localised_description",
        "group",
        "subgroup",
        "order",
    )
private val stackFields =
    listOf(
        "name",
        "type",
        "count",
        "quality",
        "health",
        "spoil_percent",
        "spoil_tick",
        "prototype",
        "is_module",
        "ammo",
        "durability",
    )

private val technologyFields =
    listOf(
        "name",
        "enabled",
        "researched",
        "level",
        "saved_progress",
        "prerequisites",
        "successors",
        "research_unit_count",
        "research_unit_count_formula",
        "research_unit_energy",
        "research_unit_ingredients",
        "force",
        "prototype",
        "upgrade",
        "visible_when_disabled",
        "localised_name",
        "localised_description",
        "order",
    )

internal fun parseRelatedWorldQuery(args: JsonObject): WorldQuery {
    require(args.keys.all { it in setOf("selection", "fields", "limit", "offset", "surface") }) {
        "Unknown world query argument"
    }
    val selection = args.getValue("selection").jsonObject
    val kind = selection.getValue("kind").stringArgument()
    if (kind == "quickbar") return parseQuickbarQuery(args, selection)
    val inventory = kind == "inventory" || kind == "inventories"
    val allowedSelection =
        if (inventory) setOf("kind", "owner", "inventory")
        else setOf("kind", "type", "names", "search", "product", "ingredient")
    require(selection.keys.all { it in allowedSelection }) { "Unknown $kind selection field" }
    val limit = args["limit"]?.intArgument() ?: 64
    require(limit in 1..if (kind == "inventory") 512 else 128) { "Unsupported related query limit" }
    val offset = args["offset"]?.intArgument() ?: 0
    require(offset in 0..65536) { "offset must be in 0..65536" }
    var defaults: List<String>
    val allowed: List<String>
    if (inventory) {
        val owner = selection["owner"]?.jsonObject ?: buildJsonObject { put("kind", "player") }
        require(owner["kind"]?.stringArgument() in setOf("player", "entities") + localEntityKinds) {
            "Inventory owner must select the local player, a local entity reference or exactly one spatial entity"
        }
        // Reuse the spatial contract without allowing recursive related-object selections.
        parseWorldQuery(
            buildJsonObject {
                put("selection", owner)
                args["surface"]?.let { put("surface", it) }
            }
        )
        if (kind == "inventories") {
            require("inventory" !in selection && "fields" !in args) {
                "Inventory discovery does not accept inventory or fields"
            }
            defaults = emptyList()
        } else {
            val name = selection["inventory"]?.stringArgument() ?: "main"
            require(
                name.length in 1..128 && name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
            ) {
                "Use main or an inventory name returned by discovery"
            }
            defaults = listOf("name", "count", "quality", "health", "spoil_percent")
        }
        allowed = stackFields
    } else {
        require("surface" !in args) { "Catalog queries do not select a surface" }
        if (kind == "recipes" || kind == "technologies") {
            require("type" !in selection) { "$kind selects the local force's live objects" }
            allowed = if (kind == "recipes") recipeFields else technologyFields
            defaults =
                if (kind == "recipes")
                    listOf(
                        "name",
                        "enabled",
                        "hidden",
                        "category",
                        "energy",
                        "ingredients",
                        "products",
                    )
                else
                    listOf(
                        "name",
                        "enabled",
                        "researched",
                        "level",
                        "prerequisites",
                        "saved_progress",
                    )
        } else {
            val type = selection.getValue("type").stringArgument()
            allowed = requireNotNull(prototypeFields[type]) { "Unsupported prototype type: $type" }
            defaults =
                when (type) {
                    "recipe" ->
                        listOf("name", "category", "energy", "ingredients", "products", "enabled")

                    "item" -> listOf("name", "type", "stack_size", "place_result")
                    "entity" -> listOf("name", "type", "items_to_place_this", "selection_box")
                    "fluid" -> listOf("name", "default_temperature", "max_temperature")
                    "technology" ->
                        listOf(
                            "name",
                            "prerequisites",
                            "effects",
                            "research_unit_ingredients",
                            "research_trigger",
                        )

                    "item_group" -> listOf("name", "type", "order", "localised_name", "subgroups")
                    "item_subgroup" -> listOf("name", "type", "order", "localised_name", "group")
                    else -> listOf("name", "level", "next", "next_probability")
                }
        }
        selection["names"]?.let {
            val names = it.jsonArray.map { name -> name.stringArgument() }
            require(
                names.size in 1..64 &&
                        names.distinct().size == names.size &&
                        names.all { name -> name.length in 1..256 && '\u0000' !in name }
            ) {
                "names requires 1..64 distinct nonempty prototype names"
            }
        }
        selection["search"]?.let {
            val search = it.stringArgument()
            require(search.length in 1..128 && '\u0000' !in search) {
                "search requires 1..128 characters"
            }
        }
        for (field in listOf("product", "ingredient")) selection[field]?.let { value ->
            require(
                kind == "recipes" ||
                        (kind == "prototypes" && selection["type"]?.stringArgument() == "recipe")
            ) {
                "$field is only valid for recipes or recipe prototypes"
            }
            val filter = value.jsonObject
            require(
                filter.keys == setOf("type", "name") &&
                        filter["type"]?.stringArgument() in setOf("item", "fluid")
            ) {
                "$field requires type:item|fluid and name"
            }
            val name = filter.getValue("name").stringArgument()
            require(name.length in 1..256 && '\u0000' !in name) { "Invalid $field name" }
        }
    }
    val fields = args["fields"]?.jsonArray?.map { it.stringArgument() } ?: defaults
    if (kind != "inventories")
        require(
            fields.size in 1..32 &&
                    fields.distinct().size == fields.size &&
                    fields.all { it in allowed }
        ) {
            "Unsupported $kind fields; choose from ${allowed.joinToString()}"
        }
    return WorldQuery(
        buildJsonObject {
            args.forEach { (key, value) -> put(key, value) }
            put("limit", limit)
            put("offset", offset)
            putJsonArray("fields") { fields.forEach { add(it) } }
        }
    )
}

internal fun relatedSelectionProperties() = buildJsonObject {
    putJsonObject("owner") {
        put("type", "object")
        put(
            "description",
            "For inventory/inventories only. Defaults to {kind:'player'} (current controller). Use {kind:'character'} for the attached character, including in remote view; vehicle/physical_vehicle follow the corresponding native player references. Missing local entities are errors. Otherwise use an entities selection by unit_number or bounded position/area and filters; it must resolve exactly one entity.",
        )
    }
    putJsonObject("inventory") {
        put("type", "string")
        put(
            "description",
            "For inventory only. Defaults to main (character/player main inventory); otherwise use a name returned by inventories. Rejects names belonging to a different owner inventory.",
        )
    }
    putJsonObject("names") {
        put("type", "array")
        put("minItems", 1)
        put("maxItems", 64)
        put("uniqueItems", true)
        putJsonObject("items") { put("type", "string") }
        put(
            "description",
            "Exact names for prototypes or force recipes/technologies. Missing names are reported separately.",
        )
    }
    putJsonObject("search") {
        put("type", "string")
        put("minLength", 1)
        put("maxLength", 128)
        put(
            "description",
            "Case-insensitive substring of internal names for catalogs; may combine with names.",
        )
    }
    for (name in listOf("slots", "screen_pages")) putJsonObject(name) {
        put("type", "array")
        put("minItems", 1)
        put("maxItems", if (name == "slots") 128 else 16)
        put("uniqueItems", true)
        putJsonObject("items") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", 65536)
        }
        put(
            "description",
            "quickbar only. Explicit one-based API indices. Slots return filters, not inventory counts; screen_pages return active quickbar page numbers. Per-index nil/error results remain explicit. Provide at least one index list; fields/limit/offset/surface are not accepted.",
        )
    }
    for (name in listOf("product", "ingredient")) putJsonObject(name) {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") {
            add("type")
            add("name")
        }
        putJsonObject("properties") {
            putJsonObject("type") {
                put("type", "string")
                putJsonArray("enum") {
                    add("item")
                    add("fluid")
                }
            }
            putJsonObject("name") {
                put("type", "string")
                put("minLength", 1)
                put("maxLength", 256)
            }
        }
        put(
            "description",
            "Recipes or recipe prototypes only: match at least one raw $name entry by exact type/name. Combines with other filters. No assumption that recipe and product names match; no craftability inference. Relation scanning is bounded to 32768 entries; narrow with names/search if exceeded.",
        )
    }
}

internal fun relatedFieldsDescription(): String =
    "Inventory stack fields: ${stackFields.joinToString()}. Force recipes: ${recipeFields.joinToString()}. Force technologies: ${technologyFields.joinToString()}. Prototype fields: " +
            prototypeFields.entries.joinToString("; ") { (kind, fields) ->
                "$kind: ${fields.joinToString()}"
            }

private fun parseQuickbarQuery(args: JsonObject, selection: JsonObject): WorldQuery {
    require(args.keys == setOf("selection")) { "quickbar accepts only selection" }
    require(selection.keys.all { it in setOf("kind", "slots", "screen_pages") }) {
        "Unknown quickbar field"
    }
    require("slots" in selection || "screen_pages" in selection) {
        "Specify quickbar slots or screen_pages"
    }
    for ((field, maximum) in listOf("slots" to 128, "screen_pages" to 16)) selection[field]?.let { value ->
        val indices = value.jsonArray.map { it.intArgument() }
        require(
            indices.size in 1..maximum &&
                    indices.distinct().size == indices.size &&
                    indices.all { it in 1..65536 }
        ) {
            "$field requires 1..$maximum distinct indices in 1..65536"
        }
    }
    return WorldQuery(
        buildJsonObject {
            put("selection", selection)
            put("limit", selection["slots"]?.jsonArray?.size ?: 0)
            putJsonArray("fields") {}
        }
    )
}
