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

On Windows, run Gradle from an x64 developer command prompt with `INCLUDE` and `LIB` populated. Alternatively, ignored
`local.properties` can supply `native.INCLUDE`, `native.LIB` and `native.PATH` as semicolon-separated paths.
Use forward slashes in Java properties files. Each supplied value replaces the corresponding environment variable;
include the existing command search paths when overriding `native.PATH`.

```sh
./gradlew build
```

On Windows, use `.\gradlew.bat` instead of `./gradlew`.

`build` produces the binaries and runs local automated tests; `assemble` only builds, and `check` only runs verification
and builds its dependencies. No Factorio installation or running game is needed for these commands.
Outputs are in `build/bin/<target>/debugExecutable/` and `build/bin/<target>/releaseExecutable/`.
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
explicitly. The Linux target currently supports attachment, status and UI snapshots with path selection, including
native type, text, enabled and own-visible predicates and raw own render/search flags, plus finite clicks with optional
Ctrl/Shift/Alt modifiers, text replacement and finite key chords with optional selected-widget focus.
Prototype predicates, remaining optional properties
and world/chat/input/screenshot acceptance are in progress. World queries now use the shared Lua
source through the resident; viewport reads are still unavailable. Its state/pause observations remain unknown until
validated.
The fixed world-query Lua scripts, argument validation, results and session lifecycle already live in `commonMain`;
both platform adapters reuse those scripts.
HTTP servers and the
HTTP-only acceptance clients use CIO through common source sets. HTTP clients are test dependencies only. Host-only
CMake/CTest tasks are registered on each matching host, with task names derived from the target (`buildLinuxX64Native`,
`buildLinuxX64NativeFixtures`, `testLinuxX64Native`, and the corresponding `MingwX64` tasks) and separate host/target
output directories. Other hosts do not
acquire these task dependencies merely by configuring the project. `WindowsNativeBuild`, `KonanWindows.cmake` and
the `native.INCLUDE`/`native.LIB`/`native.PATH` settings are specifically for this Windows adapter.
Cross-compiling the Kotlin executable does not build its resident library; build complete packages on their target OS.

When implementing `macosArm64`, keep the standard KMP source sets, add the target's own
cinterop/toolchain/runtime
handling, and connect only the tests supported on the current host to `check`. Keep shared dependencies portable;
do not reuse Windows DLL names, SDK settings or architecture constants in shared configuration. No placeholder targets
or platform implementations are needed before those ports exist.

## Publishing

Pushes and pull requests use `.github/workflows/ci.yml` to call the reusable `build.yml` pipeline with read-only
permissions. It runs the enabled platform matrix without publishing or requiring a game installation. Checks for
the same ref cancel an older check run; they do not share the publishing concurrency group.

Three manual Actions entry points share `.github/workflows/publish.yml`, which calls the same `build.yml` pipeline:

- **publish-github** (`publish-github.yml`): build, test and upload ZIPs to a GitHub Release.
- **publish-npm** (`publish-npm.yml`): build, test and publish all enabled platform packages, then the launcher
  package. No package selection is required; a platform publication failure prevents launcher publication.
- **publish-all** (`publish-all.yml`): build and test once, then publish both destinations.

These are the only manual entry points. The shared `build.yml` and `publish.yml` workflows are called internally.

Select the branch or tag using **Run workflow**. The entry-point files must exist on the default branch for GitHub
to offer their manual triggers. Set `ProjectInfo.VERSION` in
[`ProjectInfo.kt`](buildSrc/src/main/kotlin/com/hiczp/factorio/mcp/buildlogic/ProjectInfo.kt) before publishing.
`build.gradle.kts` uses that constant as the project version; npm packages and the generated MCP handshake version
derive from it. External scripts and the publishing workflow read the version with:

```sh
./gradlew --quiet printVersion
```

On Windows, use `.\gradlew.bat --quiet printVersion`. The task prints only the version with `--quiet`, always runs
when requested, and does not build native binaries. GitHub publication creates tag `v<version>` at the selected commit
and publishes
`factorio-mcp-<version>-<platform>.zip` as a Release attachment. An existing tag causes publication to fail instead
of replacing an existing release. All three entry points share a concurrency group. GitHub may replace an older
pending run when another is queued; an in-progress run is not cancelled.

The cacheable `generateBuildVersion` task in `buildSrc` uses KotlinPoet to generate the runtime
`BuildVersion.VERSION` constant in an internal object. Its version input and generated source directory are declared
to Gradle. `commonMain` consumes the task's output provider, so compilation depends on generation automatically.
Generated code stays under `build/generated/`; KotlinPoet is a build dependency only.

`.github/release-platforms.json` controls the build matrix. Windows x64 and Linux x64 are enabled; macOS ARM64
remains disabled. This configures builds and manually requested publications; it does not establish Linux feature
parity or live-game acceptance. Finish those checks before publishing the current Linux implementation.
Each enabled runner executes the standard `gradlew build`, including local automated tests, and stages files from
`build/bin/<target>/releaseExecutable/`. The Windows runner initializes its installed MSVC/Windows SDK environment;
compilation still uses Kotlin/Native's toolchain. No Factorio installation or manual acceptance environment is needed.

Enabled platforms build in parallel. Each runner invokes Gradle once with `build` and, when requested, its npm
package generation task. Gradle executes shared dependencies only once in that task graph. Both publication
formats use the same release binaries; uploaded artifacts carry the tested binaries and npm tarballs to later
jobs without compiling again. Gradle's build cache reuses eligible task outputs across runs, while local up-to-date
checks avoid repeated work within a workspace. Artifact handoff does not depend on a cache hit.

After all builds and tests succeed, GitHub and npm publication can run concurrently. The npm job downloads its
tarballs once and publishes platform packages sequentially, followed by the launcher. A platform failure prevents
launcher publication. This keeps the small upload steps in one job rather than adding runners and artifact
handoffs just to parallelize them. Publication steps do not rerun Gradle or tests; whole build directories and
project-local Gradle state are not transferred between jobs.

Publication starts only after every enabled platform succeeds. GitHub publication makes one ZIP per platform and
uploads them together using the workflow's `GITHUB_TOKEN`; only this job has `contents: write` in the shared pipeline.
The entry points permit that job's elevated permission; metadata, build and npm jobs retain read-only tokens.
Repository policies must allow Release and tag creation. Test reports remain available as Actions artifacts for seven
days, including when
tests fail. Intermediate TAR files preserve executable permissions across jobs before final ZIP packaging.
Each TAR filename includes its platform; downloads merge into one directory so packaging uses the same paths for
one or multiple enabled platforms.

For npm, configure the repository's `NPM_TOKEN` Actions secret with publish access to
`@czp3009/factorio-mcp`, `@czp3009/factorio-mcp-linux_x64` and `@czp3009/factorio-mcp-mingw_x64`. Use credentials that
support unattended publication
under your npm account's security policy. `setup-node` supplies a temporary npm configuration; the token is exposed
only to the publish step. No npm credentials are needed for GitHub-only releases. The plugin generates npm packages
during the build; `npm pack` archives are handed to the npm job, which uploads every platform package before
the launcher. Windows packages include `bin/factorio-mcp.exe` and the adjacent `bin/factorio_bridge.dll`.
The DLL copy is configured in `stage.platforms.mingwX64`, so it does not apply to other platform packages.
The plugin derives the launcher's optional dependencies from the configured executable targets. The npm packaging
job rejects a launcher whose platform list differs from the enabled release matrix, before any publication.
The publication job also validates the complete archive set against the launcher dependencies, including agreement with
the version read by the build job, exact dependency
versions and OS/CPU declarations, before publishing any archive. Missing, duplicate, extra or stale platform packages
reject publication. Keep the matrix aligned with completed targets before publishing a release.

Publication is not atomic across packages or destinations. Existing npm versions are not overwritten, and a later
failure does not undo packages or a GitHub Release already published. Inspect the logs before retrying; use a new
Gradle version for changed artifacts. No public publication occurs during ordinary `build` or `check`.

Two separate caches keep repeat builds from downloading the toolchain and dependencies again:

- Gradle's user-home `caches` and `wrapper` directories are keyed by runner OS/architecture, wrapper version and
  build/dependency configuration. Restore prefixes reuse downloads when build configuration changes. Metadata and
  compilation jobs have separate keys so a metadata-only cache cannot prevent saving the full build's dependencies.
- Kotlin/Native's `~/.konan` directory holds compiler distributions and native dependencies, including the bundled
  LLVM toolchain. Its keys separate OS/architecture and Kotlin version; restore prefixes never cross those boundaries.

Project `build/`, project `.gradle/` and test results are not restored as dependency caches. Cache misses download
dependencies normally. Caching requires no custom token or configuration-cache encryption secret. GitHub may evict old
caches according to repository limits; this affects build time, not which checks run.

### Local npm verification

Node.js 24+ and npm are needed only for npm packaging/publication and npm-based launches. The
`com.hiczp.kotlin-native-npm-publishing` Gradle plugin owns package staging and local publishing tasks.
The Gradle version also generates the version reported in the MCP handshake.

After the normal `build`, generate and inspect the launcher and the current host's package without publishing:

```sh
./gradlew generateKotlinNativeNpmMainPackage generateKotlinNativeNpmLinuxX64Package
npm pack ./build/kotlinNativeNpmPublishing/main --dry-run
npm pack ./build/kotlinNativeNpmPublishing/platforms/linux_x64 --dry-run
```

On Windows, use `.\gradlew.bat`, `generateKotlinNativeNpmMingwX64Package` and `platforms/mingw_x64`.
Choose the host package explicitly: aggregate plugin tasks can also select cross-compilable executables, whose
resident libraries are not built on a foreign host.

For an actual local publication, use a disposable registry bound to localhost, such as Verdaccio. Install its
dependencies with `npm install --prefix temp/npm-publishing/registry verdaccio@6`; keep its explicit configuration,
storage and credentials under `temp/npm-publishing/`. Do not change global npm configuration or install global
packages. In a dedicated shell, point npm at temporary configuration/cache paths before installing the registry:

```sh
mkdir -p temp/npm-publishing
export NPM_CONFIG_USERCONFIG="$PWD/temp/npm-publishing/user.npmrc"
export NPM_CONFIG_GLOBALCONFIG="$PWD/temp/npm-publishing/global.npmrc"
export NPM_CONFIG_CACHE="$PWD/temp/npm-publishing/cache"
```

In PowerShell:

```powershell
$scratch = New-Item -ItemType Directory -Force temp/npm-publishing
$env:NPM_CONFIG_USERCONFIG = "$($scratch.FullName)/user.npmrc"
$env:NPM_CONFIG_GLOBALCONFIG = "$($scratch.FullName)/global.npmrc"
$env:NPM_CONFIG_CACHE = "$($scratch.FullName)/cache"
```

Create those two configuration files, configure the temporary user file for your local registry and its test
credentials, and leave the temporary global file empty. Use an explicit public `--registry` when installing
Verdaccio itself. Start Verdaccio with `--config` pointing at the temporary configuration. Once it is listening,
publish through the plugin, substituting your local registry URL:

```sh
./gradlew publishKotlinNativeNpmLinuxX64Package publishKotlinNativeNpmMainPackage \
  -PnpmRegistry=http://127.0.0.1:4873/
```

On Windows, use `.\gradlew.bat` with `publishKotlinNativeNpmMingwX64Package` and
`publishKotlinNativeNpmMainPackage`. The plugin orders the platform publication before the launcher.
`-PnpmOtp=...` is available for interactive registries that require an OTP. Credentials remain npm configuration,
not Gradle properties. Ordinary builds neither start a registry nor publish packages.

From an empty consumer directory under `temp/`, use `npm install @czp3009/factorio-mcp@latest`, then
`npm exec -- factorio-mcp --no-http`. Also test `npx --yes @czp3009/factorio-mcp@latest --no-http` from a separate
empty directory. Keep the temporary npm environment active so both use the local registry. Send MCP `initialize`,
`notifications/initialized` and a `status` call; expect a detached server without needing Factorio. Close stdin and
check clean exit. Verify the installed executable and adjacent resident library match the release build, the
executable retains execute permission on Linux, the launcher declares only implemented platforms, and stdout
contains only protocol messages. Stop the test registry and close the dedicated
shell afterward. Recreate its disposable storage or use a new version before republishing changed artifacts.

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

Windows screenshot readback and PNG encoding run synchronously in the rendering callback. A suspended capture can hold
the
ordinary command queue until cancellation. Use on-demand capture; do not introduce tool timeouts or background
process-liveness polling to hide a stalled renderer.

The Linux `frame_readback` component is linked into the resident. Given a verified current render context and
authoritative window framebuffer dimensions, it reads the default OpenGL back buffer into bounded top-down RGB
storage. It restores the read framebuffer, pixel-pack buffer, alignment, row length and skip settings after capture
or failure. It reports consumed GL errors and rejects a pre-existing error before changing state. Native fixtures
cover byte bounds, row orientation, state restoration, unsupported read buffers and driver errors without a GPU.
The Linux Kotlin `PngEncoder` compresses top-down RGB pixels outside the game callback using standard zlib
bindings. It enforces the 16 MiB encoded limit and writes PNG signature, IHDR, IDAT and IEND with CRCs. Tests
independently verify CRCs, decompress pixel rows and reject oversized incompressible output. The screenshot
operation now connects render-hook admission, dimension checks and bounded RGB IPC to this encoder; explicit
`ScreenshotAcceptanceTest` writes consecutive PNGs for visual inspection using a selected endpoint,
PID and output prefix.
`GraphicsSwapCall` locates the native SDL device callback call within the selected OpenGL swap implementation.
It requires a contiguous device-global load, window argument move and aligned indirect call, rejecting missing or
ambiguous candidates. Wide multiplication elsewhere is explicitly decoded without propagating register values
through it. Unit and explicit installed-file checks cover this locator. Device ownership, callback ABI, live
window association and hook admission are separate requirements; the locator alone does not authorize a patch.
`GraphicsDrawableLayout` derives the SDL window identity check, both nullable size-callback slots and the raw
width/height fallback from the selected framebuffer-size getter. It validates callback argument setup and the
packed integer return, and rejects invalid-object paths reaching size storage. Synthetic tests vary member
locations and reject altered branches, aliased outputs and mismatched dimension packing. This is conditional
layout evidence; allocation bounds and current device/window ownership must still be established before use.
`GraphicsFrameSize` composes the native size getter with bounded graphics/window members, their primary RTTI
identities and the current window obtained through the validated event-pump argument. It checks reciprocal
graphics/window ownership and the native-window accessor layout. Reusing the getter requires validating those
live associations against the rendering callback; the metadata does not retain instances or install a hook.
`FrameContextMetadata` packages these fields for the native boundary and compares the consulted functions,
read-only data and selected relocated vtable/RTTI words against the loaded process. The opt-in
`GraphicsLoadedAcceptanceTest` selects `FACTORIO_MCP_TEST_PID` explicitly and performs this read-only comparison;
it neither injects nor invokes game code.
The native `frame_context` reader checks the main thread, exact callback return site, current
device, current window, concrete vtables, reciprocal graphics/window references and native window identity
before calling the size getter. It reacquires every pointer, returns only bounded dimensions and clears its
output on cancellation or failure. Fixtures cover rejected associations, foreign threads, getter exceptions,
invalid dimensions and replacement of the current graphics object.

`SdlDeviceAllocation` traces each selected backend callback store to its allocator result, resolves the SDL
calloc entry through ELF relocations and validates the unchanged-argument wrapper to imported `calloc`.
Constant allocation arguments establish the device extent; callback stores must fit that extent. Selected-file
checks cover X11 OpenGL, X11 GLES and Wayland GLES; these are static layout checks, not live backend coverage.
The resulting factory, backend and allocator evidence is included in `FrameContextMetadata`'s loaded comparison.
The fixture-linked `frame_hook` wrapper preserves entry registers, stack arguments, flags and user XSTATE before
tail-transferring to the original backend callback. Its fixture exercises both supported save modes, altered
floating-point state, stack arguments, mixed return values, native exceptions, unwind traversal and removal.
Graphics validation does not require an unchanged poll slot: polling supplies a static window association,
while capture checks the current object's concrete identity and its actual graphics/getWindow entries. The
opt-in `GraphicsKeyHookAcceptanceTest` verifies loaded graphics evidence after an SDK key dispatch installs
the keyboard hook, observes the menu pause round trip and detaches. It requires an explicit endpoint, PID and
menu key, and remains outside default tests. No render hook is installed by that acceptance test.

The resident's `FrameHookOwner` admits callback-slot installation/removal only on the main thread and
rechecks the current SDL device, field bounds and pointer ownership. Protection failures and vanished devices
retain ownership for cleanup retry. The original forwarding target remains stable across removal.
`GlLoaderBinding` traces each required GLAD slot to its exact SDK lookup string and checks all selected-slot
assignments, including null/error paths. Its explicit installed-file acceptance does not invoke OpenGL.
`FrameApiBinding` checks readable live slots and executable entry mappings; native `readFrameApi` rechecks
all six expected slot values and returns no partial API on failure. These entry-validation components are rechecked by
the resident before capture;
live acceptance is explicitly selected outside the default test run.
`FrameContextMetadata` includes the loader proof in its loaded-code/read-only comparison. The explicit
`GraphicsLoadedAcceptanceTest` also binds the six current GL entries against the process mappings, without
invoking them. Typed frame API and hook-site declarations are available through the Linux cinterop; this
does not install a render hook by itself. `FrameHookBinding` selects the current proven backend, checks the
device extent and callback page protection, and supplies a typed site for main-thread revalidation.
The screenshot operation installs that callback at the frontend safe point and waits for presentation. It
rechecks context and API slots, writes bounded RGB pixels, and lets Kotlin encode the PNG. A canceled pending
capture can complete at the frontend safe point without presentation. Detach removes the frame hook before
removing the frontend hook. `frame_resident_fixture` exercises consecutive captures, row orientation, cancellation
without presentation, stale GL slots and detach restoration through the real resident hooks and IPC, without a GPU.
Live X11 OpenGL acceptance covers consecutive main-menu captures, a running single-player world, a paused
menu after native keyboard-hook admission, resuming and detaching. Suspended-render cancellation is currently
covered by the native fixture; GLES/Wayland capture and multiplayer synchronization remain separate gates.

Linux UI snapshots currently read `toggled` for toggle-mode button implementations whose primary vtable and byte getter
are established from the selected ELF. `WidgetToggleLayout` derives the slot from the button table and
collects matching primary tables; it does not assume the method exists in the generic Widget table.
The resident matches the current table before accessing that slot, rechecks the getter entry and reads only
the bounded mode and property bytes. As on Windows, ordinary buttons omit `toggled`. The mode location is
resolved from `Button::isToggleButton`; invalid mode or state bytes fail explicitly. Unknown tables/getters
remain unavailable.
Optional property collection follows selector resolution in the same frontend safe point.
`WidgetToggleAcceptanceTest` is an explicit installed-file check. Live acceptance also verified that selecting
another new-game scenario changes the two observed button values and restoring the selection restores both values.
After the mode-filter correction, renewed live acceptance verified that ordinary main-menu buttons omit
the property while scenario buttons still expose both states and restore their values after reselection.
This does not establish coverage of other optional widget properties or all button implementations.
`WidgetCheckAcceptanceTest` separately checks the selected ELF's named `isChecked` inline predicates in
the native state-transition and event-dispatch functions. The parser requires matching receiver-relative
four-byte fields and enum comparisons. Concrete descendants are discovered from matching RTTI references;
each candidate requires a validated public nonvirtual ancestry path, independent complete-object bounds and
primary identity. The installed-file check currently covers CheckBox, RadioButton and ControlCheckBox.
Ambiguous paths, cycles, unsupported inheritance and out-of-bounds base fields are rejected.
The resident matches these concrete primary tables and reads the bounded raw state after selector resolution.
Kotlin preserves unnamed enum values as `unknown_<value>`, using the same fallback as Windows. Native fixtures
cover signed/unknown values, unmatched types, ambiguous tables and invalid bounds. Live acceptance on the
new-game replay checkbox observed `unknown_0`, `unknown_1` after a native click, and `unknown_0` after restoring
the setting, followed by detach. A dedicated single-player circuit scenario also verified ControlCheckBox:
the chest's read-contents setting changed from `unknown_1` to `unknown_0` and back through native UI clicks,
then the scenario was exited without saving and MCP detached. This does not establish multiplayer effects
or CRC agreement. A script-created RadioButton pair also passed live selection/restoration checks; the
shared Lua object query independently read its `state` as true, false and true across those changes.
Enum-name recovery remains incomplete.
`WidgetSliderAcceptanceTest` validates selected-file metadata for the slider's value, minimum, maximum and
step. Value/step getter and setter locations must agree; range setters must cross-check each other's bounds,
including reversed floating comparisons. Concrete primary tables and public nonvirtual ancestry are verified
before bounded resident reads; selected-file checks cover Slider and DoubleSlider. Native fixtures cover raw
negative and non-finite values, unmatched concrete types, overlapping fields, duplicate tables and invalid bounds.
Kotlin copies these values into the same slider result model as Windows. A dedicated single-player scenario
verified Slider value/range/step in whole-tree and selected-subtree reads, with its value independently confirmed
through the shared Lua object query. DoubleSlider has selected-file validation but no live example yet.
`WidgetProgressAcceptanceTest` validates progress direction and value metadata: two native methods must
reference the same named inline equality definition and the same bounded original-receiver byte. Inline ranges
may include validated entry callee-save pushes. The value's named inline setter must write a bounded double on
the same sized allocation that receives the verified ProgressBar primary table. Native setters are not invoked.
Text-flag resolution cross-checks the original receiver's boolean gate in matching named text-reservation
inlines, without evaluating their subsequent style/font predicates. Fields must not overlap. Concrete primary
tables and public nonvirtual ancestry are validated for ProgressBar, ControllerProgressBar and BatteryIndicatorGui.
The resident reads raw value/direction/text-flag members after selector resolution and rejects invalid boolean
bytes. Native fixtures cover unknown directions, negative/non-finite values, duplicate tables and invalid field
bounds/overlap. Kotlin uses the existing progress model and unnamed-enum fallback.
Live single-player acceptance observed all three concrete types, including both observed direction values.
A dedicated script progress bar returned 0.375 in whole-tree and selected-subtree reads, matching the shared
Lua query. Live text flags were false; true and invalid flags currently have fixture coverage only. Direction
enum-name recovery remains incomplete. The scenario was exited without saving and MCP detached successfully.
`WidgetDropdownAcceptanceTest` validates selected-file metadata for the dropdown's signed selection field,
embedded list, option-range pointers, element stride and text-widget pointer. The count accessor must subtract
two bounded original-receiver pointers; the item wrapper must preserve its hidden string output, list receiver
and index. The indexed item pointer must reach the verified Widget text slot with the original signed index and
matching stride. Synthetic tests reject mismatched receivers, widths, slots, storage, indices and bounds.
Concrete dropdown identity and ancestry are validated before resident observations. The resident copies selection
and bounded option labels after selector resolution, using the same verified text-field readers as UI nodes.
Unknown text overrides retain the selection and report unavailable options. Each snapshot resets its option budget;
fixtures verify 64 options per widget, 1024 per snapshot, UTF-8 boundary truncation, empty labels, malformed ranges
and selected-subtree budget isolation. Kotlin checks wire ranges before copying into the shared option model.
Live single-player acceptance read a closed 70-item dropdown twice, including a selected-subtree read: selection
69 was retained outside the returned 64-item prefix, matching Lua selection 70. Empty labels and rich-text markup
were preserved; a long Chinese label was shortened at a complete character boundary and marked truncated.
The scenario was exited without saving and MCP detached successfully. This does not establish multiplayer CRC
agreement or exhaustive dropdown variants.
ELF pointer readers use a bounded relocation index shared within a related table-validation batch; duplicate
relocations, partial overlaps and nonlocal pointer relocations remain rejected. Candidate references alone
are never accepted as pointer evidence.

## Tests

### Local fixtures

Run all local automated tests with the standard verification task:

```powershell
.\gradlew.bat check
```

The Kotlin suite runs common and Windows tests, including stdio and Lua fixtures. `mingwX64Test` runs just this suite;
`check` also runs the CMake/CTest fixtures through `testMingwX64Native`. Reports are under
`build/reports/tests/mingwX64Test/` and `build/native-tests/mingw_x64/mingwX64/Testing/Temporary/`.
Gradle excludes the `com.hiczp.factorio.mcp.acceptance` and `com.hiczp.factorio.mcp.offline` test packages regardless of
environment variables. Put
real-game tests in the acceptance package and installed-file-only checks in the offline package so the default test
tasks keep excluding them. Run these
external checks explicitly from the built test executable as described below. The `checkTestPackageBoundaries` task
fails the build if external-game
environment markers appear in any other test package.

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

`QueryLuaFixtureTest` in the standard `nativeTest` source set runs on both desktop platforms and executes the actual
fixed reader in a separate Lua runtime with synthetic objects. Only library loading is platform-specific. CMake fetches
the pinned official Lua source, verifies its SHA-256 and builds the fixture with the same toolchain automatically.
The first test build needs network access; subsequent builds reuse the downloaded source. The fixture is never
shipped or loaded by product code.

The finite `KeyGesture` sequence is shared in `src/nativeMain/native/key_gesture.h`. Both platform CMake test suites
run its game-independent fixture, covering every cancellation boundary for chords of one through eight keys,
reverse release order, final event-pump acknowledgement and rejected admissions. Linux also tests its event-poll
assembly adapter with a synthetic window, including real-event priority, caller frame forwarding, XSTATE preservation,
exception propagation and hook removal. The resident fixture exercises the complete command lifecycle with synthetic
GUI/window tables: exact caller/thread filtering, real-event priority, final-up acknowledgement, cancellation,
retained cleanup after a clock failure, detach and reattach. Game-free fixtures do not establish live game effects.
The keyboard event writer fixture varies field placement, preserves untouched bytes, rejects nonempty events before
writing and checks fresh unsigned clock reads, wraparound and invalid timestamps. The common SDL2 scancode vocabulary
contains public SDK constants; game event offsets, kinds, defaults and timestamp scaling still require matching
native evidence. `KeyboardEventMetadata` verifies those bytes against the loaded image and writes typed configuration;
its explicit installed-file test does not attach or dispatch input.

Linux render-flag fixtures vary receiver registers, member placement, field width, bit mask and virtual-method position.
They reject multi-bit masks, altered branches, foreign receivers, writes and out-of-bounds fields. Native snapshots
check independent parent/child render, visible and search-hidden flags; no effective visibility is computed.

Native chat fixtures check retained-list traversal, output bounds, normal submission routing and destructor cleanup
after exceptions. Common tests check observation cursors, duplicate messages, cached-text updates and invalid chat
arguments. These and Lua/mock tests do not establish authoritative multiplayer effects or CRC safety. Enable
`ExpandedQueryAcceptanceTest` against the disposable local scenario to check representative inspection/player/detail
reads and synchronized chat, including authoritative events and subsequent full CRC checkpoints.

`mingwX64Test` builds the project's debug executable and supplies its path to the stdio smoke tests automatically.
These tests run without a Factorio client. For direct execution of the native test binary, set
`FACTORIO_MCP_TEST_EXECUTABLE` to an already built executable; `--ktest_filter` can select individual tests.
For direct Lua fixture tests, also set `FACTORIO_MCP_TEST_LUA_LIBRARY` to
`build/native-tests/linux_x64/linuxX64/lua/libquery_lua_fixture.so`, or
`build/native-tests/mingw_x64/mingwX64/lua/query_lua_fixture.dll` on Windows. Gradle supplies the path automatically.

For the current Linux UI acceptance, first build normally, start an HTTP endpoint and select an already initialized
client with ptrace permission. Then invoke the built test executable explicitly:

```sh
FACTORIO_MCP_ACCEPTANCE_URL=http://127.0.0.1:3000/mcp FACTORIO_MCP_TEST_PID=12345 \
  build/bin/linuxX64/debugTest/test.kexe '--ktest_filter=com.hiczp.factorio.mcp.acceptance.UiReadAcceptanceTest.*'
```

With the same endpoint and PID, select `com.hiczp.factorio.mcp.acceptance.InputBindingsAcceptanceTest.*` to check
read-only keyboard/mouse binding
discovery, custom flags, linked owners, filtering, pagination and detach. This test is shared by the native targets
and works at the main menu. It does not change bindings or send input. Dedicated controller slots are outside its scope.

The opt-in `UiClickAcceptanceTest` additionally takes `FACTORIO_MCP_TEST_OPEN_TEXT` and
`FACTORIO_MCP_TEST_CLOSE_TEXT`, the exact displayed labels of unique `agui::TextButton` controls for opening a
menu and returning from it. Start on the menu containing the opening button, with external modifiers released.
It performs plain round trips and all seven modifier combinations through `ui_action`, reads the resulting UI
separately, checks another plain round trip, and detaches. These checks do not require OS input or screenshots
and do not establish loaded-world, capture-transfer or multiplayer effects.

`LoadedWorldQueryAcceptanceTest` is shared by the native targets. Select an endpoint and PID with a disposable
world already loaded, generated origin tiles, and at least three entities in the area from `(-32, -32)` to `(64, 32)`.
It requires the installation's runtime API documentation and checks player identity, object inspection, bounded
entity/tile results, prototype identity, native Lua error recovery, repeated
query/UI reads and detach. It does not launch or stop either process. This single-client check is separate from
the shared `WorldQueryAcceptanceTest`, which requires the isolated multiplayer scenario and full-CRC log evidence.

This checks attachment, bounded postorder UI snapshots, root/subtree selection, observed type/text predicates,
positional matching, incomplete-search rejection, detach and retained-resident reattachment. It does not establish
remaining Linux tool coverage or multiplayer effects. Gradle excludes installed-game acceptance from its default run.
The snapshot includes each widget's own visible, render-enabled and search-hidden flags.
For a client at the main menu with no world loaded, select
`com.hiczp.factorio.mcp.acceptance.WorldQueryAdmissionAcceptanceTest.*` instead to check live
query
metadata verification, missing-world rejection, subsequent UI/status calls and detach. That test does not demonstrate
a successful query in a loaded world.

### Installed-game metadata checks

For Linux world-object bounds, ownership and Lua stack analysis, select the installed ELF explicitly after the normal
build:

```sh
FACTORIO_MCP_TEST_ELF=/path/to/Factorio/bin/x64/factorio \
  build/bin/linuxX64/debugTest/test.kexe '--ktest_filter=com.hiczp.factorio.mcp.acceptance.WorldMetadataAcceptanceTest.*'
```

This reads the executable file without launching or attaching to Factorio. It verifies instruction-derived stack
access, allocation/deletion bounds and the identity-guarded global scenario member. A separate test resolves member-call
provenance and non-returning failure paths; a combined test assembles the bounded world layout. Virtual member
candidates
still require live type validation. These checks do not establish live Lua-state ownership or world-query execution.
The script-state test cross-checks the LuaGameScript pointer member at three native Lua call sites in two methods,
along with its allocation bound and primary vtable identity. It also derives the two byte flags saved and temporarily
overridden before the load handler's first named Lua call. Script selection, state identity and callable Lua ABI remain
separate checks.

Select `com.hiczp.factorio.mcp.acceptance.WidgetRenderAcceptanceTest.*` with the same ELF variable to check render-flag
metadata, relocation and
changed-code/data rejection. `WidgetRenderFlag` uses the exact `shouldRender` inline ranges within native widget
painting code. Its single-bit read must use the same receiver as the following typed Widget virtual dispatch, whose
position comes from matching RTTI/vtable symbols. Every selected range must agree on the bounded field. The adapter
reads that field at the UI safe point; it does not invoke rendering or combine ancestor flags. Consulted code and
readonly table evidence must match the loaded image before attachment.

Select `com.hiczp.factorio.mcp.acceptance.ControlMetadataAcceptanceTest.*` with the same ELF variable to check the
control object extent and
initialized input-registry name lookup. `VectorElementSize` derives the element stride from a typed destruction
loop. `NamedPointerRegistry` checks bounded empty, matched and missing-name paths, including length and byte
comparisons, pointer advancement, register preservation and native result identity. The byte comparator is identified
through its ELF import relocation. Independent compiled fixtures vary member placement; malformed guard, branch,
stride, comparison target and object bounds must be rejected. These checks neither invoke game functions nor collect
live bindings, and do not establish complete input-binding support.

The same check resolves the two keyboard/mouse value slots from the named inline active-input getter in the native key
query.
`ControlSlots` accepts verified member-pointer or member-offset selection, checks the common input-mode source,
and connects type/code comparisons to the original key argument. The controller reader independently identifies
the controller pair through its two unmodified native code arguments, so it can exclude that pair; the scalar argument
register is derived from the callee's discriminator prefix. Only the keyboard/mouse pair is returned. Slot fields must
fit the control extent and remain distinct. Compiled fixtures
reorder keyboard/controller members and vary their padding, with checks for incorrect global identity, missing inline
scope, unsupported conditions and insufficient object bounds. Other keyboard/mouse binding kinds and live
control collection require separate verification. Controller slots and controller execution are outside the tool scope.

`ControlModifiers` associates single-bit guards with native Ctrl/Shift checks and the named inline Alt check in
`checkRequiredModifiersMatch`. Original byte provenance survives register copies and reloads across native checks;
all three guards must use one bounded field and distinct masks. This derives required-key bits without invoking the
checks or reproducing the game's matching decision. Other bits remain raw. Fixtures vary both field placement and
mask values, execute every flag byte against all eight synthetic pressed-key combinations, and reject reversed
guards, compound masks and insufficient bounds. The installed-file check shares its DWARF inline index with slot
resolution. Complete live keyboard/mouse binding output remains separate work.

`LinkedReceiverPrefix` checks bounded null-terminated forwarding at function entry. It verifies both a direct loop
and an initially guarded loop, including the retained nonnull receiver, original root, pointer width and field bounds.
The installed check compares the independent `triggeredBy` and `isActive` paths. Compiled fixtures vary member
placement; corruption tests change guards, backedges, registers and bounds. This is metadata evidence only. Live
collection must separately validate registry membership, bound traversal and reject cycles within its safe point.

`LuaBooleanMember` derives custom-input prototype flag locations from the named native Lua readers. The wrapper and
prototype extents come from sized deletion; original wrapper-pointer and byte provenance must reach a bounded scalar
store inside matching `lua_pushboolean` inline ranges. The Lua stack top and value stride are resolved independently.
All normal returns must pass the original byte to that output, without changes or overlap with the type tag. Fixtures
vary wrapper, prototype and output placement, include a native call before the output, and reject changed sources,
branch-dependent values, missing inline scopes, out-of-bounds reads/writes and bypassed results. The installed-file
check covers enabled, spectating and cutscene flags. It does not invoke Lua or collect live control flags.

`ControlPrototype` combines those flags with the control's prototype pointer. `PointerMemberArgument` verifies the
bounded first-call prefix in the prototype's Lua name reader and the control's localized-name reader.
The name paths must agree on their embedded member, and the Lua reader must use the same prototype pointer as the
flag readers. These are static pointer-association checks; the adapter does not call the name reader or request
translation. It follows pointer copies and constant member adjustments,
preserves the forwarded Lua state, and rejects
unrelated calls, changed null guards and out-of-bounds members. Compiled fixtures vary object placement and include
hidden return storage. The prototype's primary vtable, RTTI and sized deleting destructor must identify the same
class. Live collection still needs to match that vtable before dereferencing a prototype and verify loaded code/data;
these metadata checks do not establish a callable localized-string return ABI.

`NativeStringLayout` resolves the native string data, length and inline-buffer accessors. `StringStorageProof`
independently derives the complete string size from its typed shared owner's sized deletion. It checks all three
owner paths (null, inline buffer and heap buffer), including the original allocation, capacity-plus-terminator byte
count, object size and preserved System V frame. The native string destructor must separately free exactly the
heap buffer through the game's own allocator and leave inline storage alone. Fixtures vary fields and storage sizes;
corruption tests reject reversed guards, changed pointers, truncation, wrong allocator targets and missing capacity
increments. This establishes storage and destruction only; each function producing a returned string still needs
its own receiver and hidden-output ABI validation before the adapter can invoke it.

`StringResultStorage` identifies the original output argument through matching native inline constructor and length
setter ranges. The data pointer must be initialized to that output object's own inline buffer, and the selected
length store must not bypass initialization. Direct output accesses remain within the independently established
string size. Calls from the selected native caller must supply the complete output in a reserved, aligned frame
region that does not overlap saved preserved registers. Compiled fixtures vary the layout and execute both ordinary
C++ calls and an explicit output-first bridge, including destruction. Negative tests cover changed arguments,
missing or partial inline ranges, initialization bypasses, bounds and caller frames. This proves hidden output
placement only; receiver/other arguments require separate evidence, and callers must not assume a useful RAX result.
The decoder recognizes ordinary `BSR` instructions in native callers; provenance analysis invalidates their
destination, and scalar analysis rejects unsupported results or flags instead of retaining earlier values.

`ByteViewArguments` identifies the requested data pointer and size through imported `memcmp`/`bcmp` calls. Full-width
original input words must survive register copies and private stack spills, and every selected comparison must agree.
It follows the same words through named calls or a sole tail-call exit without assuming `string_view` field order.
Additional original arguments and complete zeroed arguments can be required explicitly. Compiled fixtures reverse
the aggregate's word order and check matching/different bytes (including embedded NUL), repeated lookups, discarded
extra parameters and actual forwarding. Negative tests reject ambiguous or truncated words, conflicting dispatches,
changed output arguments, missing zeroing, additional exits and escaped private frames.

`EmptyStringOutput` checks hidden string output placement using native constructor evidence.
A complete named native empty-string constructor must initialize one original output pointer's data, zero length
and inline terminator using the independently resolved string layout. All paths reaching that constructor retain
the existing private-frame checks; unrelated branches with borrowed locals are outside this point proof. The selected
native caller must reserve the complete output storage. Compiled fixtures exercise ordinary C++ returns and the
explicit output-first bridge for empty and nonempty results, including destruction, while varying field placement.
Synthetic checks cover private pointer spills, truncation, exposed storage, wrong fields and incomplete constructors.
This identifies the hidden output argument; other arguments and live effects require separate validation.

Input-binding observations expose control IDs and structured bindings without invoking localized name or description
readers. UI text, cached chat text and raw localization expressions retain the game's original values.
Byte compare-exchange decoding requires explicit opt-in. Pointer analysis discards the implicitly written accumulator;
scalar analysis rejects its unsupported result and flags. Stack-layout and jump-table analysis continue to reject it.

`ControlGuiFlag` resolves the GUI flag passed by the final linked owner to both native value checks. The value
check's bounded entry prefix must use an unchanged original byte argument to guard the first named GUI query;
no entry path may bypass that test or gate.
After the independently proven linked-owner prefix, scalar analysis starts with only that retained receiver as
an object reference. Both call arguments must derive from one bounded byte field and implement the same single-bit
extraction for every possible byte value. Compiled fixtures vary field placement and mask, exercise linked and
unlinked owners, and test every flag byte against both synthetic GUI states. Corruption tests reject foreign roots,
changed widths, different fields between calls, compound/altered bits and incorrect gate/call targets. This checks
native data flow without invoking the input checks or reproducing their action-eligibility decisions.
The linked owner establishes the member layout only. The portable control snapshot continues to report each
control's own `gui_input` field rather than substituting its binding owner's flag.

`ControlUsage` traces the native ContinuousAction argument through original scalar bytes, private spills and calls
to its modifier check. A bounded callee prefix establishes the original four-byte scalar spill before the first named
required-modifier call. Both control value checks must receive the same conversion of one bounded owner field: a
single equality discriminator with constant outcomes. The expression must have no other dependency on that field.
`PrivateValueCopies` tracks explicitly selected scalar arguments without relaxing pointer provenance or allowing
borrowed locals. Fixtures vary field placement, comparison values and native enum outcomes; corruption tests reject
partial/changed copies, exposed storage, non-equality conversions and extra field dependencies. This resolves raw
usage values and their native conversion only; enum display names and live binding output require separate work.
Guarded switch decoding also accepts an unsigned byte comparison followed by a matching zero extension into the
address index. The copy must retain the compared byte, and no edge may bypass the guard or table calculation.
Signed/partial copies, different registers and invalid table targets are rejected. Event readers requiring a
complete discriminator continue to reject byte-only guards while retaining their existing 32/64-bit forms.

The default-script test separately specializes the native command function's empty-string path without executing it.
It checks the empty-range and name-length guards, every compared name byte, and the common null-result path.
The Lua allocation test follows the allocator's non-null initialization prefix, verifies allocator/userdata arguments,
and derives the embedded global object and main-state self-reference within the allocation bounds. It also resolves
the stack-capacity guard using the independently derived top member and value stride. This is file-only evidence;
live state identity, readiness and each invoked function's ABI still require validation.
Additional file-only checks verify protected-callback argument forwarding and normal state restoration, the complete
zero-index stack-reset path for a well-formed frame, string-push arguments across collector paths, and the XMM0 double
argument on the number-push path with sufficient capacity. The string-result check follows the native string path with
a non-null size output, verifying the state/index arguments, size_t write, returned data pointer and restored registers.
Matching game-independent fixtures vary member padding and
value stride; mutation tests reject changed arguments, bounds, guards and frame handling. The protection check does not
interpret exception landing pads, and the push checks establish argument placement rather than reimplementing Lua.
The string-push return value is unused because the optimized entry may omit it. Number pushes still require a runtime
capacity check before admission; file analysis alone does not establish that precondition.
Reader checks derive the parser's first-refill callback and its state, userdata and local size output. A separate loader
check cross-validates these fields against the private stream initialized by `lua_load`, including its zero initial
count and original arguments. It also locates the parser's name/mode argument storage and the protected call's saved
stack displacement, error handler and non-yield counter. Parser checks cross-validate the original mode argument at
both binary/text character searches and the original name argument at its first-byte read. The shared provenance
analyzer permits only the reader's independently verified local size output and invalidates that region after the call;
unproven frame exposure remains rejected. A further check derives the protected-call callback record, result-count
width and disabled-yield dispatch from either a tail call or an ordinary call/return. Result-provenance checks verify
that raw protection's int status reaches normal returns of both the internal protected call and the loader, including
cleanup calls that overwrite caller-saved registers. Unsupported status spills are rejected. The outer protected-call
check specializes both query argument counts with one result, a zero error handler and no continuation. It verifies
the function/record construction, original state, saved displacement, typed dispatch, returned status and frame
restoration. It distinguishes five/six-argument entries through continuation provenance and derives the native status
and stack guards. Initializer analysis independently identifies an embedded base frame, bounds its accessed fields
within the state allocation, and verifies allocator/userdata provenance before admitting allocator-returned storage.
It does not claim to recover the entire CallInfo type or execute initialization. Native fixtures exercise these ABIs,
initialization, mode/name checks, result preservation and dispatch with different padding; they run without a game.
The native world-reference fixture checks unique primary-vtable/RTTI selection, changed roots, cancellation, missing
objects, overlapping members and inaccessible pointers. The reader retains no object references between calls and
supplies the Linux query endpoint. The script-reference fixture checks first-element selection, exact raw
name bytes, script RTTI, changed ranges and rejected pointers. Script references still require separate readiness,
Lua allocation/stack and entry-ABI validation before query execution. Ordinary Gradle tests instead use
compiler-generated
fixtures with different member layouts, allocation sizes and value sizes, including calls, atomic loops, virtual
dispatch
and DWARF no-return declarations.

The Linux script reader rejects active loading, a disabled script and non-boolean flag bytes. The native Lua query
fixture exercises the execution adapter against the pinned test-only Lua library: source loading, arguments and viewport
values, both protected-call parameter forms, bounded results/errors, a busy stack, cancellation and stack restoration.
It covers Lua longjmp and C++ exception propagation through the callback. The endpoint verifies game function addresses
before use and reacquires world, local player, default script and Lua state in the same frontend callback.
A composed fixture exercises that complete chain with the test Lua runtime, including changed roots/player indices,
script loading, selection failure, cancellation and recovery after a Lua error.
Reading the selected installation's runtime API documentation uses the same common Kotlin code
on both platforms.

The Linux Lua-state reader combines these derived fields, verifies the embedded global/main-state identity,
requires the base call frame and zero native status, and reports the protected handler, element count and capacities.
It rejects overlapping/out-of-range layouts, malformed pointer ordering/alignment and unreadable memory without
retaining references. The execution adapter now requires that layout: it rejects a busy stack or preexisting protected
handler before admission, checks the callback's handler and exact stack progress after loading and each push, and
checks fresh capacity before every push. After protected execution it validates cleanup even when cancellation was
requested; failed cleanup cannot produce a successful query result. Fault fixtures cover missing loaded/pushed values,
changed capacities, missing protection/callbacks and ineffective stack cleanup. These tests use private headers only
from the synthetic test Lua runtime, never as a game layout. Successful live-world query acceptance remains outstanding.

`LuaApi` combines all entry/layout proofs, including the internal wrapper's callback arguments, error-handler argument
and saved stack displacement. It relocates the validated entries and provides full function-byte verification against
the live process for the entry and supporting functions. Byte-comparison bounds are distinct from the smaller
instruction-analysis bounds. The explicit file-only `assemblesLuaQueryApi` check exercises this combined resolution;
it does not establish live script ownership, local-player selection or world-query acceptance.

Player checks derive allocation bounds from named allocation/constructor calls at matching DWARF line boundaries,
typed pointer members from argument stores/calls, and the unsigned native index from the value converted and stored
by the Lua index reader. The native local-player predicate is checked across null and pointer-equality cases against
the independently typed Game/GameView members. Primary RTTI validation permits secondary vtable groups without
interpreting their entries as callable methods. Runtime player reads validate object bounds, primary type identities
and fresh index values. `assemblesWorldQueryMetadata` combines the layouts and Lua API without starting a game;
the endpoint additionally compares every consulted function range with the live executable before first use.

UI lifetime metadata checks also read primary class RTTI and direct base descriptions from the matching ELF,
rejecting unsupported metaclasses, unresolved references, private/virtual bases and out-of-bounds subobjects.
A bounded instruction interpreter verifies the null-target arm of the native targeter assignment across empty,
head, middle and tail link states. It checks unlinking, clearing, frame restoration and preserved registers using
symbolic pointers, with no concrete game addresses. Compiler-generated fixtures vary padding and field order;
corruption tests reject missing cleanup, foreign receivers, calls and register clobbering. A native helper validates
live targeter links before calling that null-target operation and checks its resulting links. Native fixtures cover
head/middle/tail removal, idempotent empty cleanup, inaccessible pointers, broken links, exceptions and partial cleanup.
A separate proof admits fresh non-null assignment into zeroed, stable targeter storage, checking both empty and
occupied destination lists. Its native helper rejects already registered storage and validates the new links.
An exception after registration retains ownership for explicit release; the helper never replays that assignment.
The native lifetime-clearing operation is checked against the same layout, including empty lists, multiple records,
unchanged unrelated storage, register preservation and decreasing loop progress. `TargetReference` owns fixed,
nonmovable list storage and retains ownership until explicit release succeeds. `UiTargetReference` binds a selected
widget to its original root and reacquires current addresses at each safe point. Borrowed dispatch targets carry
that reference through the existing lifetime checks before and after callbacks. Root access remains available for
capture cleanup when the selected widget disappears. Native fixtures cover invalidation, address reuse, clock and
dispatch callbacks, replacement roots, release failure and retry. The complete persistent action command remains
pending.
The hover entry guard and widget-destruction path independently identify a GUI target and its associated constant
state resets. The latter proof checks original receiver/argument provenance, the Widget base conversion, every
empty/head/middle/tail case, and unchanged state for a different destroyed widget. Compiler fixtures vary the targeter
and Widget layouts; invalid bounds, reversed guards, incorrect links and unexpected calls are rejected. These checks
support the pending UI action adapter; they do not establish live capture cleanup or action acceptance.

Mouse-event metadata checks derive the output/source/replacement-Widget arguments, copied storage extent, source
field and coordinate fields from the native coordinate-rebasing function. They verify both guarded parent walks,
named padding-method slots, complete copied storage, bounded writes and frame restoration. Compiler fixtures exercise
separate and packed coordinate stores with different field/slot layouts. Widget dispatch checks independently prove
that the original Widget and event reach the typed virtual handler. These register-argument checks discard saved
frame evidence across calls and external writes because native dispatchers lend local lifetime sentinels to callbacks;
the stricter frame/output analyses retain their existing rejection rules. Tests reject foreign arguments, missing
parent guards, unknown slots, writes into the source event, excess reads, damaged saved registers and arguments
reloaded from potentially modified local storage. A separate bitset-gate proof derives the event and Widget fields
whose nonzero intersection dominates every typed mouse dispatch. It checks reaching definitions, original event
provenance, widths and bounds, and rejects inverted or bypassed guards. This establishes a native mask field, not
the meaning of its bits.

The input-conversion decoder also resolves bounded, signed-relative switch tables from read-only ELF data. It
verifies the unsigned upper bound, zero-extended address index, table calculation and every target's instruction
boundary. Direct branches, other switch cases and local calls cannot bypass the guard or enter the calculation.
Compiler-generated switch fixtures and malformed-table tests run without the game. The installed-ELF check validates
the native input conversion's tables.

Local aggregate checks use those verified control-flow edges to establish register-held frame addresses and fresh
writes immediately before a typed event call. They derive constant writes and fields that equal the actual receiver,
without recovering pointers from saved local contents. They stop at calls/joins and discard prior writes on possible
external aliases; partial overwrites cannot preserve an earlier whole-field value. Read-only literal-pool loads retain
their exact byte ranges for loaded-executable comparison. This narrow analysis does not authorize unrelated memory
reads, stack restoration or unwinding. Compiled event fixtures vary padding and use separate native dispatch functions;
tests cover branch bypasses, callback/alias invalidation, changed receivers, packed literal stores and invalid local
arguments. The installed-file check verifies construction writes for the GUI logic's mouse-down, mouse-up, mouse-enter
and mouse-leave calls, cross-checking source fields with the independent event-copy proof. Complete event-field and
button/modifier interpretation, action integration and live acceptance remain separate requirements.

Input-clock fixtures exercise both initialization states, native `gettimeofday` output bounds, register restoration
and the floating return. The decoder enables implicit wide multiplication only for analyses that model both output
registers. Tests reject foreign writes, unverified calls, incompatible output sizes and invalid or changing divisors.
Installed-file checks also bind the concrete clock override to the GUI's input pointer through matching RTTI and
virtual slots. The abstract input base has no standalone allocation bound; only its vptr is read, within the verified
complete handler object. Member-call analysis includes repeated loop visits and requires a fresh matching receiver
after call clobbers. These checks prepare event dispatch; they do not establish live action support.

Typed event-construction checks derive the returned element's stride and surviving enum/time argument stores.
They include every normal return path, reject cycles and escaping branches, and invalidate evidence on overlapping
or potentially aliased writes. Separate array-pointer loads retain distinct identities. Compiled fixtures vary
padding and element size; an installed-file test applies the same analysis to the game's typed event constructor.
This establishes header metadata only, not payload enums, nontrivial member ownership or a callable constructor ABI.

Local-field copy checks follow individual load/return bytes through register moves and local storage to a typed
reference or outgoing stack argument. Outgoing storage requires aligned, reserved call-frame space; callee
classification is checked separately. The checks preserve low-byte evidence through wider moves, forget local contents
at calls or possible
external aliases, and intersect evidence at joins. Separate register-only argument tracking includes call clobbers
and repeated loop visits, without recovering saved pointers. Compiled modifier fixtures and corruption tests run
without the game. Installed-file checks trace the Event timestamp and native Control/Shift getter results into the
mouse-input queue argument. These are path-specific copies; getter ABI and later GUI conversion remain separate
requirements before runtime use.

Queue-append checks independently derive the MouseInput element extent. They verify a complete ordered copy of the
same local argument into a cursor, the matching last-element boundary comparison leading to the typed slow append,
and advancement of that same cursor. Different paths must agree on the queue association and stride. Compiled
fixtures vary queue placement and element size, with fast/slow runtime checks and malformed-copy/guard tests.
`MouseInputLayout` combines this bound with the timestamp, Control/Shift copies and conditional inline Alt evidence,
rejecting overlapping or out-of-bounds fields. Remaining input fields and MouseEvent conversion are not established
by these checks.

Scalar-decision checks compare ordered calls, returned-pointer reads, equality branches and result expressions
between a named getter and an inlined computation. They retain call/read order and native constants while ignoring
register allocation and private frame setup. Loops, external writes, unknown inputs and unsupported operations reject.
Compiled fixtures vary field placement and lookup keys and check values and call counts without the game. The
installed-file check identifies the inline Alt result's later local copy. This evidence is conditional on the lookup
argument contracts and receiver identity; it does not authorize invoking those helpers, which may insert or allocate,
or establish returned-object bounds. Those obligations remain separate before runtime use.

Selected-byte copy checks also distinguish adjacent fields read together in one machine word. Handler modifier
checks associate the independently identified mouse-input fields with unique original-handler byte sources, bound
every source read, and reject overlapping modifiers and queue pointers. Compiled fixtures exercise packed and separate
loads with different padding, all modifier combinations, truncated copies and foreign receivers. The installed-file
check establishes this source-to-output relationship only; state-update ABI and subsequent GUI conversion remain
separate requirements.

Inline-accessor checks preserve DWARF address-versus-length forms and decode bounded DWARF 4/5 range lists,
including indexed address/range tables. They require one complete definition matching the ELF symbol and unwind
range, and reject nested ranges outside their owner or boundaries inside an instruction. Large LTO units are
streamed; the resolver retains function offsets rather than every DIE's attributes. Named reads must retain their
original argument address, access width and independently established object bound. Incoming stack reads can be
cross-checked against at least two reference-argument fields with identical abstract getter origins and an agreed
object base. Saved pointers, clobbered registers, conflicting paths and missing optimized ranges cannot provide
that evidence. A coalesced read identifies only its complete span, not an individual Boolean within it. Compiled
line-table-only fixtures exercise DWARF 4/5, different member placement, register and stack arguments, malformed
ranges/origins and lost inline evidence without an installed game. These are field/location checks, not permission
to invoke an enclosing function or evidence of working resident actions.

Inline frame-copy checks identify complete external-object copies into private frame storage within named inline
ranges. Field-copy checks then follow fresh suffix loads from the identified object into independently bounded
reference or stack arguments. Compiled fixtures exercise reordered fields, packed reads and both call forms.
`MouseEventFields` cross-checks coordinate/source rebasing, reference and stack getter identities, the wheel value,
and native timestamp/modifier copies. A packed Alt/Control read is separated only by independently traced byte copies.
These checks identify fields; they do not establish object lifetime across arbitrary intervening callbacks.

Named construction checks require a straight path from an inline constructor to its consumer, complete fresh
field coverage and no previous-output reads, external stores or intervening callbacks. Overlapping initialization
stores retain only each byte's final owner; partially overwritten pointers cannot establish receiver identity.
Compiled fixtures vary padding, including overlapping zero-initialization stores. Mouse construction checks bind
the source to the dispatch receiver, derive event types from native constants and reject unknown dynamic or nonzero
fields outside the identified members. Read-only literal bytes remain evidence that must match the loaded image.
Enter construction separately traces its previous-widget field through private locals, loop joins and native calls
with independently bounded event-reference arguments. Those exposed frame ranges remain invalidated by later calls
or external writes. Unbounded/escaped frame aliases, truncated pointers and ambiguous joins are rejected. The native
nullable base conversion must agree with the matching Widget RTTI; the GUI receiver and member bounds must also match.
The current source field is excluded using its independent receiver proof. Both enter sites must agree on the remaining
field, source GUI member, event type and defaults. Runtime enter reads that actual previous GUI target and validates
its current widget lifetime before and after obtaining the clock; later phases use their independently verified zero
default for that field. Compiler fixtures vary base and member placement and exercise null and nonnull previous targets.
Input-state synchronization, remaining dispatch details and callback cleanup still require verification before
resident actions are enabled.

Integer value slices preserve read sites, access widths, truncation, shift-count masking, signed/unsigned comparisons
and conditional moves/set instructions. Byte and word writes combine their result with the proven prior upper bits;
they never assume that a condition result zeroes the rest of a register. Scalar address arithmetic preserves 32-bit
wrapping. They reject ambiguous reaching definitions, unsupported flag producers, unproven entry
registers, call clobbers and unbounded argument reads. Compiled fixtures vary member placement and guard constants
and exercise negative/out-of-range shift counts without the game. Input-mask checks require a bounded fresh queue
store and a native guarded single-bit computation using the Event type and a separate scalar field. The proof
identifies its exact store site; the separate controller-to-mouse path cannot supply that evidence. These checks do
not assign button names or input-update semantics. Reverse frame-copy checks separately identify directly copied wheel
and modifier/time fields. The GUI's conditional replacement of the button value is not treated as a direct copy.
Each symbolic input remains a separate read; this analysis does not establish immutability across callbacks.

Guarded byte-table checks identify the original argument byte, its bounded integer transformations, an unsigned
range guard, a zero-extended address index and the read-only scalar table. Every input byte is evaluated against
the decoded index arithmetic; table values are read from the selected executable. Intervening entry edges, changed
indices, unsupported guards and truncated tables reject. The resulting evidence retains the table address and values
for later loaded-image comparison. An entry-path check binds the public SDL2 mouse-button fields and all
left/middle/right
press/release cases to the lookup. Every branch must have a proven predicate over the SDK input. Calls, loops, unknown
inputs, external writes and frame restoration reject; only fresh local-frame stores can precede admission. The SDK
constants describe the public library ABI and introduce no SDL runtime dependency. Native event kinds, downstream
payload writes and loaded-table comparison still require separate checks before runtime use. Compiler-generated fixtures
vary field
placement and index adjustment and exercise all byte values. The decoder distinguishes packed integer-to-float
conversion, byte arithmetic and locked memory decrement; atomic status flags cannot become an ordinary comparison
proof. A full-width increment/decrement can establish a zero-extended switch index, while a byte write cannot.

Indexed payload checks follow the table result into a queue element without losing its register definition across
calls or joins. They connect the queue receiver to a typed native call, derive the element stride and require matching,
nonoverlapping type/time/code stores. Native transition values are evaluated from the SDK type, including SETcc and
preserved upper bits. Compiled fixtures vary input placement and element size and exercise optional queue growth.
These are file-only data-flow checks; they do not authorize calling the optimized conversion function or establish
input-state synchronization, GUI dispatch, callback lifetimes or cleanup.

InputState object checks derive complete-object bounds from its typed nonvirtual deleter and independently bind
the control/shift/inline-alt receivers to one bounded member of the named global context. Pointer copies must retain
their full width and unambiguous reaching loads. The native update switch is decoded with exact byte/word signed
extensions and rotate operand widths. Selected mouse cases must read the proven Event fields through the original
event argument and update the same bounded receiver member with complementary single-bit set/clear masks. Fixture
layouts, case order and input codes vary independently of the game's values. These checks identify the primary held
mask and argument data flow. The selected event initialization and remaining update effects are checked separately
before these values can be used by runtime clicks.

The Linux resident includes a single-edge mouse-state adapter. It fills bounded scalar event storage, reacquires the
global/state identity around native update and post-update calls, and reports which calls entered and returned.
It preserves the first failure, performs no retries or effect checks, and leaves admission, cancellation and held-input
ownership to its caller. Native fixtures exercise both edges, exceptions, changed owners, inaccessible state, invalid
metadata and successful dispatch without an effect. Kotlin checks prove that the selected mouse post-update paths only
read the event type and restore their frames. Combined metadata retains function evidence and consulted immutable
non-executable data, including lookup and jump tables, for loaded-image comparison. Relocated writable/RELRO pointers
still require their separate identity checks. `MouseButtonOwnership` pairs the selected button's edges, rejects an
already held button, preserves unrelated bits and retains ownership after uncertain release. An entered release is
never replayed; cleanup reconciles the authoritative service and held bit before requesting a timestamp. A vanished
service can discharge its old ownership even if the GUI clock has also disappeared. Selected native Event copy paths are
checked
against the derived mouse types: bounded scalar/vector copies must preserve the identified fields byte for byte,
restore preserved registers and avoid calls or source writes. This copy evidence does not establish destruction
semantics or authorize unverified event reads in other native functions.
The complete selected update branches are checked for Event pointer uses, including loops and control-flow joins.
Direct reads must stay within the identified type/code fields; writes, pointer arithmetic and pointer stores reject.
Call sites with a possibly live Event pointer in argument registers are retained for analysis. Register liveness does
not establish that the callee consumes that register. ABI verification applies to the adapter's actual entry points
and data derivation; unchanged native calls inside the game do not create additional foreign-call boundaries.

Keyboard metadata derives key press/release cases, the native key-code argument and held-state stores from the same
update entry. It checks the complete selected post-update paths and reuses `InputEventUses` to bound event reads.
The successful keyboard arms of the named Ctrl/Shift/Alt getters identify their native key codes and the additional
byte they test; those bytes must be cleared by the paired post-update path. The typed lookup's linear search and
existing-key return establish map bounds, record stride, key and value positions, with agreement between both lookup
overloads. Compiler-generated fixtures vary padding, key positions and record sizes. `KeyboardStateMetadata` retains
the consulted function/data evidence for loaded-image comparison, supplementing the shared mouse-state owner evidence.
The native key-state reader is passive: it never calls the allocating lookup, bounds traversal, validates sorted unique
keys and boolean bytes, and clears outputs on failed reads. Its fixtures cover missing keys, malformed ranges, duplicate
keys and inaccessible storage. `KeyboardKeyOwnership` uses this reader to reject held/blocked keys, validate newly
created records before GUI admission and reconcile cleanup after uncertain release. The same native entry adapter
tracks update/post-update progress for mouse and keyboard events, with fresh service identities around each call.
Entered releases are never replayed. Click modifiers use these components; timed keyboard input remains unfinished.

The resident's action target resolver requires a complete traversal and exactly one selector match, checks the target's
ancestor enabled/destruction flags, and collects geometry only for that target. Target addresses remain inside the
current frontend callback. A separate liveness check reacquires the authoritative GUI/root and traverses current child
ranges without text or geometry callbacks. The single mouse-event dispatch adapter uses this check before and after the
native callback, writes the resolved event fields into aligned bounded storage, and reports entered/returned progress.
Timestamps come from the verified native input clock, not a caller-provided value or fallback clock. The clock reader
checks the current GUI/input association, concrete table and slot target before invoking the double-return getter,
then checks the input identity again and rejects nonfinite/negative results. Mouse dispatch revalidates its target
after reading the clock as well. Native fixtures cover unknown/missing/replaced/unreadable handlers, changed tables,
invalid results, exceptions, and root replacement during clock acquisition without a subsequent widget callback.
Target/root removal is a normal callback outcome; exceptions preserve the original dispatch failure. Native fixtures
exercise mutations and exceptions, invalid layouts/coordinates, canceled admission, ambiguous selection and incomplete
or cyclic trees. Plain clicks combine these primitives with modal admission, event construction, input-state mirroring,
gesture sequencing and capture cleanup through `UiClickCommand`.
The mouse-down resolver derives the single-bit native Widget gate controlling its nested click call, verifies the
original widget/event arguments and requires the nonzero flag edge on every path to that call. Fixture object padding
and flag bits vary, and native execution checks the additional dispatch. Click construction is resolved independently
alongside down/up/leave construction. These results supply the finite gesture configuration.

The native capture adapter remembers the capture present before a gesture and preserves that ownership. Cleanup
reacquires the current GUI/root, resolves a transferred capture through a fresh traversal, obtains its rectangle and
sends at most one up at the original absolute gesture point expressed in that recipient's local coordinates. It then
unlinks the current capture and applies the dynamically resolved GUI reset stores. A callback may remove its widget,
transfer capture, restore the original capture or replace the root; each case is checked before subsequent access.
Callback exceptions preserve the first failure while cleanup continues. Failed unlink/reset operations retain their
unfinished state without replaying up or completed reset stores. The original failure remains the terminal result
after cleanup succeeds. Failures before entering a captured widget's up handler retain that pending up rather than
unlinking its capture as if the callback ran. The borrowed runtime view is confined to one safe point.
Native fixtures include transferred capture, exceptions, destruction, root changes, missing targets, invalid geometry,
failed unlink and a read-only page interrupting the reset between fields. `UiCaptureMetadata` combines the dynamic
layout/reset proofs, writes the typed native layout and compares consulted function bodies with the loaded executable.
Real-game capture-transfer acceptance remains outstanding.

The finite GUI gesture adapter sequences enter, down, optional click, up and leave within one safe point.
It reads the native click-on-down flag after down returns, rechecks lifetime around every dispatch, and releases
capture acquired or transferred by callbacks. Each phase records entry separately from return: an exception does
not authorize replay. Cleanup preserves the first error and retains unfinished up, leave or capture work.
Leave waits until the first capture cleanup completes, then a separate bounded phase releases capture acquired by
leave itself. An up delivered through capture cleanup
also discharges the original target's pending up when the recipient matches. Completed gestures cannot be run again.
Fixtures exercise exceptions at each phase, flag changes during down, removed widgets, replaced roots, transferred
capture, pre-entry clock failure and failed unlink followed by cleanup without repeated callbacks.

`UiMouseCommand` retains pointer-free mouse/capture progress and stable native lifetime references across callbacks.
It reacquires the original root and selected target before continuing cleanup, protects the previous-widget argument
around the clock callback, and tracks transferred capture recipients independently. A changed capture between
callbacks is not released without proven command ownership; the command retains its resources and failure instead.
Reference-release failures also keep the command unfinished, and retrying those releases never replays the gesture.
The first failure remains available after all cleanup succeeds. Native fixtures cover deferred up/leave, repeated
unlink failure, foreign capture, destroyed roots, invalidated previous widgets, reference-release failure and capture
acquired by leave.

`UiClickCommand` combines the finite gesture with mirrored button ownership. It copies its configuration before
admission, selects against the complete live tree, checks modal ownership and obtains native input time. Both GUI
and input cleanup retain the first error; a later cleanup operation only resumes unfinished releases. The resident
keeps one fixed command while cleanup is owned and refuses other operations except cleanup/detach. Kotlin checks
that ownership before preparing another payload, including after reconciling an abandoned completed request.
Detach cannot remove the frontend hook while action resources remain owned. `ui_action` uses this path for
left/right/middle clicks with optional Ctrl/Shift/Alt. Modifiers
are mirrored before the button and held through GUI cleanup, then released in reverse order after the button.
Native fixtures cover all three buttons, every modifier combination, each GUI phase throwing, input exceptions,
cancellation before/after admission, rejected selectors, preexisting button/key holds, blocked keys, uncertain releases
and delayed cleanup after the original IPC configuration is overwritten. These are game-free checks,
not evidence of multiplayer effects or complete action parity.

Text-entry metadata checks verify `Widget::focus` forwarding and the `TextBox::keyDown` virtual handler boundary.
`KeyEventFields` cross-checks named text-editing getters against the embedded event constructed by `Gui::logic`
and passed to its keyboard listener dispatcher. Initialization defaults come from the matching GUI constructor;
padding and fields the constructor leaves unwritten are not invented. `NativeScalarFunction` evaluates selected
integer conversion paths with verified switch tables, without calling game code. Compiler fixtures vary event and
owner padding, and negative tests cover unknown arguments, missing defaults, aliasing writes, changed return paths
and damaged frames. `KeyInputFields` traces input bytes from the named keyboard dequeue into these event fields,
retaining their source identity through private scalar spills. `KeyInputConversion` follows the actual event producer's
control-key overrides and extended-key output after its pure keycode conversion. Unknown branch conditions, outputs,
calls and escaping private addresses are rejected. Select
`com.hiczp.factorio.mcp.acceptance.WorldMetadataAcceptanceTest.resolvesTextEntryAbi` for the
field/entry check or
`com.hiczp.factorio.mcp.acceptance.WorldMetadataAcceptanceTest.composesTextEditingConfigurationAndChecksLoadedEvidence`
for the
complete configuration, relocation and changed-code rejection check. These installed-file checks do not dispatch
actions.

`UiTextCommand` implements `set_text` through native focus, select-all, backspace and Unicode key events. It obtains
fresh native input time and reborrows/recasts the target after callbacks, preserving native editing restrictions.
The bounded request and configuration are copied before admission. Cancellation stops further edits while reference
cleanup retains ownership and the first failure until it finishes; cleanup never replays edits. Native fixtures cover
Unicode and empty text, wrong target types, invalid scalars/layouts, callback exceptions, cancellation, target
invalidation,
overwritten request/configuration and retryable reference cleanup.
The shared `UiTextAcceptanceTest` is opt-in and uses the public HTTP contract. Set `FACTORIO_MCP_ACCEPTANCE_URL`,
`FACTORIO_MCP_TEST_PID` and `FACTORIO_MCP_TEST_TEXT_SELECTOR` (a selector JSON object) for a disposable search field.
Optional `FACTORIO_MCP_TEST_TEXT_OPEN` and `FACTORIO_MCP_TEST_TEXT_CLOSE` contain JSON arrays of text-button labels for
menu navigation. Run `com.hiczp.factorio.mcp.acceptance.UiTextAcceptanceTest.*` explicitly from the built test
executable. It checks ASCII, Unicode,
replacement and empty text through separate UI observations, restores the original text, then detaches. It does not
launch or terminate processes and does not establish coverage of every text-control restriction or multiplayer effects.

`UiKeyCommand` implements finite `press_key` chords through the native event pump. Optional target selection/focus runs
at the GUI safe point, with lifetime revalidation after callbacks. An omitted selector preserves current focus and
clears any previous selector payload. Only empty native polls at the verified main-thread caller may produce a key
edge. Every admitted down owns a reverse-order up; cancellation and failures retain owed releases for cleanup, and
completion waits for the pump acknowledgement after the final up. Native fixture checks cover failed event writes,
focus destruction/exceptions and retryable reference cleanup. `UiKeyMetadataAcceptanceTest` explicitly selects
`FACTORIO_MCP_TEST_PID` to compare live code/data and check relocated configuration without injecting or sending input.

The poll wrapper also preserves entry registers, flags and floating state around a pre-poll interceptor. Outside
an explicit synchronous event-pump scope, it forwards to the unchanged native poll. Within a verified scope,
`EventPump` provides at most one event and then empty results without consuming real window events. Only the
verified main-thread call site can consume that payload; interception bypasses the ordinary empty-poll callback.
Its progress distinguishes event delivery, the following empty poll and return from the pump. Writer failure,
native exceptions and early returns do not replay a delivered event. Fixtures cover both interception and
forwarding, native exception unwinding, reentrant/foreign-thread rejection and failed event construction.
No product command opens this scope yet: Linux timed input still requires the native pump's callable ABI and
phase validation, evaluation hook and concrete keyboard/mouse routing before using it in the game.

The shared `UiKeyAcceptanceTest` uses the same URL, PID and disposable text-selector variables as the text test.
Run `com.hiczp.factorio.mcp.acceptance.UiKeyAcceptanceTest.*` explicitly from the built test executable. It checks
selected/current focus, Backspace,
Home/Delete, Ctrl+A and editing after every Ctrl/Shift/Alt modifier combination through independent `ui_read` calls.
It restores the original field contents and detaches, leaving menu navigation to the operator. These checks do not
establish every key binding, game-world effects or authoritative multiplayer safety.

`MouseGestureMetadata` combines event construction, all five dispatch ABIs, clock binding, capture and modal layouts,
and button identities in a typed native configuration. Button masks are traced from the SDL conversion through the
input queue into the actual widget event field, with a bounded conditional copy proof. Local construction copies
outside the outgoing event object do not count as additional fields. Configuration uses the public left/right/middle
order and verifies native phase defaults, object sizes and targetable-base agreement. Consulted functions and constant
bytes must match the loaded image before use. `composesMouseGestureConfigurationAndChecksLoadedEvidence` is an
explicit installed-file check; it does not dispatch game actions.

Modal admission derives the GUI stack bounds, record stride and targetable conversion from matching named
`getModalWidget` and `widgetIsModalChild` inline code. A bounded symbolic evaluator checks null/identity cases,
reverse scans and parent traversal across varied modal records and ancestor relationships. It accepts only known
pointer fields and supported scalar/control-flow operations, requires loop progress, and checks native result
polarity against the verified early-return arm and the continuation after the following absence check. Compiled
fixtures vary object/record padding and reject incorrect parents and inverted results. Consulted function bodies
must match the loaded executable before using the typed layout.
The resident reads the last nonnull modal entry without pruning the native stack. It validates both the selected
and modal widgets in the current tree, walks a bounded parent chain, and rejects stale targets, cycles, inaccessible
records/parents and excessive stack/depth. Selection plus modal admission clears the action target on rejection.
Native fixtures cover nested modal ownership, empty records, root replacement and invalid layouts. These adapters
are used by plain-click admission, but do not by themselves establish live modal/action acceptance.

Kotlin tests track the compiled native fixture artifacts as inputs, so changing a
fixture binary reruns the metadata tests without requiring changes to Kotlin sources.

Linux input-context fixtures verify fresh ownership across the global input source, Player, GameView, Map,
GameActionHandler and default script. Tick and pause candidates are accepted only after their owners match the
selected world. Tests cover ambiguous children, inaccessible memory, stale associations, invalid booleans and
overlapping/out-of-bounds fields. The raw tick remains an unsigned 64-bit value; the stop level remains a byte.
Attached `status` uses these same-callback reads for `in_game`/`paused`; an absent or not-ready context remains
`unknown` with a null pause value. A false pause observation does not prove that simulation ticks are advancing.
Constructor analysis derives handler candidates from a verified primary-vptr initialization and publication into
the original Game receiver. Register clobbers, incompatible control-flow paths and overwritten primary tables
discard that evidence. Neither these observations nor a pointer equality establish a cross-frame object lifetime.

Linux retirement metadata checks resolve GameView's concrete primary virtual entry and verify the owning Game's
null-guarded retirement call followed by clearing its member. The base class need not have a standalone vtable.
Compiled fixtures vary layouts and force captured pointers through loops and stack spills. Negative checks reject
alternate receivers, skipped dispatch, inverted guards and reentry from the later destructor body. Loaded-evidence
checks compare consulted code, RTTI and table words, relocating pointers separately from scalar RTTI fields.
Native fixtures cover retirement notification before original dispatch, register/stack argument and floating-state
preservation, exception unwinding, removal, stale admission, concurrent notification and address reuse. Lifetime
tokens contain comparison-only identities; admission still requires a fresh context at a verified safe point.
The resident installs retirement notification when an input-context observation first needs it and removes it during
detach. Task-bound timed input is not yet connected to this notification.

The native event-route fixture covers source dispatch, the zero-result GUI fallback, evaluation, and input-state
update/post-update. The selected executable determines whether state updates precede source dispatch or follow
evaluation. File-only checks require exactly one update on both source-result paths for each selected keyboard
event kind, reject other event-field dependencies and unsupported call ordering, and verify the post-update exit.
Sender metadata also collects the consulted function/data ranges, primary virtual tables and RTTI name pointers
for comparison with a loaded process. The explicit installed-file check validates original and simulated relocated
images and rejects individual corruption of each collected region. This is evidence comparison, not live dispatch.
Binary evidence copies use bounded bulk reads for mapped files and byte arrays. Unit checks cover nested subviews,
empty/end-of-view reads, invalid ranges, independent returned bytes and rejection after a mapping is closed.
Keyboard metadata checks both state-update and post-update Event reads against its initialized byte set and rejects
unverified event-pointer arguments. Post-update coverage reuses the existing typed lookup/zero-store path proof,
including its frame restoration and held-byte preservation checks. This does not establish all source/GUI consumer
reads or authorize live dispatch by itself.
The resident-linked route resolver checks the bound task context and input-service identity before and after every
stage. It reacquires source/evaluation receivers from that context, and GUI/handler receivers from the current
singleton with primary RTTI checks. Native fixtures cover GUI replacement, retirement and service replacement at
every stage, preserving progress without replay. This resolver does not supply execution-phase admission or replace
the emitter's separate owned-key cleanup after a world binding fails.
Fixtures cover both orders, worker-thread admission, reentry, exceptions and retirement after every stage, retaining
entered/returned progress without replay. These checks do not establish live timed-input acceptance; entry ABI,
event payload coverage, callback lifetimes and safe execution phase remain admission requirements.

`KeyboardRouteKey` joins private keyboard-event construction with the source/GUI/state route and passive key
ownership checks. Every stage verifies the admitted input-service identity; key-map scans occur at edge admission
and release reconciliation. New presses reject keys already held or blocked. Any entered down stage retains an
up obligation, including a native exception. Cleanup uses a separate resolver from the new-input world guard and
may run on the explicitly admitted cleanup thread under the task owner's synchronization.
A release rejected before any native stage may be attempted by a later cleanup call. Once any release stage has
entered, cleanup never replays the route: it requires all entered calls to have returned, final post-update completion
and a freshly observed cleared key before releasing ownership. Service replacement or uncertain routing retains
ownership. Native fixtures exercise both update orders, GUI fallback/bypass, exceptions at every stage, reentry,
foreign-thread rejection, worker-to-main cleanup and transient pre-dispatch rejection. This adapter is compiled into
the resident but is not yet connected to a public timed-input command. Its caller must still provide verified execution
phases, input-service lifetime, complete event-consumer coverage and a concrete cleanup resolver.

The fixed eight-entry `KeyboardRouteKeys` owner connects routed keys to sequence cleanup. It retains every key
with an unresolved native release and rejects duplicate presses or capacity overflow without dispatch. A down rejected
before ownership leaves no native key to release; a subsequent cleanup call for it is a no-op. Once a key's release
is confirmed, its entry is removed even if the key retained a historical press error. This lets the sequence report
the original failure and finish cleanup instead of treating that old error as continued ownership. Combined native
sequence fixtures cover failed partial chords, down exceptions followed by successful cleanup, and uncertain releases
that keep the sequence in its cleanup state without replay.

Linux chat submission metadata currently identifies the native console callback's constant action type and local
InputAction storage. The extent comes from the typed vector destruction loop, including equivalent add-positive and
subtract-negative cursor advances. The callback proof requires the same bounded local object at construction,
submission and destruction, rejects construction bypass/reconstruction, and checks normal cleanup paths.
The resolved action type must also make the native `noData` predicate return false. `NativeScalarFunction`
evaluates that selected scalar path without invoking native code; bounded private stack allocation must be balanced
without discarding saved registers. The same evaluator remains shared with native keyboard conversion.
`ChatPayloadType` derives the constructor's RTTI-table index from the original action argument, checks the selected
relocated pointer and RTTI name against native string symbols, and verifies the constructor's equality comparison.
Changed scalar sources, pointer/name mismatches, different fields and inverted or collapsed branches are rejected.
These table words still need loaded-image verification before runtime configuration is admitted.
`ChatActionHeader` cross-checks the constructor's original scalar argument and bounded receiver store against
the destructor's unsigned discriminator read and the submitter's original action argument. It rejects changed
receivers, field widths, bounds and helper arguments. This discriminator proof does not establish payload ownership
or the complete submission ABI; indirect tails terminate only its point-provenance analysis.
`ChatActionPayload` checks that the constructor reads exactly the resolved data pointer and length through its
original source-string argument. It derives the embedded inline-string location from receiver-relative pointer
initialization, checks its bounds and separation from the discriminator, and requires initialization before source
reads. Extra source-memory operands and initialization bypasses are rejected. Copy/allocation and destruction
ownership are not established by this point-provenance check.
`ChatStringCopy` separately evaluates the string-copy suffix for every byte length from zero through the chat
limit. It checks inline capacity, allocation size, original source bytes and length, single-byte or imported `memcpy`
arguments, length/capacity stores and the final terminator. Copy/terminator writes must belong to the final buffer.
Narrow-register reads retain truncation semantics; unsupported partial writes are rejected. This suffix proof uses
verified original-argument provenance and call-frame alignment, but does not replace complete entry/frame validation.
Normal constructor returns must pass through that payload construction. `ChatConstructorFrame` separately checks
normal-return stack and callee-saved register restoration, with call alignment and saved-storage protections.
Its indexed accesses must come from the independently verified RTTI and copy proofs for the same body; such evidence
cannot authorize a known frame alias. Scalar address calculations cannot truncate known frame pointers. These
checks do not replace exception/unwind metadata or live loaded-code validation.
`ChatActionDestruction` independently selects the native discriminator-table case for the resolved chat action.
It validates the bounded switch prefix and applies the string cleanup proof to both inline and heap paths, including
the sized-delete pointer/capacity argument and restored native frame. Branch-table analysis supports up to 4096
entries and retains read-only-data, target-boundary and guarded-index checks. Tests reject wrong cases, pointers,
capacity arithmetic, frame restoration and oversized tables. Copy and destruction checks remain separate from
live admission and loaded-image verification.
`ChatActionAcceptanceTest` is an explicit installed-file check of this metadata only; it does not send messages.
`ChatStringEntries` verifies the native empty-string constructor against the resolved storage layout and the
explicit byte-range assignment forwarding entry. It checks receiver, source bytes, length, original frame and
callee-saved registers, rejecting additional memory reads. The game's own constructor, assignment and destructor
manage string allocation; the adapter does not fabricate a borrowed string object.
The resident-linked `ChatSubmitCommand` owns bounded string/action storage and reacquires an admitted player before
construction/submission. It preserves the original message, records dispatch progress separately from cleanup,
cleans completed constructions after cancellation or exceptions, and never replays entered submission/destruction.
An uncertain destructor retains ownership. Native fixtures use actual C++ strings and action copies/moves to exercise
each failure stage, reentry, foreign threads, cancellation and unchanged Unicode bytes. These are fixture/file checks,
not live chat acceptance.
`ChatSubmissionGate` derives the raw four-byte exclusion comparison from the console callback. It requires the
not-equal edge to dominate submission and the compared handler's Player/Map/Game chain to match the actual submitted
player. Borrowed-call analysis exposes register provenance while discarding frame-address values; independent local
storage proofs remain required. `ChatAdmission` reacquires the world and native local player at each frontend check,
verifies the Map's Game back-reference and handler RTTI, and rejects the exact native excluded value. It does not
call Lua or derive a chat restriction from pause state. Native fixtures cover raw predicate values, player selection,
replacement, cancellation, foreign threads, invalid layouts and wrong ownership/type.
`ChatAdmissionLayout` assembles those world/player layouts, the callback guard, owned Map size and handler RTTI
into typed IPC configuration. It cross-checks the player's Map member against its constructor, validates owner
sizes and field bounds, rejects Game handler/player/view aliasing, and relocates addresses with overflow checks.
`ChatAdmissionAcceptanceTest` is an explicit installed-file assembly check; it neither attaches nor sends chat.
Loaded-image evidence validation remains required before this configuration may be used for a live command.
`ChatAdmissionMetadata` captures the layout's function and read-only lookup evidence, primary RTTI headers/name
pointers and the context destructor slot used during layout resolution. `ElfEvidence` compares code and data,
relocates classified pointer words (including overlaps with read-only ranges), and leaves scalar words unchanged.
Tests cover nonzero load bias, null pointers, modified bytes, short reads and invalid relocation before memory reads.
The explicit admission acceptance test checks captured evidence against the selected file; this is not a live-process
acceptance claim. `ChatSubmitArguments` verifies the original Player index is copied into the bounded action header,
separate from its discriminator/string payload, and normal return/tail paths preserve the frame and action argument.
`ChatMetadata` composes the admission, string/action entry, ownership and loaded-code checks into `FmLinuxChatConfig`.
`ChatMetadataAcceptanceTest` checks complete configuration assembly, file-backed evidence and relocation without
attaching or submitting a message.
The typed resident chat command carries bounded message bytes, native entry configuration and separate submission
progress. Resident fixtures exercise the real IPC/frontend dispatch with Unicode bytes, successful submission,
submission exceptions and exclusion/cancellation. Isolated destructor-failure cases verify that cleanup and detach
retain ownership without replaying uncertain destructors or submission.
Linux `sendChat` now resolves/verifies that configuration, sends original UTF-8 bytes through the leased resident
channel and requires completed dispatch with no retained cleanup before returning success. Shared plain-message
validation is reused by both platform entry points. Native failure progress remains distinct from game effects;
uncertain work is not replayed. Manual Linux single-player acceptance submitted a Unicode message and found its
exact UTF-8 bytes once in the newly saved world. This does not establish multiplayer synchronization or chat-read
coverage. `ChatSendAcceptanceTest` explicitly requires `FACTORIO_MCP_ACCEPTANCE_URL`, `FACTORIO_MCP_TEST_PID` and
`FACTORIO_MCP_TEST_CHAT_MESSAGE`; it checks invalid-message rejection, one submission, attachment liveness and
detach. Verify delivery separately. Restart Factorio after changing a mapped resident before further acceptance.
`ChatReadAcceptanceTest` uses the same explicit environment variables and a disposable loaded world. It reads a
cursor, submits one original message, verifies its presence in retained history, and checks that advancing the
cursor does not return the same record again. Linux single-player acceptance passed with Unicode and rich-text
markers. This is retained-history observation, not authoritative multiplayer or full-CRC evidence.

All game-facing tool implementations prefer Lua when it provides the required behavior and synchronization;
use game or framework C++ only for concrete gaps. Existing native implementations still require this review.
For chat, The installed runtime API has console printing,
clearing and a script-event raiser, but no existing-history reader or normal synchronized player-chat submission.
The inspected print implementation calls OutputConsole directly; raising the console-chat event dispatches script
handlers rather than submitting player input. These gaps justify the current native fallback. Preserve game text,
rich-text markers, links and formatting unchanged; do not recreate or interpret special chat content.

For chat observation, `NativeListNodeLayout` derives sentinel/node links, the embedded record start and allocation
extent from the typed list destructor. It checks empty and nonempty paths, original node ownership at destruction
and sized deletion, stack restoration and every live register input across the loop backedge. A freed node alias
cannot stand in for the next iteration's node. Synthetic tests reject changed fields, allocation bounds, calls,
branches, frames and stale aliases. `ChatReadMetadataAcceptanceTest` explicitly checks this metadata against the
selected installed ELF without attaching. These checks alone do not implement `chat_read`; console ownership,
record fields and bounded string observation still require integration.
`ChatRecordText` associates the record's embedded `LocalisedString` through its typed save call, requiring the same
original record member and serializer on every incoming path and checking the address against the node's allocation
bound. This is static member evidence only; neither save function is invoked. Fixture tests cover disagreeing
branches, pointer loads, changed arguments and bounds; the
explicit installed-file test includes this association.
`LocalisedTextLayout` derives the object extent from the native recursive child traversal in cache clearing, and
associates cached string length/buffer fields through their zeroing operations with the verified native string
layout. The installed-file check requires the complete text object to fit within the console record allocation.
`VectorElementSize` treats calls to the exact enclosing entry as ordinary recursive ABI calls for this analysis;
calls into its interior remain rejected. Only its analysis CFG uses a substituted external target, while receiver
analysis and loaded evidence retain original bytes. Neither clearing nor translation is invoked. Raw-expression
formatting and runtime bounded observation remain unfinished.
`ChatConsoleMember` traces the receiver of the typed `OutputConsole::add` call back through the original LuaPlayer's
Player member. The LuaPlayer constructor independently establishes that first pointer; the Player allocation bounds
the console member. Borrowed text/settings locals supply no pointer or layout evidence. Fixture tests reject changed
owners, pointer widths, inline addresses, callees and bounds; installed-file acceptance composes this with the node
and text checks. This metadata association does not invoke printing or clear the console.
`ChatConsoleLists` associates two nonoverlapping reset groups with that same typed console. Each group contains
two pointer stores of one sentinel and one adjacent zeroed word, with no alternate entry between those stores.
The independently derived next-link position selects one pointer field. The other pointer and zeroed word are
previous-link/count candidates; their traversal/removal roles, storage classification and enclosing allocation
are checked separately before live reading. Synthetic and explicit installed-file checks cover these
reset groups without executing the clearing function.
`NativeListLinks` verifies the native unlink operation reads two original-node pointers and performs exactly the
two reciprocal neighbour updates, with bounded pointer fields and no calls or frame modifications. The console
resolver cross-checks each previous-link candidate against this proof. Count semantics, storage classification and
allocation bounds are separate requirements. Neither unlink nor clearing is invoked during these checks.
`ChatConsoleCounts` cross-checks both reset words against original-console 64-bit decrements inside matching
DWARF `_M_dec_size` inline ranges in message removal. It rejects changed owners, widths, duplicate/missing fields
and unrelated inline ranges. Installed-file acceptance composes this count proof with the previous layout checks;
storage classification is verified separately.
`ChatConsoleExtent` derives the console's complete-object extent from the original Player's owned console pointer
passed to sized delete. It associates the named inline console destructor with deletion either inside its range or
immediately after a bounded, uninterrupted argument-setup sequence. The latter must have no bypassing entry.
Both list layouts must fit this extent. Synthetic tests cover owner, field, call, scope and bound mismatches;
explicit installed-file acceptance includes the resulting extent. No destructor or delete function is invoked.
`ChatConsoleStreams` follows the two named game-state/local submission wrappers to the settings byte controlling
list selection. It verifies unchanged console/text arguments, constant private settings initialization and the
original settings receiver at the conditional selection. Both specialized choices must reach native list insertion
through the selected sentinel's next link. Synthetic tests cover reversed conditions and unrelated settings,
lists or insertion references. Installed-file acceptance composes this association with the console bounds.
MOVLHPS remains a distinct vector operation in the decoder; it does not establish scalar pointer provenance.
`ChatRecordPlayer` traces the optional original Player argument through bounded private locals, requires its
independently verified index load or the native null-branch constant, and proves the selected store targets this
record allocation's unadjusted result. The field must fit the record and avoid its embedded text. Synthetic tests
cover changed allocation, pointer owner, branch, field and overlapping vector writes to a saved argument. Explicit
installed-file acceptance composes this proof with the console metadata; it does not yet enable runtime reading.
`ChatRecordTick` verifies a Map member supplied through the named `MapTick &` construction of a console Item,
then copied unchanged into the fresh node inside the Item constructor's inline range. It follows the original
console's Player back-pointer and independently established Player-to-Map member, bounds the Map source and
record destination, and rejects overlap with the text or player fields. `NativeAllocationResult` supplies shared
exact-allocation provenance for the tick and player copies. Runtime use must match the console back-pointer to
the selected Player. This is the native record update tick, not Lua `game.tick`; the installed-file check rejects
that earlier incorrect association and verifies the native construction instead. Neither construction nor any
other game function is invoked during metadata resolution.
`LocalisedRawCall` establishes the native hidden returned-string storage in RDI and the original LocalisedString
receiver in RSI before its bounded mode dispatch. It verifies the named empty-string construction against the
independent native string layout. Compiler-attributed leading register saves/frame allocation are trimmed without
excluding constructor writes. Fixtures reject storage, receiver, mode-width/bounds and range mismatches; explicit
installed-file acceptance includes this call placement. This proves the entry's argument placement, not runtime
snapshot copying, cleanup or a request to translate text.
The resident's `ChatReadCommand` implements bounded two-list traversal with original node identities, reciprocal
link checks, total counts, tick/player fields and existing cached/native-formatted text. It preserves explicit byte
lengths, embedded NUL and rich text, and truncates at UTF-8 boundaries. Private returned-string storage is destroyed
before continuing; an uncertain destructor retains ownership and is never replayed. Tests cover empty/truncated
histories, invalid links/pointers/bounds, cancellation, exceptions, reentrancy and foreign-thread rejection. This
component is linked through typed IPC into the shared `chat_read` history projection. The resident reacquires the
world, local player and console at the frontend safe point. Kotlin validates snapshot bounds before constructing
shared records. Resident fixtures also cover empty history, stale console ownership, cancellation and invalid
layouts. Explicit installed-file acceptance also verifies complete metadata composition and typed wire population.
These checks are separate from live-game acceptance. The shared `ChatReadAcceptanceTest` also passes against the
Linux release endpoint in a dedicated single-player world; multiplayer/full-CRC acceptance remains pending.

The evaluation adapter filters the exact native return address before touching its context or task state, in
addition to the admitted thread and receiver. Metadata traces the handler's original source member and its virtual
call, rejecting missing or duplicate candidates and excluding unrelated calls through the same slot. Virtual tail
exits require restored stack/callee-saved registers, the same selected source receiver, and a primary-table target
outside the handler. Temporary exit substitutions exist only in analysis; executable evidence retains the original
bytes. Scalar float loads/stores and comparisons retain their operand widths in the instruction model.
Native fixtures use an assembly caller with a real return label and cover unrelated callers, foreign threads,
reentry, normal completion and exception cleanup. The nonblocking gate spans the original evaluation. These checks
establish caller discrimination, not worker synchronization or safe GUI access. Public timed input still requires
execution-phase admission, concrete input ownership/cleanup and Kotlin/wire integration.
Cancellation is published through an atomic request. Only an admitted evaluation or frontend phase mutates the
sequence or releases held input. A request during a native evaluation waits for that evaluation to return, counts
the completed evaluation, then leaves release work to cleanup. Fixtures cover cross-thread cancellation before
admission and during evaluation, deferred cleanup, transient release rejection and repeated cancellation without
duplicate release. This component remains fixture-linked until the full input route is admitted.

The task-context adapter binds fresh game/source/player/Map/view identities and checks them before and after each
new input edge. A retired view or an observed ownership change permanently rejects further dispatch for that task.
Fresh context reads require the global source, the Game source used by the native event sender and the selected
GameActionHandler source to agree. The Game member is resolved from both sender virtual calls. Fixtures reject
missing/mismatched references and retain an admitted task's ownership after a coherent source replacement.
Paused/stopped contexts reject new input, while releases remain available for cleanup. Fixtures combine this adapter
with the shared input sequence, including reentrant retirement, partial chords, unavailable release paths and
coherent player replacement through the real context reader. They do not establish native event routing or live
timed-input acceptance. The concrete emitter must track entered native releases so cleanup can reconcile them
without replaying an uncertain dispatch; task bindings must remain owned until that cleanup finishes.

The explicit retirement check accepts the same ELF and optional PID variables as the input-context check below:

```sh
FACTORIO_MCP_TEST_ELF=/path/to/Factorio/bin/x64/factorio \
  build/bin/linuxX64/debugTest/test.kexe '--ktest_filter=com.hiczp.factorio.mcp.acceptance.ViewLifetimeMetadataAcceptanceTest.*'
```

After the normal build, run the installed-file input-context check explicitly. Set `FACTORIO_MCP_TEST_PID` as well
to compare the collected code/data evidence against an already running selected process. This check installs no
hook and does not send input:

```sh
FACTORIO_MCP_TEST_ELF=/path/to/Factorio/bin/x64/factorio \
  build/bin/linuxX64/debugTest/test.kexe '--ktest_filter=com.hiczp.factorio.mcp.acceptance.InputSourceMetadataAcceptanceTest.*'
```

The shared SDK pause-status acceptance requires a disposable, already running single-player world and its configured
menu-toggle keyboard key. It opens/closes the menu twice, checks the native pause observation and detaches. On Windows,
set the same environment variables in PowerShell and use `build/bin/mingwX64/debugTest/test.exe`:

```sh
FACTORIO_MCP_ACCEPTANCE_URL=http://127.0.0.1:12345/mcp \
FACTORIO_MCP_TEST_PID=1234 FACTORIO_MCP_TEST_MENU_KEY=ESCAPE \
  build/bin/linuxX64/debugTest/test.kexe '--ktest_filter=com.hiczp.factorio.mcp.acceptance.PauseStatusAcceptanceTest.*'
```

On Windows, the PDB and runtime API checks use the following explicit inputs.
After `build`, set `FACTORIO_MCP_TEST_PDB` to the installed game's matching `factorio.pdb` and
`FACTORIO_MCP_TEST_RUNTIME_API` to its `doc-html/runtime-api.json`. Leave `FACTORIO_MCP_TEST_PID` unset for file-only
work, then run:

```powershell
$env:FACTORIO_MCP_TEST_PDB = 'C:/path/to/Factorio/bin/x64/factorio.pdb'
$env:FACTORIO_MCP_TEST_RUNTIME_API = 'C:/path/to/Factorio/doc-html/runtime-api.json'
.\build\bin\mingwX64\debugTest\test.exe '--ktest_filter=com.hiczp.factorio.mcp.acceptance.DebugMetadataAcceptanceTest.virtualInterfaceMetadataResolvesFromAnExplicitPdb:com.hiczp.factorio.mcp.offline.OfflineQueryMetadataTest.*'
```

These checks resolve installed metadata without launching or injecting a game. They do not establish live ABI safety.
For the additional live-process metadata check, set `FACTORIO_MCP_TEST_PID` and select
`com.hiczp.factorio.mcp.acceptance.DebugMetadataAcceptanceTest.inputMetadataResolvesFromTheLoadedTarget` instead. Check
activation/output as well as
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
.\build\bin\mingwX64\debugTest\test.exe '--ktest_filter=com.hiczp.factorio.mcp.acceptance.HttpAcceptanceTest.*'
```

The HTTP tests initialize normal MCP sessions, check advertised tools and preconditions, exercise
reads/capture/bindings,
and cycle sessions and repeated detach/attach. They can detach the shared attachment. They never launch or terminate
the supplied endpoint or game. Test clients use Ktor's CIO engine on every platform and accept HTTP endpoints only.

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
.\build\bin\mingwX64\debugTest\test.exe '--ktest_filter=com.hiczp.factorio.mcp.acceptance.*'
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
