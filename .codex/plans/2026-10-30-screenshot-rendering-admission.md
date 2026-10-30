# Reject screenshots when the game has suspended rendering

Status: implementation pending. This is a handoff plan, not evidence that the proposed change works.
Created: 2026-10-30.

The user explicitly requested this plan under `.codex/plans`. This location is an exception to the repository's
usual `temp/` location for unfinished plans. Raw research and future verification results remain under ignored
`temp/`. Follow the current [AGENTS.md](../../AGENTS.md), and work without subagents.

## User intent and scope

The user wants agents to play Factorio with less machine resource consumption. Screenshots are optional, but
normal gameplay, input, UI queries/actions, and terrain/world queries must remain available. Linux research has
already demonstrated a useful setup: a full graphical client on Xvfb/Openbox, minimized with
`halt-rendering-when-minimized=true`. The client keeps processing frontend logic and simulation while skipping
drawing. This is not the dedicated headless server or a client with graphics initialization removed.

The requested update addresses accidental screenshot requests in this state. Currently they can wait indefinitely
for a rendered frame and block later observations. At the existing frontend safe point, read the game's actual
rendering-suppression condition and return a normal tool error immediately if drawing is suspended.

The user's preferred design is deliberately simple: one live native condition check at screenshot admission,
provided the supported main-loop path keeps that decision valid until capture. Do not add execution timeouts,
frame-age heuristics, background polling, or a general rendering-stall monitor. Preserve existing cancellation and
cleanup. Apply the same public behavior to Linux x64 and Windows x64; do not introduce another platform or renderer.

This task does not add process launching, virtual-display management, automatic minimization/restoration, forced
focus, demand rendering, graphics-resource unloading, or a new headless mode to product code. Do not remove the
screenshot tool. Active rendering must continue to produce normal game-and-UI PNGs.

## Evidence already established

### Historical Linux runtime verification

Primary record: [headless-resource README](../../temp/headless-resource-20261030/README.txt).
These results predate this proposed update and must not be reported as its acceptance results.

- Tested executable: Linux Factorio 2.1.24, build 87808, official DLC enabled. Repository source at the verified run
  was `8837ca8`. The host was Ubuntu 20.04 x64, Ryzen 7 3700X, 31 GiB RAM, using the installed Steam Linux Runtime 4.
- Display: Xvfb `:91`, 1280x720x24, Openbox, Mesa 21.2.6 llvmpipe. The window was observed as
  Iconic / `_NET_WM_STATE_HIDDEN`. Packages were extracted locally; no system package installation was needed.
- `./gradlew assemble linuxX64Test` succeeded before the final run: 757 ordinary tests, zero failures/errors/skips.
  The test client was restarted with the rebuilt artifacts. See `build-check.log`, `unit-summary.json`, and
  `current-artifact-sha256.txt` in that directory.
- Five explicit official-SDK HTTP acceptance tests passed while minimized:
  `InputAcceptanceTest`, `LoadedWorldQueryAcceptanceTest`, `UiActionAcceptanceTest`,
  `ChatReadAcceptanceTest.observesSubmittedTextAndAdvancesNativeTickOffset`, and `InputBindingsAcceptanceTest`.
  Coverage included normal input and cleanup, UI gestures/text, bounded world/entity/tile observations, Lua error
  recovery, chat, and keyboard/mouse bindings. UI acceptance checked authoritative local-server effects and full CRC.
  See `current-minimized-acceptance.log` and `current-minimized-chat-bindings.log`.
- Additional `world_overview` and direct tile queries succeeded. See `current-minimized-overview.json` and
  `current-minimized-tiles.json`.
- A warmed screenshot succeeded with rendering restored, producing a 1280x720 PNG. A subsequent capture while
  minimized stayed pending for about 27 seconds until explicit screenshot cancellation. Status and tile reads
  succeeded afterward. See `current-warm-capture.json`, `current-warm-capture.png`,
  `current-minimized-capture-request.json`, `current-minimized-capture.json`,
  `current-minimized-capture-cancel.json`, `current-after-capture-cancel.json`, and `current-after-cancel-tiles.json`.
- In 15-second samples, game CPU changed from 1031.66% of one core while rendering to 40.46% while minimized,
  a 96.08% reduction for this software-rendered small fixture. RSS remained about 3.05 GiB. This does not establish
  comparable savings for hardware rendering, large factories, or other compositors. See `summary.json`,
  `current-rendering-resource.json`, and `current-minimized-resource-repeat.json`.
- Successful boundary queries observed ticks 31296 -> 32204 around a 15.003-second sample plus query overhead,
  consistent with approximately 60 UPS. Final client/server logs reached full-CRC tick 36900 without desync text.

Evidence exclusions matter. Preliminary Oct 29 binaries failed a world-query decoder operation in both restored
and minimized states; source already contained its fix. Some exploratory queries overlapped an acceptance test's
detach and failed for that reason. The README identifies those artifacts; do not reuse them as current-code
regressions or successful gameplay evidence. September research under `temp/factorio-mcp-research/headless/`
belongs to another executable and remains historical.

The experiment demonstrated continued gameplay with drawing suspended, not every tool/property/mod combination.
It did not test Wayland, another window manager, physical GPU utilization, or Windows minimization.

### Linux native predicate and main-loop ordering

Primary record: [render-state findings](../../temp/screenshot-render-state-20261030/findings.txt).
This was static analysis of the selected Linux executable, with no new runtime acceptance or product changes.

The observed rendering guard in `MainLoop::run` and `GlobalContext::swapRenderBuffers` is equivalent to:

```text
headlessMode
    OR native_suppression_flag
    OR (minimized_flag AND graphics_settings_present AND halt_when_minimized)
```

- `GlobalContext::headlessMode` has a matching symbol. The ordinary minimized-state flag is tied to a
  `MainLoop::processEvents(bool)` branch logging `Window minimized`.
- A restoration branch logs `Window exposed, shown, restored, or maximized` and clears the minimized flag and
  the adjacent suppression flag. A separate event branch sets that suppression flag. Its exact meaning has not
  yet been established; do not rename it publicly as focus, visibility, or another inferred state.
- The named setting `halt-rendering-when-minimized` is associated with the guard's value through
  `GraphicsSettings::GraphicsSettings(PropertyTree const&)` and the `SimpleConfigItem<bool>` constructor.
  Its observed default is true. Formal pointer/type/layout validation for an adapter is still required.
- Research-only offsets and addresses are preserved in `findings.txt` and disassembly. They are not permissible
  product constants. Resolve all required locations dynamically from the selected executable's matching metadata
  and bounded decoded instructions.

The observed normal graphical iteration is:

```text
MainLoop::prePrepare -> processEvents(false)
    -> MainLoop::prepare, when didGameUpdate is true
        -> Gui::logic returns -> existing MCP frontend safe point
    -> update worker starts -> prepareBitmaps
    -> native rendering guard -> swapRenderBuffers -> frame readback/swap
```

No additional direct `processEvents` call was found on that ordinary path before frame submission. An OS minimize
event arriving later is processed in the next event phase; it does not itself asynchronously rewrite the game's
cached minimized flag. This supports a single admission check for the reported ordinary minimization case.

It is not yet a complete proof for every path, suppression event, setting update, or Windows binary. Screenshot
admission and readback are separate phases. The next agent must verify the relevant same-iteration relationship
and state stability, rather than assuming an entire screenshot request is atomic because tools use safe points.

Evidence files in `temp/screenshot-render-state-20261030/`:

- `pre-prepare.asm`: event processing before preparation.
- `process-events.asm`: cached minimized/restored state updates.
- `swap-render-buffers.asm`: small native rendering guard.
- `graphics-settings.asm`, `bool-config.asm`: named configuration value association.
- `setup-settings.asm`: exploratory material, not a completed pointer-provenance proof.
- Main-loop context is in `temp/headless-resource-20261030/main-loop.asm` and `prepare.asm`.

### Windows evidence and remaining uncertainty

Windows source inspection establishes the same two-stage screenshot structure: command service after GUI logic,
then DirectX frame capture in `Present`. It does not establish the equivalent native suppression predicate or
Windows main-loop/event ordering. Matching PDB inspection, relevant control-flow validation, and Windows runtime
acceptance remain outstanding. Do not present the Linux predicate's exact layout or ordering as verified on Windows.

## Current implementation map

All paths below are relative to the repository root. Resolve symbols/functions in current source rather than
depending on historical line numbers.

| Area | Starting points | Current behavior relevant to the change |
| --- | --- | --- |
| Shared lifecycle | `src/commonMain/kotlin/com/hiczp/factorio/mcp/GameSession.kt` | `screenshot` reserves single-flight admission and queues an observation; `cancelScreenshot` cancels and waits for cleanup. |
| Public tool | `src/commonMain/kotlin/com/hiczp/factorio/mcp/McpServer.kt` | Capture/cancel schema, PNG result, existing tool-error wrapping, and description of suspended-render waiting. |
| Linux metadata | `src/linuxX64Main/kotlin/com/hiczp/factorio/mcp/` | Start with `ResidentConnection.kt`, `FrontendSite.kt`, `ElfEvidence.kt`, `FrameContextMetadata.kt`, `GraphicsSwapCall.kt`, `FrameApiBinding.kt`, and `FrameHookBinding.kt`. |
| Linux admission | `src/linuxX64Main/native/resident.cpp`, `gui_hook.S`, `linux_ipc.h` | `fm_after_gui_logic` admits `FM_LINUX_SCREENSHOT`, validates context/API, and installs a frame hook. A running screenshot otherwise waits or handles cancellation. |
| Linux readback | `src/linuxX64Main/native/resident.cpp`, `frame_context.*`, `frame_hook_owner.*`, `frame_readback.*`, `frame_hook.S` | `fm_before_frame` validates the frame context and reads pixels before swap. |
| Windows metadata | `src/mingwX64Main/kotlin/com/hiczp/factorio/mcp/DebugSymbols.kt`, `ResidentConnection.kt` | Matching PDB/type metadata and native screenshot request setup. Follow the existing typed boundary. |
| Windows admission/readback | `src/mingwX64Main/native/resident.cpp`, `screenshot.cpp` | `updateGui` calls original GUI logic and then `service` for the verified prepare caller. Screenshot operation 6 remains running until `present` captures a non-test DirectX frame. |

Inspect Windows admission, capture, cancellation, and cleanup first as the established behavioral reference.
This change has concrete justification for improving both platforms: Linux reproduced a blocked screenshot while
normal frontend queries/actions remained available.

## Implementation tasks

1. **Confirm the authoritative predicate and its lifetime on both platforms.**
   - Briefly check the current documented game Lua API for an appropriate read-only rendering-state observation
     and its actual availability in menus and paused worlds. That API assessment has not been completed. Record
     the concrete capability gap before using native state; do not invent a new world-mutation or Lua execution path.
   - On Windows, resolve fields through the selected executable's matching PDB, validating declaring/member types,
     widths, and object bounds. Establish the equivalent native guard and event/update order.
   - On Linux, turn the recorded semantic relationships into bounded dynamic resolution. Validate global receiver
     provenance, settings-object identity, flag widths/bounds, and named setting/value association. The existing
     disassembly is a starting point, not a license to copy offsets or instruction fingerprints.
   - Preserve all branches of the actual native condition. A minimized window alone is insufficient because
     `halt-rendering-when-minimized=false` may permit drawing. Pause, focus, world presence, and absent frames are
     not substitutes for the guard.
   - Verify that an admitted screenshot reaches its capture callback in the supported iteration and that relevant
     state cannot change in between in a way that suppresses that frame. Inspect relevant setters/reentry paths
     locally; do not expand this into a recursive whole-program scan.
   - If a concrete counterexample defeats one-check reliability, preserve its evidence and reassess the smallest
     necessary correction. Do not silently claim reliability or add a speculative monitoring framework.

2. **Add the minimal safe-point admission check.**
   - Resolve metadata using existing attachment/first-use mechanisms and reuse it within the owned attachment.
     Read current game values at native screenshot admission, after GUI logic, rather than caching rendering state
     when the MCP request arrives or when attachment is established.
   - If the native guard says rendering is suppressed, complete the screenshot with an explicit terminal failure
     before arming readback. Follow existing command completion and cleanup paths so the shared observation queue
     and single-screenshot reservation are released without requiring a separate cancel call.
   - Otherwise preserve the existing renderer-specific capture path and output. Do not force a frame or alter
     the game's setting/window state. Do not advertise generic detection of driver hangs or a stalled frontend.
   - Keep the native boundary typed, use existing error transport/serialization conventions, and expose the same
     meaningful error on both platforms. Suggested wording: `Screenshot unavailable while rendering is suspended.`
     Final wording is an implementation choice; this is not an existing error identifier.
   - Preserve cancellation before admission, cancellation after admission with cleanup, idle repeated cancellation,
     busy rejection, detach, and process-exit reconciliation. Native notifications must stay nonblocking. Do not
     discard hook/result ownership if cleanup fails.
   - Keep lifecycle and cancellation policy in shared Kotlin. Avoid unrelated capture refactoring, new schemas,
     public diagnostic tools, or persistent platform-specific branches in the portable contract.

3. **Add focused, meaningful verification.**
   - Metadata fixtures must cover semantically equivalent compiler forms and reject ambiguous/unsafe field,
     pointer, width, or ABI provenance. Use synthetic fixture layouts, never researched game offsets as constants.
   - Verify predicate branches, especially minimized + halt enabled versus disabled, and verify native completion
     on rejection without a live frame hook/readback owner. Cover the unknown suppression flag by observed native
     behavior without inventing public semantics.
   - In common lifecycle tests, show that a screenshot rejection clears admission and permits the next observation
     and capture. Reuse existing cancellation/detach tests rather than duplicating implementation internals.
   - Starting tests include `GameSessionTest` cases `screenshotRejectsConcurrentCaptureAndCancellationWaitsForCleanup`,
     `screenshotCanBeCancelledBeforeNativeAdmission`, and `detachAlsoDrainsActionsAndScreenshots` under `commonTest`.
   - Linux fixture starting points: `GraphicsSwapCallTest`, `FrameApiBindingTest`, `FrameHookBindingTest`,
     `ElfEvidenceTest`, and native `frame_context_fixture.cpp`, `frame_hook_owner_fixture.cpp`,
     `frame_hook_fixture.cpp`, `frame_readback_fixture.cpp`. Windows has native `screenshot_test.cpp`.
   - Use normal Gradle/KMP and CMake/CTest wiring. Run the applicable ordinary build/tests on both hosts sequentially.
     Tests must not require a running game by default. Test watchdogs are allowed; product timeouts are not.

4. **Run explicit real-game acceptance for the new behavior.**
   - Start from Linux `com.hiczp.factorio.mcp.acceptance.ScreenshotAcceptanceTest` and its existing consecutive-PNG
     baseline. Use the official SDK Streamable HTTP client with explicit endpoint/PID and keep external-game
     checks in the acceptance package, outside the default Gradle run. Share coherent checks where appropriate.
   - Check existing processes first; use at most one graphical client and one isolated localhost server. Reuse
     Steam. Do not terminate externally managed processes or touch existing saves. Restart the test game after
     updating MCP/resident artifacts as required by the repository's attachment rules.
   - For Linux, reuse the disposable Xvfb/Openbox test approach if useful. Window-state control belongs only in
     test helpers. No desktop capture, forced focus, or OS keyboard/mouse simulation is required.
   - Verify the following matrix on each implemented platform and record unsupported/unexecuted cases honestly:

     | Scenario | Required observation |
     | --- | --- |
     | Normal rendering, including a menu and a rendered paused world | Capture returns a valid PNG; repeated capture still works. |
     | Minimized with halt enabled, after the game processes the event | Capture returns the suspension error without restore or cancel. |
     | Immediately after rejection | UI and terrain/world queries complete in a loaded world; another capture is not stuck busy. |
     | Restore rendering after rejection | A new capture succeeds. |
     | Minimized with halt disabled and native guard permitting drawing | Capture is allowed and succeeds; minimization alone is not rejected. |
     | Request queued before a rendering-state change | Admission uses the current native state when it actually executes. |
     | Minimize event around admission/readback | Controlled fixture or runtime evidence validates the claimed event-order boundary and absence of the reported race. |
     | Existing busy/cancel/detach/process-exit paths | Existing completion and cleanup guarantees remain intact. |

   - For rejected capture, a test watchdog may detect a regression; successful completion must occur while the
     game stays minimized, and subsequent observations must not depend on explicit screenshot cancellation.
   - Preserve working gameplay/input/UI/world observations in the minimized configuration. Reuse the relevant
     existing acceptance tests, including authoritative effects/full CRC when actions are exercised.
   - Restore any test configuration. Record tested executable/build, artifact identities, endpoint setup, results,
     and cleanup in a new `temp/` evidence directory. Keep the October 30 baseline intact.

5. **Update the public contract and finish the handoff.**
   - Update the `screenshot` description in `McpServer.kt`, the screenshot and queue sections in
     [tools.md](../../tools.md), and the practical limitations in both [README.md](../../README.md) and
     [README-zh-cn.md](../../README-zh-cn.md).
   - Replace the ordinary minimized-rendering pending behavior with the implemented immediate admission error.
     Keep capture/cancel semantics, renderer requirements, and optional-screenshot guidance concise and aligned.
     Do not promise immediate error delivery when the entire frontend safe point itself cannot execute, or claim
     every possible rendering/backend stall is detected by this guard.
   - Review adjacent wording about suspended rendering and observation queues for consistency; preserve the real
     distinction between rendering being skipped and the frontend not reaching an observation's safe point.
   - Update [development.md](../../development.md) only for reusable build/test procedures that change. Keep raw
     outcomes, performance comparisons, implementation research, and verification gaps out of end-user guides.
   - Do not trigger CI, create commits, or publish packages. Report ordinary tests, native fixtures, and explicit
     platform acceptance separately; historical baseline results are not new-change validation.

## Completion criteria and practical handoff notes

The update is complete when both supported platforms use a validated native rendering condition at the existing
frontend safe point; ordinary suspended-render captures return a useful error without blocking subsequent tools;
active-render capture and existing lifecycle behavior remain correct; and tests/documentation match that behavior.
A missing Windows proof or acceptance run remains an explicit gap, not completed cross-platform support.

The original research processes were all cleaned up: owned MCP, graphical client, localhost server, Openbox, and
Xvfb were stopped; user configuration was restored byte-for-byte; the existing Steam session was left running.
Historical PID/display/port values in the evidence identify that experiment, not currently usable attachments.
Discover the current environment before testing. No product implementation was changed to create this plan.

The evidence under `temp/` is local and ignored. A different checkout will need those directories transferred
explicitly or the investigations repeated; the plan alone does not include raw disassembly, logs, or artifacts.
