package com.hiczp.factorio.mcp

/**
 * World-local scheduler factory. The native adapter supplies a nonblocking send function.
 * Completion adapters must deliver one event per submitted operation, in order within
 * their response type, after the submission callback returns.
 */
internal val residentSchedulerSource = """
    return function(send, capacity)
        assert(type(send) == "function", "A completion sender is required")
        assert(type(capacity) == "number" and capacity > 0 and capacity == math.floor(capacity), "Invalid queue capacity")

        local function queue()
            local values, first, last = {}, 1, 0
            return {
                push = function(value)
                    last = last + 1
                    values[last] = value
                end,
                pop = function()
                    if first > last then return nil end
                    local value = values[first]
                    values[first] = nil
                    first = first + 1
                    if first > last then first, last = 1, 0 end
                    return value
                end,
                peek = function() return values[first] end,
                size = function() return last - first + 1 end
            }
        end

        local submissions = queue()
        local listeners = {}
        local pending, running = 0, false
        local scheduler = {}

        local function deliver(id, value, failed)
            -- Delivery failure never retains a result or interrupts the next task.
            pcall(send, id, value, failed)
        end

        function scheduler.listen(responseType)
            assert(type(responseType) == "string" and responseType ~= "", "Invalid response type")
            assert(not listeners[responseType], "Completion listener already registered")
            local waiting = queue()
            listeners[responseType] = waiting
            return function(value, failed)
                local id = waiting.pop()
                if not id then return false end
                pending = pending - 1
                deliver(id, value, failed == true)
                return true
            end
        end

        function scheduler.submit(task)
            assert(type(task) == "table", "Invalid task")
            assert(type(task.id) == "string" and task.id ~= "", "A task ID is required")
            assert(type(task.callback) == "function", "A task callback is required")
            assert(task.ready == nil or type(task.ready) == "function", "Invalid readiness callback")
            assert(task.responseType == nil or listeners[task.responseType], "Unknown completion listener")
            if pending >= capacity then
                deliver(task.id, "Game task queue is full", true)
                return false
            end
            -- Own a small task description, not the caller's mutable table.
            submissions.push({id=task.id, callback=task.callback, ready=task.ready, responseType=task.responseType})
            pending = pending + 1
            return true
        end

        function scheduler.run()
            assert(not running, "Task execution cannot be reentered")
            running = true
            -- Bound a phase to its admitted batch, including when a callback submits work.
            local count = submissions.size()
            for i = 1, count do
                local task = submissions.peek()
                local readyOk, ready = true, true
                if task.ready then readyOk, ready = pcall(task.ready) end
                if readyOk and not ready then break end
                submissions.pop()
                local ok, value = readyOk, ready
                if ok then ok, value = pcall(task.callback) end
                if ok and task.responseType then
                    -- The listener only retains the ID; the callback can now be collected.
                    listeners[task.responseType].push(task.id)
                else
                    pending = pending - 1
                    deliver(task.id, value, not ok)
                end
            end
            running = false
        end

        function scheduler.status()
            local queued = submissions.size()
            return {queued=queued, waiting=pending-queued, pending=pending}
        end

        return scheduler
    end
""".trimIndent()

internal fun residentBootstrapScript(capacity: Int): String {
    require(capacity > 0)
    return """
        assert(game and script and not game.simulation and script.mod_name == "level", "An active world is required")
        local create = (function()
            $residentSchedulerSource
        end)()
        local send = assert(__factorio_mcp_send_v1, "Native IPC library is missing")
        __factorio_mcp_send_v1 = nil
        local native = assert(__factorio_mcp_native_v1, "Native action library is missing")
        __factorio_mcp_native_v1 = nil
        local function deliver(id, value, failed)
            if failed then value = {message=tostring(value)} end
            local ok, json = pcall(helpers.table_to_json, {value=value})
            if not ok then
                json = helpers.table_to_json({value={message="Task result is not JSON serializable"}})
                failed = true
            end
            send(id, json, failed and 1 or 0)
        end
        local key = "__factorio_mcp_resident_v1"
        local old = rawget(_G, key)
        if old then
            assert(type(old) == "table" and old.version == 1, "Resident marker collision")
            old.active = false
        end
        local state = {version=1, active=true, scheduler=create(deliver, $capacity), native=native}
        local currentPlayer
        function state.player()
            if currentPlayer and currentPlayer.valid and currentPlayer.connected and native('is_local_player',currentPlayer)==1 then
                return currentPlayer
            end
            currentPlayer=nil
            for _,candidate in pairs(game.players) do
                if candidate.connected and native('is_local_player',candidate)==1 then
                    assert(not currentPlayer, "Multiple local players are unsupported")
                    currentPlayer=candidate
                end
            end
            return assert(currentPlayer, "The injected client has no active local player")
        end
        local observers, completions = {tick={}, input={}, control={}}, {}
        local registration
        function state.completion(kind)
            if not completions[kind] then completions[kind] = state.scheduler.listen(kind) end
            return completions[kind]
        end
        function state.observe(phase, callback, failed)
            assert(observers[phase], "Unknown game phase")
            local token = {}
            observers[phase][token] = {callback=callback, failed=failed}
            local remove=function() observers[phase][token]=nil end
            if registration then registration[#registration+1]=remove end
            return remove
        end
        local function dispatch(phase, event)
            -- Snapshot the phase so callbacks registered during dispatch run next time.
            local batch = {}
            for token, observer in pairs(observers[phase]) do batch[#batch+1] = {token, observer} end
            for _, entry in ipairs(batch) do
                if observers[phase][entry[1]] then
                    local ok, done = pcall(entry[2].callback, event)
                    if not ok or done then observers[phase][entry[1]] = nil end
                    if not ok then pcall(entry[2].failed, done) end
                end
            end
        end
        function state.event(id, callback, failed)
            local phase=string.format('event:%s',id)
            if not observers[phase] then
                local previous=script.get_event_handler(id)
                local filters=script.get_event_filter(id)
                assert(not filters or #filters==0, "Cannot replace a filtered scenario event handler")
                observers[phase]={}
                script.on_event(id,function(event)
                    if previous then previous(event) end
                    if state.active then dispatch(phase,event) end
                end)
            end
            return state.observe(phase,callback,failed)
        end
        rawset(_G, "__factorio_mcp_phase_v1", function(phase)
            if state.active then dispatch(phase) end
        end)
        rawset(_G, key, state)
        rawset(_G, "__factorio_mcp_submit_v1", function(id, source)
            local chunk, message = load(source, "=factorio-task", "t", _ENV)
            if not chunk then deliver(id, message, true); return end
            local ok, task = pcall(chunk)
            if not ok then deliver(id, task, true); return end
            if type(task) ~= "table" then deliver(id, "Task description must be a table", true); return end
            local callback=task.callback
            if type(callback)=="function" then
                task.callback=function()
                    local previousRegistration=registration
                    local previousAction,previousResearch=state.player_action,state.research
                    local created={}
                    registration=created
                    local success,value=pcall(callback)
                    registration=previousRegistration
                    if not success then
                        -- Roll back a partially initialized task before the scheduler replies.
                        -- Its observers must never consume a later task's completion slot.
                        for _,remove in ipairs(created) do remove() end
                        state.player_action,state.research=previousAction,previousResearch
                        error(value,0)
                    end
                    return value
                end
            end
            task.id = id
            local accepted, reason = pcall(state.scheduler.submit, task)
            if not accepted then deliver(id, reason, true) end
        end)
        local id = defines.events.on_tick
        local previous = script.get_event_handler(id)
        state.wrapper = function(event)
            if previous then previous(event) end
            if state.active then
                dispatch("tick")
                state.scheduler.run()
            end
        end
        script.on_event(id, state.wrapper)
        return "bound"
    """.trimIndent()
}
