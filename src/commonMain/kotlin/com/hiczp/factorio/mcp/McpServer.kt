package com.hiczp.factorio.mcp

import io.github.oshai.kotlinlogging.FormattingAppender
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Transports share one local game connection and one mutation lock. */
internal fun serveMcp(options: McpOptions) = runBlocking(Dispatchers.Default) {
    KotlinLoggingConfiguration.logStartupMessage = false
    KotlinLoggingConfiguration.direct.logLevel = Level.WARN
    KotlinLoggingConfiguration.direct.appender = object : FormattingAppender() {
        override fun logFormattedMessage(loggingEvent: KLoggingEvent, formattedMessage: Any?) {
            Platform.writeError(formattedMessage.toString())
        }
    }
    Platform.initializeCancellation()
    val requests = Mutex()
    val actions = Mutex()
    var game: GameProcess? = null
    var gamePid: Int? = null
    val observed = mutableMapOf<Int, GameProcess>()
    var observedPid: Int? = null
    val timeout = 10000
    val server = Server(
        Implementation("factorio-mcp", "0.0.1"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
        instructions = "Call status to initialize observation of a local Factorio process, then attach after entering a world. Tasks execute autonomously in the active world. Ordinary tools always act on this client's local player; use players only for public summaries. Read game data through semantic tools. Check status after a world change or MCP restart. Never starts Factorio."
    )

    fun tool(
        name: String, description: String, properties: JsonObject = buildJsonObject {},
        required: List<String> = emptyList(), mutating: Boolean = true,
        render: (String) -> CallToolResult = { CallToolResult(content = listOf(TextContent(it))) },
        operation: suspend (JsonObject) -> String
    ) {
        server.addTool(name, description, ToolSchema(properties = properties, required = required)) { request ->
            try {
                val args = request.arguments ?: buildJsonObject {}
                require(args.keys.all { it in properties }) { "Unknown argument" }
                check(!Platform.cancelled) { "Server is shutting down" }
                val result = withContext(Dispatchers.IO) {
                    if (mutating) actions.withLock { operation(args) } else operation(args)
                }
                render(result)
            } catch (e: TimeoutCancellationException) {
                CallToolResult(
                    content = listOf(TextContent("Game operation timed out. Admitted tasks may still execute; inspect game state before retrying.")),
                    isError = true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent(e.message ?: "Game operation failed")), isError = true)
            }
        }
    }

    suspend fun active(): GameProcess {
        val target = requests.withLock { game ?: error("Call attach first") }
        val state = Json.parseToJsonElement(target.status(timeout)).jsonObject
        check(state["ready"]?.jsonPrimitive?.boolean == true) { "The current world Lua hook is absent or not ready; call attach before using game tools" }
        check(state["state"]?.jsonPrimitive?.content == "in_game") { "Enter a game and call attach; current state: $state" }
        return target
    }

    fun selectedPid(args: JsonObject): Int {
        require(args.size <= 1) { "Specify either pid or process_name" }
        val selected = args["pid"]?.jsonPrimitive?.int ?: (if (args.isEmpty()) gamePid ?: observedPid else null)
        ?: Platform.findProcesses(args["process_name"]?.jsonPrimitive?.content ?: "factorio").let { matches ->
            check(matches.size == 1) { "Expected one process, found PIDs: $matches" }
            matches.single()
        }
        require(selected > 1) { "PID must be greater than 1" }
        return selected
    }

    fun target(pid: Int): GameProcess = observed.getOrPut(pid) { GameProcess(pid) }
    tool(
        "attach",
        "Bind the current single-player or multiplayer world. Requires the status observer to exist; otherwise call status first. Menu/loading states are errors. Reuses compatible adapters, returns ready=false while awaiting a safe world callback, and never starts Factorio. Call again after changing worlds. PID or exact process name; multiple matches require PID.",
        schema("pid" to "integer", "process_name" to "string")
    ) { args ->
        requests.withLock {
            val selected = selectedPid(args)
            check(gamePid == null || gamePid == selected) { "Attached to PID $gamePid; detach before selecting another process" }
            val current = target(selected)
            current.connectResident(timeout)
            game = current
            gamePid = selected
            current.status(timeout)
        }
    }
    tool(
        "detach",
        "Release MCP observation and attachment sessions. Factorio and its resident code keep running; admitted tasks finish independently."
    ) {
        requests.withLock {
            observed.values.forEach { it.close() }
            observed.clear()
            game = null
            gamePid = null
            observedPid = null
        }
        buildJsonObject { put("detached", true) }.toString()
    }
    tool(
        "status",
        "Identify a process using developer debug information, then install or reuse a small resident observer. Does not bind Lua or enable player actions. Reports loading, main_menu, in_game, transitioning, unknown or unrecognized, plus resident/ready and waiting_for. PID or exact process name; multiple matches require PID. With no arguments, queries the selected session or reports detached. Loading may require a later status call to finish observation setup.",
        schema("pid" to "integer", "process_name" to "string"),
        mutating = false
    ) { args ->
        requests.withLock {
            if (args.isEmpty() && gamePid == null && observedPid == null) {
                return@withLock buildJsonObject { put("state", "detached") }.toString()
            }
            val selected = selectedPid(args)
            try {
                val current = target(selected)
                observedPid = selected
                current.status(timeout)
            } catch (e: UnrecognizedTarget) {
                buildJsonObject {
                    put("pid", selected)
                    put("state", "unrecognized")
                    put("recognized", false)
                    put("resident", false)
                    put("ready", false)
                    put("stage", "identification")
                    put("reason", e.message)
                }.toString()
            }
        }
    }
    tool(
        "screenshot",
        "Render the local player's game view and GUI as PNG. Uses the injected client's local player and requires a graphical client.",
        render = { CallToolResult(content = listOf(ImageContent(data = it, mimeType = "image/png"))) }) {
        active().screenshot(timeout)
    }
    tool(
        "player",
        "Read this client's local player. section: status (default, compact identity/position/reach/controller), cursor, crafting queue, research queue, quickbar, or equipment. Paginated list sections; inventories have their own tool.",
        schema("section" to "string", "limit" to "integer", "offset" to "integer"),
        mutating = false
    ) {
        active().readPlayer(it, timeout)
    }
    tool(
        "inventory",
        "Read a local character inventory (name defaults to character_main), or an entity inventory at x/y (name defaults to chest). An entity may instead use index from inspect_entity, mutually exclusive with name. view=summary groups items by name and quality; view=slots returns indexed stacks, filters and spoil progress/deadlines. Paginated; empty_slots is physical space, not insertion feasibility. Use official inventory names appropriate to the entity type.",
        schema(
            "name" to "string",
            "index" to "integer",
            "view" to "string",
            "x" to "number",
            "y" to "number",
            "limit" to "integer",
            "offset" to "integer"
        ),
        mutating = false
    ) {
        active().inspectInventory(it, timeout)
    }
    tool(
        "inspect_entity",
        "Inspect an entity at x/y in a charted chunk. view=overview returns status, recipe, fluids and temperatures, burner, pending inventory delivery requests, actual inserter endpoints and inventory summaries indexed by the game's actual inventory IDs. limit/offset page each inventory's item list. view=configuration reads settings and lists writable_settings for configure_entity. Other players' characters are excluded.",
        schema("x" to "number", "y" to "number", "view" to "string", "limit" to "integer", "offset" to "integer"),
        listOf("x", "y"),
        mutating = false
    ) {
        active().inspectEntity(it, timeout)
    }
    tool(
        "prototypes",
        "Read static prototype data by kind: items, entities or fluids. Optional exact name; otherwise paginated internal-name ordering. Entity prototypes include collision boxes and alternative placement items. Use recipes and technologies for those domains.",
        schema("kind" to "string", "name" to "string", "limit" to "integer", "offset" to "integer"),
        listOf("kind"),
        mutating = false
    ) {
        active().readPrototypes(it, timeout)
    }
    tool(
        "technologies",
        "Read live force technology state, prerequisites, missing prerequisites, science costs, effects and research triggers. Optional exact name or literal internal-name search. available=true filters enabled, unresearched technologies whose prerequisites are researched; this does not guarantee science supply or completion of trigger research.",
        schema(
            "name" to "string",
            "search" to "string",
            "available" to "boolean",
            "limit" to "integer",
            "offset" to "integer"
        ),
        mutating = false
    ) {
        active().technologies(it, timeout)
    }
    tool(
        "inspect_blueprint",
        "Analyze a single blueprint without importing it or changing the cursor. Supply string or data (blueprint contents), or neither for the held configured blueprint. Returns entity/quality counts and prototype placement alternatives; include_data and include_string opt into decoded contents and encoded export. At most 128 entities/tiles and 24000 input bytes. Does not infer factory throughput or flow.",
        schema("string" to "string", "data" to "object", "include_data" to "boolean", "include_string" to "boolean"),
        mutating = false
    ) {
        active().inspectBlueprint(it, timeout)
    }
    val recipeProperties = schema(
        "name" to "string",
        "search" to "string",
        "category" to "string",
        "ingredient" to "string",
        "product" to "string",
        "include_hidden" to "boolean",
        "limit" to "integer",
        "offset" to "integer"
    )
    tool(
        "recipes",
        "Read recipe prototype contents, including ingredients, products and probabilities. Search is a literal case-insensitive internal-name substring; category, ingredient and product filters are exact. Hidden recipes are omitted unless include_hidden=true or name is explicit. Does not report current unlocks or inventory feasibility. Live paginated query, no cache.",
        recipeProperties,
        mutating = false
    ) {
        active().recipes(it, false, timeout)
    }
    tool(
        "available_recipes",
        "List recipes currently enabled for the local player's force, with the same filters as recipes. Enabled does not mean hand-craftable or affordable. Use recipes for content and crafting_check for current hand-crafting feasibility.",
        recipeProperties,
        mutating = false
    ) {
        active().recipes(it, true, timeout)
    }
    tool(
        "crafting_check",
        "Check whether the current player can queue a recipe/count without changing the game. Reports game-calculated craftable count, unlock technologies and direct ingredient shortfalls; automatic intermediate crafting is assessed by the game. Counts are crafts, not output items. Current craft tool accepts normal-quality hand crafting.",
        schema("recipe" to "string", "count" to "integer"),
        listOf("recipe"),
        mutating = false
    ) {
        active().craftingCheck(it, timeout)
    }
    tool(
        "inspect_area",
        "Inspect nearby entities, resources, production machines, ghosts, tiles or visibility (mode). Visibility returns explored/currently revealed flags for the intersecting chunks. Optional entity type (entities mode) and relation any/own/hostile; ghost results include ghost_name. Returns distance-sorted entities and grouped resource amounts/status diagnoses. Optional exact name/status filters, radius 1..64, default 16. Production includes only the local force. At most 512 entities in charted chunks; scan_truncated signals partial summaries and nearest-within-scan ordering. Paginated output.",
        schema(
            "mode" to "string",
            "type" to "string",
            "relation" to "string",
            "name" to "string",
            "status" to "string",
            "x" to "number",
            "y" to "number",
            "radius" to "integer",
            "limit" to "integer",
            "offset" to "integer"
        ),
        mutating = false
    ) {
        active().inspectArea(it, timeout)
    }
    tool(
        "inspect_networks",
        "Read friendly electric entity buffer/connection, logistic robot and item counts, and rolling-stock train state/basic schedule at x/y (default local position). Missing networks are false. Train schedules exclude groups/interrupts. Item and schedule results are paginated. No world changes.",
        schema("x" to "number", "y" to "number", "limit" to "integer", "offset" to "integer"),
        mutating = false
    ) {
        active().inspectNetworks(it, timeout)
    }
    tool(
        "preview_build",
        "Preview 1..128 placements without building: each placement has entity name, x/y, optional cardinal direction, item and quality. Returns independent player placement checks and aggregated inventory requirements. Does not simulate collisions between proposed placements or guarantee later success. Ambiguous placement items require explicit item selection.",
        buildJsonObject {
            putJsonObject("placements") {
                put("type", "array")
                put("minItems", 1)
                put("maxItems", 128)
                putJsonObject("items") {
                    put("type", "object")
                    put(
                        "properties",
                        schema(
                            "name" to "string",
                            "x" to "number",
                            "y" to "number",
                            "direction" to "string",
                            "item" to "string",
                            "quality" to "string"
                        )
                    )
                    put("required", JsonArray(listOf("name", "x", "y").map(::JsonPrimitive)))
                    put("additionalProperties", false)
                }
            }
        },
        listOf("placements"),
        mutating = false
    ) {
        active().previewBuild(it, timeout)
    }
    tool(
        "wait_for",
        "Wait without holding input for inventory_count (main inventory name/quality/count), crafting_empty, research_complete (name), entity_status (entity name/status/x/y), entity_inventory (item name/count, inventory name, x/y), or position (x/y, optional tolerance, default 0.5). MCP polls read-only snapshots and permits other calls. timeout_ms is 1..60000, default 10000. Returns matched=false on condition timeout; world/player context changes fail. No automatic actions or retries of mutations.",
        schema(
            "kind" to "string",
            "inventory" to "string",
            "tolerance" to "number",
            "name" to "string",
            "quality" to "string",
            "count" to "integer",
            "status" to "string",
            "x" to "number",
            "y" to "number",
            "timeout_ms" to "integer"
        ),
        listOf("kind"),
        mutating = false
    ) {
        active().waitFor(it)
    }
    tool(
        "players",
        "Read only public player summaries: ID, exact name, online status, character/respawn status, surface and position. Optionally filter by id or name. Does not expose inventories or select a player for other tools.",
        schema("id" to "integer", "name" to "string", "limit" to "integer", "offset" to "integer"),
        mutating = false
    ) {
        active().players(it, timeout)
    }
    tool(
        "inspect_space",
        "Read space state: section=platforms (default) lists this force's visible platforms, hubs, orbit/transit, speed and pending deletion; locations reports prototype properties and force unlocks; connections reports route endpoints and lengths; surfaces lists accessible existing surfaces. Optional exact name and pagination. Does not create surfaces or change the world.",
        schema("section" to "string", "name" to "string", "limit" to "integer", "offset" to "integer"),
        mutating = false
    ) {
        active().inspectSpace(it, timeout)
    }
    tool(
        "remote_view",
        "Enter remote view at x/y (default current viewed position), optionally on an existing accessible surface by name. zoom defaults to 1. action=exit returns to the physical controller if the game permits it; exit accepts no other arguments. Returns after synchronized view confirmation, without moving the character. Other position-based tools operate on the viewed surface. Remote construction uses ghosts and the game's visibility rules.",
        schema("action" to "string", "surface" to "string", "x" to "number", "y" to "number", "zoom" to "number")
    ) {
        active().remoteView(it, timeout)
    }
    tool(
        "create_platform",
        "Order a new space platform through the game's creation dialog, using its default name, orbit and starter-pack quality. Requires space platforms unlocked and remote view. Returns the new platform index when the order is confirmed; starter-pack delivery continues afterwards. Inspect platforms before retrying an uncertain result."
    ) {
        active().createPlatform(timeout)
    }
    tool(
        "launch_rocket",
        "Launch the ready rocket from the currently opened friendly silo to an owned platform in orbit above it. platform is an index from inspect_space. transport_player defaults to false (cargo launch); true boards the local character, subject to the game's inventory restrictions. Returns when launch is accepted, without waiting for the cargo pod to arrive.",
        schema("platform" to "integer", "transport_player" to "boolean"),
        listOf("platform")
    ) {
        active().launchRocket(
            it.getValue("platform").jsonPrimitive.int,
            it["transport_player"]?.jsonPrimitive?.boolean ?: false,
            timeout
        )
    }
    tool(
        "platform_schedule",
        "Manage the opened owned platform hub: action=append adds one location; action=pause or action=resume changes its operating mode; action=go selects an existing one-based index and starts travel; action=remove deletes that index. Confirms synchronized acceptance, not arrival. inspect_space returns the schedule and current journey state.",
        schema("action" to "string", "location" to "string", "index" to "integer")
    ) {
        when (val action = it["action"]?.jsonPrimitive?.content ?: "append") {
            "remove" -> active().indexedGuiAction(
                "platform_remove",
                it["index"]?.jsonPrimitive?.int ?: error("index is required"),
                true,
                timeout
            )

            "go" -> active().indexedGuiAction(
                "platform_go",
                it["index"]?.jsonPrimitive?.int ?: error("index is required"),
                true,
                timeout
            )

            "append" -> active().appendPlatformStop(
                it["location"]?.jsonPrimitive?.content ?: error("location is required"), timeout
            )

            "pause", "resume" -> {
                require("location" !in it) { "location is only accepted for append" }
                active().setPlatformPaused(action == "pause", timeout)
            }

            else -> error("action must be append, go, remove, pause or resume")
        }
    }
    tool(
        "logistic_section",
        "Enable or disable one manual logistic request section of the currently opened owned entity. index is from inspect_entity(view=configuration). Automatic sections are controlled by the game. Existing section activation is preserved by configure_entity when replacing requests.",
        schema("index" to "integer", "active" to "boolean"),
        listOf("index", "active")
    ) {
        active().indexedGuiAction(
            "logistic_section",
            it.getValue("index").jsonPrimitive.int,
            it.getValue("active").jsonPrimitive.boolean,
            timeout
        )
    }
    tool(
        "land_player",
        "Drop the local character from its platform to the planet currently being orbited. Requires an onboard character and a planetary orbit. Returns after physical arrival, with no held input or later MCP message required."
    ) {
        active().landPlayer(30000)
    }
    tool(
        "research",
        "Queue an available technology through the player's normal research action. Returns its queue position after simulation confirmation; does not wait for research completion. An already queued technology is left unchanged.",
        schema("name" to "string"),
        listOf("name")
    ) {
        active().submitResearch(it.getValue("name").jsonPrimitive.content, timeout)
    }
    tool(
        "move",
        "Take one finite walking step in a cardinal direction. Waits for simulation movement and release. A blocked step reports a timeout.",
        schema("direction" to "string"),
        listOf("direction")
    ) { active().basicAction("move", it, timeout) }
    tool("clear_cursor", "Return the held item to inventory and clear a cursor ghost; waits for confirmation.") {
        active().basicAction("clear_cursor", it, timeout)
    }
    tool(
        "quickbar", "Take the item in a slot of the first active quickbar into the cursor.",
        schema("slot" to "integer"), listOf("slot")
    ) { active().basicAction("quickbar", it, timeout) }
    tool(
        "drive",
        "Apply one finite driving input to the occupied car/tank: forward, backward, left or right. Returns after vehicle motion and input release. Steering may require motion; fuel and an unobstructed path are required. The vehicle can coast after release.",
        schema("direction" to "string"),
        listOf("direction")
    ) { active().basicAction("drive", it, timeout) }
    tool(
        "quickbar_page",
        "Select the next top quickbar page, or the previous page when previous=true. Confirms the synchronized page change.",
        schema("previous" to "boolean")
    ) { active().basicAction("quickbar_page", it, timeout) }
    tool(
        "use_item",
        "Use the held capsule at world coordinates, respecting its normal range and cooldown. Returns on the game's item-use event, without waiting for delayed effects.",
        schema("x" to "number", "y" to "number"),
        listOf("x", "y")
    ) { active().basicAction("use_item", it, timeout) }
    tool(
        "build",
        "Place the held entity item or seed at world coordinates, optionally as a ghost. Respects player reach and inventory.",
        schema("x" to "number", "y" to "number", "ghost" to "boolean"),
        listOf("x", "y")
    ) { active().basicAction("build", it, timeout) }
    tool(
        "rotate", "Rotate a reachable entity at world coordinates.",
        schema("x" to "number", "y" to "number", "reverse" to "boolean"), listOf("x", "y")
    ) { active().basicAction("rotate", it, timeout) }
    tool(
        "pipette", "Select the placement item for an entity at world coordinates.",
        schema("x" to "number", "y" to "number"), listOf("x", "y")
    ) { active().basicAction("pipette", it, timeout) }
    tool(
        "drop_item", "Drop one held item at world coordinates.",
        schema("x" to "number", "y" to "number"), listOf("x", "y")
    ) { active().basicAction("drop_item", it, timeout) }
    tool("vehicle", "Enter a nearby vehicle or leave the current vehicle; waits for simulation confirmation.") {
        active().basicAction("vehicle", it, timeout)
    }
    tool(
        "switch_weapon",
        "Select the next equipped weapon and wait for simulation confirmation. Requires at least two equipped weapons and a character outside a vehicle."
    ) {
        active().basicAction("switch_weapon", it, timeout)
    }
    tool(
        "craft",
        "Queue hand crafting by recipe name and count. Returns after the simulation accepts the queue entries, without waiting for items to finish.",
        schema("recipe" to "string", "count" to "integer"),
        listOf("recipe", "count")
    ) {
        active().craft(it.getValue("recipe").jsonPrimitive.content, it.getValue("count").jsonPrimitive.int, timeout)
    }
    tool(
        "mine",
        "Mine one resource yield or one reachable entity at world coordinates. In remote view, confirms a deconstruction order or ghost removal; robot/platform completion may follow. Releases mining in the game even if MCP exits.",
        schema("x" to "number", "y" to "number"),
        listOf("x", "y")
    ) {
        active().finiteControl(
            "mine",
            it.getValue("x").jsonPrimitive.double,
            it.getValue("y").jsonPrimitive.double,
            timeout
        )
    }
    tool(
        "transfer",
        "Transfer the held stack into a reachable entity, or collect its contents when the cursor is empty. Optionally transfer half. Confirms the normal player transfer event.",
        schema("x" to "number", "y" to "number", "half" to "boolean"),
        listOf("x", "y")
    ) { active().basicAction("transfer", it, timeout) }
    tool(
        "transfer_items",
        "Transfer an exact requested count (1..100) between the main inventory and a reachable friendly entity inventory (default chest). direction is deposit or withdraw. Requires an empty cursor; skips filtered destination slots. Uses confirmed normal slot actions, reports actual/partial counts, and returns cursor leftovers on normal completion. Non-atomic: timeout/cancellation may leave cursor items or an uncertain final operation; inspect before retrying. timeout_ms defaults to 30000, maximum 60000.",
        schema(
            "name" to "string",
            "quality" to "string",
            "count" to "integer",
            "direction" to "string",
            "inventory" to "string",
            "x" to "number",
            "y" to "number",
            "timeout_ms" to "integer"
        ),
        listOf("name", "count", "direction", "x", "y")
    ) {
        active().transferItems(it)
    }
    tool(
        "open_entity", "Open the interface of a reachable entity. Requires an empty cursor.",
        schema("x" to "number", "y" to "number"), listOf("x", "y")
    ) { active().basicAction("open_entity", it, timeout) }
    tool(
        "take_item",
        "Take an owned item stack from the character inventory into the cursor. Requires an empty cursor; quality defaults to normal.",
        schema("name" to "string", "quality" to "string"),
        listOf("name")
    ) {
        active().inventoryItem(
            it.getValue("name").jsonPrimitive.content,
            it["quality"]?.jsonPrimitive?.content ?: "normal",
            false,
            timeout
        )
    }
    tool(
        "equip",
        "Equip owned armor, a gun or ammunition from its exact main-inventory slot, including replenishing equipped ammunition. Requires an empty cursor; quality defaults to normal.",
        schema("name" to "string", "quality" to "string"),
        listOf("name")
    ) {
        active().inventoryItem(
            it.getValue("name").jsonPrimitive.content,
            it["quality"]?.jsonPrimitive?.content ?: "normal",
            true,
            timeout
        )
    }
    tool(
        "inventory_slot",
        "Manipulate a precise one-based inventory slot: take, take_half, put, put_one, swap, transfer to the other inventory, set_filter, clear_filter, or open its item interface. Supports character inventories (including armor/ammo) and inventories of the currently opened reachable entity. Inventory defaults to character_main; inspect inventories before choosing slots. send_to_planet with inventory=hub_main sends one stack from an orbiting platform to its planet and returns before delivery.",
        schema("inventory" to "string", "slot" to "integer", "action" to "string"),
        listOf("slot", "action")
    ) {
        active().inventorySlot(it, timeout)
    }
    tool(
        "equipment",
        "Place held equipment or take installed equipment at zero-based x/y in the currently opened armor grid. Open the armor with inventory_slot action=open first. Uses normal player submission and confirms the grid change.",
        schema("action" to "string", "x" to "integer", "y" to "integer"),
        listOf("action", "x", "y")
    ) {
        active().equipment(
            it.getValue("action").jsonPrimitive.content,
            it.getValue("x").jsonPrimitive.int,
            it.getValue("y").jsonPrimitive.int,
            timeout
        )
    }
    tool(
        "pave",
        "Place held paving material at world coordinates using the current terrain brush. Consumes owned items and confirms the target tile changed.",
        schema("x" to "number", "y" to "number"),
        listOf("x", "y")
    ) {
        active().basicAction("pave", it, timeout)
    }
    tool(
        "place_blueprint",
        "Place the held configured blueprint as ghosts at world coordinates, using its current orientation. Returns after the game confirms placement; inspect the world for partial placement around obstacles.",
        schema("x" to "number", "y" to "number"),
        listOf("x", "y")
    ) {
        active().basicAction("place_blueprint", it, timeout)
    }
    tool(
        "import_blueprint",
        "Create/import a single blueprint into an empty cursor through the normal synchronized import action. Specify either string (Factorio blueprint string) or data (blueprint JSON contents with entities/tiles, optional label/version). At most 128 entities/tiles; omitted version uses the running game version.",
        schema("string" to "string", "data" to "object")
    ) {
        active().importBlueprint(it["string"]?.jsonPrimitive?.content, it["data"]?.jsonObject, timeout)
    }
    tool(
        "configure_entity",
        "Edit specified settings on a reachable friendly entity through its normal game controls; normal game dependencies between settings still apply. Opens the entity if needed; opening requires an empty cursor. No inventory space is needed. Use inspect_entity view=configuration for current values and writable_settings. Supported fields depend on entity type; the settings schema lists them. Other fields are rejected before any submission. Returns after synchronized confirmation. Changed settings leave the entity GUI open; unchanged requests preserve the current GUI.",
        buildJsonObject {
            for ((name, definition) in schema("x" to "number", "y" to "number")) put(name, definition)
            put("settings", entitySettingsSchema)
        },
        listOf("x", "y", "settings")
    ) {
        active().configureEntity(
            it.getValue("x").jsonPrimitive.double,
            it.getValue("y").jsonPrimitive.double,
            it.getValue("settings").jsonObject,
            timeout
        )
    }
    tool(
        "cancel_crafting",
        "Cancel a specified number of crafts from a one-based crafting queue entry. Returns after the normal cancellation event.",
        schema("index" to "integer", "count" to "integer"),
        listOf("index", "count")
    ) {
        active().cancelCrafting(it.getValue("index").jsonPrimitive.int, it.getValue("count").jsonPrimitive.int, timeout)
    }
    tool(
        "set_recipe",
        "Choose a normal-quality recipe in the currently opened, reachable assembling machine. Uses its normal recipe selector and waits for simulation confirmation.",
        schema("recipe" to "string"),
        listOf("recipe")
    ) { active().setRecipe(it.getValue("recipe").jsonPrimitive.content, timeout) }
    tool(
        "pickup",
        "Pick up nearby ground items, then release pickup automatically. Returns after the first pickup and simulation release."
    ) {
        active().finiteControl("pickup", null, null, timeout)
    }
    tool(
        "attack",
        "Fire the selected weapon at a world target, then stop after ammunition consumption or a confirmed hit. Projectile travel and capture effects may continue after return. Requires suitable ammunition and range.",
        schema("x" to "number", "y" to "number"),
        listOf("x", "y")
    ) {
        active().finiteControl(
            "attack",
            it.getValue("x").jsonPrimitive.double,
            it.getValue("y").jsonPrimitive.double,
            timeout
        )
    }
    tool(
        "copy_entity_settings",
        "Copy settings from an explicit reachable source to a reachable compatible target, each an object with x/y. Performs confirmed normal copy and paste actions under one action lock. Does not depend on a previous tool call's clipboard. A failure never retries the paste automatically.",
        schema("source" to "object", "target" to "object"),
        listOf("source", "target")
    ) {
        active().copyEntitySettings(it, timeout)
    }
    tool(
        "repair",
        "Repair a reachable damaged entity for one confirmed repair event, then release automatically. Requires a held repair pack.",
        schema("x" to "number", "y" to "number"),
        listOf("x", "y")
    ) {
        active().finiteControl(
            "repair",
            it.getValue("x").jsonPrimitive.double,
            it.getValue("y").jsonPrimitive.double,
            timeout
        )
    }
    try {
        serveTransports(server, options)
    } finally {
        withContext(NonCancellable) {
            requests.withLock { observed.values.forEach { it.close() }; observed.clear(); game = null }
            server.close()
        }
    }
}

private fun schema(vararg fields: Pair<String, String>): JsonObject = buildJsonObject {
    for ((name, type) in fields) putJsonObject(name) { put("type", type) }
}
