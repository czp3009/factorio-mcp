package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal fun JsonElement.stringArgument(): String {
    val value = jsonPrimitive
    require(value.isString) { "Expected a JSON string" }
    return value.content
}

internal fun JsonElement.intArgument(): Int {
    val value = jsonPrimitive
    require(!value.isString) { "Expected a JSON integer" }
    return requireNotNull(value.intOrNull) { "Expected a JSON integer" }
}

internal fun JsonElement.booleanArgument(): Boolean {
    val value = jsonPrimitive
    require(!value.isString) { "Expected a JSON boolean" }
    return requireNotNull(value.booleanOrNull) { "Expected a JSON boolean" }
}

internal fun JsonElement.doubleArgument(): Double {
    val value = jsonPrimitive
    require(!value.isString) { "Expected a JSON number" }
    return requireNotNull(value.doubleOrNull) { "Expected a JSON number" }
}
