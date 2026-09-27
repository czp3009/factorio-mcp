# Development

Contributor instructions for the current Windows implementation. User setup and the tool catalog belong in
[README](README.md); public arguments and result semantics belong in [tools.md](tools.md). Follow
[AGENTS.md](AGENTS.md) for project conventions. Research, plans, measurements and test-run evidence stay under ignored
`temp/`; they are not prerequisites for using or building a checkout.

## Build

Build on Windows x64 with a JDK compatible with the Gradle wrapper, CMake 3.24+ and Ninja on `PATH`.
Gradle downloads Kotlin/Native and uses its bundled compiler, assembler and linker; a separate LLVM installation is
unnecessary. Install Visual Studio Build Tools with the MSVC C++ build components and Windows SDK. The Visual Studio
IDE is unnecessary.

Run Gradle from an x64 developer command prompt with `INCLUDE` and `LIB` populated. Alternatively, ignored
`local.properties` can supply `native.INCLUDE`, `native.LIB` and `native.PATH` as semicolon-separated paths.
Use forward slashes in Java properties files. Each supplied value replaces the corresponding environment variable;
include the existing command search paths when overriding `native.PATH`.

```powershell
.\gradlew.bat build
```

`build` produces the binaries and runs local automated tests; `assemble` only builds, and `check` only runs verification
and builds its dependencies. No Factorio installation or running game is needed for these commands.
The executable and DLL are in `build/bin/mingwX64/debugExecutable/` and `build/bin/mingwX64/releaseExecutable/`.
Keep each executable with its adjacent DLL. To build one variant, use `linkDebugExecutableMingwX64` or
`linkReleaseExecutableMingwX64`.

| Command                      | Purpose                                                                |
|------------------------------|------------------------------------------------------------------------|
| `.\gradlew.bat assemble`     | Build binaries without running tests.                                  |
| `.\gradlew.bat build`        | Build product binaries and run all local automated tests.              |
| `.\gradlew.bat check`        | Run all local automated tests, building their dependencies.            |
| `.\gradlew.bat mingwX64Test` | Run common and Windows Kotlin tests, including stdio and Lua fixtures. |
| `.\gradlew.bat clean build`  | Rebuild and test from a clean output directory.                        |

Stop MCP before rebuilding its executable. Close any game that has loaded a DLL you intend to replace, and restart
Factorio after updating MCP before attaching again. Build tasks do not stop processes. Test/documentation-only changes
can reuse the running game and installed artifacts.

CMake owns native compilation and fixtures; `WindowsNativeBuild` invokes it from Gradle. Product output is scoped by
host and target under `build/native/`, with fixtures under `build/native-tests/`. Product builds do not compile test
fixtures. Ninja checks compiler-discovered dependencies, including SDK headers outside
the repository, on every native build. Unchanged native output does not overwrite an identical mapped runtime DLL.
Use a fresh native build directory when changing the compiler or SDK installation.

### Platform boundaries

The intended targets are Windows x64 (`mingwX64`), Linux x64 (`linuxX64`) and macOS ARM64 (`macosArm64`).
Only `mingwX64` is implemented. Its cinterop definition lives in `src/mingwX64Main/cinterop/bridge.def` and is selected
explicitly. Windows SDK configuration, DLL copying, WinHTTP and native fixtures belong to that target. Host-only
CMake/CTest tasks are registered on Windows x64, with task names derived from the target (`buildMingwX64Native`,
`buildMingwX64NativeFixtures`, `testMingwX64Native`) and separate host/target output directories. Other hosts do not
acquire these task dependencies merely by configuring the project. `WindowsNativeBuild`, `KonanWindows.cmake` and
the `native.INCLUDE`/`native.LIB`/`native.PATH` settings are specifically for this Windows adapter.

When implementing `linuxX64` or `macosArm64`, keep the standard KMP source sets, add the target's own
cinterop/toolchain/runtime
handling, and connect only the tests supported on the current host to `check`. Keep shared dependencies portable;
do not reuse Windows DLL names, SDK settings or architecture constants in shared configuration. No placeholder targets
or platform implementations are needed before those ports exist.

## GitHub releases

Run **Actions → Release → Run workflow** and select the branch or tag to build. The workflow file must exist on the
default branch for GitHub to offer this manual trigger. Set `version` in `build.gradle.kts` before publishing; the
workflow reads the evaluated Gradle project version, creates tag `v<version>` at the selected commit, and publishes
`factorio-mcp-<version>-<platform>.zip` as a Release attachment. An existing tag causes publication to fail instead
of replacing an existing release. Concurrent release runs are serialized.

`.github/release-platforms.json` controls the build matrix. Only Windows x64 is enabled. Once `linuxX64` or
`macosArm64` is implemented and tested, enable its row and supply any new platform-specific CI prerequisites.
Each enabled runner executes the standard `gradlew build`, including local automated tests, and stages files from
`build/bin/<target>/releaseExecutable/`. The Windows runner initializes its installed MSVC/Windows SDK environment;
compilation still uses Kotlin/Native's toolchain. No Factorio installation or manual acceptance environment is needed.

The publication job starts only after every enabled platform succeeds. It makes one ZIP per platform and uploads
them together using the workflow's `GITHUB_TOKEN`; only this job has `contents: write`. Repository policies must
allow Release and tag creation. Test reports remain available as Actions artifacts for seven days, including when
tests fail. Intermediate TAR files preserve executable permissions across jobs before final ZIP packaging.
Each TAR filename includes its platform; downloads merge into one directory so packaging uses the same paths for
one or multiple enabled platforms.

Two separate caches keep repeat builds from downloading the toolchain and dependencies again:

- Gradle's user-home `caches` and `wrapper` directories are keyed by runner OS/architecture, wrapper version and
  build/dependency configuration. Restore prefixes reuse downloads when build configuration changes. Metadata and
  compilation jobs have separate keys so a metadata-only cache cannot prevent saving the full build's dependencies.
- Kotlin/Native's `~/.konan` directory holds compiler distributions and native dependencies, including the bundled
  LLVM toolchain. Its keys separate OS/architecture and Kotlin version; restore prefixes never cross those boundaries.

Project `build/`, project `.gradle/` and test results are not restored as dependency caches. Cache misses download
dependencies normally. No custom token or configuration-cache encryption secret is required. GitHub may evict old
caches according to repository limits; this affects build time, not which checks run.

## Maintenance map

| Area             | Primary sources                                                                | Invariants                                                                                                  |
|------------------|--------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| Tool contract    | `McpServer.kt`, `UiSchema.kt`, `InputSchema.kt`, query parsers in `commonMain` | Keep advertised descriptions, validation and tools.md consistent; reject unsupported arguments.             |
| Lifecycle        | `GameSession.kt`, `InputTaskOwner.kt`                                          | Cooperative cleanup, one terminal result, shared detach/process-exit cleanup, retryable resource ownership. |
| Observation      | `UiSnapshot.kt`, common world readers                                          | Typed serialization, explicit missing/truncated data, raw native meaning, bounded work.                     |
| Windows boundary | `WindowsProcess.kt`, debug metadata/layout adapters                            | Matching PE/PDB; dynamically resolved members and virtual methods; checked process/IPC ownership.           |
| Resident         | `src/mingwX64Main/native/`                                                     | Necessary ABI adapters and safe-point hooks only; bounded commands and nonblocking notifications.           |
| Tests            | `src/commonTest/`, `src/mingwX64Test/`                                         | Portable unit tests, platform fixtures and explicitly enabled real-game acceptance remain distinct.         |

### Lifetimes and safe points

UI reads and selector resolution run at the verified frontend phase after update work and before rendering. Reacquire
the current root for every request. Complete selector discovery before collecting optional subtree properties, so
unrelated widgets cannot exhaust the selected result's resource budget. Native virtual positions, receiver adjustments,
member widths and enum values must come from matching debug information. A symbol's name alone does not validate its
ABI.

Frontend key gestures drain complete press/release events independently of simulation ticks. Widget clicks also clear
native capture acquired or transferred by callbacks; revalidate targets after callbacks. Finite input advances on
consecutive local-player input evaluations and can release controls at the frontend phase when ticks stop. Its world
identity and requester process handle bound its lifetime. Never dispatch through a destroyed world or replay an
uncertain mutation.

Single-phase requests and input admission share a serial queue. Admitted input completes through its own task mapping,
so other tools remain serviceable. Detach aborts active and queued work, waits for cleanup, then removes hooks.
Retain failed cleanup owners for retry. Separate successful unhook from local resource disposal; a retry must not
repeat an already completed unhook or discard the primary operation error.

The typed resident contract in `bridge.h` has no build negotiation or compatibility management. Reuse relies on the
restart-after-update requirement. A reconnect must reconcile an admitted orphan command before reusing the command
slot. An acquired mutex does not prove that the previous command finished. Remote bootstrap memory remains owned until
its thread is confirmed stopped or the target exits; a failed wait is not completion.

World readers reacquire the local player and Lua context at the same verified frontend phase, restore the Lua stack
on success/error, and pass query arguments as data to fixed bounded readers. Do not expose arbitrary Lua, console or
administrator commands. Inventory reads must not allocate synthetic items or test insertion capacity. Prototype
queries preserve structured untranslated strings rather than issuing client-only translation requests.

Object inspection loads the selected installation's `doc-html/runtime-api.json` lazily to discover native API
members and validate inspection requests. `RuntimeApiFile.kt` locates it relative to the selected executable;
users do not configure a separate path. Actual values come from the live game. A missing file prevents inspection
queries, while other query selectors remain available.

### Performance

UI output limits do not avoid the complete native tree walk. Selector lookup indexes the observed tree, deduplicates
matches and bounds repeated overlapping searches. Optional property collection runs only for selected subtrees.
Text replacement rechecks lifetimes after callbacks. Keep metadata caches confined to resolution and native-object
caches confined to one safe-point observation; visibility caches also belong to one query and the entity's actual
surface.

Screenshot readback and PNG encoding run synchronously in the rendering callback. A suspended capture can hold the
ordinary command queue until cancellation. Use on-demand capture; do not introduce tool timeouts or background
process-liveness polling to hide a stalled renderer.

## Tests

### Local fixtures

Run all local automated tests with the standard verification task:

```powershell
.\gradlew.bat check
```

The Kotlin suite runs common and Windows tests, including stdio and Lua fixtures. `mingwX64Test` runs just this suite;
`check` also runs the CMake/CTest fixtures through `testMingwX64Native`. Reports are under
`build/reports/tests/mingwX64Test/` and `build/native-tests/mingw_x64/mingwX64/Testing/Temporary/`.
Gradle excludes `*AcceptanceTest` and `OfflineQueryMetadataTest` regardless of environment variables. Run these
external checks explicitly from the built test executable as described below.

To run a single Kotlin test class, use
`.\gradlew.bat mingwX64Test --tests 'com.hiczp.factorio.mcp.StdioSmokeTest'`.
Use `--rerun-tasks` when deliberately repeating unchanged automated tests. A successful Gradle run establishes local
fixture coverage only; the external checks below are separate, subsequent steps for a developer or agent.

| Fixture group                  | Checks                                                                                                                                                         |
|--------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Common contracts and lifecycle | Strict arguments, projection/truncation, bindings, input sequencing, active/queued cancellation and cleanup failures.                                          |
| Windows resources              | PE/PDB bounds, orphan requests, bootstrap waits, process-exit notification and independently retryable handle/mapping cleanup.                                 |
| Detours and event ABI          | Bounded relocation, register/argument and unwind preservation, transaction rollback, entry ownership, removal and complete event release.                      |
| Native UI adapters             | Adjusted receivers, metadata-driven fields/virtual positions, unknown/raw values, null objects, UTF-8 bounds, flags, sprites, quality conditions and switches. |
| Selectors and capture          | Complete bounded search, overlapping paths, positional selection, widget destruction, transferred capture and release.                                         |
| Resident input and world       | Adjacent ticks, pause/unload/requester exit, replacement/cancellation, Lua stack restoration, bounded output and viewport conversion.                          |
| Cursor and transfer reads      | Native cursor representations, hand-location values, unavailable versus idle transfer state, metadata-driven deque traversal and segment bounds.               |
| Rendering                      | WARP Direct3D backbuffer capture and PNG encoding without a Factorio process.                                                                                  |

`QueryLuaFixtureTest` executes the actual fixed reader in a separate Lua runtime with synthetic objects. CMake fetches
the pinned official Lua source, verifies its SHA-256 and builds the fixture with the same toolchain automatically.
The first test build needs network access; subsequent builds reuse the downloaded source. The fixture is never
shipped or loaded by product code.

Native chat fixtures check retained-list traversal, output bounds, normal submission routing and destructor cleanup
after exceptions. Common tests check observation cursors, duplicate messages, cached-text updates and invalid chat
arguments. These and Lua/mock tests do not establish authoritative multiplayer effects or CRC safety. Enable
`ExpandedQueryAcceptanceTest` against the disposable local scenario to check representative inspection/player/detail
reads and synchronized chat, including authoritative events and subsequent full CRC checkpoints.

`mingwX64Test` builds the project's debug executable and supplies its path to the stdio smoke tests automatically.
These tests run without a Factorio client. For direct execution of the native test binary, set
`FACTORIO_MCP_TEST_EXECUTABLE` to an already built executable; `--ktest_filter` can select individual tests.
For direct Lua fixture tests, also set `FACTORIO_MCP_TEST_LUA_DLL` to
`build/native-tests/mingw_x64/mingwX64/lua/query_lua_fixture.dll`. Gradle supplies both paths automatically.

### Installed-game metadata checks

After `build`, set `FACTORIO_MCP_TEST_PDB` to the installed game's matching `factorio.pdb` and
`FACTORIO_MCP_TEST_RUNTIME_API` to its `doc-html/runtime-api.json`. Leave `FACTORIO_MCP_TEST_PID` unset for file-only
work, then run:

```powershell
$env:FACTORIO_MCP_TEST_PDB = 'C:/path/to/Factorio/bin/x64/factorio.pdb'
$env:FACTORIO_MCP_TEST_RUNTIME_API = 'C:/path/to/Factorio/doc-html/runtime-api.json'
.\build\bin\mingwX64\debugTest\test.exe '--ktest_filter=*DebugMetadataAcceptanceTest.virtualInterfaceMetadataResolvesFromAnExplicitPdb:*OfflineQueryMetadataTest.*'
```

These checks resolve installed metadata without launching or injecting a game. They do not establish live ABI safety.
For the additional live-process metadata check, set `FACTORIO_MCP_TEST_PID` and select
`*DebugMetadataAcceptanceTest.inputMetadataResolvesFromTheLoadedTarget` instead. Check activation/output as well as
test counts; external checks without their required environment can return early.

### HTTP acceptance

After `build`, start Factorio normally and start a disposable MCP endpoint as described in README.
Restart Factorio before attaching an updated resident.
For example, in a separate terminal:

```powershell
.\build\bin\mingwX64\debugExecutable\factorio-mcp.exe --no-stdio --http-port 3000
```

Select the already initialized game's PID explicitly:

```powershell
$env:FACTORIO_MCP_ACCEPTANCE_URL = 'http://127.0.0.1:3000/mcp'
$env:FACTORIO_MCP_TEST_PID = '12345'
.\build\bin\mingwX64\debugTest\test.exe '--ktest_filter=*HttpAcceptanceTest.*'
```

The HTTP tests initialize normal MCP sessions, check advertised tools and preconditions, exercise
reads/capture/bindings,
and cycle sessions and repeated detach/attach. They can detach the shared attachment. They never launch or terminate
the supplied endpoint or game. Windows test clients use Ktor's WinHTTP engine; common test code uses the portable API.

The stdio initialization/EOF and invalid-startup-argument tests own only their MCP child process.
`FACTORIO_MCP_TEST_PID` also enables installed-process metadata checks. Tests impose
watchdogs; product tools do not have execution deadlines.

### Local multiplayer acceptance

Use one graphical client and a disposable local server, never a remote server or an ordinary user save. Enable the
official DLC on both peers: the scenario references quality and Space Age items. The train UI test currently expects
Simplified Chinese native menu labels. Keep default keyboard/mouse bindings for the full fixture suite and leave
rendering active. Release external keyboard modifiers before starting; the UI fixture checks native modifier state
without clearing input owned by someone else. Product selectors use observed text and bindings; these are
test-environment assumptions.

1. Create a test server write directory such as `temp/ui-acceptance`, and copy
   `src/mingwX64Test/resources/ui-actions` to `temp/ui-acceptance/scenarios/ui-actions`.
2. Create a server config with `[path]`, `read-data` pointing to the installed Factorio data directory, and `write-data`
   pointing to the `temp/ui-acceptance` root. Use absolute paths. Keep its saves, configuration and logs under `temp/`.
3. Use server settings with `visibility.public` and `visibility.lan` false, `require_user_verification: false`,
   `auto_pause: false`, and `minimum_latency_in_ticks: 32`. This latency floor keeps the timing fixture above ordinary
   local jitter; it is not a product networking change.
4. Start the server in a separate terminal, replacing the executable path below, then join it through the graphical
   client's normal multiplayer UI. Do not grant the test player administrator rights.

```powershell
& 'C:/path/to/Factorio/bin/x64/factorio.exe' `
    --config "$PWD/temp/ui-acceptance/config.ini" `
    --start-server-load-scenario ui-actions --bind 127.0.0.1:34216 `
    --server-settings "$PWD/temp/ui-acceptance/server-settings.json"
```

Keep the scenario window open, set both log paths in addition to the endpoint/PID, and run the acceptance suite:

```powershell
$env:FACTORIO_MCP_UI_SERVER_LOG = "$PWD/temp/ui-acceptance/factorio-current.log"
$env:FACTORIO_MCP_UI_CLIENT_LOG = "$env:APPDATA/Factorio/factorio-current.log"
.\build\bin\mingwX64\debugTest\test.exe '--ktest_filter=*AcceptanceTest.*'
```

These tests mutate the disposable scenario: controls, inventory interactions, player/controller state, blueprint
placement and train schedules. Start a fresh scenario for a full rerun; some cases intentionally leave built ghosts.
A focused rerun can reuse the current scenario only when its expected initial state has been restored.

| Acceptance class                                              | Scope                                                                                                                                                |
|---------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| `UiFlowAcceptanceTest`                                        | Native/scenario controls, text restrictions, modifiers, offscreen options, item/quality properties, resource budgets and recreation.                 |
| `WorldQueryAcceptanceTest`, `RelatedWorldQueryAcceptanceTest` | Spatial identity, surface errors, empty/quality-separated inventories, filters, catalogs and force recipes.                                          |
| `PlayerContextAcceptanceTest`                                 | Character/vehicle references, remote/spectator transitions, weapons, quickbar, technologies and recipe relations.                                    |
| `WorldOverviewAcceptanceTest`                                 | Native viewport, grouped observations, explicit areas, terrain coverage and work bounds.                                                             |
| `ExpandedQueryAcceptanceTest`                                 | Combined native type/name filters, recipe/quality/fluid/filter details, metadata inspection, player selection and synchronized chat with later CRCs. |
| `InputAcceptanceTest`, `WheelInputAcceptanceTest`             | Stable-latency movement effects, replacement/cancellation/detach, concurrent observation, mouse targeting and wheel impulses.                        |
| `MouseMotionAcceptanceTest`, `BlueprintMotionAcceptanceTest`  | Held mouse motion, GUI capture release, cancellation of future points and a row of blueprint ghosts.                                                 |
| `TrainUiAcceptanceTest`                                       | Nested schedule selection, number entry, switch states, authoritative schedule/mode changes and restoration of the initial context.                  |

The scenario logs authoritative events and requests full CRCs every 300 ticks. Compare client/server observations
before dependent actions and await later CRC checkpoints. Dispatch success, screenshot appearance and full-CRC
agreement are separate assertions. While input remains held, compare the confirmed common log prefix rather than
waiting for a continuously growing server tail. Preserve failed runs and their logs under `temp/` before resetting.
Direct test execution reports to the terminal and returns a nonzero exit code on failure; capture output under
`temp/` when retaining acceptance evidence. Stop only the endpoint/server processes you started for the test after
finishing. No Gradle command launches Factorio or prepares a multiplayer scenario.

### Manual lifecycle and mixed flows

Use standard HTTP tools for exploratory flows; retain requests, replies and screenshots under `temp/`. Exercise menus,
load/save, pause, world replacement and mixed UI/world reads separately from fixture coverage.
Initialize an MCP session, retain its session ID and protocol-version headers, then use `tools/call` through the
endpoint above. Prefer structured queries and widget actions; use `screenshot` when a result needs visual verification.
An agent follows the same setup, commands and checks as a human operator.

For cursor-state acceptance, compare physical stacks, cursor ghosts, blueprint-library records and hand locations
against native UI interactions and authoritative scenario logs. Import a sufficiently large disposable blueprint
through the normal import dialog on a local multiplayer client. Observe `status.input_transfer` during the transfer,
compare its counters with screenshots of the cursor percentage, then independently verify the imported blueprint's
entity count on both peers and await later full CRC checkpoints. Queue disappearance alone is not an import-success
assertion. Clipboard setup, if needed for a large fixture, belongs to the test operator and must restore previous data.

For input cleanup, start a long request from one session, pause single-player or leave a local multiplayer world from
another, and check the original caller's terminal result before resuming. Confirm that movement does not resume and
that a fresh gesture can still move through the area; terrain blockage is not proof of key release.

To test abrupt requester exit, terminate only an endpoint owned by the test operator, restart the unchanged artifact,
and check detached status before explicit attach. Verify that old input does not resume. To test queued cleanup,
suspend rendering, admit a screenshot plus read/action requests from other sessions, then detach. Each live caller
must receive a terminal abort, cleanup must finish, and reattachment must work after restoring rendering.

These manual checks do not run automatically with the suite. Distinguish them from Kotlin unit tests, native fixtures,
local multiplayer results and claims about arbitrary mods. When a resident build changes, restart Factorio before
continuing; do not force focus or inspect the desktop without the user's testing permission.

## Manual HTTP requests

Initialize a session, send `notifications/initialized`, and retain the returned session header. Use the negotiated
protocol version on subsequent requests:

```powershell
$base = 'http://127.0.0.1:3000/mcp'
$request = @{ jsonrpc='2.0'; id=1; method='initialize'; params=@{
    protocolVersion='2025-11-25'; capabilities=@{}; clientInfo=@{name='example';version='1'}
} }
$response = Invoke-WebRequest -UseBasicParsing -Uri $base -Method Post `
    -ContentType 'application/json' -Headers @{Accept='application/json, text/event-stream'} `
    -Body ($request | ConvertTo-Json -Depth 10 -Compress)
$initialized = $response.Content | ConvertFrom-Json
$headers = @{
    Accept='application/json, text/event-stream'
    'Mcp-Session-Id'=$response.Headers['Mcp-Session-Id']
    'MCP-Protocol-Version'=$initialized.result.protocolVersion
}
Invoke-WebRequest -UseBasicParsing -Uri $base -Method Post -ContentType 'application/json' -Headers $headers `
    -Body (@{jsonrpc='2.0'; method='notifications/initialized'} | ConvertTo-Json -Compress)
$call = @{jsonrpc='2.0'; id=2; method='tools/call'; params=@{name='status'; arguments=@{}}}
Invoke-WebRequest -UseBasicParsing -Uri $base -Method Post -ContentType 'application/json' -Headers $headers `
    -Body ($call | ConvertTo-Json -Depth 10 -Compress)
Invoke-WebRequest -UseBasicParsing -Uri $base -Method Delete -Headers $headers
```

For curl, serialize requests to UTF-8 files and use `curl.exe --data-binary '@request.json'` with the same headers.
HTTP DELETE closes the session, not the shared attachment. Do not replay uncertain mutations on request failure.
