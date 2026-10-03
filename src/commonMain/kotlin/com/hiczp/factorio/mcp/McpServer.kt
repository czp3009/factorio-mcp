package com.hiczp.factorio.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.*

private fun toolResult(value: JsonObject, error: Boolean = false) = CallToolResult(
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
                Prefer structured reads: world_query for objects, properties and collections, world_overview for spatial observations, chat_read for retained messages, and ui_read for interface state. Discover inspect members and use bounded paths, filters, fields and pages. Use screenshot for missing visual information, verification or requested images.
                Use ui_action with live selectors for widgets and input for finite keyboard/mouse sequences. Discover current bindings with input_bindings. Use set_text for editable text.
                Interpret native values, text, repetitions and hierarchy yourself. Missing, null and unavailable are distinct, widget flags are local, and controller context can differ from the physical character. Check truncation before treating results as complete.
                Every action, including chat, reports client dispatch/execution and cleanup. Completion does not confirm server acceptance, delivery or gameplay success. Network latency and prediction rollback can delay, change or undo effects; reads observe current state. Actions do not wait for a resulting state. Observe effects before dependent actions. Do not blindly repeat a mutation whose result was lost or uncertain.
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
                toolResult(buildJsonObject { put("error", failure.message ?: "Tool aborted") }, error = true)
            } catch (failure: Exception) {
                toolResult(buildJsonObject { put("error", failure.message ?: "Operation failed") }, error = true)
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
            toolResult(operation(args))
        }
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
        "Read interface structure and native properties in menus, paused worlds and gameplay. Returns postorder nodes with text, own flags, parent links and snapshot-local IDs; actions require live selectors. Selected subtrees narrow output, while selector resolution requires a complete bounded traversal. Missing properties mean unsupported or unavailable; inspect reasons and truncation. Flags and bounds do not establish effective visibility or action eligibility. Options and selected_index are zero-based. Icon references are opaque and snapshot-local; original script GUI icon names are readable through world_query inspection. Interpret native values and hierarchy yourself. Use screenshot for missing visual information.",
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
        "Discover native and mod control IDs and current primary/secondary keyboard/mouse bindings; available in menus and paused worlds. Use bindings to construct input controls or ui_action press_key; control IDs themselves are not accepted as input. For linked controls use effective_bindings from binding_owner; otherwise use bindings. Include required modifiers. has_binding counts known keyboard/mouse types, not action eligibility. Controller slots and controller input are unsupported. Results sort by ID; pages are fresh observations. Check snapshot_complete, next_offset and missing_ids/unobserved_ids before concluding a control is absent. Sends no input.",
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
                put("description", "Case-insensitive substring of the control ID.")
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
        "Summarize entities in the viewport or an explicit area with group_by and optional numeric aggregates, or read individual entities with selected fields. Requires a loaded world/local player; works while paused or covered by UI. Returns bounded observations and viewport/controller context, including hidden data; UI occlusion is ignored. Check truncation, coverage and excluded aggregate values. Grouping and calculations use observed properties without interpreting gameplay. Does not move the camera or generate chunks.",
        worldOverviewSchema(),
    ) { args ->
        game.query(parseWorldOverview(args))
    }
    tool(
        "world_query",
        "Read native objects, properties, inventories, catalogs and related collections without opening UI. Requires a loaded world/local player; works while paused. Use inspect members for discoverable properties and passive methods, values for selected properties, and path/entries for related objects or collections. Discover inventory references before reading slots. Narrow selections, fields and pages. Missing, nil and read errors remain distinct; localized expressions stay untranslated. Reads may include hidden data. Check truncation; pages are fresh observations and entity ordering is not stable.",
        worldQuerySchema(),
        listOf("selection"),
    ) { args ->
        game.query(parseWorldQuery(args))
    }
    tool(
        "chat_read",
        "Read retained local chat and notifications without opening UI; optionally wait for later messages. Requires a loaded world. Pass next_offset to continue the same player's history, including multiple messages at one native tick. Watermarks survive MCP restarts with unchanged history; reset them on world/player changes. text is existing cached display text and may be empty/stale; raw preserves the localization expression. Check history_lost, has_more and boundary/snapshot truncation. This is bounded observation, not a lossless subscription or server-wide log; unseen evicted messages cannot be recovered.",
        chatReadSchema(),
    ) { args ->
        val request = parseChatRead(args)
        game.readChat(request.offset, request.limit, request.timeout)
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
        "Capture the next rendered game+UI frame as PNG, or cancel the active capture and wait for cleanup. Only one capture may be pending; another capture fails while busy. Prefer structured reads; use images for missing visual information, verification or requested images. Requires active rendering (DirectX on Windows, OpenGL on Linux); suspended rendering may leave capture pending and block queued tools. Excludes desktop and OS/Steam overlays. Cancellation also works through MCP request cancellation.",
        buildJsonObject {
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") {
                    add("capture")
                    add("cancel")
                }
                put("default", "capture")
                put("description", "Cancel stops the current capture without starting another; repeatable when idle.")
            }
        },
    ) { args ->
        val action = args["action"]?.stringArgument() ?: "capture"
        require(action == "capture" || action == "cancel") { "action must be capture or cancel" }
        if (action == "cancel") {
            return@register toolResult(game.cancelScreenshot())
        }
        val snapshot = game.screenshot()
        val metadata = buildJsonObject {
            put("source", "factorio_rendered_frame")
            put("scope", "game_and_ui")
            put("state", snapshot.state)
            put("ui_frame", snapshot.frame)
            put("width", snapshot.imageWidth)
            put("height", snapshot.imageHeight)
        }
        CallToolResult(
            structuredContent = metadata,
            content =
                listOf(
                    TextContent(Json.encodeToString(JsonObject.serializer(), metadata)),
                    ImageContent(Base64.encode(checkNotNull(snapshot.image)), "image/png"),
                )
        )
    }
    return server
}
