# Development

Contributor instructions for the Windows implementation and Linux adapter. User setup and the tool catalog belong in
[README](README.md); public arguments and result semantics belong in [tools.md](tools.md). Follow
[AGENTS.md](AGENTS.md) for project conventions. Research, plans, measurements and test-run evidence stay under ignored
`temp/`; they are not prerequisites for using or building a checkout.

## Build

Build on Linux x64 or Windows x64 with a JDK compatible with the Gradle wrapper, CMake 3.24+ and Ninja on `PATH`.
Gradle downloads Kotlin/Native and uses its bundled compiler, assembler and linker; a separate LLVM installation is
unnecessary. Linux needs the system C/C++ development headers and standard libraries. On Windows, install Visual
Studio Build Tools with the MSVC C++ build components and Windows SDK. The Visual Studio
IDE is unnecessary.

On Windows, configure semicolon-separated SDK/toolchain paths in ignored `local.properties`, using forward slashes:

| Configuration key | Environment variable fallback |
|-------------------|-------------------------------|
| `windows.native.includeDirectories` | `FACTORIO_MCP_WINDOWS_NATIVE_INCLUDE_DIRECTORIES` |
| `windows.native.libraryDirectories` | `FACTORIO_MCP_WINDOWS_NATIVE_LIBRARY_DIRECTORIES` |
| `windows.native.toolDirectories` | `FACTORIO_MCP_WINDOWS_NATIVE_TOOL_DIRECTORIES` |

File values take precedence. Include/library paths are passed through the compiler's standard `INCLUDE`/`LIB`
subprocess interface; those ambient variables are not read as project configuration. Tool directories are prepended
to the subprocess `PATH`.

```sh
./gradlew build
```

On Windows, use `.\gradlew.bat` instead of `./gradlew`.

`build` produces the binaries and runs local automated tests; `assemble` only builds, and `check` only runs verification
and builds its dependencies. No Factorio installation or running game is needed for these commands.
Outputs are in `build/bin/<target>/debugExecutable/` and `build/bin/<target>/releaseExecutable/`.
Windows and WSL can use the same checkout. Run their builds sequentially; metadata library indexes are regenerated
when the host-visible project path changes. Gradle releases its process and project-cache handles after each build.
Linux uses `linuxX64`, with `factorio-mcp.kexe` and the adjacent `libfactorio_mcp_resident.so`;
Windows uses `mingwX64`, with `factorio-mcp.exe` and `factorio_bridge.dll`.
The Linux PNG encoder uses Kotlin/Native's standard zlib bindings and the system `libz.so.1` runtime library.
Keep each executable with its adjacent library. To build one variant, use `linkReleaseExecutableLinuxX64` or
`linkReleaseExecutableMingwX64` (replace `Release` with `Debug` for that variant).

| Command                      | Purpose                                                                |
|------------------------------|------------------------------------------------------------------------|
| `./gradlew assemble`         | Build binaries without running tests.                                  |
| `./gradlew build`            | Build product binaries and run all local automated tests.              |
| `./gradlew check`            | Run all local automated tests, building their dependencies.            |
| `./gradlew linuxX64Test`     | Run common and Linux Kotlin tests with automatically built fixtures.   |
| `.\gradlew.bat mingwX64Test` | Run common and Windows Kotlin tests, including stdio and Lua fixtures. |
| `./gradlew clean build`      | Rebuild and test from a clean output directory.                        |

Stop MCP before rebuilding its executable. Close any game that has loaded a resident library you intend to replace, and
restart
Factorio after updating MCP before attaching again. Build tasks do not stop processes. Test/documentation-only changes
can reuse the running game and installed artifacts.

CMake owns native compilation and fixtures; the native build adapters in `buildSrc` invoke it from Gradle. Product
output is scoped by
host and target under `build/native/`, with fixtures under `build/native-tests/`. Product builds do not compile test
fixtures. Ninja checks compiler-discovered dependencies, including SDK headers outside
the repository, on every native build. Unchanged native output does not overwrite an identical mapped runtime library.
Use a fresh native build directory when changing the compiler or SDK installation.

The shared native input sequence and its fixtures live in `src/nativeMain/native/` and `src/nativeTest/native/`.
Both platform CTest suites exercise tick progression, repeated callbacks, mouse motion, cancellation and retained
release ownership after dispatch failures without a game. These fixtures do not establish platform event routing
or real-game effects; those require explicit acceptance tests.

### Platform boundaries

The intended targets are Windows x64 (`mingwX64`), Linux x64 (`linuxX64`) and macOS ARM64 (`macosArm64`).
Implemented targets use default KMP registration on every host. Kotlin/Native may cross-compile supported targets;
KMP disables test execution for foreign desktop targets. `linuxX64` and `mingwX64` use standard named target blocks
and the default hierarchy. Their cinterop definitions live beside the corresponding platform sources and are selected
explicitly. Both implement the portable tool contract. A build target alone does not establish real-game acceptance
or coverage of every native widget/property; run the platform checks before declaring a new port supported.
Keep current research and acceptance evidence under ignored `temp/`.
The fixed world-query Lua scripts, argument validation, results and session lifecycle already live in `commonMain`;
both platform adapters reuse those scripts.
HTTP servers and the
HTTP-only acceptance clients use CIO through common source sets. HTTP clients are test dependencies only. Host-only
CMake/CTest tasks are registered on each matching host, with task names derived from the target (`buildLinuxX64Native`,
`buildLinuxX64NativeFixtures`, `testLinuxX64Native`, and the corresponding `MingwX64` tasks) and separate host/target
output directories. Other hosts do not
acquire these task dependencies merely by configuring the project. `WindowsNativeBuild`, `KonanWindows.cmake` and
the `windows.native.*` settings are specifically for this Windows adapter.
Cross-compiling the Kotlin executable does not build its resident library; build complete packages on their target OS.

When implementing `macosArm64`, keep the standard KMP source sets, add the target's own
cinterop/toolchain/runtime
handling, and connect only the tests supported on the current host to `check`. Keep shared dependencies portable;
do not reuse Windows DLL names, SDK settings or architecture constants in shared configuration. No placeholder targets
or platform implementations are needed before those ports exist.

## Publishing

Pushes and pull requests use `.github/workflows/ci.yml` to call the reusable `build.yml` pipeline with read-only
permissions. It runs the enabled platform matrix, including offline npm package installation and execution,
without publishing or requiring a game installation. Checks for
the same ref cancel an older check run; they do not share the publishing concurrency group.

Three manual Actions entry points share `.github/workflows/release.yml`, which checks the requested destinations
and required credentials before calling the same `build.yml` pipeline:

- **github-release** (`github-release.yml`): build, test and upload ZIPs to a GitHub Release.
- **npm-release** (`npm-release.yml`): build, test and publish all enabled platform packages, then the launcher
  package. No package selection is required; a platform publication failure prevents launcher publication.
- **all-release** (`all-release.yml`): build and test once, then publish both destinations.

The shared `build.yml` and `release.yml` workflows are called internally.

Select the branch or tag using **Run workflow**. The entry-point files must exist on the default branch for GitHub
to offer their manual triggers. Set `ProjectInfo.VERSION` in
[`ProjectInfo.kt`](buildSrc/src/main/kotlin/com/hiczp/factorio/mcp/buildlogic/ProjectInfo.kt) before publishing.
`build.gradle.kts` uses that constant as the project version; npm packages and the generated MCP handshake version
derive from it. External scripts and the publishing workflow read the version with:

```sh
./gradlew --quiet printVersion
```

On Windows, use `.\gradlew.bat --quiet printVersion`. The task prints only the version with `--quiet`, always runs
when requested, and does not build native binaries. The build metadata job validates SemVer, including numeric
prerelease identifiers, before starting the platform builds. GitHub publication creates tag `v<version>` at the selected
commit and publishes `factorio-mcp-<version>-<platform>.zip` as a Release attachment. An existing tag can be reused
only if it resolves to that same commit; existing releases are never replaced. All three entry points share a
concurrency group. GitHub may replace an older
pending run when another is queued; an in-progress run is not cancelled.

`.github/release-platforms.json` controls the build matrix. Windows x64 and Linux x64 are enabled; macOS ARM64
remains disabled. This configures builds and manually requested publications; release candidates also need explicit
real-game acceptance. Local workflow and tarball checks do not establish an actual CI run or publication.
Each enabled runner executes the standard `gradlew build`, including local automated tests, and stages files from
`build/bin/<target>/releaseExecutable/`. The Windows runner initializes its installed MSVC/Windows SDK environment;
compilation still uses Kotlin/Native's toolchain. No Factorio installation or manual acceptance environment is needed.

Enabled platforms build in parallel on separate runners. Local Windows and WSL builds sharing a checkout must run
sequentially. Each runner invokes Gradle once with `build` and its host's `verifyNpm<target>Package` task.
Both publication formats use the same tested release binaries. Each runner uploads its verified platform tarball;
the Linux runner also uploads its verified launcher tarball. Publication jobs use these artifacts without compiling
or packing again. After every enabled build succeeds, GitHub and npm publication can run concurrently.

Publication starts only after every enabled platform succeeds. GitHub publication makes one ZIP per platform and
uploads them together using the workflow's `GITHUB_TOKEN`; only this job has `contents: write` in the shared pipeline.
The entry points permit that job's elevated permission; metadata, build and npm jobs retain read-only tokens.
Repository policies must allow Release and tag creation. Test reports remain available as Actions artifacts for seven
days, including when tests fail. Intermediate TAR files preserve executable permissions across jobs before final ZIP
packaging.

For npm, configure the repository's `NPM_TOKEN` Actions secret with publish access to
`@czp3009/factorio-mcp`, `@czp3009/factorio-mcp-linux_x64` and `@czp3009/factorio-mcp-mingw_x64`. Use credentials that
support unattended publication
under your npm account's security policy. A requested npm publication fails before building when this secret is missing;
this checks presence, not whether the registry will accept the token. `setup-node` supplies a temporary npm configuration;
the token is exposed only to the credential check and publish steps. No npm credentials are needed for GitHub-only releases.
Stable versions use npm's `latest` tag. Prerelease versions use `next`, and their GitHub Releases are marked as
prereleases without becoming the latest release. Build metadata alone does not mark a version as a prerelease.
Linux packages include `bin/factorio-mcp.kexe` and the adjacent `bin/libfactorio_mcp_resident.so`;
Windows packages include `bin/factorio-mcp.exe` and `bin/factorio_bridge.dll`. Build verification checks archive contents,
exact dependency versions, OS/CPU declarations, agreement with the enabled matrix, offline installation and execution.
Before publishing, the npm job checks the complete archive set and version against the launcher. Missing, duplicate,
extra or stale platform packages reject publication. Platform packages are published sequentially before the launcher;
a platform failure prevents launcher publication. Keep the matrix aligned with completed targets before releasing.

Publication is not atomic across packages or destinations. Existing npm versions are not overwritten, and a later
failure does not undo packages or a GitHub Release already published. Inspect the logs before retrying; use a new
Gradle version for changed artifacts. No public publication occurs during ordinary `build` or `check`.

`gradle/actions/setup-gradle` manages Gradle user-home caching. A separate `~/.konan` cache holds Kotlin/Native
compiler distributions and dependencies, with keys separating OS, architecture and Kotlin version. Project build outputs
and test results are not transferred through these caches. Cache misses download dependencies normally; they do not
change which checks run. No custom token or configuration-cache encryption secret is required.

### Local npm verification

Node.js 24+ and npm are needed only for npm packaging/publication and npm-based launches. The
`com.hiczp.kotlin-native-npm-publishing` Gradle plugin owns package staging and local publishing tasks.
The Gradle version also generates the version reported in the MCP handshake.

Verify the launcher and the current host's package without publishing:

```sh
./gradlew verifyNpmLinuxX64Package
```

On Windows:

```powershell
.\gradlew.bat verifyNpmMingwX64Package
```

The task builds the host release binary and uses the plugin's normal generation tasks. It checks the release matrix,
exact optional dependency versions and package contents, then runs `npm pack` and installs the unmodified launcher
and host tarballs together into a temporary consumer with `npm install --offline --ignore-scripts`. npm uses isolated
empty configuration files and a temporary cache. No registry, credentials, global installation or publication is
involved. The installed executable and resident must match the release build byte for byte; Linux executable
permissions are checked before launch. The installed launcher must answer MCP `initialize` and detached `status`,
write only protocol output to stdout, and exit cleanly when stdin closes.

Reports and the verified tarballs are in `build/reports/npm/<target>/`; temporary consumer installations and caches
are removed when verification ends. The checks have watchdogs for their own child processes and never launch or
attach to Factorio. Run these tasks explicitly when changing packaging; ordinary local `build` and `check` do not
require Node.js or npm. CI invokes them on every enabled host. These checks establish local package installation and
execution, not publication or game acceptance.

For staging inspection alone, use `generateKotlinNativeNpmMainPackage` with
`generateKotlinNativeNpmLinuxX64Package` or `generateKotlinNativeNpmMingwX64Package`. Choose the host package explicitly:
aggregate plugin tasks can also select cross-compilable executables, whose resident libraries are not built on a
foreign host.

## Maintenance map

| Area             | Primary sources                                                                | Invariants                                                                                                  |
|------------------|--------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| Tool contract    | `McpServer.kt`, `UiSchema.kt`, `InputSchema.kt`, query parsers in `commonMain` | Keep advertised descriptions, validation and tools.md consistent; reject unsupported arguments.             |
| Lifecycle        | `GameSession.kt`, `InputTaskOwner.kt`                                          | Cooperative cleanup, one terminal result, shared detach/process-exit cleanup, retryable resource ownership. |
| Observation      | `UiSnapshot.kt`, common world readers                                          | Typed serialization, explicit missing/truncated data, raw native meaning, bounded work.                     |
| Windows boundary | `ResidentConnection.kt`, debug metadata/layout adapters                        | Matching PE/PDB; dynamically resolved members and virtual methods; checked process/IPC ownership.           |
| Linux boundary   | Linux `ResidentConnection.kt`, ELF/DWARF readers and instruction analyzers     | Matching executable, bounded dynamic layout/ABI proofs, checked process/IPC ownership.                      |
| Resident         | `src/linuxX64Main/native/`, `src/mingwX64Main/native/`                         | Necessary ABI adapters and safe-point hooks only; bounded commands and nonblocking notifications.           |
| Tests            | `src/commonTest/`, `src/nativeTest/`, platform test source sets                | Portable unit tests, platform fixtures and explicitly enabled real-game acceptance remain distinct.         |

## Tests

`build`, `check` and the platform test tasks run game-independent Kotlin tests and native CMake/CTest fixtures.
Gradle builds their dependencies automatically. Optimized native fixtures keep assertions enabled.
These checks do not require Factorio, an HTTP endpoint or scenario preparation.

Installed-file checks live in `com.hiczp.factorio.mcp.offline`; real-game acceptance lives in
`com.hiczp.factorio.mcp.acceptance`. Gradle excludes both packages from its ordinary test run.
Run external checks explicitly from the built test executable with the exact class filter and required environment.
An enabled target or a passing unit suite alone does not establish game support or full widget/property coverage.
Record the selected files, artifact scope, test output and failed runs under ignored `temp/`.

### HTTP acceptance

Start an initialized Factorio client normally, without game arguments. Reuse an existing Steam client.
Run only one graphical client across Windows and WSL. Start a separate MCP endpoint:

```sh
build/bin/linuxX64/releaseExecutable/factorio-mcp.kexe --no-stdio --http-port 3000
```

On Windows, use the executable in `build/bin/mingwX64/releaseExecutable/`:

```powershell
& .\build\bin\mingwX64\releaseExecutable\factorio-mcp.exe --no-stdio --http-port 3000
```

Select the endpoint and existing game's PID explicitly, then run a focused acceptance class:

```sh
export FACTORIO_MCP_ACCEPTANCE_URL=http://127.0.0.1:3000/mcp
export FACTORIO_MCP_TEST_PID=12345
build/bin/linuxX64/debugTest/test.kexe \
  --ktest_filter='com.hiczp.factorio.mcp.acceptance.ChatReadAcceptanceTest.*'
```

On Windows:

```powershell
$env:FACTORIO_MCP_ACCEPTANCE_URL = 'http://127.0.0.1:3000/mcp'
$env:FACTORIO_MCP_TEST_PID = '12345'
& .\build\bin\mingwX64\debugTest\test.exe `
    '--ktest_filter=com.hiczp.factorio.mcp.acceptance.ChatReadAcceptanceTest.*'
```

Acceptance clients use the official SDK over Streamable HTTP and initialize normal MCP sessions. They may detach
its shared attachment, but never launch or terminate an externally supplied endpoint or game. Watchdogs belong to
these tests; product tools have no execution deadline. Stdio smoke tests own only their MCP subprocess and verify
initialization, detached status, clean protocol output and EOF shutdown.

### Local multiplayer acceptance

Use one isolated local server and a disposable scenario alongside the graphical client. Never use a remote server
or overwrite an ordinary user save. Enable the official DLC on both peers for the supplied fixture's quality and
Space Age objects. Do not operate the client concurrently with tools. Some existing Windows fixtures require
Simplified Chinese UI text and default keyboard/mouse bindings; these are test prerequisites, not product assumptions.
`TrainUiAcceptanceTest` uses `FACTORIO_MCP_TEST_UI_LANGUAGE=en` for English or `zh-CN` for Simplified Chinese
(the default). The test selects the fixture's original text; the product does not translate it.

1. Copy `src/nativeTest/resources/ui-actions` into a new directory such as
   `temp/ui-acceptance/scenarios/ui-actions`.
2. Create a server configuration whose `[path]` uses the installed game's `data` directory for `read-data` and the
   absolute test directory for `write-data`. Keep configuration, scenario copies and logs under `temp/`.
3. Disable public/LAN visibility, user verification and auto-pause in the server settings. Use
   `minimum_latency_in_ticks: 32` to exercise delayed input. Keep console commands disabled (`allow_commands: "false"`)
   and do not grant the client administrator rights. Prefer peaceful terrain without enemy bases for unrelated tests.
4. Check for an existing server before starting one. Join it through the graphical client's ordinary multiplayer UI.

Only the headless server uses launch arguments:

```sh
"/path/to/Factorio/bin/x64/factorio" \
  --config "$PWD/temp/ui-acceptance/config.ini" \
  --start-server-load-scenario ui-actions \
  --bind 127.0.0.1 --port 34216 \
  --server-settings "$PWD/temp/ui-acceptance/server-settings.json"
```

On Windows, use `& 'C:/path/to/Factorio/bin/x64/factorio.exe'` and PowerShell backticks for line continuation.
Set both peers' log paths in addition to the HTTP endpoint/PID:

```sh
export FACTORIO_MCP_UI_SERVER_LOG="$PWD/temp/ui-acceptance/factorio-current.log"
export FACTORIO_MCP_UI_CLIENT_LOG="$HOME/.factorio/factorio-current.log"
build/bin/linuxX64/debugTest/test.kexe \
  --ktest_filter='com.hiczp.factorio.mcp.acceptance.UiActionAcceptanceTest.*'
```

The Linux Steam installation may use a different user-data directory; select its actual log path.
On Windows:

```powershell
$env:FACTORIO_MCP_UI_SERVER_LOG = "$PWD/temp/ui-acceptance/factorio-current.log"
$env:FACTORIO_MCP_UI_CLIENT_LOG = "$env:APPDATA/Factorio/factorio-current.log"
& .\build\bin\mingwX64\debugTest\test.exe `
    '--ktest_filter=com.hiczp.factorio.mcp.acceptance.UiActionAcceptanceTest.*'
```

Run classes with their specific fixture prerequisites rather than enabling every external check indiscriminately.
Focused checks can reuse a fixture when its expected initial state has been restored; use a fresh scenario for a full
rerun of tests that leave new objects or cursor state.

| Acceptance class | Scope |
|------------------|-------|
| `UiActionAcceptanceTest` | Generic native widget gestures, restricted text, modifiers, offscreen options and recreation. |
| `UiFlowAcceptanceTest` | Windows-specific native cleanup checks and broader UI property/inventory interaction coverage. |
| `UiIconAcceptanceTest`, `UiNumberAcceptanceTest`, `UiIdentityAcceptanceTest` | Structured image references, original script GUI icon names, numbers and native item/quality values. |
| `WorldQueryAcceptanceTest`, `RelatedWorldQueryAcceptanceTest`, `ExpandedQueryAcceptanceTest` | Direct world and related-object reads, inventories, filters, catalogs, metadata and query recovery. |
| `WorldOverviewAcceptanceTest`, `ViewportAcceptanceTest` | Explicit areas, native viewport observations and bounded spatial results. |
| `InputAcceptanceTest`, `WheelInputAcceptanceTest` | Finite keyboard/mouse execution, replacement, cancellation, detach and independent authoritative effects. |
| `ChatReadAcceptanceTest`, `ChatSendAcceptanceTest` | Original retained records, repeated content, native paging boundaries, waiting, cancellation and local submission. |
| `HttpAcceptanceTest` | Shared HTTP sessions, every advertised tool, screenshots and repeated attach/detach. |
| `PlayerContextAcceptanceTest`, `BlueprintMotionAcceptanceTest`, `TrainUiAcceptanceTest` | Authoritative controller transitions, held mouse blueprint placement and nested native train UI interactions. |

The scenario records authoritative events and forces full CRC every 300 ticks. Compare server and client events,
then await later full CRCs after writable paths and potentially stateful reads. Client dispatch, authoritative effects,
screenshot appearance and CRC agreement are separate assertions. While input is held, compare the confirmed common
log prefix rather than waiting for a continuously growing server tail. Preserve failed runs before resetting a fixture.
Neither a tool-count check nor these selected classes establishes coverage of every widget or mod property.

### Manual lifecycle and mixed flows

Use initialized HTTP sessions for exploratory menus, pause, world replacement, game exit, MCP restart and mixed reads.
Prefer structured queries and widget actions. Retain requests/replies and any required game screenshots under `temp/`.
Do not force focus, simulate OS input or inspect the desktop without testing permission.

Check cancellation during long input, terminal aborts for queued observations and detach cleanup. On game exit,
verify detached status and local resource release. Restarting an unchanged MCP must initially report detached;
explicit attach must reconcile outstanding resident work without replaying an uncertain mutation. Product updates
require restarting Factorio before reattachment. Preserve retained ownership when cleanup fails so detach can retry.

For blueprint import or held mouse motion, independently inspect resulting cursor/blueprint objects and authoritative
fixture events, then await full CRC. Queue disappearance and a completed action result do not prove gameplay intent.
Use focused scenario tests for controller transitions, train schedules and inventory interactions when changing those
paths. Keep platform fixture, unit, single-player, multiplayer and manual claims separate.

After acceptance, detach cooperatively and stop only the endpoint/server processes owned by the test operator.
Remove exactly owned external scenario links and temporary directories. Preserve user saves and original research;
archive a test autosave only after verifying that it belongs to the disposable scenario.

## Manual HTTP requests

Use curl with UTF-8 request files, standard JSON/SSE Accept headers and the negotiated session/protocol headers.
Initialize first with a request such as:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-11-25",
    "capabilities": {},
    "clientInfo": {"name": "example", "version": "1"}
  }
}
```

```sh
curl --silent --show-error --dump-header headers.txt --output response.json \
  --header 'Accept: application/json, text/event-stream' \
  --header 'Content-Type: application/json' \
  --data-binary @request.json http://127.0.0.1:3000/mcp
```

Retain `Mcp-Session-Id` from the response headers and `protocolVersion` from its result. Send
`notifications/initialized` without an ID, then call `tools/call` with the same two headers:
`Mcp-Session-Id` and `MCP-Protocol-Version`. On Windows, use `curl.exe`; PowerShell also supports structured requests
serialized with `ConvertTo-Json`. An HTTP DELETE closes the session, not the shared attachment. Do not replay
uncertain mutations after a request failure.
