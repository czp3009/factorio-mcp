# Linux port handoff

Updated: 2026-09-30. Paused at the user's request after finishing the current metadata verification.
The full Linux port is **not complete**. Resume the original objective; do not treat this checkpoint as a
reduced definition of success. There is no external blocker requiring permission.

## Workspace and sources of truth

- Repository: `/home/czp/IdeaProjects/factorio-mcp`.
- HEAD at handoff: `6befb0b9ede571344fb894504dc88c7b0e5acbd5`.
- The working tree contains extensive staged, unstaged and untracked Linux-port work (over 700 status entries).
  It is not committed. Preserve all of it; do not reset, clean, restore HEAD over it, or infer that staged files
  represent the latest implementation. Some staged additions have subsequently been renamed or removed.
- Read current `AGENTS.md` before continuing. Its Linux exception and lasting user requirements were already
  updated with authorization. The original Windows implementation at HEAD is the validated behavioral reference.
  Inspect it before each tool implementation; extracting shared code is a separate refactoring concern.
- `temp/linux-port/STATUS.md` is the chronological evidence index. Start with its current checkpoint and final
  sections, not old statements of missing capabilities. Preserve historical failures and original test scopes.
- Research and raw evidence are under ignored `temp/linux-port/`. `development.md` has detailed current validation
  scopes. README, tools documentation and release configuration still require a final consistency audit.

## Latest completed work and immediate next step

The current work is optional Switch widget properties for `ui_read`, matching the Windows reader's own state
byte and allow-none boolean. Windows references are `SwitchLayouts.kt` and `collectSwitch` in
`src/mingwX64Main/native/widget_properties.cpp`. Do not turn the state into an inferred boolean gameplay mode.

New Linux metadata components:

- `ConstructedInlineByteMember.kt`: proves a named single-byte store uses the same bounded allocator result
  passed to the selected constructor. Checks constructor dominance and rejects receiver loss, extra operations,
  wrong widths, invalid ranges and out-of-bounds fields. Never invokes game code.
- `EmbeddedPrimaryTable.kt`: derives a bounded embedded object from a verified primary-table address/store pair
  in the original receiver's constructor. Checks provenance, alignment, ambiguity and object extents.
- `ConstructedByteCopy.kt`: proves an identified original receiver byte is copied unchanged into a bounded
  constructed allocation. The source load and destination store must be an uninterrupted pair.
- `WidgetSwitchState.kt`: composes the above. Uses innermost named `setState` DWARF ranges in
  `CustomSwitch::createWidget`, sized-destructor bounds, Switch RTTI and LabeledSwitch construction. Resolves
  `allow_none` through the existing `LuaBooleanMember` reader for `LuaGuiElement::luaReadAllowNoneState`, then
  validates the source byte's copy into the constructed object. Both fields are relative to the embedded Switch.
  The class now contains both state and allow-none; renaming to `WidgetSwitchFields` would be reasonable during
  runtime integration, but is not required for correctness.
- Unit tests: `ConstructedInlineByteMemberTest`, `EmbeddedPrimaryTableTest`, `ConstructedByteCopyTest`.
- Explicit selected-file test: `WidgetSwitchAcceptanceTest`. It is excluded from the default Gradle run.

Latest results:

- `temp/linux-port/switch-allow-check.log`: `./gradlew check` passed in 1m27s, including 152 native CTest fixtures
  and the Kotlin tests. This run needs no game client.
- `temp/linux-port/switch-allow-installed.log`: `WidgetSwitchAcceptanceTest` passed in 84391ms against the
  installed game ELF. It resolved Switch size 424, state 417, allowNone 416. These numbers are **evidence only**,
  never product constants. Earlier state/embedding successes and the rejected outer setter are preserved in
  `switch-state-*` and `switch-embedded-*` logs.
- No Switch runtime reader, live Switch acceptance or new release executable was produced in these turns.
  No wire changes were made. Do not claim that Switch properties are already exposed by MCP.

Next implementation step: wire these bounded fields through a concrete Switch layout, typed resident structs,
safe-point copying and common snapshot serialization. Use the slider/progress/dropdown implementations as local
integration examples. Preserve Windows semantics. Windows gets enum names from PDB; Linux must derive names from
verified evidence or retain the existing unknown-value convention until that work is done. Never invent enum
labels from observed integer values. Add native fixture and Kotlin copy/serialization checks, then build a fresh
release and perform explicit live reads and selector checks. Do not reuse an old resident after wire changes.

## Runtime state at pause

The owned graphical game PID 847798 and MCP PID 847954 were terminated during handoff to release memory. They had
been left detached at the main menu after dropdown acceptance, with no pending test save. Their launcher/shell
parents were 847797 and 847953. Gradle daemons were stopped. Recheck processes before resuming; PIDs are historical
and must never be reused as authorization to signal a future process.

The last running release was the dropdown runtime build. Its former endpoint was
`http://127.0.0.1:37475/mcp`; that endpoint/session is no longer a live test environment. Debug test output contains
the new Switch metadata. Rebuild the release before testing new product code.

Installed game:

```text
/home/czp/.steam/debian-installation/steamapps/common/Factorio/bin/x64/factorio
/home/czp/.steam/debian-installation/steamapps/common/Factorio/doc-html/runtime-api.json
```

Test-only launcher (enables ptrace permission, supplies no game arguments):

```bash
SteamAppId=427520 SteamGameId=427520 temp/linux-port/launch-game \
  /home/czp/.steam/debian-installation/steamapps/common/Factorio/bin/x64/factorio \
  /home/czp/IdeaProjects/factorio-mcp/temp/linux-port/resumed-game.log
```

Wait for `Factorio initialised`. Check existing processes first. At most one graphical client and one isolated
local multiplayer server may run. No local game server was running at handoff. Never test a remote game server.

MCP launch after building:

```bash
build/bin/linuxX64/releaseExecutable/factorio-mcp.kexe --no-stdio --http-port 0
```

Use the printed endpoint. Initialize Streamable HTTP with protocol `2025-11-25`, retain `Mcp-Session-Id`, send
`notifications/initialized`, and include `MCP-Protocol-Version` and the standard JSON/SSE Accept headers. Old curl
helpers in `temp/` contain expired endpoint/session values; update them rather than assuming they remain valid.

## Existing disposable game scenario

Scenario directory: `temp/linux-port/scenarios/factorio-mcp-check-controls-20260930/`, linked from
`/home/czp/.factorio/scenarios/factorio-mcp-check-controls-20260930`. It is a dedicated peaceful fixture; the user's
existing save `czp` was not modified. Exit without saving when finished. Scenario edits apply on fresh scenario
creation; product/resident updates require restarting the game before attaching.

The scenario currently creates a chest, combinator and a script GUI frame `mcp_radio_fixture` with caption
`MCP radio fixture`: radio controls, slider (7.5, range -10..30, step 0.5), progressbar (0.375), and a dropdown with
70 entries, selected Lua index 70. Entries include an empty string, Chinese/rich text, and a long Chinese label.

Navigation previously used `ui_action` native TextButton selectors for:
single-player -> create game -> the fixture scenario -> next -> start. The installed UI language is Chinese;
exact historical request/response JSON files are in `temp/linux-port/dropdown-runtime-*`. Preserve displayed
text verbatim; do not add translations or infer programmatic IDs from human text.

Selected subtree selectors use `native_type`; returned node types are in `type`. A path can locate the Window
with text `MCP radio fixture`, then its descendant `agui::DropDown`. Shared Lua `world_query` can inspect
player.gui.screen["mcp_radio_fixture"]["mcp_dropdown"]. `selected_index` from Lua is one-based; native dropdown
projection is zero-based. Do not confuse these contracts.

## Current feature evidence and material gaps

- Native UI roots cover both menu and world UI. Lua GUI roots cover script-created GUI, not all vanilla/menu
  widgets. Do not replace full native UI observation with the smaller Lua GUI tree.
- Toggle, raw check state, slider, progress and dropdown runtime properties have fixture and explicit live
  evidence in the final STATUS sections. Progress includes ProgressBar, ControllerProgressBar and BatteryIndicatorGui;
  `hasText=true` was fixture-tested only. DoubleSlider has metadata evidence but no live sample. Preserve these limits.
- Dropdown live acceptance observed selection 69 and 64 of 70 options, including empty/raw text and safe UTF-8
  truncation. An initial option-pool reset bug was fixed and reverified. Both whole-tree and selected-subtree reads
  passed, and shared Lua independently reported selected index 70. No selection was changed in that acceptance.
- OpenGL screenshots have explicit menu/world/paused evidence. Capture restores GL state and uses the game frame,
  not the desktop. Do not repeat screenshots routinely after every action.
- Shared Lua world readers, chat and UI actions have substantial implementation/evidence elsewhere in STATUS.
  Audit their exact scopes before making completion claims; historical coverage is not exhaustive acceptance.
- **Timed `input` is not implemented end-to-end.** Current Linux `Platform.beginInput` still delegates to
  `super.beginInput`. Main research index: `temp/linux-port/input-phase-findings.md`. Native components include
  InputEvaluation, KeyboardPumpKey, KeyboardRouteKey, InputTaskContext and InputSequence, but they do not establish
  complete safe admission/synchronization. Resolve worker phase, world/view lifetime and normal event transition
  handling before routing timed input. Do not replace it with unsynchronized Lua mutation or weaker tick semantics.
- Remaining optional UI properties include Switch runtime integration, verified enum names and further
  prototype/quality/item/number/icon/viewport fields. Compare the complete Windows implementation, not only this list.
- Writable behavior still requires authoritative local-server effects and full CRC checks. A frontend/game-thread
  callback alone does not establish replication. Read-only-looking Lua APIs can alter serialized state too.

## Source-set consolidation still required

Shared-code extraction is not finished. Preserve validated Windows behavior while separating portable logic
from platform mechanisms; do not independently redesign input behavior as part of moving files.

Current input distribution:

- `commonMain`: `InputSchema.kt`, `InputSequence.kt`, `InputBindings.kt`, `InputTaskOwner.kt` and
  `InputTransfer.kt` contain shared contracts, parsing/validation, result models and parts of lifecycle management.
- `src/nativeMain/native/input_sequence.cpp` and `.h`: shared native sequence state machine for tick accounting,
  mouse motion, held buttons, release and cancellation. These are C++ sources built through CMake, not Kotlin
  commonMain code. A shared file's existence alone does not prove Linux runtime integration is complete.
- Platform source sets still contain event adapters, task IPC/resource handling and execution-phase integration.
  Windows `NativeInputTask.kt` and native `resident_input.cpp` mix platform mechanisms with logic that should be
  reviewed for further extraction. Linux timed-input integration remains incomplete.

During completion, review both implementations together and extract remaining portable task policy, validation,
serialization and lifecycle logic into `commonMain` where appropriate. Keep necessary shared resident sequence
mechanics in the common native CMake component. Leave OS IPC, hooks, symbol/layout discovery, ABI calls and verified
platform execution boundaries in their platform adapters. Do not add wrappers for already-portable library APIs.
Apply the same review to other tools and Lua readers, not only input. Use role-based names for equivalent classes;
retain platform/ABI names when they describe a real distinction.

Preserve default KMP hierarchy, standard directories and named target/source-set configuration. Do not create
custom hierarchy edges to accommodate the cleanup. Verify both platforms' build wiring and behavior after shared
changes, without executing foreign binaries or triggering CI. Record any locally unverified Windows scope.

## Required completion gates, still open

1. Standard local build succeeds and expected artifacts exist in the target output directories.
2. Default unit/native tests are game-independent and CI-ready. Tests depending on an installed ELF, endpoint or
   prepared scenario remain explicit executable invocations, never environment-enabled Gradle tests.
3. Explicit manual/agent real-game acceptance covers all tools with relevant effects and cleanup; distinguish
   single-player, native fixtures and multiplayer/full-CRC evidence.
4. Code, server/tool descriptions, schemas, README translations, `tools.md` and `development.md` agree.
5. Publish to a disposable **local** npm registry, install, inspect package contents and execute the installed package.
   This final local-registry gate has not been done.
6. Audit GitHub Actions for Windows/Linux builds, tests, npm and GitHub Release publication. Files were edited earlier,
   but final validation is pending. **Do not trigger CI or publish npm remotely.** Do not claim CI execution.

Maintain default KMP hierarchy and named `mingwX64`/`linuxX64` configuration, portable common logic, and role-based
names where platform names add no meaning. Only implemented targets are registered. Cross-compilation is allowed
when dependencies/toolchains support it; never execute a foreign platform binary. HTTP servers use CIO. Current
HTTP-only test clients also use CIO; a future product/HTTPS client should consistently use Curl.

## Reproduction and operating rules

```bash
./gradlew check
FACTORIO_MCP_TEST_ELF=/home/czp/.steam/debian-installation/steamapps/common/Factorio/bin/x64/factorio \
  build/bin/linuxX64/debugTest/test.kexe '--ktest_filter=*WidgetSwitchAcceptanceTest.*'
```

- Wait for the exact build/test process to finish before editing dependent code or starting another build. An
  observation timeout is not process termination. Reuse and poll the same live session.
- Keep raw research, logs and experiments in ignored `temp/`. This handoff is in `.codex/plans` at the user's request.
- Lua first, then native capability gaps; keyboard/mouse input only. Human-facing text is opaque and unchanged.
- Dynamic matching ELF/DWARF/decoded instructions are authorized. Hardcoded game offsets, address tables,
  instruction fingerprints and guessed virtual slots are prohibited. Reject unsupported or ambiguous code.
- No product process lifecycle management, console/RCON, OS input simulation, forced focus or desktop capture.
- Preserve cooperative cancellation/detach, bounded safe-point reads, exact cleanup ownership and uncertain-mutation
  reconciliation. Never replay an uncertain mutation or poll/retry at runtime to fulfill gameplay intent.
- Do not spawn subagents without explicit authorization. Keep user conversation in Chinese with ASCII punctuation;
  documentation/comments are English unless a translation was explicitly requested.
- Keep the goal paused until the user resumes it. Do not mark it complete based on this checkpoint.
