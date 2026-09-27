package com.hiczp.factorio.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

internal fun createServer(game: GameSession): Server {
    val server =
        Server(
            Implementation("factorio-mcp", BuildVersion.VERSION),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
            instructions =
                """
                Attach to an existing, fully loaded Factorio client; this server never launches it.
                Prefer direct structured reads: world_overview for spatial surveys, world_query for native objects and related state, chat_read for retained messages, and ui_read for interface state or information without a supported direct reader. Use inspect member discovery and bounded paths instead of opening individual machine windows. Narrow filters, fields and pages to limit context. Use screenshot only for missing visual information, verification or requested images; do not capture after every action.
                Prefer ui_action with live selectors for UI, including offscreen controls. Prefer set_text on an available text field over dragging a slider. Use input for world controls or held mouse gestures; discover current bindings with input_bindings instead of assuming default keys or mouse meanings.
                Interpret native values and hierarchy yourself: missing, null and unavailable are distinct, widget flags are local, and controller context can differ from the physical character. Check truncation before treating results as complete.
                Action completion confirms dispatch, not a game outcome. Observe effects before dependent actions, especially in multiplayer. Do not blindly repeat a mutation whose result was lost or uncertain.
                Tools have no execution timeout. Send explicit MCP cancellation to stop unwanted work; HTTP disconnection alone is not guaranteed to cancel. All clients share this server's attachment.
                """
                    .trimIndent(),
        )

    fun register(
        name: String,
        description: String,
        properties: JsonObject = buildJsonObject {},
        required: List<String> = emptyList(),
        operation: suspend (JsonObject) -> CallToolResult,
    ) {
        server.addTool(
            name,
            description,
            ToolSchema(properties = properties, required = required),
        ) { request ->
            try {
                val args = request.arguments ?: buildJsonObject {}
                require(args.keys.all { it in properties }) { "Unknown argument" }
                operation(args)
            } catch (failure: CancellationException) {
                if (!currentCoroutineContext().isActive) throw failure
                CallToolResult(
                    content = listOf(TextContent(failure.message ?: "Tool aborted")),
                    isError = true,
                )
            } catch (failure: Exception) {
                CallToolResult(
                    content = listOf(TextContent(failure.message ?: "Operation failed")),
                    isError = true,
                )
            }
        }
    }

    fun tool(
        name: String,
        description: String,
        properties: JsonObject = buildJsonObject {},
        required: List<String> = emptyList(),
        operation: suspend (JsonObject) -> JsonObject,
    ) {
        register(name, description, properties, required) { args ->
            CallToolResult(content = listOf(TextContent(operation(args).toString())))
        }
    }

    tool(
        "status",
        "Check this server's attachment, PID, observed game state, ui_ready and pause status. Takes no arguments; never searches for or attaches to a process. A null paused value means unknown, not running. State does not guarantee tool availability; each call checks its own prerequisites. Detected process exit clears the attachment. input_transfer observes the local outgoing queue and the front batch blueprint import segment counters; absence is not import success. Use world_query for held item/ghost/record state.",
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
        "Inspect the live UI in menus, paused worlds and gameplay; prefer this over screenshots for UI discovery. Optionally select subtrees to reduce output. Returns native types, text, own flags and properties in postorder (children before parents), with snapshot-local IDs and parent links. IDs cannot target actions; a missing parent can indicate a root, selected root or truncated ancestor. Properties may include check_state, toggled, selected_index/options, slider/progress, switch, prototype/quality/quality_condition, element stack/item fields, numeric overlays and icon references into sprites. Options and selected_index are zero-based; null selection means none. Missing properties are unsupported or unavailable, not false or empty; inspect unavailable reasons and truncation flags. Null quality does not encode a filter condition; read quality_condition separately. Own visibility/enabled flags and bounds do not establish effective visibility, clipping, occlusion or action eligibility; hidden widgets may retain stale values. Native values are not interpreted as gameplay state. Sprite references are snapshot-local metadata, not rendered images; custom-painted content may require screenshot.",
        buildJsonObject {
            putJsonObject("max_nodes") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", 4096)
                put("default", 4096)
                put(
                    "description",
                    "Traversal limit, including when selector is set. An incomplete selector search fails; selected subtrees only narrow the returned nodes and optional properties.",
                )
            }
            putJsonObject("bounds") {
                put("type", "boolean")
                put("default", false)
                put(
                    "description",
                    "Include widget geometry when needed for positioning; geometry does not imply visibility.",
                )
            }
            put("selector", selectorSchema())
        },
    ) { args ->
        val limit = args["max_nodes"]?.intArgument() ?: 4096
        require(limit in 1..4096) { "max_nodes must be in 1..4096" }
        game.read(
            limit,
            args["bounds"]?.booleanArgument() ?: false,
            args["selector"]?.let(::parseSelector),
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
        "Discover native and mod control IDs, labels, descriptions and current keyboard/mouse/controller bindings; available in menus and paused worlds. Use bindings to construct input controls or ui_action press_key; control IDs themselves are not accepted as input. For linked controls use effective_bindings from binding_owner; otherwise use bindings. Include required modifiers. has_binding is not action eligibility; controller execution is unsupported. Results sort by ID; pages are fresh observations. Check snapshot_complete, next_offset and missing_ids/unobserved_ids before concluding a control is absent. Sends no input.",
        buildJsonObject {
            putJsonObject("ids") {
                put("type", "array")
                put("maxItems", 1024)
                putJsonObject("items") { put("type", "string") }
                put(
                    "description",
                    "Exact control IDs. Omit to discover controls; combines with search.",
                )
            }
            putJsonObject("search") {
                put("type", "string")
                put("description", "Case-insensitive substring of ID, label or description.")
            }
            putJsonObject("offset") {
                put("type", "integer")
                put("minimum", 0)
                put("maximum", 1024)
                put("default", 0)
                put(
                    "description",
                    "Zero-based offset into matching controls; use next_offset to continue.",
                )
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", 1024)
                put("default", 64)
            }
        },
    ) { args ->
        val ids =
            args["ids"]?.let { value ->
                val array = value as? JsonArray ?: error("ids must be an array")
                require(array.size <= 1024) { "ids exceeds bound" }
                array.map { it.stringArgument() }.toSet()
            }
        val search = args["search"]?.stringArgument()
        val offset = args["offset"]?.intArgument() ?: 0
        val limit = args["limit"]?.intArgument() ?: 64
        require(offset in 0..1024 && limit in 1..1024) {
            "offset must be in 0..1024 and limit in 1..1024"
        }
        game.bindings(ids, search, offset, limit)
    }
    tool(
        "world_overview",
        "Survey the viewport or a map area, optionally filtered by native entity types or prototype names. Choose grid summaries or individual entities with selected fields and recipe/fluid/filter details; use world_query for deeper inspection. Requires a loaded world/local player; supports pause, open UI and normal/remote views. Returns viewport/controller context and bounded live observations, which may include hidden data. UI occlusion is ignored. Check truncation, unscanned cells and coverage; tile samples are not all tiles. Does not move the camera or generate chunks.",
        worldOverviewSchema(),
    ) { args ->
        game.query(parseWorldOverview(args))
    }
    tool(
        "world_query",
        "Read live objects directly without opening their UI. Includes entity recipe/fluid/filter details, players, inventories and catalogs. Requires a loaded world/local player; works while paused or covered by UI. players lists current-world players; player defaults to self or selects a name/index. Controller and physical positions can differ. Player cursor_stack, cursor_ghost and cursor_record are distinct; inspect the relevant cursor reference for item condition or blueprint properties. Discover inventories before reading slots; quickbar is filters, not stock. Use inspect members to discover native attributes and admitted read methods, values for properties, and path/entries for related objects or collections; bounded references are not complete contents. Prototype definitions and force recipes/technologies preserve native availability, not inferred craftability. Narrow spatial queries by types/names and catalogs by names/search/recipe relations. Missing, nil and read errors remain distinct. Reads may expose hidden data; localized strings stay untranslated. Check truncation; pages are fresh observations, with no stable entity ordering.",
        worldQuerySchema(),
        listOf("selection"),
    ) { args ->
        game.query(parseWorldQuery(args))
    }
    tool(
        "chat_read",
        "Read chat and notifications retained by the local client's output console without opening UI. Requires a loaded world. text is the existing cached display text and may be empty/stale; raw preserves the localization expression. No sender/channel inference. Optional cursor returns later observations in this attachment; this is not a lossless subscription or server-wide log. Check history_lost, has_more and truncation. Messages unseen and evicted between reads cannot be recovered.",
        chatReadSchema(),
    ) { args ->
        game.readChat(args["after"]?.stringArgument(), args["limit"]?.intArgument() ?: 64)
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
        "Send finite keyboard/mouse combinations over consecutive local-player input ticks in a running world. Resolve current bindings with input_bindings; pass physical controls, not control IDs. Each operation presses its controls together, holds for ticks, then releases before the next operation; repeating a control in adjacent operations releases and represses it. Supports viewport positioning and held mouse motion without OS input/focus. Prefer ui_action for widgets; wheel routing over UI is not reliable in all states. Only one input runs at a time; busy calls fail unless stop_previous:true. Pause, unload, detach or cancellation aborts and releases held controls; UI/view changes alone do not abort. Returns dispatch progress, completed_operations and evaluated_ticks, never gameplay success or exact authoritative multiplayer effect timing.",
        inputSequenceProperties(),
        listOf("operations"),
    ) { args ->
        game.input(parseInputSequence(args))
    }
    register(
        "screenshot",
        "Capture a PNG of the next rendered game+UI frame for information unavailable through structured tools, visual verification or an explicitly requested image. Prefer ui_read/world_overview/world_query; avoid routine screenshots after actions. Works in menus and paused worlds without focusing the game. Requires DirectX and active rendering; minimized/suspended rendering can leave this call pending and block other queued tools until cancellation. Excludes desktop and OS/Steam overlays. Returns image plus dimensions, state and frame metadata; frequent captures can reduce performance.",
    ) {
        val snapshot = game.screenshot()
        val metadata = buildJsonObject {
            put("source", "factorio_directx_frame")
            put("scope", "game_and_ui")
            put("state", snapshot.state)
            put("ui_frame", snapshot.frame)
            put("width", snapshot.imageWidth)
            put("height", snapshot.imageHeight)
        }
        CallToolResult(
            content =
                listOf(
                    TextContent(metadata.toString()),
                    ImageContent(Base64.encode(checkNotNull(snapshot.image)), "image/png"),
                )
        )
    }
    return server
}
