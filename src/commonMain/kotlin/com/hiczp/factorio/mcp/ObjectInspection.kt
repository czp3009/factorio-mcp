package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

/**
 * Explicit passive methods. Metadata supplies availability/signatures, never mutation authority.
 * Force statistics getters and LuaFlowStatistics count attributes/methods can allocate serialized state and are excluded.
 */
private val baseInspectionMethods =
    setOf(
        "get_recipe",
        "get_blueprint_entity_count",
        "is_blueprint_setup",
        "get_control_behavior",
        "get_inventory",
        "get_main_inventory",
        "get_output_inventory",
        "get_module_inventory",
        "get_fuel_inventory",
        "get_burnt_result_inventory",
        "get_filter",
        "get_bar",
        "get_fluid",
        "get_fluid_contents",
        "get_fluid_count",
        "get_fluid_capacity",
        "get_fluid_filter",
        "get_fluid_box_neighbours",
        "get_fluid_box_pipe_connections",
        "has_fluid_segment",
        "get_fluid_segment_capacity",
        "get_fluid_segment_filter",
        "get_fluid_segment_fluid",
        "get_fluid_segment_id",
        "get_fluid_segment_extent_bounding_box",
        "get_driver",
        "get_passenger",
        "get_transport_line",
        "get_max_transport_line_index",
        "get_contents",
        "get_item_count",
        "get_schedule",
        "get_logistic_point",
        "get_logistic_sections",
        "get_circuit_network",
        "get_signals",
        "get_signal",
        "get_electric_input_flow_limit",
        "get_electric_output_flow_limit",
        "is_connected_to_electric_network",
        "get_resource_counts",
        "get_pollution",
        "get_total_pollution",
        "get_infinity_container_filter",
        "get_infinity_pipe_filter",
        "get_active_quick_bar_page",
        "get_quick_bar_slot",
        "get_market_items",
        "get_section",
        "get_slot",
    )

private val interfaceInspectionMethods = mapOf(
    "LuaEntityPrototype" to setOf(
        "has_flag", "get_attraction_range_elongation", "get_crafting_speed", "get_duration",
        "get_energy_distribution_efficiency", "get_fluid_usage_per_tick", "get_inserter_extension_speed",
        "get_inserter_rotation_speed", "get_inventory_size", "get_inventory_size_bonus",
        "get_max_circuit_wire_distance", "get_max_distance_of_nearby_sector_revealed",
        "get_max_distance_of_sector_revealed", "get_max_energy", "get_max_energy_production",
        "get_max_energy_usage", "get_max_health", "get_max_power_output", "get_max_speed",
        "get_max_wire_distance", "get_mining_drill_radius", "get_pumping_speed", "get_researching_speed",
        "get_supply_area_distance", "get_valve_flow_rate",
    ),
    "LuaItemPrototype" to setOf(
        "get_ammo_type", "get_cursor_box_type", "get_durability", "get_entity_filter_mode",
        "get_entity_filters", "get_entity_type_filters", "get_inventory_size", "get_inventory_size_bonus",
        "get_module_effects", "get_selection_border_color", "get_selection_mode_flags", "get_spoil_ticks",
        "get_tile_filter_mode", "get_tile_filters",
    ),
    "LuaRecipePrototype" to setOf("get_ingredient_quality", "get_product_amount", "get_product_quality"),
    "LuaSchedule" to setOf(
        "get_inside_interrupt", "get_interrupt", "get_interrupts", "get_record", "get_record_count",
        "get_records", "get_wait_condition", "get_wait_condition_count", "get_wait_conditions",
    ),
    "LuaTrainManager" to setOf("get_train_by_id", "get_train_stops", "get_trains"),
    "LuaSurface" to setOf(
        "get_chunks", "get_property", "get_tile", "get_hidden_tile", "get_double_hidden_tile",
        "is_chunk_generated", "count_entities_filtered", "count_tiles_filtered",
        "find_logistic_network_by_position", "find_closest_logistic_network_by_position",
        "find_logistic_networks_by_construction_area",
    ),
    "LuaItemCommon" to setOf(
        "get_blueprint_entities", "get_blueprint_tiles", "get_blueprint_entity_tag", "get_blueprint_entity_tags",
        "get_entity_filter", "get_mapper", "get_tag", "get_tag_names", "get_tile_filter",
    ),
    "LuaForce" to setOf(
        "is_quality_unlocked",
        "is_space_location_unlocked",
        "is_space_platforms_unlocked",
        "get_friend",
        "get_cease_fire",
        "get_entity_count",
        "get_spawn_position",
        "get_surface_hidden",
        "get_script_visible",
        "get_evolution_factor",
        "get_logistic_group",
        "get_logistic_groups",
        "get_space_platforms",
    ),
    "LuaControl" to setOf("get_inventory_name", "get_max_inventory_index", "get_requester_point"),
    "LuaTransportLine" to setOf("get_detailed_contents", "get_item_position", "get_line_item_position"),
)

internal val inspectionMethods = baseInspectionMethods + interfaceInspectionMethods.values.flatten()

// Reading empty statistics through the Lua wrapper can allocate serialized counters.
internal val allocatingStatisticsAttributes = setOf(
    "input_counts", "output_counts", "storage_counts",
    "input_quality_counts", "output_quality_counts", "storage_quality_counts",
)

internal fun admittedInspectionAttribute(lineage: List<String>, attribute: String): Boolean =
    "LuaFlowStatistics" !in lineage || attribute !in allocatingStatisticsAttributes

internal fun admittedInspectionMethod(lineage: List<String>, method: String): Boolean =
    method in baseInspectionMethods || lineage.any { method in interfaceInspectionMethods[it].orEmpty() }

internal val nativeMemberPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")

internal fun nativeFields(value: JsonElement): List<String> = value.jsonArray.map { it.stringArgument() }.also {
    require(it.distinct().size == it.size && it.all { field -> field.matches(nativeMemberPattern) }) {
        "fields must contain distinct native attribute names"
    }
}

internal fun parseObjectInspection(args: JsonObject): WorldQuery {
    require(
        args.keys.all {
            it in setOf(
                "selection",
                "fields",
                "mode",
                "offset",
                "limit",
                "depth",
                "surface",
                "search",
                "reads"
            )
        }
    ) {
        "Unknown object inspection argument"
    }
    val selection = args.getValue("selection").jsonObject
    require(selection.keys.all { it in setOf("kind", "target", "path") }) {
        "Inspection selection accepts kind, target and path"
    }
    val target = selection.getValue("target").jsonObject
    args["surface"]?.let(::validateSurfaceSelector)
    val kind = target.getValue("kind").stringArgument()
    require(
        kind in
                setOf(
                    "game",
                    "prototypes",
                    "defines",
                    "entities",
                    "player",
                    "force",
                    "surface",
                    "planet",
                    "prototype",
                    "recipe",
                    "technology",
                ) + localEntityKinds
    ) {
        "Unsupported inspection target"
    }
    if (kind in setOf("game", "prototypes", "defines")) {
        require(target.keys == setOf("kind") && "surface" !in args) {
            "Global targets accept only kind"
        }
    } else if (kind in setOf("surface", "planet", "prototype", "recipe", "technology")) {
        require(target.keys.all { it in setOf("kind", "name", "type", "player", "force") }) {
            "Unknown inspection target member"
        }
        if (kind != "surface")
            require(target["name"]?.stringArgument()?.isNotEmpty() == true) {
                "Target name is required"
            }
        target["name"]?.let {
            val name = it.stringArgument()
            require(name.isNotEmpty() && '\u0000' !in name)
        }
        require(kind == "prototype" || "type" !in target) { "type is only valid for prototype" }
        if (kind == "prototype")
            require(target["type"]?.stringArgument()?.matches(nativeMemberPattern) == true) {
                "Unknown prototype catalog"
            }
        require(kind in setOf("recipe", "technology") || "player" !in target) {
            "player selects a recipe/technology force"
        }
        target["player"]?.let(::validatePlayerSelector)
        require(kind in setOf("recipe", "technology") || "force" !in target) { "force requires recipe or technology" }
        require(target.keys.count { it in setOf("force", "player") } <= 1) { "Select force or player, not both" }
        target["force"]?.let(::validateSurfaceSelector)
        require(kind == "surface" || "surface" !in args) {
            "surface is only valid for surface/spatial targets"
        }
    } else
        parseWorldQuery(
            buildJsonObject {
                put("selection", target)
                args["surface"]?.let { put("surface", it) }
            }
        )
    val options = JsonObject(args.filterKeys { it !in setOf("selection", "surface", "reads") } +
            ("path" to (selection["path"] ?: JsonArray(emptyList()))))
    val read = observationRead(options)
    require(read["mode"]?.jsonPrimitive?.content in setOf("values", "entries") || "reads" !in args) {
        "reads requires values or entries"
    }
    return WorldQuery(buildJsonObject {
        put("selection", target)
        args["surface"]?.let { put("surface", it) }
        read["limit"]?.let { put("limit", it) }
        put("fields", JsonArray(emptyList()))
        put("inspection", read)
        args["reads"]?.let { put("reads", observationReads(it)) }
    })
}
