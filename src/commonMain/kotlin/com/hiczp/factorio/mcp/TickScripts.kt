package com.hiczp.factorio.mcp

private const val SESSION = "__factorio_mcp_diagnostic_v1"

internal fun luaQuote(text: String): String = buildString {
    append('"')
    text.encodeToByteArray().forEach { append('\\'); append((it.toInt() and 255).toString().padStart(3, '0')) }
    append('"')
}

internal fun installScript(source: String, count: Int, token: String, untilReady: Boolean = false): String = """
    if not game or not script or game.simulation or script.mod_name ~= "level" then return "unavailable-vm" end
    local key, token = "$SESSION", ${luaQuote(token)}
    local user, syntax_error = load(${luaQuote(source)}, "=user-on-tick", "t", _ENV)
    assert(user, syntax_error)
    local stale = rawget(_G, key)
    if stale then
      assert(type(stale) == "table" and stale.version == 1 and type(stale.stop) == "function", "session global collision")
      stale.stop()
    end
    local id = defines.events.on_tick
    local previous = script.get_event_handler(id)
    local state = {version=1, token=token, count=0, done=false, ok=true, memo={}}
    local wrapper
    state.stop = function()
      if script.get_event_handler(id) == wrapper then script.on_event(id, previous) end
      state.done = true
    end
    wrapper = function(event)
      if previous then previous(event) end
      if state.done then return end
      state.count = state.count + 1
      state.tick = event.tick
      -- Run as an official event callback. User code can access the event with `local event = ...`.
      local ok, value = pcall(user, event, state.memo)
      ${if (untilReady) "if ok and value == nil then return end" else ""}
      state.ok = ok
      if ok then
        local encoded, json = pcall(helpers.table_to_json, {value=value})
        if encoded then
          if #json > 200000 then state.ok=false; state.error="result exceeds 200000 bytes"
          else state.value_json=json end
        else state.ok=false; state.error=string.format("return value is not JSON serializable: %s",tostring(json)) end
      else state.error = tostring(value) end
      if state.error then state.error = string.sub(state.error, 1, 8192) end
      if not state.ok or ${if (untilReady) "value ~= nil" else "state.count >= $count"} then state.stop() end
    end
    rawset(_G, key, state)
    script.on_event(id, wrapper)
    return "installed"
""".trimIndent()

internal fun pollScript(token: String): String = """
    if not game or not script or game.simulation or script.mod_name ~= "level" then return "unavailable-vm" end
    local state = rawget(_G, "$SESSION")
    if not state or state.token ~= ${luaQuote(token)} then return "lost" end
    if not state.done then return "pending" end
    local response = {operation_id=state.token, ok=state.ok, callbacks=state.count, tick=state.tick, error=state.error}
    local value = state.value_json and helpers.json_to_table(state.value_json) or {}
    rawset(_G, "$SESSION", nil)
    return string.format([[%s
    %s]],state.ok and 'done-ok' or 'done-error',helpers.table_to_json{execution=response,result=value})
""".trimIndent()

internal fun cleanupScript(token: String? = null): String = """
    if not game or not script or game.simulation or script.mod_name ~= "level" then return "unavailable-vm" end
    local state = rawget(_G, "$SESSION")
    if state ${if (token != null) "and state.token == ${luaQuote(token)}" else ""} then
      assert(state.version == 1 and type(state.stop) == "function", "session global collision")
      state.stop()
      rawset(_G, "$SESSION", nil)
    end
    return "detached"
""".trimIndent()
