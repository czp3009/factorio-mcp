package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal val relatedWorldKinds =
    setOf("inventories", "inventory", "prototypes", "recipes", "technologies", "quickbar")

internal fun parseRelatedWorldQuery(args: JsonObject): WorldQuery {
    require(args.keys.all { it in setOf("selection", "fields", "limit", "offset", "surface", "depth", "reads") }) {
        "Unknown world query argument"
    }
    val selection = args.getValue("selection").jsonObject
    val kind = selection.getValue("kind").stringArgument()
    if (kind == "quickbar") return parseQuickbarQuery(args, selection)
    val inventory = kind == "inventory" || kind == "inventories"
    val allowedSelection =
        if (inventory) setOf("kind", "owner", "inventory")
        else setOf("kind", "type", "names", "search", "product", "ingredient", "force")
    require(selection.keys.all { it in allowedSelection }) { "Unknown $kind selection field" }
    val limit = args["limit"]?.intArgument() ?: if (inventory || "names" in selection) null else 64
    require(limit == null || limit > 0) { "limit must be positive" }
    val offset = args["offset"]?.intArgument() ?: 0
    require(offset >= 0) { "offset must be nonnegative" }
    var defaults: List<String>
    if (inventory) {
        val owner = selection["owner"]?.jsonObject ?: buildJsonObject { put("kind", "player") }
        require(owner["kind"]?.stringArgument() in setOf("player", "entities") + localEntityKinds) {
            "Inventory owner must select a player, a player entity reference or exactly one spatial entity"
        }
        // Reuse the spatial contract without allowing recursive related-object selections.
        parseWorldQuery(
            buildJsonObject {
                put("selection", owner)
                args["surface"]?.let { put("surface", it) }
            }
        )
        if (kind == "inventories") {
            require("inventory" !in selection && "fields" !in args && "depth" !in args) {
                "Inventory discovery does not accept inventory, fields or depth"
            }
            defaults = emptyList()
        } else {
            val name = selection["inventory"]?.stringArgument() ?: "main"
            require(
                name.isNotEmpty() && '\u0000' !in name
            ) {
                "Use main or an inventory name returned by discovery"
            }
            defaults = listOf("name", "count", "quality", "health", "spoil_percent")
        }
    } else {
        require("surface" !in args) { "Catalog queries do not select a surface" }
        if (kind == "recipes" || kind == "technologies") {
            require("type" !in selection) { "$kind selects the local force's live objects" }
            selection["force"]?.let(::validateSurfaceSelector)
            defaults =
                if (kind == "recipes")
                    listOf(
                        "name",
                        "enabled",
                        "hidden",
                        "categories",
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
            require(type.matches(nativeMemberPattern)) { "Invalid prototype catalog name" }
            require("force" !in selection) { "force requires recipes or technologies" }
            defaults =
                when (type) {
                    "recipe" ->
                        listOf("name", "categories", "energy", "ingredients", "products", "enabled")

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
                    "quality" -> listOf("name", "level", "next", "next_probability")
                    else -> listOf("name", "type", "localised_name")
                }
        }
        selection["names"]?.let {
            val names = it.jsonArray.map { name -> name.stringArgument() }
            require(
                names.isNotEmpty() &&
                        names.distinct().size == names.size &&
                        names.all { name -> name.isNotEmpty() && '\u0000' !in name }
            ) {
                "names requires distinct nonempty prototype names"
            }
        }
        selection["search"]?.let {
            val search = it.stringArgument()
            require(search.isNotEmpty() && '\u0000' !in search) {
                "search requires a nonempty string without NUL"
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
            require(name.isNotEmpty() && '\u0000' !in name) { "Invalid $field name" }
        }
    }
    val fields = args["fields"]?.let(::nativeFields) ?: defaults
    return WorldQuery(
        buildJsonObject {
            args.forEach { (key, value) -> put(key, value) }
            limit?.let { put("limit", it) }
            put("offset", offset)
            if (kind != "inventories") put("depth", observationDepth(args["depth"]?.intArgument()))
            args["reads"]?.let { put("reads", observationReads(it)) }
            putJsonArray("fields") { fields.forEach { add(it) } }
            put("fields_explicit", "fields" in args)
        }
    )
}

internal fun quickbarSelectionProperties() = buildJsonObject {
    putJsonObject("slots") {
        put("type", "array")
        put("minItems", 1)
        put("uniqueItems", true)
        putJsonObject("items") {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") {
                add("page_index")
                add("slot_index")
            }
            putJsonObject("properties") {
                for (name in listOf("page_index", "slot_index")) putJsonObject(name) {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 256)
                }
            }
        }
        put(
            "description",
            "quickbar only. One-based page/slot locations returning raw slot contents. Invalid game locations and empty slots remain per-entry error/nil results. Provide slots or screen_pages; fields/limit/offset/surface are not accepted.",
        )
    }
    putJsonObject("screen_pages") {
        put("type", "array")
        put("minItems", 1)
        put("uniqueItems", true)
        putJsonObject("items") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", 4294967295L)
        }
        put(
            "description",
            "quickbar only. One-based screen-row indices returning active page numbers. Per-index nil/error results remain explicit.",
        )
    }
}

private fun parseQuickbarQuery(args: JsonObject, selection: JsonObject): WorldQuery {
    require(args.keys.all { it in setOf("selection", "depth") }) { "quickbar accepts selection and optional depth" }
    require(selection.keys.all { it in setOf("kind", "slots", "screen_pages") }) {
        "Unknown quickbar field"
    }
    require("slots" in selection || "screen_pages" in selection) {
        "Specify quickbar slots or screen_pages"
    }
    selection["slots"]?.let { value ->
        val locations = value.jsonArray.map { item ->
            val location = item.jsonObject
            require(location.keys == setOf("page_index", "slot_index")) {
                "Each quickbar slot requires page_index and slot_index"
            }
            val page = location.getValue("page_index").intArgument()
            val slot = location.getValue("slot_index").intArgument()
            require(page in 1..256 && slot in 1..256) { "Quickbar page/slot indices must be in 1..256" }
            page to slot
        }
        require(locations.isNotEmpty() && locations.distinct().size == locations.size) {
            "slots requires distinct page/slot locations"
        }
    }
    selection["screen_pages"]?.let { value ->
        val indices = value.jsonArray.map { index ->
            val number = index.jsonPrimitive
            require(!number.isString) { "screen_pages requires unsigned API indices" }
            requireNotNull(number.longOrNull) { "screen_pages requires integer indices" }
        }
        require(
            indices.isNotEmpty() &&
                    indices.distinct().size == indices.size &&
                    indices.all { it in 1..4294967295L }
        ) {
            "screen_pages requires distinct unsigned 32-bit indices"
        }
    }
    return WorldQuery(
        buildJsonObject {
            put("selection", selection)
            put("depth", observationDepth(args["depth"]?.intArgument()))
            putJsonArray("fields") {}
        }
    )
}
