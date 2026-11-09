package com.hiczp.factorio.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.*

private fun toolResult(value: JsonObject, error: Boolean = false) =
    CallToolResult(
        content = listOf(TextContent(Json.encodeToString(JsonObject.serializer(), value))),
        structuredContent = value,
        isError = if (error) true else null,
    )

internal fun createServer(game: GameSession): Server {
    val server =
        Server(
            Implementation("factorio-mcp", BuildVersion.VERSION),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
            instructions =
                """
                Attach to an existing, fully loaded Factorio client; this server never launches it.
                Prefer structured reads: world_query for objects, properties and collections, world_overview for spatial summaries, chat_read for retained messages, and ui_read for interface state. world_query takes a query object: select roots, choose native fields, and batch relative reads for each root value or inspected entry. Discover attributes and admitted method signatures with inspect members; prototypes and defines expose native catalogs and enums. Bindings, players, inventory kinds/slots, explicit catalog names and inspect members return all matches by default; inspect values uses depth rather than entry counts. Spatial selections are count-bounded observations without continuation; check selection_complete and refine the area/filters or increase limit. Collection pages are fresh observations, not a frozen snapshot. Start UI discovery with max_depth:1 or 2, then narrow a live selector. fields:[] omits optional contents. Trees expand by depth, without node-list clipping or cross-call handles; native object references require paths. Explicit collection counts are positive 32-bit integers, not byte budgets; string slices count UTF-8 bytes. Each observation stays at one safe point; a page may still require a full source scan. Use screenshot for missing visual information, verification or requested images.
                Use input to combine keyboard/mouse chords, simultaneous holds and timed action sequences in one call, including continuous drags with turns. Overlap tick ranges to combine controls; offset them to sequence actions. Discover current keys/buttons/modifiers with input_bindings. Use ui_action with live selectors for widgets and set_text for editable text.
                Interpret native values, text, repetitions and hierarchy yourself. Missing, null and unavailable are distinct, widget flags are local, and controller context can differ from the physical character. Follow has_more/next positions for pages; inspect other truncation and depth-bound references independently.
                Every action, including chat, reports client dispatch/execution and cleanup. Completion does not confirm server acceptance, delivery or gameplay success. Network latency and prediction rollback can delay, change or undo effects; reads observe current state. Actions do not wait for a resulting state. Plan known input sequences together; observe results before making decisions that depend on them. Do not blindly repeat a mutation whose result was lost or uncertain.
                Tools have no execution timeout. Send explicit MCP cancellation to stop unwanted work; HTTP disconnection alone is not guaranteed to cancel. All clients share this server's attachment.
                All tools assume the player is not operating the game concurrently. Errors or unexpected results from concurrent player actions are outside the tool contract; tools do not detect or compensate for that interference.
                """
                    .trimIndent(),
        )

    fun register(
        name: String,
        description: String,
        properties: JsonObject = buildJsonObject {},
        required: List<String> = emptyList(),
        definitions: JsonObject? = null,
        operation: suspend (JsonObject) -> CallToolResult,
    ) {
        server.addTool(
            name,
            description,
            ToolSchema(properties = properties, required = required, defs = definitions),
        ) { request ->
            try {
                val args = request.arguments ?: buildJsonObject {}
                require(args.keys.all { it in properties }) { "Unknown argument" }
                operation(args)
            } catch (failure: CancellationException) {
                if (!currentCoroutineContext().isActive) throw failure
                toolResult(
                    buildJsonObject { put("error", failure.message ?: "Tool aborted") },
                    error = true,
                )
            } catch (failure: Exception) {
                toolResult(
                    buildJsonObject { put("error", failure.message ?: "Operation failed") },
                    error = true,
                )
            }
        }
    }

    fun tool(
        name: String,
        description: String,
        properties: JsonObject = buildJsonObject {},
        required: List<String> = emptyList(),
        definitions: JsonObject? = null,
        operation: suspend (JsonObject) -> JsonObject,
    ) {
        register(name, description, properties, required, definitions) { args -> toolResult(operation(args)) }
    }

    tool(
        "status",
        "Observe attachment, PID, game state and pause status without attaching or searching for a process. Null paused means unknown. Each tool checks its own prerequisites; status is not a capability guarantee. Detected exit clears the attachment.",
    ) {
        game.status()
    }
    tool(
        "attach",
        "Attach to an existing local Factorio client after startup loading finishes. Supply exactly one PID or executable process_name; ambiguous names fail. Matching developer debug information is required. Repeating attach to the same process reuses the attachment; detach before selecting another live process. After updating or moving factorio-mcp, restart Factorio before attaching. Returns attachment status.",
        buildJsonObject {
            putJsonObject("pid") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", Int.MAX_VALUE)
                put("description", "Existing client PID. Mutually exclusive with process_name.")
            }
            putJsonObject("process_name") {
                put("type", "string")
                put(
                    "description",
                    "Executable name, e.g. factorio.exe; must identify exactly one process. Mutually exclusive with pid.",
                )
            }
        },
    ) { args ->
        require(args.size == 1) { "Specify exactly one of pid or process_name" }
        val pid =
            args["pid"]?.intArgument()
                ?: run {
                    val name = args.getValue("process_name").stringArgument()
                    val matches = Platform.findProcesses(name)
                    check(matches.size == 1) {
                        "Expected one process named $name; found PIDs: $matches"
                    }
                    matches.single()
                }
        require(pid > 0) { "PID must be positive" }
        game.attach(pid)
    }
    tool(
        "detach",
        "Cancel pending tools across all clients, wait for cooperative cleanup, and release the attachment without closing Factorio. Takes no arguments and is repeatable. An inert library may remain loaded for reuse. A cleanup error retains ownership; retry detach before attaching elsewhere.",
    ) {
        game.detach()
    }
    tool(
        "ui_read",
        "Read live UI subtrees at one frontend safe point. Without selector, start at the game's UI root. Each match is an independent tree rooted at depth 0; flat preorder nodes preserve sibling order and snapshot-local parent IDs. Overlapping matches repeat their subtrees. Selectors are resolved afresh; IDs cannot address later calls. Start with max_depth:1 or 2, then select a branch. children_truncated appears only where real children are omitted by depth. fields optionally narrows contents. gui supplements native ancestry with existing script identity, references and requested attributes. Native properties include retained prototype identities, graph series and raw achievement-card state. Use collection for one widget's paged options, graph data, GUI value or stored text. Flags are own widget values, not effective visibility or action eligibility. Tooltips copy stored values without provider callbacks. Images expose retained identities only, without frames or simulation scripts.",
        buildJsonObject {
            put("collection", uiCollectionSchema())
            putJsonObject("fields") {
                put("type", "array")
                put("uniqueItems", true)
                putJsonObject("items") {
                    put("type", "string")
                    putJsonArray("enum") { UiField.entries.forEach { add(it.path) } }
                }
                put("description", "Optional result paths to capture. Omit for all; [] returns structure, script identity and own flags only. gui_fields selects attributes within gui.properties. Collection reads select their own source and reject fields.")
            }
            putJsonObject("bounds") {
                put("type", "boolean")
                put("default", false)
                put(
                    "description",
                    "Include widget geometry when needed for positioning; geometry does not imply visibility.",
                )
            }
            putJsonObject("max_depth") {
                put("type", "integer")
                put("minimum", 0)
                put("maximum", Int.MAX_VALUE)
                put("default", MAX_OBSERVATION_DEPTH)
                put("description", "Maximum child depth relative to each returned root. Zero returns roots only. Omitted or larger values use 65535. Reaching that depth truncates children normally; it is not an error. Boundary leaves have no children_truncated flag.")
            }
            put("selector", selectorSchema())
            putJsonObject("gui_fields") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "Readable LuaGuiElement attribute names when gui.properties is selected, or [\"*\"] for the target API's complete readable catalog. Defaults include caption, tooltip, elem_tooltip, tags, style, sprite variants, toggled, auto_toggle, number and elem identity. Empty list omits optional script attributes. Unsupported attributes report read_status.")
            }
            putJsonObject("gui_depth") {
                put("type", "integer")
                put("minimum", 0)
                put("maximum", Int.MAX_VALUE)
                put("default", 8)
                put("description", "Relative preview depth for each plain-table GUI attribute value; default 8. Values above 65535 use 65535. Nonempty deeper tables have depth_bound references with children_truncated; empty tables remain empty. Lua objects remain references. GUI collection paths can select nested values directly.")
            }
        },
    ) { args ->
        val collection = args["collection"]?.let(::parseUiCollection)
        require(collection == null || "selector" in args) { "collection requires a selector" }
        require(collection == null || args.keys.none { it in setOf("fields", "max_depth", "bounds", "gui_fields") }) {
            "collection returns one selected value and does not accept fields/max_depth/bounds/gui_fields"
        }
        val maxDepth = uiDepth(args["max_depth"]?.intArgument())
        game.read(
            args["bounds"]?.booleanArgument() ?: false,
            args["selector"]?.let(::parseSelector),
            args["gui_fields"]?.jsonArray?.map { it.stringArgument() } ?: defaultGuiFields,
            observationDepth(args["gui_depth"]?.intArgument(), 8),
            collection,
            maxDepth,
            parseUiFields(args["fields"]),
        )
    }
    tool(
        "ui_action",
        "Operate UI in menus, paused worlds and gameplay without OS input or focus. Prefer live widget actions over viewport clicks: existing offscreen controls can be selected without scrolling. click/set_text require a selector resolving exactly one target allowed by enabled ancestors and modal state; incomplete or ambiguous searches fail. click sends a complete mouse gesture; set_text replaces editable text subject to native restrictions. press_key sends one key chord including release to current focus, or focuses a selected widget first; it neither types character text nor holds across ticks. Use input_bindings for current keys, set_text for text, and input for holds. Returns dispatch:completed, not confirmation that the intended UI/game change occurred.",
        uiActionProperties(),
        listOf("action"),
    ) { args ->
        game.action(parseUiAction(args))
    }
    tool(
        "input_bindings",
        "Discover native and mod control IDs and current primary/secondary keyboard/mouse bindings; available in menus and paused worlds. Returns all matches unless limit is supplied; results sort by ID and pages are fresh observations. Use bindings for input controls or ui_action press_key; control IDs themselves are not input. For linked controls use effective_bindings from binding_owner. Include required modifiers. has_binding counts keyboard/mouse types, not action eligibility. Controller input is unsupported. Check snapshot_complete, has_more and missing_ids/unobserved_ids before concluding a control is absent. Sends no input.",
        buildJsonObject {
            putJsonObject("ids") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put(
                    "description",
                    "Exact control IDs. Omit to discover controls; combines with search.",
                )
            }
            putJsonObject("search") {
                put("type", "string")
                put("description", "Case-insensitive substring of the control ID.")
            }
            putJsonObject("offset") {
                put("type", "integer")
                put("minimum", 0)
                put("maximum", Int.MAX_VALUE)
                put("default", 0)
                put(
                    "description",
                    "Zero-based offset into matching controls; use next_offset to continue.",
                )
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", Int.MAX_VALUE)
                put("description", "Omit to return all matching controls. Set a positive count for a page.")
            }
        },
    ) { args ->
        val ids =
            args["ids"]?.let { value ->
                val array = value as? JsonArray ?: error("ids must be an array")
                array.map { it.stringArgument() }.toSet()
            }
        val search = args["search"]?.stringArgument()
        val offset = args["offset"]?.intArgument() ?: 0
        val limit = args["limit"]?.intArgument()
        require(offset >= 0 && (limit == null || limit > 0)) { "offset must be nonnegative and limit positive" }
        game.bindings(ids, search, offset, limit)
    }
    tool(
        "world_overview",
        "Summarize entities in the viewport or an explicit area with group_by and optional numeric aggregates, or read individual entities with selected fields and batched relative reads. Requires a loaded world/local player; works while paused or covered by UI. Grid pages use cell_limit and next_cell; retain the original explicit area, surface and cell_size to continue. Returns viewport/controller context, including hidden data; UI occlusion is ignored. Check truncation, coverage and excluded aggregate values. Grouping and calculations use observed properties without interpreting gameplay. Does not move the camera or generate chunks.",
        worldOverviewSchema(),
        definitions = worldReadDefinitions(),
    ) { args ->
        game.query(parseWorldOverview(args))
    }
    tool(
        "world_query",
        "Read current objects, properties, collections and native catalogs without moving the player or opening UI. Select entities by area/position and native filters; retain observed surface/position/unit_number for precise follow-ups. reads applies explicit native paths to each available selected root value, or each entry of an inspected collection, in one observation. inspect members discovers admitted passive attributes, methods and signatures; search narrows member names. Global game/prototypes/defines roots expose existing structures and enum values. Inventory discovery returns actual names and slots. Native references require paths; table previews expand by depth. Check selection_complete, nested truncation and collection continuation separately. Requires a loaded world/local player; works while paused and may include hidden data.",
        worldQuerySchema(),
        listOf("query"),
        worldQueryDefinitions(),
    ) { args ->
        game.query(parseWorldQuery(args.getValue("query").jsonObject))
    }
    tool(
        "chat_read",
        "Read retained local chat and notifications without opening UI; optionally wait for later messages. Requires a loaded world. Pass next_offset to continue the same player's history, including multiple messages at one native tick. Watermarks survive MCP restarts with unchanged history; reset them on world/player changes. text is cached and may be empty/stale; raw preserves its localization expression. player_name is present when separately stored. Use text_slice for one textual field; repeat a message's read_from with limit:1 to continue that field. Check history_lost, has_more and truncation. Evicted messages cannot be recovered; this is not a lossless subscription or server-wide log.",
        chatReadSchema(),
    ) { args ->
        val request = parseChatRead(args)
        game.readChat(request.offset, request.limit, request.timeout, request.textSlice)
    }
    tool(
        "chat_send",
        "Submit one plain message as the local player through normal game input. Requires a loaded world. No console commands or administrator privileges. Returns dispatch:completed, not server acceptance or delivery; game/mod rules still apply. Do not retry after an uncertain result.",
        chatSendSchema(),
        listOf("text"),
    ) { args ->
        game.sendChat(parseChatMessage(args))
    }
    tool(
        "input",
        "Execute coordinated keyboard/mouse actions in one call: multi-key chords, simultaneous holds, repeated presses and timed sequences. Combine a held button, multiple mouse paths and rotation presses to drag-build a belt route with turns. Schedule overlapping or successive actions on one shared tick clock; keep the whole gesture in one call. Nonempty timelines require a running world; discover current keys/buttons/modifiers with input_bindings. Prefer ui_action for widgets. World paths must stay onscreen. Releases its held keys/buttons on completion or abort; reports client execution, not gameplay success.",
        inputSequenceProperties(),
        listOf("timeline"),
    ) { args ->
        game.input(parseInputSequence(args))
    }
    tool(
        "set_rendering",
        "Request drawing suppression or restoration once. Simulation and structured tools remain available, including menus and paused worlds. Window/system events may change drawing again; minimization can still suspend it after enabling. Returns dispatch:completed after local execution, without waiting for a frame. No persistent mode or restoration on detach. Shares the screenshot queue; cancel a stalled capture explicitly. Requires an attached graphical client.",
        buildJsonObject {
            putJsonObject("enabled") {
                put("type", "boolean")
                put("description", "False suppresses drawing once; true clears that suppression once.")
            }
        },
        listOf("enabled"),
    ) { args ->
        game.setRendering(args.getValue("enabled").booleanArgument())
    }
    register(
        "screenshot",
        "Capture the next rendered game+UI frame, or cancel the active capture and wait for cleanup. Prefer structured reads; use images for missing visual information, verification or requested images. Start with JPG and max_height:720 to reduce image traffic; use PNG for lossless pixels. Source frames must fit 8192 pixels per side and 16777216 total pixels before resizing. Only one capture may be pending. Requires active rendering (OpenGL on Linux, DirectX on Windows); suspended rendering returns an error at frontend admission. A stalled frontend or graphics backend can delay capture. Excludes desktop and OS/Steam overlays. MCP request cancellation also stops capture.",
        buildJsonObject {
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") {
                    add("capture")
                    add("cancel")
                }
                put("default", "capture")
                put(
                    "description",
                    "Cancel stops the current capture without starting another; repeatable when idle.",
                )
            }
            putJsonObject("format") {
                put("type", "string")
                putJsonArray("enum") {
                    add("jpg")
                    add("png")
                }
                put("default", "jpg")
                put("description", "JPG uses lossy encoding; PNG is lossless.")
            }
            putJsonObject("quality") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", 100)
                put("default", ImageEncoder.DEFAULT_JPEG_QUALITY)
                put("description", "JPG capture only. Higher values retain more detail and generally produce larger images. Omit to use the default.")
            }
            putJsonObject("max_height") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", Int.MAX_VALUE)
                put("description", "Shrink after source capture, preserving aspect ratio; cannot bypass the source-frame budget. Never enlarges or crops. Omit for original dimensions.")
            }
        },
    ) { args ->
        val action = args["action"]?.stringArgument() ?: "capture"
        require(action == "capture" || action == "cancel") { "action must be capture or cancel" }
        val format = ImageFormat.parse(args["format"]?.stringArgument() ?: "jpg")
        val maxHeight = args["max_height"]?.intArgument()
        val quality = args["quality"]?.intArgument()
        require(maxHeight == null || maxHeight > 0) { "max_height must be positive" }
        if (action == "cancel") {
            require(args.keys.none { it == "format" || it == "max_height" || it == "quality" }) {
                "format, max_height and quality apply only to capture"
            }
            return@register toolResult(game.cancelScreenshot())
        }
        val (snapshot, image) = game.screenshot(format, maxHeight, quality)
        val metadata = buildJsonObject {
            put("source", "factorio_rendered_frame")
            put("scope", "game_and_ui")
            put("state", snapshot.state)
            put("ui_frame", snapshot.frame)
            put("source_width", checkNotNull(snapshot.image).width)
            put("source_height", snapshot.image.height)
            put("width", image.width)
            put("height", image.height)
            put("format", image.format.argument)
            image.quality?.let { put("quality", it) }
        }
        CallToolResult(
            structuredContent = metadata,
            content =
                listOf(
                    TextContent(Json.encodeToString(JsonObject.serializer(), metadata)),
                    ImageContent(Base64.encode(image.bytes), image.format.mimeType),
                ),
        )
    }
    return server
}
