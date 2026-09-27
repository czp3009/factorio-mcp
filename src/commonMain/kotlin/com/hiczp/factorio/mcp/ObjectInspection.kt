package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

/**
 * Explicit passive methods. Metadata supplies availability/signatures, never mutation authority.
 */
internal val inspectionMethods =
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
        "get_capacity",
        "get_connections",
        "get_pipe_connections",
        "get_fluid_segment_contents",
        "get_locked_fluid",
        "get_prototype",
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
        "get_input_count",
        "get_output_count",
        "get_storage_count",
        "get_flow_count",
        "get_resource_counts",
        "get_item_production_statistics",
        "get_fluid_production_statistics",
        "get_entity_build_count_statistics",
        "get_kill_count_statistics",
        "get_pollution",
        "get_total_pollution",
        "get_infinity_container_filter",
        "get_infinity_pipe_filter",
        "get_active_quick_bar_page",
        "get_quick_bar_slot",
        "get_market_items",
        "get_section",
        "get_slot",
        "get_vehicle_logistic_point",
        "get_personal_logistic_slot",
    )

internal class RuntimeApi(document: JsonObject) {
    private val classes =
        document.getValue("classes").jsonArray.associate {
            val value = it.jsonObject
            value.getValue("name").stringArgument() to value
        }

    init {
        require(classes.size in 1..1024) { "Runtime API class count exceeds bound" }
        require(document["application"]?.stringArgument() == "factorio") {
            "Not a Factorio runtime API"
        }
    }

    private fun members(
        name: String,
        kind: String,
        stack: Set<String> = emptySet(),
    ): List<JsonObject> {
        require(name !in stack && stack.size < 32) { "Invalid runtime API inheritance" }
        val value = classes[name] ?: error("Missing API class $name")
        val inherited =
            value["parent"]?.stringArgument()?.let { members(it, kind, stack + name) }.orEmpty()
        val own = value[kind]?.jsonArray?.map { it.jsonObject }.orEmpty()
        require(own.size <= 2048) { "Runtime API member count exceeds bound" }
        return (inherited + own)
            .associateBy { it.getValue("name").stringArgument() }
            .values
            .toList()
    }

    /**
     * Only member identities enter the fixed reader. Descriptions remain outside the game process.
     */
    val catalog: JsonObject by lazy {
        buildJsonObject {
            classes.keys.sorted().forEach { name ->
                putJsonObject(name) {
                    val operators = members(name, "operators")
                    put(
                        "indexed",
                        operators.any {
                            it["name"]?.jsonPrimitive?.content == "index" && "read_type" in it
                        },
                    )
                    put(
                        "length",
                        operators.any {
                            it["name"]?.jsonPrimitive?.content == "length" && "read_type" in it
                        },
                    )
                    putJsonArray("attributes") {
                        members(name, "attributes")
                            .filter { "read_type" in it }
                            .map { it.getValue("name").stringArgument() }
                            .sorted()
                            .forEach {
                                require(it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                                    "Invalid API attribute name"
                                }
                                add(it)
                            }
                    }
                    putJsonArray("methods") {
                        members(name, "methods")
                            .map { it.getValue("name").stringArgument() }
                            .filter { it in inspectionMethods }
                            .sorted()
                            .forEach { add(it) }
                    }
                }
            }
        }
    }

    fun memberInfo(className: String, name: String, method: Boolean): JsonObject? {
        if (className !in classes) return null
        val member =
            members(className, if (method) "methods" else "attributes").find {
                it["name"]?.jsonPrimitive?.content == name
            } ?: return null
        return JsonObject(
            member.filterKeys {
                it in
                        setOf(
                            "name",
                            "description",
                            "read_type",
                            "optional",
                            "subclasses",
                            "parameters",
                            "format",
                            "return_values",
                        )
            }
        )
    }

    fun prepare(query: WorldQuery): WorldQuery =
        if ("inspection" !in query.arguments) query
        else query.copy(arguments = JsonObject(query.arguments + ("api" to catalog)))

    fun annotate(result: JsonObject): JsonObject {
        if (result["observation"]?.jsonPrimitive?.content != "object_inspection") return result
        val objects =
            result.getValue("objects").jsonArray.map { value ->
                val item = value.jsonObject
                val className = item["object_name"]?.jsonPrimitive?.content ?: return@map value
                val members = item["members"]?.jsonArray ?: return@map value
                JsonObject(
                    item +
                            ("members" to
                                    JsonArray(
                                        members.map { raw ->
                                            val entry = raw.jsonObject
                                            val info =
                                                memberInfo(
                                                    className,
                                                    entry.getValue("name").stringArgument(),
                                                    entry["kind"]?.jsonPrimitive?.content == "method",
                                                )
                                            if (info == null) entry else JsonObject(entry + ("api" to info))
                                        }
                                    ))
                )
            }
        return JsonObject(result + ("objects" to JsonArray(objects)))
    }
}

internal fun parseObjectInspection(args: JsonObject): WorldQuery {
    require(
        args.keys.all { it in setOf("selection", "fields", "mode", "offset", "limit", "surface") }
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
    if (kind == "game") {
        require(target.keys == setOf("kind") && "surface" !in args) {
            "game target accepts only kind"
        }
    } else if (kind in setOf("surface", "planet", "prototype", "recipe", "technology")) {
        require(target.keys.all { it in setOf("kind", "name", "type", "player") }) {
            "Unknown inspection target member"
        }
        if (kind != "surface")
            require(target["name"]?.stringArgument()?.isNotBlank() == true) {
                "Target name is required"
            }
        target["name"]?.let {
            val name = it.stringArgument()
            require(name.isNotBlank() && name.length <= 256 && '\u0000' !in name)
        }
        require(kind == "prototype" || "type" !in target) { "type is only valid for prototype" }
        if (kind == "prototype")
            require(target["type"]?.stringArgument() in prototypeFields) {
                "Unknown prototype catalog"
            }
        require(kind in setOf("recipe", "technology") || "player" !in target) {
            "player selects a recipe/technology force"
        }
        target["player"]?.let(::validatePlayerSelector)
        require(kind == "surface" || "surface" !in args) {
            "surface is only valid for surface/spatial targets"
        }
    } else
        parseWorldQuery(
            buildJsonObject {
                put("selection", target)
                args["surface"]?.let { put("surface", it) }
                put("limit", 2)
            }
        )
    val path = selection["path"]?.jsonArray ?: JsonArray(emptyList())
    require(path.size <= 12) { "Inspection path exceeds 12 steps" }
    path.forEach { value ->
        val step = value.jsonObject
        require(step.keys.count { it in setOf("property", "index", "method") } == 1) {
            "Path step requires exactly one property, index or method"
        }
        when {
            "property" in step -> {
                require(step.keys == setOf("property"))
                require(
                    step.getValue("property").stringArgument().let {
                        it.length <= 128 && it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))
                    }
                )
            }

            "index" in step -> {
                require(step.keys == setOf("index"))
                val index = step.getValue("index").jsonPrimitive
                require(
                    if (index.isString) index.content.length in 1..256 && '\u0000' !in index.content
                    else index.intOrNull?.let { it in 1..65536 } == true
                )
            }

            else -> {
                require(step.keys.all { it in setOf("method", "arguments", "result") })
                require(step.getValue("method").stringArgument() in inspectionMethods) {
                    "Method is not an admitted passive query"
                }
                val parameters = step["arguments"]?.jsonArray ?: JsonArray(emptyList())
                require(
                    parameters.size <= 8 && parameters.toString().encodeToByteArray().size <= 4096
                ) {
                    "Query method arguments exceed bound"
                }
                fun validate(value: JsonElement, depth: Int) {
                    require(depth <= 4) { "Query argument nesting exceeds bound" }
                    when (value) {
                        is JsonObject ->
                            value.forEach { (key, item) ->
                                require(key.length <= 128 && '\u0000' !in key)
                                validate(item, depth + 1)
                            }

                        is JsonArray -> {
                            require(value.size <= 64)
                            value.forEach { validate(it, depth + 1) }
                        }

                        is JsonPrimitive -> {
                            require(value.content.length <= 512 && '\u0000' !in value.content)
                            if (value != JsonNull && !value.isString && value.booleanOrNull == null)
                                require(value.doubleOrNull?.isFinite() == true) {
                                    "Query numbers must be finite"
                                }
                        }
                    }
                }
                parameters.forEach { validate(it, 0) }
                require((step["result"]?.intArgument() ?: 1) in 1..8) {
                    "Method result index must be 1..8"
                }
            }
        }
    }
    val mode = args["mode"]?.stringArgument() ?: "values"
    require(mode in setOf("values", "members", "entries")) {
        "Inspection mode must be values, members or entries"
    }
    val fields = args["fields"]?.jsonArray?.map { it.stringArgument() }
    require(
        fields == null ||
                mode == "values" &&
                fields.size in 1..64 &&
                fields.distinct().size == fields.size &&
                fields.all { it.length <= 128 && it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
    ) {
        "Invalid inspection fields"
    }
    val offset = args["offset"]?.intArgument() ?: 0
    val limit = args["limit"]?.intArgument() ?: 64
    require(offset in 0..65536 && limit in 1..256) {
        "Inspection offset must be 0..65536 and limit 1..256"
    }
    return WorldQuery(
        buildJsonObject {
            put("selection", target)
            args["surface"]?.let { put("surface", it) }
            put("limit", limit)
            put("offset", offset)
            put(
                "fields",
                fields?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonArray(emptyList()),
            )
            putJsonObject("inspection") {
                put(
                    "path",
                    JsonArray(
                        path.map { value ->
                            val step = value.jsonObject
                            if ("method" !in step) step
                            else
                                JsonObject(
                                    step +
                                            ("argument_count" to
                                                    JsonPrimitive(step["arguments"]?.jsonArray?.size ?: 0))
                                )
                        }
                    ),
                )
                put("mode", mode)
            }
        }
    )
}
