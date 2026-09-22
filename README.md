# factorio-mcp

An independent Linux native executable using the
official [Kotlin MCP SDK](https://github.com/modelcontextprotocol/kotlin-sdk). It serves **stdio and Streamable HTTP**
on the same machine as Factorio. Call `status` to identify an existing process and initialize its state observer,
then call `attach` after entering a single-player or multiplayer world. It never starts Factorio.

The native program exposes 57 MCP tools, using a world-local Lua scheduler and the normal synchronized client submission
paths
for player operations. The verified tool catalog is below; advanced configuration and every possible player action are
not yet covered.

## Build and configure

Build hosts: Linux x86-64 and Windows x86-64. Both produce a Linux x86-64 executable.
Requirements: a JDK, CMake 3.24 or newer, and Make on Linux or Ninja on Windows, available on `PATH`.
Gradle manages Kotlin/Native; CMake reuses its Clang, Linux sysroot, linker, and binary utilities. No separate GCC,
MSVC, Zig, or Linux cross-compiler installation is required. The initial build downloads dependencies,
Capstone instruction-decoder sources, official SDL2 headers, and the {fmt} formatting library.
The injector and detours are implemented in this repository; no third-party injection framework is used.

Use the standard Kotlin Multiplatform tasks directly from the IDE's Gradle tool window:

| Task | Purpose |
|------|---------|
| `runDebugExecutableLinuxX64` | Build and run the Debug executable on Linux. |
| `runReleaseExecutableLinuxX64` | Build and run the Release executable on Linux. |
| `linkDebugExecutableLinuxX64` | Build the Debug executable. |
| `linkReleaseExecutableLinuxX64` | Build the Release executable. |
| `linuxX64Binaries` | Build the target's binaries, including Debug and Release executables. |
| `assemble` | Assemble the project's outputs. |
| `build` | Assemble the project's outputs and run checks. |

From a terminal, use `./gradlew TASK` on Linux or `.\gradlew.bat TASK` in Windows PowerShell. For example,
`./gradlew runDebugExecutableLinuxX64` runs the application on Linux, while
`.\gradlew.bat linkReleaseExecutableLinuxX64` cross-compiles the Release executable on Windows.
The native CMake build is already a dependency of the Kotlin/Native link tasks.

The executables are `build/bin/linuxX64/debugExecutable/factorio-mcp.kexe` and
`build/bin/linuxX64/releaseExecutable/factorio-mcp.kexe`. Both are complete Linux executables that can be run directly.
Windows can build these binaries; running them requires Linux, including a Linux environment such as WSL.
Native fixture tests run through `check` or `build` on Linux and are skipped on Windows.
CMake build directories are separated by host under `build/native/`; Windows and WSL cannot share a CMake cache.
For WSL builds, keep the checkout and build directories on its Linux filesystem.
IDE import and Kotlin compilation only generate bindings from headers. CMake runs when linking a native executable
or explicitly invoking `buildNativeBridge` or `testNativeBridge`.

If a proxy is required, Gradle dependency downloads use Java's proxy properties in the user Gradle configuration.
CMake downloads use the `https_proxy` environment variable instead, for example `http://localhost:10808`.
Restart the Gradle daemon after changing its environment.

Configure your agent with the resulting executable's absolute path:

```json
{
  "mcpServers": {
    "factorio-mcp": {
      "command": "/absolute/path/factorio-mcp/build/bin/linuxX64/releaseExecutable/factorio-mcp.kexe",
      "args": [
        "--no-http"
      ]
    }
  }
}
```

The executable embeds its native resident library. It does not require a JVM, Python, Frida, a preload library, or a
separately distributed payload at runtime. It uses system native libraries, including glibc and libstdc++. Standard
output contains only MCP protocol messages; diagnostics go to standard error.

Linux must permit attaching to the target with `ptrace`. With Yama `ptrace_scope=1`, sharing a user ID is generally
insufficient for attaching to a Steam-launched game. The environment that starts MCP must supply suitable ptrace
permissions. The program does not change system security settings.

## Transports and interactive testing

With no arguments, both transports are enabled. HTTP binds only to `127.0.0.1:3000/mcp` and uses the SDK's
Streamable HTTP session handling and localhost Host/Origin validation. Both transports share the same attachment,
tool catalog and mutation lock. Disconnecting one protocol session does not detach the game or close other sessions.

- `--no-http`: stdio only; exits when the agent closes stdin.
- `--no-stdio`: HTTP only; suitable for a persistent local test process.
- `--http-port PORT`: change the HTTP port; `0` asks the OS for a free port. The listening URL is printed to stderr.
- Disabling both transports is an error. SIGINT/SIGTERM shuts down the process; with HTTP enabled, stdin EOF closes
  only stdio and HTTP continues running.

Start an independent test server:

```bash
build/bin/linuxX64/debugExecutable/factorio-mcp.kexe --no-stdio --http-port 3000
```

Initialize a standard MCP session using `curl` and `jq`:

```bash
jq -nc '{jsonrpc:"2.0",id:1,method:"initialize",params:{protocolVersion:"2025-11-25",capabilities:{},clientInfo:{name:"curl",version:"1"}}}' |
  curl -sS -D /tmp/factorio-mcp-headers -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    --data-binary @- http://127.0.0.1:3000/mcp
MCP_SESSION=$(awk 'tolower($1)=="mcp-session-id:" {gsub("\r", "", $2); print $2}' /tmp/factorio-mcp-headers)

jq -nc '{jsonrpc:"2.0",method:"notifications/initialized"}' |
  curl -sS -H "Mcp-Session-Id: $MCP_SESSION" -H 'MCP-Protocol-Version: 2025-11-25' \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    --data-binary @- http://127.0.0.1:3000/mcp

jq -nc '{jsonrpc:"2.0",id:2,method:"tools/list",params:{}}' |
  curl -sS -H "Mcp-Session-Id: $MCP_SESSION" -H 'MCP-Protocol-Version: 2025-11-25' \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    --data-binary @- http://127.0.0.1:3000/mcp
```

Use the same headers with `method: "tools/call"` and `params: {name: "status", arguments: {pid: ...}}`, then call
`attach` and the desired player tools. New request IDs belong to the HTTP session. After restarting MCP, initialize a
new session and call `status`/`attach` again; the compatible observer and current-world hook are reused.

Keep Factorio and the local test server running during ordinary tool development. Rebuild and restart only MCP when
its common code changes. A changed resident ABI requires a fresh Factorio process because resident upgrades are not
supported. Tests can connect to a persistent endpoint with `McpClient(url="http://127.0.0.1:3000/mcp")` from
`tests/mcp_http.py`; this helper sends the same HTTP messages as curl and does not own the external server.

## Tools

| Tool                                    | Behavior                                                                                                                                                                                                                                                                                 |
|-----------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `attach`                                | Bind the current world by `pid` or exact `process_name`. Requires the status observer and an active game. Repeated calls reuse the current binding; changing worlds requires another call.                                                                                               |
| `detach`                                | Close the MCP connection and release the session lock. The game and resident continue running.                                                                                                                                                                                           |
| `status`                                | Identify a PID or exact process name and install/reuse the state observer. Reports loading, menu, game, transition or unknown state, resident instance, world generation and Lua readiness. Does not bind Lua.                                                                           |
| `player`                                | Local status/position/reach, cursor, crafting/research queues, quickbar or equipment; select `section`.                                                                                                                                                                                  |
| `inventory`                             | Local or entity inventory, with quality-preserving summary or precise slot view including spoil state.                                                                                                                                                                                   |
| `inspect_entity`                        | Entity overview with actual inventories, pending item deliveries, fluids/temperatures, burner, recipe and inserter endpoints, or supported configuration.                                                                                                                                |
| `prototypes`                            | Item, entity or fluid prototype content; recipes and technologies have their own tools.                                                                                                                                                                                                  |
| `technologies`                          | Live technology state, prerequisites, science costs, effects, triggers and availability filter.                                                                                                                                                                                          |
| `inspect_blueprint`                     | Read-only blueprint decode/encode and bounded prototype/quality summary; no item allocation.                                                                                                                                                                                             |
| `players`                               | Read shallow player summaries, optionally by exact `id` or `name`: identity, connection/life status, physical surface and position. Cannot retarget other tools.                                                                                                                         |
| `recipes`                               | Search live recipe prototype contents by name substring, category, ingredient or product; paginated. Preserves ingredient/product details, including probabilities. No unlock or inventory state.                                                                                        |
| `available_recipes`                     | Search recipes currently enabled for the local force. Enabled does not imply hand-craftable or affordable.                                                                                                                                                                               |
| `crafting_check`                        | Check a requested craft count, game-calculated hand-crafting feasibility, direct material shortfalls and unlock technologies.                                                                                                                                                            |
| `inspect_area`                          | Distance-sort nearby entities or resources; summarize resource amounts or friendly production statuses. Explicitly reports truncated scans.                                                                                                                                              |
| `inspect_networks`                      | Read friendly electric connection/buffer, logistic robots/items, or a selected train and its basic schedule.                                                                                                                                                                             |
| `preview_build`                         | Independently check up to 128 proposed placements and aggregate material requirements without building.                                                                                                                                                                                  |
| `wait_for`                              | Passively wait for inventory count, crafting queue empty, research completion or entity status, with a deadline. Runs in MCP and permits concurrent reads/actions.                                                                                                                       |
| `transfer_items`                        | Deposit/withdraw 1..100 items between the main inventory and a friendly entity inventory using normal slot operations. Reports actual counts, partial results and uncertain operations.                                                                                                  |
| `inspect_space`                         | Inspect platform state, schedules and journeys, unlocked space locations, connections and accessible surfaces.                                                                                                                                                                           |
| `remote_view`                           | Enter a remote view on an accessible surface, or return to the physical character. Coordinates in other tools use the viewed surface.                                                                                                                                                    |
| `create_platform`, `launch_rocket`      | Order a platform, or launch a ready silo rocket with cargo or the local character to an orbiting platform. Launch confirmation does not wait for arrival.                                                                                                                                |
| `platform_schedule`                     | Append or remove a stop, select a stop to travel to, or pause/resume thrust. Confirms schedule/mode acceptance; use `inspect_space` for the journey.                                                                                                                                     |
| `land_player`                           | Descend from an orbiting platform to its planet and wait until the character leaves the cargo pod.                                                                                                                                                                                       |
| `logistic_section`                      | Enable/disable one manual request section of the opened entity.                                                                                                                                                                                                                          |
| `research`                              | Queue an available technology through the game's native action submission path and return its confirmed queue position. Already queued technologies remain unchanged. Research completion itself is not awaited.                                                                         |
| `screenshot`                            | Render the local player's game view and GUI as a PNG and return an MCP image.                                                                                                                                                                                                            |
| `move`                                  | One finite cardinal walking step; return after movement and release. A blocked step times out.                                                                                                                                                                                           |
| `take_item`, `quickbar`, `clear_cursor` | Select an owned item stack by name/quality or an active quickbar slot, and return cursor contents to inventory.                                                                                                                                                                          |
| `equip`, `switch_weapon`                | Equip owned armor, guns, or ammunition into available slots; cycle between equipped weapons. `equip` can replenish existing ammunition.                                                                                                                                                  |
| `inventory_slot`                        | Take/split/put/swap/transfer a precise inventory slot, set/clear slot filters, or open its item interface. Supports character equipment slots and opened entity inventories. `send_to_planet` sends one hub stack down to the planet being orbited; delivery continues after submission. |
| `equipment`                             | Take or place equipment at grid coordinates in the currently opened armor grid.                                                                                                                                                                                                          |
| `quickbar_page`                         | Select the next or previous top quickbar page through normal synchronized submission.                                                                                                                                                                                                    |
| `build`                                 | Place a held entity item, plant a seed, or place an entity ghost at world coordinates, subject to normal game checks.                                                                                                                                                                    |
| `pave`                                  | Place held paving material using the current terrain brush and confirm the target tile changed.                                                                                                                                                                                          |
| `place_blueprint`                       | Place a held, configured entity/tile blueprint as ghosts. Uses its current orientation; inspect nearby entities for partial placement around obstacles.                                                                                                                                  |
| `import_blueprint`                      | Create/import a blueprint from structured data or a Factorio blueprint string, using the running game version.                                                                                                                                                                           |
| `configure_entity`                      | Change supported machine settings through normal game controls: normal-quality recipe, inserter stack size/filter activation/filter mode, splitter priorities, train limit, platform construction requests and silo automatic requests. Unrelated settings are preserved.                |
| `rotate`, `pipette`                     | Rotate an existing reachable entity, or select its placement item.                                                                                                                                                                                                                       |
| `drop_item`, `pickup`                   | Drop one held item on empty ground, or pick up items within the character's pickup distance and stop.                                                                                                                                                                                    |
| `transfer`                              | Insert a held stack into an entity, optionally half; withdraw contents when the cursor is empty.                                                                                                                                                                                         |
| `craft`, `cancel_crafting`              | Queue a bounded hand-crafting count, or cancel crafts from a queue entry. These await queue acceptance, not item production.                                                                                                                                                             |
| `mine`                                  | Mine one resource yield or one reachable entity, then release mining. In remote view, confirm ghost removal or a deconstruction order; platform/robot work can finish later.                                                                                                             |
| `open_entity`, `set_recipe`             | Open a reachable entity interface and choose/change an assembling-machine recipe.                                                                                                                                                                                                        |
| `copy_entity_settings`                  | Copy settings from explicit `source` to `target`, each `{x,y}`, through confirmed ordinary copy/paste submissions.                                                                                                                                                                       |
| `attack`, `repair`                      | Attack releases input after ammunition consumption or a hit, including capture rockets; projectile effects can continue. Repair stops after a confirmed repair event.                                                                                                                    |
| `vehicle`, `drive`                      | Enter/leave a vehicle, or apply finite forward/backward/left/right driving input to a car/tank. Driving returns after motion and input release; the vehicle can coast.                                                                                                                   |
| `use_item`                              | Use a held capsule at a position; return on item-use acceptance rather than delayed effects.                                                                                                                                                                                             |

World operations check for an active world and a bound Lua bridge on every call; a missing binding returns an MCP error
requiring `attach`. Paused simulation can prevent tick tasks
from completing. Ordinary operations and screenshots automatically use the injected client's local player. There is no
online-player-count restriction or player override argument. Client ownership is resolved internally through the game's
LuaPlayer parser and local-player lookup, using developer debug information. The separate `players` tool exposes only
public summaries; it cannot select another character for control. Entity-inventory queries also reject foreign
characters. No product tool uses console commands or RCON, and server administrator privileges are not required.

`configure_entity` edits supplied fields through the game controls and uses the same recipe implementation as
`set_recipe`.
Unrelated settings are preserved. Normal game rules still apply: disabling splitter output priority also clears its
filter.
`inspect_entity` with `view=configuration` reports current settings and the entity's `writable_settings`.
Opening an entity requires an empty cursor; editing an already opened entity does not need spare inventory space.
Changed settings leave the entity GUI open; unchanged requests preserve the current GUI. Filter mode changes require
enabled filters (or `use_filters=true` in the same call).
Blueprint tools only inspect, import or place blueprints; other tools do not create temporary blueprints.

Configuration of recipe quality, inventory bars, filter contents, circuit/combinator parameters, station names,
logistic request contents and one-time delivery requests is currently unsupported. These fields are rejected before
submission. Existing values remain readable; `logistic_section` can toggle existing manual sections. These are gaps in
basic-operation coverage, alongside the unsupported operations listed above.

Platform schedules support basic manual routing, without wait conditions, interrupts or groups. To remain at a
destination,
keep only that destination in the schedule. Pausing stops thrust and schedule advancement; it does not instantly stop a
moving platform. Supported recipe settings and blueprint construction also work on other planets and platforms. The
acceptance tests cover specific synchronized paths, not a complete Space Age playthrough.

This version targets logically basic game operations. A basic operation may require several internal submissions;
exact item transfer and copying settings are examples. Autonomous navigation, production planning, gathering workflows
and batch construction remain outside the current scope.

Read tools have separate domain schemas. The former `query` tool is removed:

| Former query kind                                         | Current tool and arguments                                                                                                                                                                                                                            |
|-----------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `player`                                                  | `player`, default `section=status`; inventories are queried separately.                                                                                                                                                                               |
| `cursor`, `crafting`, `research`, `quickbar`, `equipment` | `player(section=...)`.                                                                                                                                                                                                                                |
| `inventory`, `entity_inventory`                           | `inventory(view=slots)` for indexed stacks, or default `view=summary`; x/y selects an entity, otherwise the local character. `name` is an official inventory name appropriate to the target; an entity may instead use `index` from `inspect_entity`. |
| `entity`, `entity_configuration`                          | `inspect_entity(x,y)` or `view=configuration`.                                                                                                                                                                                                        |
| `recipes`, `technologies`                                 | Dedicated `recipes` and `technologies` tools.                                                                                                                                                                                                         |
| `world`, `tiles`                                          | `inspect_area` or `inspect_area(mode=tiles)`; results use `items`.                                                                                                                                                                                    |
| `blueprint`, `blueprint_string`                           | `inspect_blueprint(include_data=true)` or `include_string=true`; decoded contents are under `data`. Can also accept a string or structured blueprint without holding/importing it.                                                                    |
| `items`, `entity_prototypes`, `fluids`                    | `prototypes(kind=items/entities/fluids)`.                                                                                                                                                                                                             |

`inspect_blueprint` accepts one blueprint with at most 128 combined entities/tiles and 24000 input bytes.
Its summary counts entity prototypes and quality, with per-entity alternative placement items from the live game.
It does not predict production flow, throughput or buildability. Decoding/encoding does not change the cursor.

`technologies(available=true)` means enabled, unresearched and with researched prerequisites. Science supply and
trigger-based research completion are separate conditions. Exact name and literal internal-name search are supported.

Pagination uses `offset` from zero and `limit` from 1 to 128. Nearby entity queries accept `radius` from 1 to 64.
Recipes are read directly; an agent does not need to navigate the Factoriopedia.

Recipe queries use live, paginated Lua reads. There is no recipe cache: prototype content is stable during a loaded
configuration, while enabled recipes and inventory feasibility are live state. Name search uses literal internal names,
not translated display names or regular expressions. Hidden recipes are omitted unless explicitly requested.
Use `available_recipes` and `crafting_check` for dynamic state.
Ingredient shortfalls are direct normal-quality requirements; they are not a recursive raw-material expansion. The
game's
craftable count accounts for eligible automatic intermediates. An enabled machine recipe may still be impossible to
craft
by hand.

Area inspection supports entity, resource, production, ghost and tile modes, plus entity type and own/hostile relation
filters.
It scans at most 512 matching entities within radius 64 on the current surface and only reports charted
chunks. Results are sorted and summarized in MCP. When `scan_truncated` is true, counts, resource totals and nearest
ordering describe only the scanned set. Resource summaries group by prototype, not by connected ore patch. Network
inspection reports the selected electric entity's buffer/connection, not whole-network power production. Train schedules
currently exclude interrupts and groups.

Build previews check each proposed position against the current world. They do not simulate collisions between proposed
placements or reserve materials. Material alternatives come from runtime prototypes; ambiguous choices are reported,
not resolved by assuming the entity and item have the same name. Conditions may change before a later build.

MCP serializes mutating tool calls so another action cannot interleave a counted transfer or settings copy/paste. Read
tools and passive waits
remain concurrent. Counted transfer skips filtered destination slots, respects inventory bars, and is not atomic: a
failure can leave a partially completed transfer. Confirmed transfers return leftovers from the cursor through the
normal
source-slot operation. If the final mutation is uncertain, no further mutation or automatic retry is attempted. After
cancellation or MCP exit, inspect the inventories and cursor before continuing. Each admitted slot action remains finite
and game-owned; future steps of the compound transfer remain in MCP.

Research uses the public `open_technology_gui` API to open a view, captures the actual technology GUI object through its
resolved paint method, submits through `TechnologyGui::startResearch` during the normal input phase, observes the
simulation's research queue, and closes the research view. Its remaining phases belong to the game even if MCP
disconnects. It does not preserve a previously open technology page. Already applied actions are not rolled back when a
call times out; inspect state before retrying.

MCP exposes no arbitrary Lua or raw keyboard/mouse tool. Native diagnostic commands retained from the initial
experiments are separate from the MCP interface.

## Resident and world lifetime

The executable reads ELF symbols and developer DWARF information from `/proc/PID/exe` before installation, resolves
runtime addresses using the process mappings, and verifies live code against the executable. The tested Steam
installation embeds this information in the game executable itself.

There are no game address tables, object field offsets, version allowlists, or instruction fingerprints. Semantic
function names and verified calling conventions remain adapter contracts. Debug information does not automatically
explain every optimized private ABI; unsupported or missing information causes attachment to fail.

Observation initially installs only application-loop and world-update detours. World attachment enables the Lua and
player-action adapters separately. `status` performs executable identification before any target modification; missing
required developer information returns `unrecognized`. Startup observation returns `loading` only after observing the
resolved loading-screen entry, and `unknown` when no supported safe point is observed. Repeat `status` to finish
initialization. A valid world attachment can return `ready=false` with `waiting_for=world_callback`; it does not wait
for an absent Lua event until a tool deadline expires. Binding can use the return of a verified scenario Lua maintenance
call,
so a world does not need a pre-existing tick handler. A stalled resident reports `unknown`, not an inferred menu state.

Installation uses our ptrace implementation to load the embedded library and install verified, instruction-aligned
detours. Capstone only decodes instructions for relocation. The loader restores its temporary tracing state and releases
the process. The resident then uses nonblocking local IPC; ordinary tool calls do not repeatedly attach a debugger.

MCP owns task generation, request correlation, planning and higher-level orchestration. The game retains only admitted
low-level task callbacks, a bounded submission FIFO, and the world-scoped completion state needed to execute and finish
them autonomously. The native library provides IPC, lifecycle hooks, and necessary game API adapters. An executed
callback is released; deferred completion queues retain request IDs. Readiness gates preserve FIFO order when an
operation cannot yet start. Small phase dispatchers run operation-specific observers and discard them after completion
or failure.

MCP assigns request IDs and correlates responses asynchronously. Unknown IDs are discarded. The game executes admitted
tasks independently of MCP, attempts nonblocking result delivery, and retains no result history or replay buffer.
Disconnecting does not cancel admitted game work. Reconnecting MCP inspects the current state and reuses the resident.

Destroying or replacing the world Lua VM discards its queues and observers. A nonblocking lifecycle notification fails
pending MCP game requests promptly; disconnected MCP instances need no notification history. Native lifecycle hooks
invalidate the old
Lua pointer before destruction. Query `status` and call `attach` explicitly for the new world; status never rebinds Lua.
No scheduler state is stored in
save-persistent `storage`.

**Detach is not an unload operation.** The native library, detours, and current-world Lua bridge intentionally remain
installed to support recovery after MCP crashes. Strict restoration to the pre-injection process image is therefore not
provided by this architecture. Game actions also intentionally change game state. Installation faults, incompatible
native ABIs, and native crashes are separate failure classes; successful reconnect tests do not establish recovery from
arbitrary native faults.

## Multiplayer boundaries

Prefer official Lua APIs, then game C++ submission APIs, then game-internal input. Direct reads are suitable for
client-side Lua. Mutation requires separate synchronization validation.

Writes use verified synchronized player submissions. Equipment preflight inspects existing stacks and empty slots;
it does not allocate synthetic items. The acceptance suite independently checks server state and desynchronization.

Basic controls use the game's named controls or C++ submission entry points. Building and rotation call the normal
`PlayerInputSource` submission methods. World coordinates are provided through a scoped cursor getter during the input
phase; equipment-grid coordinates likewise use the game's parser and a scoped grid cursor getter. No private fields are
written. GUI targets are captured from real constructors or update calls and located
through the game's own rectangle methods. Crafting exposes the game's ungrouped recipe list, locates and scrolls to the
exact
recipe ID, and restores the ordinary list after confirmation or failure. Machine recipe selection uses the native
selection callback directly. Neither path allocates a client-only Lua translation request. No screen coordinates or
object field offsets are stored in the adapter.

Finite mining, pickup, shooting, and repair evaluate scoped control values while the game processes input. Lua observers
release them after a matching game event and wait for the simulation to stop. Pointer entry created for automation is
released on completion, timeout, or Lua/world teardown. This changes only the game's internal input state and never
raises, minimizes, or focuses an OS window. Avoid concurrent manual input while an action is running; a human can
otherwise produce indistinguishable game events.

The catalog is not a complete model of every player action. Wire editing, deconstruction/upgrade planning, train
schedule editing/driving, research cancellation/reordering, blueprint books, and save/server
navigation remain unverified and are not advertised as tools. The current configuration adapter covers the listed entity
settings, not every entity-specific control.

## Project structure and build ownership

```text
src/commonMain/kotlin/       MCP, queries, task descriptions, Lua scheduling, portable IO
src/linuxX64Main/kotlin/     Process discovery, POSIX transport, native interop
src/linuxX64Main/native/     ELF/DWARF, ptrace loader, resident, detours, CMake
src/linuxX64Test/native/     Native lifecycle, IPC, and detour fixtures
src/nativeInterop/cinterop/ Kotlin/Native bridge definition
tests/                      Real-game integration infrastructure
examples/                   Legacy diagnostic Lua examples
```

Portable filesystem work uses kotlinx-io, JSON uses kotlinx-serialization, and asynchronous requests use
kotlinx-coroutines. Platform wrappers cover actual operating-system differences rather than duplicating portable
libraries. Documentation and comments are in English; Kotlin uses imports rather than unnecessary fully qualified names.
Native diagnostics use typed results and [{fmt}](https://fmt.dev/latest/get-started/); JSON serialization stays in
Kotlin or the official game helper.

The default KMP source-set and cinterop layout is retained. CMake owns the mixed C/C++/assembly targets, freestanding
loader payload, shared resident, embedding, and native fixtures. Gradle connects native builds to Kotlin/Native binary
linking and runs CTest without duplicating compiler recipes. IDE import and cinterop generation use the checked-in C
headers and do not require CMake. Linking executables or running native fixtures requires the Linux build tools listed
above; changes to the bridge archive invalidate Kotlin/Native linking.

## Verification

```bash
./gradlew testNativeBridge linkDebugExecutableLinuxX64
python3 tests/transports.py --injector build/bin/linuxX64/debugExecutable/factorio-mcp.kexe
python3 tests/lifecycle.py --injector build/bin/linuxX64/debugExecutable/factorio-mcp.kexe
python3 tests/integration.py --injector build/bin/linuxX64/debugExecutable/factorio-mcp.kexe
```

The resident integration suite starts an isolated localhost server and at most one graphical client using the installed
Steam game. It controls a non-admin local player and checks that ordinary queries reject player overrides. Native
fixtures distinguish local and foreign candidates; testing with two simultaneously connected clients remains
outstanding. It waits for the multiplayer client to exit before starting the main-menu client. Its launcher grants
ptrace permission for the test process tree. Graphical clients use the normal user configuration and data directory;
the local test server enables the bundled Space Age mods. The lifecycle suite additionally starts observation during
loading, checks observer-only and attached MCP crash recovery, attachment without a pre-existing tick handler, repeated
status/attach calls while admitted work continues, and world-change notifications.
The catalog suite checks concurrent reads, repeated
MCP connections, finite player operations, queued research and active mining completion after MCP SIGKILL, Lua VM
replacement, main-menu gating, and independent server state. Space Age cases cover platform orders, cargo launch, remote
construction/deconstruction, direct entity settings, rejection of unsupported delivery requests, basic platform
navigation, landing, cargo downlink, planting
and capture rockets. Evidence is written to `build/verification/resident/`; the
harness closes only the processes it created. Semantic checks distinguish recipe content from changing force
availability,
compare crafting feasibility with authoritative game results, exercise area/network reads and material previews, and
test
concurrent waits and cancellation on world replacement. Counted transfers verify exact deposit/withdrawal, source
exhaustion, barred destinations, and explicit return of cursor leftovers to the source inventory.

Native CTest covers decoder relocation, floating-point and integer result preservation, pointer-scope restoration, IPC
delivery failure, ptrace thread cleanup, native abort detection, and developer debug information validation. The
real-game Lua fixture runs 18 assertions covering FIFO readiness, multiple completion types, multiple completions in one
tick, callback errors, bounded queues, and failed IPC delivery.

The acceptance run checked all 57 tools, 7 native CTests, and 18 Lua scheduler assertions against the
installed Steam Factorio 2.0.77. It fails if an advertised tool was never invoked. Player operations are independently
checked on the local server. The server forces a full CRC, and both peers are checked for desynchronization reports.

For low-level experiments on a process **without an installed resident**:

```bash
factorio-mcp status PID
factorio-mcp run PID --lua examples/tick.lua --ticks 3
factorio-mcp detach PID
```

These legacy commands use temporary tracing and intentionally reject already patched code. They are not a resident
uninstall mechanism or a synchronized multiplayer mutation API.
The current catalog uses domain-specific reads and `copy_entity_settings` with explicit source/target positions.
Passive `wait_for` also supports target position/tolerance and entity-inventory item counts; it does not move or insert
items.
