# factorio-mcp

An MCP server that lets an AI agent read world data, send finite input sequences, and inspect and operate the UI of
a running Factorio client, including menus and mod windows. Currently supports **Windows x64**, with stdio and
Streamable HTTP transports.

Tested with Factorio 2.0.77, including the official DLC.

## Requirements

- Factorio with its matching developer PDB alongside the executable.
- `factorio-mcp.exe` and `factorio_bridge.dll` in the same directory.

No mod installation or in-game administrator privileges are required. Start Factorio yourself and wait for initial
loading to finish. MCP does not manage game processes; UI actions can still activate normal menu commands such as Quit.

Building from source requires a compatible JDK, CMake, Ninja, MSVC C++ build components and the Windows SDK.
The Visual Studio IDE is unnecessary. See [development instructions](development.md#build).

## Connect

For a stdio MCP client, configure the full path to `factorio-mcp.exe` as the command and `--no-http` as its argument.
The client starts MCP for you.

For Streamable HTTP, run:

```powershell
.\factorio-mcp.exe --no-stdio --http-port 3000
```

Connect your MCP client to `http://127.0.0.1:3000/mcp`. HTTP listens on localhost only.

Both transports are enabled by default. Supported options are `--no-stdio`, `--no-http` and `--http-port PORT`.
The default port is 3000; port 0 selects a free port and reports the endpoint on stderr. At least one transport
must remain enabled. Diagnostic output uses stderr; stdout is reserved for stdio MCP messages.

Ask the agent to call `attach` with `process_name: "factorio.exe"`, then use `ui_read` to inspect the current UI.
If multiple Factorio processes are running, specify a `pid`. All clients of the same MCP server share one attachment;
`detach` cancels pending tools from every client. Closing an HTTP session does not detach the game.

Call `detach` when finished. After restarting MCP, call `attach` again. If Factorio exits, MCP stays running and clears
the old attachment when it detects the exit; explicitly attach again after starting a new game process.

After updating factorio-mcp or moving its files, **restart Factorio before attaching again**. Restarting MCP or
calling detach alone is insufficient.

## Tools

| Tool             | Purpose                                                                           |
|------------------|-----------------------------------------------------------------------------------|
| `status`         | Check the attachment, process ID and game state.                                  |
| `attach`         | Connect to an existing client by PID or process name.                             |
| `detach`         | Disconnect and cancel pending tools without closing the game.                     |
| `ui_read`        | Read UI structure, text, flags and supported control values.                      |
| `ui_action`      | Click a widget, replace text or press a key.                                      |
| `screenshot`     | Get a PNG of the rendered game and UI.                                            |
| `input_bindings` | Discover native/mod controls and their current bindings.                          |
| `input`          | Execute finite keyboard/mouse combinations, including held mouse motion.          |
| `world_query`    | Read player/world objects, inventories, quickbar, catalogs, recipes and research. |
| `world_overview` | Summarize the viewport or a bounded map area at a chosen grid scale.              |

See [tools.md](tools.md) for arguments, examples, output semantics and cancellation.

Prefer structured UI/world queries and direct widget actions. Use screenshots when structured data is insufficient,
visual verification is needed, or an image is requested, rather than after every action.

## Practical limits

- Action completion means input was dispatched, not that a gameplay objective succeeded. Observe the result before
  a dependent action, especially in multiplayer.
- UI and world reads are bounded and report incomplete data. UI properties are supported selectively; custom-painted
  pixels are not reconstructed as structured controls. World reads can include hidden objects and do not reproduce
  remembered map contents. Visibility flags do not account for UI occlusion.
- `input` requires a running world. Use `ui_action` for menus and paused UI. Direct widget dragging/scrolling and
  controller input are unsupported; offscreen options can be selected directly, and running-world mouse input provides
  a dragging fallback. Wheel routing over UI is not reliable in all tested states.
- Screenshots require DirectX and exclude desktop/Steam overlays. Minimized or suspended rendering can leave a
  screenshot pending and delay other tools; cancel it if needed. Frequent capture can reduce game performance.
- Tools have no execution deadline. Explicit MCP cancellation is supported; automatic cancellation on HTTP socket
  closure is not guaranteed.
- Some states or properties may be unavailable. Game updates can require an MCP update; tested interfaces do not
  establish coverage of every mod or custom renderer.
