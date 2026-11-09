package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal class RuntimeApi(document: JsonObject) {
    private val classes = document.getValue("classes").jsonArray.associate {
        it.jsonObject.getValue("name").stringArgument() to it.jsonObject
    }
    private val concepts = document["concepts"]?.jsonArray.orEmpty().associate {
        it.jsonObject.getValue("name").stringArgument() to it.jsonObject.getValue("type")
    }

    init {
        require(classes.isNotEmpty()) { "Runtime API class catalog is empty" }
        require(document["application"]?.stringArgument() == "factorio") { "Not a Factorio runtime API" }
    }

    private fun lineage(name: String, active: Set<String> = emptySet()): List<String> {
        require(name !in active) { "Invalid runtime API inheritance" }
        val value = classes[name] ?: error("Missing API class $name")
        return value["parent"]?.stringArgument()?.let { lineage(it, active + name) }.orEmpty() + name
    }

    private fun members(name: String, kind: String): List<JsonObject> = lineage(name)
        .flatMap { classes.getValue(it)[kind]?.jsonArray.orEmpty().map { value -> value.jsonObject } }
        .associateBy { it.getValue("name").stringArgument() }.values.toList()

    /** Retain declared native interfaces through containers, without mapping concrete runtime names. */
    private fun valueType(type: JsonElement?, active: Set<String> = emptySet()): JsonObject? {
        if (type == null) return null
        if (type is JsonPrimitive) {
            val name = type.content
            if (name in classes) return buildJsonObject { put("class", name) }
            if (name in active) return null
            return concepts[name]?.let { valueType(it, active + name) }
        }
        val value = type.jsonObject
        return when (value["complex_type"]?.stringArgument()) {
            "LuaCustomTable" -> buildJsonObject {
                put("class", "LuaCustomTable")
                valueType(value["value"], active)?.let { put("element", it) }
            }

            "array", "dictionary" -> valueType(value["value"], active)?.let {
                buildJsonObject { put("element", it) }
            }

            "table" -> {
                val parameters = value["parameters"]?.jsonArray.orEmpty() +
                        value["variant_parameter_groups"]?.jsonArray.orEmpty().flatMap {
                            it.jsonObject["parameters"]?.jsonArray.orEmpty()
                        }
                val fields = parameters.map { it.jsonObject }.groupBy { it.getValue("name").stringArgument() }
                    .mapNotNull { (name, variants) ->
                        variants.mapNotNull { valueType(it["type"], active) }.distinct().singleOrNull()
                            ?.let { name to it }
                    }.toMap()
                if (fields.isEmpty()) null else buildJsonObject { put("fields", JsonObject(fields)) }
            }

            "union" -> value["options"]?.jsonArray.orEmpty().mapNotNull { valueType(it, active) }
                .distinct().singleOrNull()

            else -> null
        }
    }

    val catalog: JsonObject by lazy {
        buildJsonObject {
            for (name in classes.keys.sorted()) putJsonObject(name) {
                val operators = members(name, "operators")
                val index = operators.find { it["name"]?.jsonPrimitive?.content == "index" }
                put("indexed", index?.containsKey("read_type") == true)
                put("length", operators.any { it["name"]?.jsonPrimitive?.content == "length" && "read_type" in it })
                valueType(index?.get("read_type"))?.let { put("index_type", it) }
                put("iterator", name == "LuaChunkIterator" && operators.any {
                    it["name"]?.jsonPrimitive?.content == "call" && it["parameters"]?.jsonArray.orEmpty().isEmpty()
                })
                val attributes = members(name, "attributes").filter {
                    "read_type" in it && admittedInspectionAttribute(
                        lineage(name),
                        it.getValue("name").stringArgument()
                    )
                }
                val methods = members(name, "methods").filter { method ->
                    admittedInspectionMethod(lineage(name), method.getValue("name").stringArgument())
                }
                putJsonArray("attributes") {
                    attributes.map { it.getValue("name").stringArgument() }.sorted().forEach {
                        require(it.matches(nativeMemberPattern)) { "Invalid API attribute name" }
                        add(it)
                    }
                }
                putJsonArray("methods") { methods.map { it.getValue("name").stringArgument() }.sorted().forEach(::add) }
                putJsonObject("attribute_types") {
                    for (attribute in attributes) valueType(attribute["read_type"])?.let {
                        put(attribute.getValue("name").stringArgument(), it)
                    }
                }
                putJsonObject("method_types") {
                    for (method in methods) {
                        val returns = method["return_values"]?.jsonArray.orEmpty().sortedBy {
                            it.jsonObject["order"]?.jsonPrimitive?.int ?: 0
                        }.map { valueType(it.jsonObject["type"]) ?: JsonObject(emptyMap()) }
                        if (returns.any { it.isNotEmpty() })
                            put(method.getValue("name").stringArgument(), JsonArray(returns))
                    }
                }
            }
        }
    }

    fun memberInfo(className: String, name: String, method: Boolean): JsonObject? {
        if (className !in classes) return null
        val member = members(className, if (method) "methods" else "attributes")
            .find { it.getValue("name").stringArgument() == name } ?: return null
        return JsonObject(member.filterKeys {
            it in setOf(
                "name",
                "description",
                "read_type",
                "optional",
                "subclasses",
                "parameters",
                "format",
                "return_values"
            )
        })
    }

    fun prepare(query: WorldQuery): WorldQuery {
        val selection = query.arguments.getValue("selection").jsonObject
        if (selection["kind"]?.jsonPrimitive?.content in setOf("prototypes", "prototype") &&
            ("inspection" !in query.arguments || "type" in selection)
        ) {
            val name = selection.getValue("type").stringArgument()
            val attribute = members("LuaPrototypes", "attributes").find {
                it.getValue("name").stringArgument() == name
            }
            require(attribute?.get("read_type")?.let {
                it is JsonObject && it["complex_type"]?.jsonPrimitive?.content == "LuaCustomTable"
            } == true) { "Unknown native prototype catalog: $name" }
        }
        val spatial = selection["owner"]?.jsonObject ?: selection
        spatial["filters"]?.jsonObject?.let { filters ->
            val definition = concepts["EntitySearchFilters"]?.jsonObject
                ?: error("Runtime API has no EntitySearchFilters definition")
            val names = definition["parameters"]?.jsonArray.orEmpty().map {
                it.jsonObject.getValue("name").stringArgument()
            }.toSet()
            require(filters.keys.all { it in names }) { "Unknown native entity filter" }
        }
        return query.copy(arguments = JsonObject(query.arguments + ("api" to catalog)))
    }

    private fun annotateObservation(item: JsonObject): JsonObject = buildJsonObject {
        item.forEach { (key, value) -> put(key, value) }
        val className = (item["api_class"] ?: item["object_name"])?.jsonPrimitive?.content
        item["members"]?.jsonArray?.let { members ->
            put("members", JsonArray(members.map { raw ->
                val entry = raw.jsonObject
                val info = className?.let {
                    memberInfo(
                        it,
                        entry.getValue("name").stringArgument(),
                        entry["kind"]?.jsonPrimitive?.content == "method"
                    )
                }
                if (info == null) entry else JsonObject(entry + ("api" to info))
            }))
        }
        item["reads"]?.jsonArray?.let { reads ->
            put("reads", JsonArray(reads.map { annotateObservation(it.jsonObject) }))
        }
        item["entries"]?.jsonArray?.let { entries ->
            put("entries", JsonArray(entries.map { raw ->
                val entry = raw.jsonObject
                if (entry["reads"] == null) entry else annotateObservation(entry)
            }))
        }
    }

    fun annotate(result: JsonObject): JsonObject = JsonObject(
        result +
                ("objects" to JsonArray(result.getValue("objects").jsonArray.map { annotateObservation(it.jsonObject) }))
    )
}
