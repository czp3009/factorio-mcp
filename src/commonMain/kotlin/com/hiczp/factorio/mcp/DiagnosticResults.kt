package com.hiczp.factorio.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun diagnosticStatusJson(pid: Int, inMenu: Boolean, buildId: String, symbols: String): String =
    buildJsonObject {
        put("pid", pid)
        put("state", if (inMenu) "main_menu" else "in_game")
        put("build_id", buildId)
        put("symbols", symbols)
        put("tick", JsonNull)
    }.toString()

internal fun pendingObserverStatus(pid: Int, loading: Boolean): String = buildJsonObject {
    put("pid", pid)
    put("state", if (loading) "loading" else "unknown")
    put("recognized", true)
    put("resident", false)
    put("ready", false)
    put("stage", "symbols_resolved")
    put("waiting_for", "safe_point")
    put("next_action", "Call status again to continue observer initialization")
}.toString()

internal fun residentUnavailableStatus(pid: Int): String = buildJsonObject {
    put("pid", pid)
    put("state", "unknown")
    put("recognized", true)
    put("resident", true)
    put("ready", false)
    put("stage", "resident_loaded")
    put("waiting_for", "resident_response")
}.toString()
