# factorio-mcp

[English](README.md) | [简体中文](README-zh-cn.md)

An MCP server that lets an AI agent read world data, execute keyboard/mouse timelines, and inspect and operate the UI of
a running Factorio client, including menus and mod windows. **No mod installation, RCON or in-game administrator
privileges are required.** Its generic interface can support any mod in principle and can be used with a client
connected to a multiplayer server.

**To operate the client, factorio-mcp injects code into the Factorio process. This may be considered cheating in
some contexts. If you are uncomfortable with this, do not use factorio-mcp.**

Supports **Linux x64 and Windows x64**, with stdio and Streamable HTTP transports.

![I'm not even touching it](images/im-not-even-touching-it.jpg)

Tested with Factorio 2.1.24, including the official DLC. Older game versions are not supported.

## Installation

Linux requires libpng (`libpng16.so.16`) and libjpeg-turbo (`libturbojpeg.so.0`). On Debian/Ubuntu, install them with
`sudo apt install libpng16-16t64 libturbojpeg0` (older releases may use the package names `libpng16-16` and `libturbojpeg`).

Choose **stdio** or **Streamable HTTP** below. Each supports downloading from GitHub Releases or using npm (via npx).
Adjust the JSON configuration format to your MCP client's requirements.

### stdio

Choose one of the following configurations for your MCP client.

#### Download from GitHub Releases

Download the ZIP for your operating system and architecture from the
[latest GitHub Release](https://github.com/czp3009/factorio-mcp/releases/latest), then extract the entire archive to a
directory of your choice. Keep the executable and its accompanying dynamic libraries in the same directory.

Add the following to your MCP client's configuration, replacing `command` with the absolute path to the executable
in that extracted directory, including its actual filename:

```json
{
  "mcpServers": {
    "factorio-mcp": {
      "command": "/absolute/path/to/factorio-mcp/factorio-mcp.kexe",
      "args": [
        "--no-http"
      ]
    }
  }
}
```

On Windows, use the `.exe` path, for example `"command": "C:/path/to/factorio-mcp/factorio-mcp.exe"`.
Forward slashes avoid backslash escaping in JSON.

#### Use npm (via npx)

Install Node.js 24+ with npm, then use this configuration instead:

```json
{
  "mcpServers": {
    "factorio-mcp": {
      "command": "npx",
      "args": [
        "--yes",
        "@czp3009/factorio-mcp@latest",
        "--no-http"
      ]
    }
  }
}
```

On Windows, if your MCP client requires a shell to run npx, set `"command": "cmd"` and use
`"args": ["/c", "npx", "--yes", "@czp3009/factorio-mcp@latest", "--no-http"]`.

### Streamable HTTP

Choose one of the following ways to start the HTTP service, then configure your MCP client to connect to it.

#### Download from GitHub Releases

Download the ZIP for your operating system and architecture from the
[latest GitHub Release](https://github.com/czp3009/factorio-mcp/releases/latest), then extract the entire archive to a
directory of your choice. Keep the executable and its accompanying dynamic libraries in the same directory.

Replace the path with the absolute path to the extracted executable:

```bash
"/absolute/path/to/factorio-mcp/factorio-mcp.kexe" --no-stdio --http-port 3000
```

On Windows (PowerShell), use the `.exe` path and prefix it with `&`:

```powershell
& "C:\path\to\factorio-mcp\factorio-mcp.exe" --no-stdio --http-port 3000
```

#### Use npm (via npx)

Install Node.js 24+ with npm, then run:

```text
npx --yes @czp3009/factorio-mcp@latest --no-stdio --http-port 3000
```

#### Configure the MCP client

Keep the HTTP service running and add the following to your MCP client's configuration:

```json
{
  "mcpServers": {
    "factorio-mcp": {
      "type": "http",
      "url": "http://127.0.0.1:3000/mcp"
    }
  }
}
```

HTTP listens only on localhost. If you change the port, update both the startup command and the connection URL.

## Requirements

Start Factorio and wait for initial loading to finish before use.

On Linux, run MCP and Factorio as the same user and allow process debugging. If Yama blocks attachment to a
Steam-launched client, `sudo sysctl kernel.yama.ptrace_scope=0` allows same-user attachment until reboot. This changes
the system-wide ptrace policy; restore its previous value when finished. See the
[Linux kernel's Yama documentation](https://docs.kernel.org/admin-guide/LSM/Yama.html).


After updating factorio-mcp or moving its files, **restart Factorio before using MCP again**.

For source builds, a compatible JDK, CMake, Ninja and the platform C++ build tools and SDK are required.
See [development instructions](https://github.com/czp3009/factorio-mcp/blob/master/development.md#build).

## Tools

| Tool             | Purpose                                                                          |
|------------------|----------------------------------------------------------------------------------|
| `status`         | Check attachment, game state and pause status.                                  |
| `attach`         | Connect to an existing client by PID or process name.                            |
| `detach`         | Disconnect and cancel pending tools without closing the game.                    |
| `ui_read`        | Read live UI subtrees by selector and relative depth, with native and script properties. |
| `ui_action`      | Click a widget, replace text or press a key.                                     |
| `screenshot`     | Get a JPG or PNG of the game and UI, with height and JPEG quality options.        |
| `set_rendering` | Suppress or restore drawing once; window/system events may change it again. |
| `input_bindings` | Map native/mod control IDs to current keyboard/mouse bindings.                   |
| `input`          | Combine keyboard/mouse chords and action sequences, including continuous drags with turns. |
| `world_query`    | Read native objects, inventories and catalogs, with batched relative paths and member discovery. |
| `world_overview` | Summarize an area by native properties or read filtered entities with selected details. |
| `chat_read`      | Read retained local chat and notifications, with offsets and optional waiting.   |
| `chat_send`      | Submit a plain chat message as the local player.                                 |

See [tools.md](tools.md) for arguments, examples, output semantics and cancellation.

## Practical limits

- Avoid operating the game while tools are in use. Concurrent player actions can cause errors or unexpected results.
- Clients connected to the same MCP process share its game attachment; `detach` cancels their pending tools.
  Closing an HTTP session does not detach the game. UI actions can activate normal menu commands, including Quit.
- Action completion reports client execution, including chat submission. It does not confirm server acceptance or
  gameplay success; network latency and prediction rollback can delay or undo effects. Observe the result before
  a dependent action.
- Input bindings, players, inventory kinds/slots, object member catalogs and explicit name/field selections return
  all matches by default. Collections support caller-selected pages; spatial queries use bounded independent areas,
  and tree values use depth limits. Incomplete data is reported, and long text supports slices. Selection and
  truncation happen before observations leave the game process. MCP requests are limited to 16 MiB; clients may also
  limit response sizes. See [tools.md](tools.md#query-sizes).
- UI properties are supported selectively; custom-painted
  pixels are not reconstructed as structured controls. World reads can include hidden objects and do not reproduce
  remembered map contents. Visibility flags do not account for UI occlusion.
- UI reads include native image references, available icon registration names, and existing script UI attributes such
  as SpritePaths, tags and tooltip expressions. Image widgets expose their existing image type and static sprite identity
  when available. Original text and rich-text markers are preserved. Animation frames, simulation scripts, image layers
  and dynamically generated tooltips are not returned; reads do not hover or invoke tooltip callbacks.
  Native graph components expose stored series labels and samples, and achievement cards expose their retained
  prototype and raw state. Formatted axis labels, achievement descriptions and progress remain in the UI tree.
- Nonempty `input` timelines require a running world. Use `ui_action` for menus and paused UI. Direct widget dragging/scrolling and
  controller input are unsupported; offscreen options can be selected directly, and running-world mouse input provides
  a dragging fallback. Wheel input over UI can be unreliable.
- Screenshots require OpenGL on Linux or DirectX on Windows and exclude desktop/Steam overlays. When the game suspends
  rendering, capture returns an error once the frontend handles the request. Only one capture runs at a time;
  use `screenshot` with `action:"cancel"` to stop it. Frequent capture can reduce game performance.
- Tools have no execution deadline. Explicit MCP cancellation is supported; automatic cancellation on HTTP socket
  closure is not guaranteed.
- Some states or properties may be unavailable, including those from mods or custom renderers. Game updates can
  require an MCP update.
- Chat reads cover messages retained by the local client, not a complete server log. Object inspection exposes
  supported readable properties and passive methods; it does not expose arbitrary Lua or all mod-private state.
  Queries that can create game state, including force statistics getters and `LuaFlowStatistics` count attributes/methods,
  are unavailable.
