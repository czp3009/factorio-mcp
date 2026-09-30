# factorio-mcp

[English](README.md) | [简体中文](README-zh-cn.md)

An MCP server that lets an AI agent read world data, send finite input sequences, and inspect and operate the UI of
a running Factorio client, including menus and mod windows. **No mod installation, RCON or in-game administrator
privileges are required.** Its generic interface can support any mod in principle and can be used with a client
connected to a multiplayer server.

**To operate the client, factorio-mcp injects code into the Factorio process. This may be considered cheating in
some contexts. If you are uncomfortable with this, do not use factorio-mcp.**

Currently, it supports **Windows x64**, with stdio and Streamable HTTP transports.

![I'm not even touching it](images/im-not-even-touching-it.jpg)

Tested with Factorio 2.0.77, including the official DLC.

## Installation

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
      "command": "/absolute/path/to/factorio-mcp/factorio-mcp",
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
"/absolute/path/to/factorio-mcp/factorio-mcp" --no-stdio --http-port 3000
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

After updating factorio-mcp or moving its files, **restart Factorio before using MCP again**.

For source builds, a compatible JDK, CMake, Ninja and the platform C++ build tools and SDK are required.
See [development instructions](https://github.com/czp3009/factorio-mcp/blob/master/development.md#build).

## Tools

| Tool             | Purpose                                                                          |
|------------------|----------------------------------------------------------------------------------|
| `status`         | Check attachment, game state and available blueprint import transfer progress.   |
| `attach`         | Connect to an existing client by PID or process name.                            |
| `detach`         | Disconnect and cancel pending tools without closing the game.                    |
| `ui_read`        | Read UI structure, text, flags and supported control values.                     |
| `ui_action`      | Click a widget, replace text or press a key.                                     |
| `screenshot`     | Get a PNG of the rendered game and UI.                                           |
| `input_bindings` | Map native/mod control IDs to current keyboard/mouse bindings.                   |
| `input`          | Execute finite keyboard/mouse combinations, including held mouse motion.         |
| `world_query`    | Inspect world objects and related properties, players, inventories and catalogs. |
| `world_overview` | Survey an area as a grid or filtered entities with selected details.             |
| `chat_read`      | Read retained local chat and notifications, with observation cursors.            |
| `chat_send`      | Submit a plain chat message as the local player.                                 |

See [tools.md](tools.md) for arguments, examples, output semantics and cancellation.

## Practical limits

- Clients connected to the same MCP process share its game attachment; `detach` cancels their pending tools.
  Closing an HTTP session does not detach the game. UI actions can activate normal menu commands, including Quit.
- Action completion means input was dispatched, not that a gameplay objective succeeded. Observe the result before
  a dependent action, especially in multiplayer.
- UI and world reads are bounded and report incomplete data. UI properties are supported selectively; custom-painted
  pixels are not reconstructed as structured controls. World reads can include hidden objects and do not reproduce
  remembered map contents. Visibility flags do not account for UI occlusion.
- `input` requires a running world. Use `ui_action` for menus and paused UI. Direct widget dragging/scrolling and
  controller input are unsupported; offscreen options can be selected directly, and running-world mouse input provides
  a dragging fallback. Wheel routing over UI is not reliable in all tested states.
- Windows screenshots require DirectX and exclude desktop/Steam overlays. Minimized or suspended rendering can leave a
  screenshot pending and delay other tools; cancel it if needed. Frequent capture can reduce game performance.
- Tools have no execution deadline. Explicit MCP cancellation is supported; automatic cancellation on HTTP socket
  closure is not guaranteed.
- Some states or properties may be unavailable. Game updates can require an MCP update; tested interfaces do not
  establish coverage of every mod or custom renderer.
- Chat reads cover messages retained by the local client, not a complete server log. Object inspection exposes
  supported readable properties and passive methods; it does not expose arbitrary Lua or all mod-private state.
