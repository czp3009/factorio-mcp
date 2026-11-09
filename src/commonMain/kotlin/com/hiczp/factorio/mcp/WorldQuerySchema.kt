package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

private fun ref(name: String) = buildJsonObject { put("\$ref", "#/\$defs/$name") }

private fun stringSchema(minimum: Int = 1, pattern: String? = null) = buildJsonObject {
    put("type", "string")
    put("minLength", minimum)
    pattern?.let { put("pattern", it) }
}

private fun countSchema(minimum: Int = 1, maximum: Long = Int.MAX_VALUE.toLong()) = buildJsonObject {
    put("type", "integer")
    put("minimum", minimum)
    put("maximum", maximum)
}

private fun arraySchema(items: JsonObject, minimum: Int = 1, unique: Boolean = true) = buildJsonObject {
    put("type", "array")
    put("minItems", minimum)
    if (unique) put("uniqueItems", true)
    put("items", items)
}

private fun objectSchema(properties: JsonObject, required: List<String> = emptyList()) = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("properties", properties)
    if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
}

private fun requiredSchema(vararg names: String) = buildJsonObject {
    put("required", JsonArray(names.map(::JsonPrimitive)))
}

private fun notRequired(vararg names: String) = buildJsonObject {
    putJsonObject("not") { putJsonArray("anyOf") { names.forEach { add(requiredSchema(it)) } } }
}

private fun variants(values: List<JsonObject>) = buildJsonObject { put("oneOf", JsonArray(values)) }

private fun choice(vararg values: String) = buildJsonObject {
    put("type", "string")
    put("enum", JsonArray(values.map(::JsonPrimitive)))
}

private fun allowedProperties(keys: Set<String>) = objectSchema(buildJsonObject {
    keys.forEach { put(it, buildJsonObject {}) }
})

internal fun worldPositionSchema() = objectSchema(buildJsonObject {
    for (field in listOf("x", "y")) putJsonObject(field) {
        put("type", "number")
        put("minimum", -1000000)
        put("maximum", 1000000)
    }
}, listOf("x", "y"))

private fun selector(kind: String, properties: JsonObject = buildJsonObject {}, required: List<String> = emptyList()) =
    objectSchema(JsonObject(properties + ("kind" to buildJsonObject { put("const", kind) })), listOf("kind") + required)

private fun condition(test: JsonObject, then: JsonObject, otherwise: JsonObject? = null) = buildJsonObject {
    put("if", test)
    put("then", then)
    otherwise?.let { put("else", it) }
}

private fun selectionCondition(kind: String) = buildJsonObject {
    putJsonObject("properties") {
        put("selection", buildJsonObject {
            putJsonObject("properties") { putJsonObject("kind") { put("const", kind) } }
        })
    }
}

/** Selection and mode constraints live below query because the SDK's tool schema has no root combinators. */
internal fun worldQueryDefinitions(): JsonObject = buildJsonObject {
    put("member", stringSchema(pattern = "^[A-Za-z_][A-Za-z0-9_]*$"))
    put("name", stringSchema(pattern = "^[^\\u0000]+$"))
    put(
        "selector_id",
        buildJsonObject {
            putJsonArray("oneOf") {
                add(ref("name"))
                add(countSchema(maximum = 4294967295L))
            }
        })
    put(
        "fields", JsonObject(
            arraySchema(ref("member"), minimum = 0) + ("description" to JsonPrimitive(
                "Native property names, discovered with inspect members. Omit for branch defaults; inspect values defaults to all admitted passive attributes. Empty omits attributes while retaining native references. Unknown, unsupported and nil reads remain explicit."
            ))
        )
    )
    put("position", worldPositionSchema())
    put(
        "area",
        objectSchema(
            buildJsonObject {
                put("left_top", ref("position"))
                put("right_bottom", ref("position"))
            },
            listOf("left_top", "right_bottom")
        )
    )
    put("path", inspectionPathSchema())
    put("read", buildJsonObject {
        objectSchema(buildJsonObject {
            put("path", ref("path"))
            put("mode", choice("values", "members", "entries", "slice"))
            put("fields", ref("fields"))
            put("offset", countSchema(0))
            put("limit", countSchema())
            put("depth", countSchema(0))
            put("search", stringSchema())
        }).forEach { (key, value) -> put(key, value) }
        put(
            "oneOf", JsonArray(
                listOf(
                    buildJsonObject {
                        allowedProperties(setOf("path", "mode", "fields", "depth")).forEach { (key, value) ->
                            put(
                                key,
                                value
                            )
                        }
                        putJsonObject("properties") {
                            put("path", buildJsonObject {})
                            put(
                                "mode",
                                choice("values")
                            )
                            put("fields", buildJsonObject {})
                            put("depth", buildJsonObject {})
                        }
                    },
                    objectSchema(buildJsonObject {
                        put("mode", choice("members"))
                        for (key in listOf(
                            "path",
                            "offset",
                            "limit",
                            "search"
                        )) put(key, buildJsonObject {})
                    }, listOf("mode")),
                    objectSchema(buildJsonObject {
                        put("mode", choice("entries"))
                        for (key in listOf(
                            "path",
                            "offset",
                            "limit",
                            "depth"
                        )) put(key, buildJsonObject {})
                    }, listOf("mode")),
                    objectSchema(buildJsonObject {
                        put("mode", choice("slice"))
                        for (key in listOf(
                            "path",
                            "offset",
                            "limit"
                        )) put(key, buildJsonObject {})
                    }, listOf("mode")),
                )
            )
        )
    })
    put(
        "reads", JsonObject(
            arraySchema(ref("read"), unique = false) + ("description" to JsonPrimitive(
                "Ordered paths read independently for each available selected root value, or each selected inspect entry. Each read has its own mode/fields/depth/page. Repetitions remain and one read failure does not discard other reads."
            ))
        )
    )
    put("entities", buildJsonObject {
        selector("entities", buildJsonObject {
            put("position", ref("position"))
            put("area", ref("area"))
            putJsonObject("radius") {
                put("type", "number")
                put("minimum", 0)
            }
            putJsonObject("unit_number") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", JsonPrimitive(ULong.MAX_VALUE))
                put(
                    "description",
                    "Current-world identity, exactly representable by Lua. Use observed position/area and surface; bare lookup requires the prototype's native index flag."
                )
            }
            put("name", entityFilterSchema("name"))
            put("type", entityFilterSchema("type"))
            putJsonObject("filters") {
                put("type", "object")
                put(
                    "description",
                    "Additional native EntitySearchFilters, such as force, quality or ghost_name; geometry, name, type and limit remain outside this object."
                )
                putJsonObject("not") {
                    putJsonArray("anyOf") {
                        for (key in listOf("position", "area", "radius", "name", "type", "limit")) add(
                            requiredSchema(
                                key
                            )
                        )
                    }
                }
            }
        }).forEach { (key, value) -> put(key, value) }
        putJsonArray("allOf") {
            add(
                variants(
                    listOf(
                        buildJsonObject {
                            put("required", JsonArray(listOf(JsonPrimitive("position"))))
                            put(
                                "not",
                                requiredSchema("area")
                            )
                        },
                        buildJsonObject {
                            put("required", JsonArray(listOf(JsonPrimitive("area"))))
                            put(
                                "not",
                                requiredSchema("position")
                            )
                        },
                        buildJsonObject {
                            put("required", JsonArray(listOf(JsonPrimitive("unit_number"))))
                            notRequired(
                                "position",
                                "area",
                                "radius",
                                "name",
                                "type",
                                "filters"
                            ).forEach { (key, value) -> put(key, value) }
                        },
                    )
                )
            )
            add(condition(requiredSchema("radius"), requiredSchema("position")))
        }
    })
    put("tiles", buildJsonObject {
        selector(
            "tiles",
            buildJsonObject {
                put("position", ref("position"))
                put(
                    "area",
                    ref("area")
                )
            }).forEach { (key, value) -> put(key, value) }
        put("oneOf", JsonArray(listOf(requiredSchema("position"), requiredSchema("area"))))
    })
    put("player_reference", variants((listOf("player") + localEntityKinds).map {
        selector(it, buildJsonObject { put("player", ref("selector_id")) })
    }))
    put("force", buildJsonObject {
        selector(
            "force",
            buildJsonObject {
                put("player", ref("selector_id"))
                put(
                    "force",
                    ref("selector_id")
                )
            }).forEach { (key, value) -> put(key, value) }
        putJsonObject("not") { put("required", JsonArray(listOf(JsonPrimitive("player"), JsonPrimitive("force")))) }
    })
    put("owner", variants(listOf(ref("entities"), ref("player_reference"))))
    put("target", variants(buildList {
        add(ref("entities"))
        add(ref("player_reference"))
        add(ref("force"))
        for (kind in listOf("game", "prototypes", "defines")) add(selector(kind))
        add(selector("surface", buildJsonObject { put("name", ref("name")) }))
        add(selector("planet", buildJsonObject { put("name", ref("name")) }, listOf("name")))
        add(
            selector(
                "prototype",
                buildJsonObject {
                    put("name", ref("name"))
                    put("type", ref("member"))
                },
                listOf("name", "type")
            )
        )
        for (kind in listOf("recipe", "technology")) add(buildJsonObject {
            selector(
                kind,
                buildJsonObject {
                    put("name", ref("name"))
                    put("player", ref("selector_id"))
                    put(
                        "force",
                        ref("selector_id")
                    )
                },
                listOf("name")
            ).forEach { (key, value) -> put(key, value) }
            putJsonObject("not") { put("required", JsonArray(listOf(JsonPrimitive("player"), JsonPrimitive("force")))) }
        })
    }))
    put("selection", variants(buildList {
        add(ref("entities"))
        add(ref("tiles"))
        add(ref("player_reference"))
        add(ref("force"))
        add(selector("players", buildJsonObject {
            putJsonObject("connected") { put("type", "boolean") }
            put("names", arraySchema(ref("name")))
            put("indices", arraySchema(countSchema(maximum = 4294967295L)))
        }))
        for (kind in listOf("inventory", "inventories")) add(selector(kind, buildJsonObject {
            put("owner", ref("owner"))
            if (kind == "inventory") put("inventory", ref("name"))
        }))
        for (kind in listOf("prototypes", "recipes", "technologies")) add(buildJsonObject {
            selector(kind, buildJsonObject {
                if (kind == "prototypes") put("type", ref("member")) else put("force", ref("selector_id"))
                put("names", arraySchema(ref("name")))
                put("search", ref("name"))
                if (kind != "technologies") for (key in listOf("ingredient", "product")) put(
                    key,
                    objectSchema(buildJsonObject {
                        put("type", choice("item", "fluid"))
                        put("name", ref("name"))
                    }, listOf("type", "name"))
                )
            }, if (kind == "prototypes") listOf("type") else emptyList()).forEach { (key, value) -> put(key, value) }
            if (kind == "prototypes") putJsonArray("allOf") {
                add(condition(buildJsonObject {
                    putJsonObject("properties") {
                        putJsonObject("type") {
                            put(
                                "const",
                                "recipe"
                            )
                        }
                    }
                }, buildJsonObject {}, notRequired("ingredient", "product")))
            }
        })
        add(
            selector(
                "inspect",
                buildJsonObject {
                    put("target", ref("target"))
                    put("path", ref("path"))
                },
                listOf("target")
            )
        )
        val quickbar = quickbarSelectionProperties()
        add(selector("quickbar", buildJsonObject {
            put("slots", quickbar.getValue("slots"))
            put("screen_pages", quickbar.getValue("screen_pages"))
        }).let { base ->
            JsonObject(
                base + ("anyOf" to JsonArray(
                    listOf(
                        requiredSchema("slots"),
                        requiredSchema("screen_pages")
                    )
                ))
            )
        })
    }))
    put("query", buildJsonObject {
        objectSchema(buildJsonObject {
            put("selection", ref("selection"))
            put("fields", ref("fields"))
            put("reads", ref("reads"))
            put("include", entityIncludesSchema())
            put("surface", ref("selector_id"))
            put("mode", choice("values", "members", "entries", "slice"))
            put("search", stringSchema())
            put(
                "depth", JsonObject(
                    countSchema(0) + ("description" to JsonPrimitive(
                        "Plain-table expansion depth, default 3, clamped to 65535. Nonempty boundary tables mark children_truncated; empty leaves remain empty. Native objects remain references and ancestor cycles are marked."
                    ))
                )
            )
            put("offset", countSchema(0))
            put(
                "limit", JsonObject(
                    countSchema() + ("description" to JsonPrimitive(
                        "Entry count, or UTF-8 bytes in slice mode; not a scan budget. Spatial default 128 without continuation; catalogs default 64 except explicit names default to all. Inventories/players/members default to all; entries 64 and slice 4096 bytes. Single selections and values reject counts."
                    ))
                )
            )
        }, listOf("selection")).forEach { (key, value) -> put(key, value) }
        putJsonArray("allOf") {
            val common = setOf("selection", "fields", "depth", "reads")
            add(condition(selectionCondition("entities"), buildJsonObject {
                allowedProperties(common + setOf("surface", "limit", "include")).forEach { (key, value) ->
                    put(
                        key,
                        value
                    )
                }
                putJsonArray("allOf") {
                    add(condition(buildJsonObject {
                        putJsonObject("properties") {
                            put(
                                "selection",
                                requiredSchema("unit_number")
                            )
                        }
                    }, notRequired("limit")))
                }
            }))
            add(condition(selectionCondition("tiles"), buildJsonObject {
                allowedProperties(common + setOf("surface", "limit")).forEach { (key, value) -> put(key, value) }
                putJsonArray("allOf") {
                    add(condition(buildJsonObject {
                        putJsonObject("properties") {
                            put(
                                "selection",
                                requiredSchema("position")
                            )
                        }
                    }, notRequired("limit")))
                }
            }))
            for (kind in listOf("player", "force") + localEntityKinds) add(
                condition(
                    selectionCondition(kind),
                    allowedProperties(common + if (kind in localEntityKinds) setOf("include") else emptySet())
                )
            )
            add(condition(selectionCondition("players"), allowedProperties(common + setOf("offset", "limit"))))
            for (kind in listOf("prototypes", "recipes", "technologies")) add(
                condition(
                    selectionCondition(kind),
                    allowedProperties(common + setOf("offset", "limit"))
                )
            )
            for (kind in listOf("inventory", "inventories")) add(condition(selectionCondition(kind), buildJsonObject {
                allowedProperties(
                    if (kind == "inventory") common + setOf(
                        "surface",
                        "offset",
                        "limit"
                    ) else setOf("selection", "surface", "offset", "limit", "reads")
                ).forEach { (key, value) -> put(key, value) }
                putJsonArray("allOf") {
                    add(condition(requiredSchema("surface"), buildJsonObject {
                        putJsonObject("properties") {
                            putJsonObject("selection") {
                                put("required", JsonArray(listOf(JsonPrimitive("owner"))))
                                putJsonObject("properties") {
                                    putJsonObject("owner") {
                                        putJsonObject("properties") {
                                            putJsonObject(
                                                "kind"
                                            ) { put("const", "entities") }
                                        }
                                    }
                                }
                            }
                        }
                    }))
                }
            }))
            add(condition(selectionCondition("quickbar"), allowedProperties(setOf("selection", "depth"))))
            add(condition(selectionCondition("inspect"), buildJsonObject {
                putJsonArray("allOf") {
                    add(
                        condition(
                            buildJsonObject { putJsonObject("properties") { put("mode", choice("values")) } },
                            notRequired("limit", "offset", "search")
                        )
                    )
                    add(condition(buildJsonObject {
                        put(
                            "required",
                            JsonArray(listOf(JsonPrimitive("mode")))
                        )
                        putJsonObject("properties") { put("mode", choice("members")) }
                    }, notRequired("fields", "depth", "reads")))
                    add(condition(buildJsonObject {
                        put(
                            "required",
                            JsonArray(listOf(JsonPrimitive("mode")))
                        )
                        putJsonObject("properties") { put("mode", choice("entries")) }
                    }, notRequired("fields", "search")))
                    add(condition(buildJsonObject {
                        put(
                            "required",
                            JsonArray(listOf(JsonPrimitive("mode")))
                        )
                        putJsonObject("properties") { put("mode", choice("slice")) }
                    }, notRequired("fields", "depth", "reads", "search")))
                    add(notRequired("include"))
                    add(
                        condition(
                            requiredSchema("surface"),
                            buildJsonObject {
                                putJsonObject("properties") {
                                    putJsonObject("selection") {
                                        putJsonObject("properties") {
                                            putJsonObject("target") {
                                                putJsonObject("properties") {
                                                    put(
                                                        "kind",
                                                        choice("entities", "surface")
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            })
                    )
                }
            }))
        }
    })
}

internal fun worldQuerySchema() = buildJsonObject {
    put("query", buildJsonObject {
        put("\$ref", "#/\$defs/query")
        put(
            "description",
            "Select current roots and optional per-root reads. Spatial limit defaults to 128 without continuation; inventories/players/explicit names are complete by default. Table depth defaults to 3 and clamps to 65535. Each call observes one safe point."
        )
    })
}

internal fun worldReadDefinitions(): JsonObject = JsonObject(worldQueryDefinitions().filterKeys {
    it in setOf("member", "fields", "path", "read", "reads")
})
