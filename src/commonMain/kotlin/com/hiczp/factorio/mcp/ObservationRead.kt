package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal fun inspectionPath(value: JsonElement): JsonArray {
    val path = value.jsonArray
    for (value in path) {
        val step = value.jsonObject
        require(step.keys.count { it in setOf("property", "index", "method") } == 1) {
            "Path step requires exactly one property, index or method"
        }
        when {
            "property" in step -> require(
                step.keys == setOf("property") &&
                        step.getValue("property").stringArgument().matches(nativeMemberPattern)
            ) { "Invalid property step" }

            "index" in step -> {
                require(step.keys == setOf("index")) { "Invalid index step" }
                val index = step.getValue("index").jsonPrimitive
                require(index.isString || index.doubleOrNull?.isFinite() == true) { "Invalid collection key" }
            }

            else -> {
                require(step.keys.all { it in setOf("method", "arguments", "result") }) { "Invalid method step" }
                require(
                    step.getValue("method").stringArgument() in inspectionMethods
                ) { "Method is not an admitted passive query" }
                val arguments = step["arguments"]?.jsonArray ?: JsonArray(emptyList())
                val pending = ArrayDeque<JsonElement>()
                pending.addAll(arguments)
                while (pending.isNotEmpty()) when (val item = pending.removeLast()) {
                    is JsonObject -> item.forEach { (key, child) ->
                        require('\u0000' !in key) { "Method argument keys must not contain NUL" }
                        pending.addLast(child)
                    }

                    is JsonArray -> pending.addAll(item)
                    is JsonPrimitive -> {
                        require('\u0000' !in item.content) { "Method arguments must not contain NUL" }
                        if (item != JsonNull && !item.isString && item.booleanOrNull == null)
                            require(item.doubleOrNull?.isFinite() == true) { "Method numbers must be finite" }
                    }
                }
                require((step["result"]?.intArgument() ?: 1) > 0) { "Method result index must be positive" }
            }
        }
    }
    return JsonArray(path.map { value ->
        val step = value.jsonObject
        if ("method" !in step) step else JsonObject(
            step +
                    ("argument_count" to JsonPrimitive(step["arguments"]?.jsonArray?.size ?: 0))
        )
    })
}

internal fun observationRead(args: JsonObject): JsonObject {
    require(args.keys.all {
        it in setOf(
            "path",
            "mode",
            "fields",
            "offset",
            "limit",
            "depth",
            "search"
        )
    }) { "Unknown read argument" }
    val mode = args["mode"]?.stringArgument() ?: "values"
    require(mode in setOf("values", "members", "entries", "slice")) { "Unknown read mode" }
    require(mode == "values" || "fields" !in args) { "fields requires values" }
    require(mode != "values" || args.keys.none {
        it in setOf(
            "offset",
            "limit"
        )
    }) { "values does not accept collection counts or offsets" }
    require(mode in setOf("values", "entries") || "depth" !in args) { "depth requires values or entries" }
    require(mode == "members" || "search" !in args) { "search requires members" }
    args["search"]?.let { require(it.stringArgument().isNotEmpty()) { "search must be nonempty" } }
    args["fields"]?.let(::nativeFields)
    val offset = args["offset"]?.intArgument() ?: 0
    val limit = args["limit"]?.intArgument() ?: when (mode) {
        "values", "members" -> null
        "entries" -> 64
        else -> 4096
    }
    require(offset >= 0 && (limit == null || limit > 0)) { "offset must be nonnegative and limit positive" }
    return buildJsonObject {
        put("mode", mode)
        put("path", inspectionPath(args["path"] ?: JsonArray(emptyList())))
        args["fields"]?.let { put("fields", it) }
        args["search"]?.let { put("search", it) }
        if (mode != "values") put("offset", offset)
        limit?.let { put("limit", it) }
        put("depth", observationDepth(args["depth"]?.intArgument()))
    }
}

internal fun observationReads(value: JsonElement): JsonArray = JsonArray(value.jsonArray.also {
    require(it.isNotEmpty()) { "reads must not be empty" }
}.map { observationRead(it.jsonObject) })
