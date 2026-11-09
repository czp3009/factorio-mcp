package com.hiczp.factorio.mcp

internal const val MAX_OBSERVATION_DEPTH = 65535

internal fun observationDepth(requested: Int?, default: Int = 3): Int {
    require(requested == null || requested >= 0) { "Observation depth must be nonnegative" }
    return (requested ?: default).coerceAtMost(MAX_OBSERVATION_DEPTH)
}
