# Add a one-shot stop_rendering tool

Status: implementation pending. Created: 2026-10-30.
This document is a plan for another agent, not a claim of implemented or verified product behavior.

The user explicitly requested a new plan under `.codex/plans`. This overrides the normal location for unfinished
plans; raw research and verification results stay under ignored `temp/`. Follow [AGENTS.md](../../AGENTS.md)
and work without subagents.

## Authoritative task and boundaries

Add a new MCP tool that directly makes the attached graphical client stop drawing its window, without actually
minimizing the window. Preserve normal simulation, input, UI logic and queries/actions, and terrain/world queries.
The purpose is to reduce rendering resource consumption while an agent continues playing.

The user explicitly accepts that subsequent user actions, window events, or other system events may make the
game resume drawing. Persistence is not part of the contract. A one-time native state change is the preferred
starting implementation. The absence of user interaction does not itself guarantee indefinite persistence.

This task is independent of [the screenshot admission plan](2026-10-30-screenshot-rendering-admission.md).
Do not implement that plan as part of this task, require it as a prerequisite, or make screenshot-error behavior
an acceptance criterion here. The plans may use some of the same native evidence, but deliver different tools
and behavior. Preserve the existing screenshot contract until its separate task changes it.

The earlier research note proposed `rendering { enabled: false | true }` and attachment-owned suppression.
Those were exploratory suggestions, not user requirements. The user's latest instruction narrows this task to
a new stop-drawing action. Do not expand it into a persistent mode, a public resume/toggle/status API, or automatic
restoration on detach. Normal cleanup of in-flight commands and owned native resources remains mandatory.

## Proposed public contract

Use `stop_rendering` as the tool name, with an empty argument object. Keep one identical contract on Linux x64
and Windows x64. No duration, persistence, retry, screenshot, or platform-specific arguments are needed.

```json
{}
```

Return the existing finite-action convention after local execution and required command cleanup:

```json
{"dispatch":"completed"}
```

- Requires explicit attachment to a fully initialized graphical client and execution at the existing verified
  frontend safe point. A loaded world or running simulation should not be a prerequisite; verify menus and
  paused worlds as well as gameplay.
- Execute a one-time change that enters the game's existing drawing-suppressed path. Do not wait for a frame,
  a simulation tick count, a server acknowledgement, or measured resource savings before returning.
- Completion reports local command execution. It does not promise that drawing is still stopped when the caller
  receives the result, or that it will remain stopped afterward. Do not return a durable `rendering:false` claim.
- Calling again when the native flag is already set should succeed without accumulating ownership. A fresh
  explicit call after a known restoration may set it again; do not replay requests with uncertain results.
- Do not minimize, hide, resize, focus, or otherwise manage the OS window. Do not change persistent game settings,
  pause the world, or alter synchronized gameplay state.
- Do not add monitoring, periodic writes, event interception, automatic retries, or a hook whose only purpose is
  to keep suppression in force. User/system restoration is allowed, not a condition to detect and compensate for.
- A completed one-shot state change is not a held MCP input or an ongoing lease. Detach should perform normal
  command/hook/resource cleanup, without introducing a stale-state rollback or a requirement to restore drawing.
  Validate that the chosen mechanism needs no retained object or unfinished callback after completion.

Suggested concise tool description, to refine against the actual implementation:

> Stop drawing the game window without minimizing it. Simulation and structured tools remain available. This is
> a one-time request; later user or window/system events may resume drawing. Requires an attached client.

## Established evidence

### Linux native control candidate

The strongest current evidence is [explicit-render-control research](../../temp/render-control-20261030/README.md).
It adds a concrete event association to the previously unnamed native suppression flag in
[the earlier render-state analysis](../../temp/screenshot-render-state-20261030/findings.txt).

Historical selected executable: Linux Factorio 2.1.24, build 87808, located during research at
`/home/czp/.steam/debian-installation/steamapps/common/Factorio/bin/x64_/factorio`.
Reidentify the installed executable before implementation/testing; this is not a version constraint or allowlist.

The observed native guard in `MainLoop::run` and `GlobalContext::swapRenderBuffers` is equivalent to:

```text
headlessMode
    OR occlusion_associated_suppression_flag
    OR (minimized_flag AND graphics_settings_present AND halt_when_minimized)
```

The middle branch independently suppresses drawing. It does not require the minimized flag or the
`halt-rendering-when-minimized` setting. It also participates in skipping some rendering preparation in
`MainLoop::prepare`; some GUI preparation remains outside that guard.

New static event-path evidence:

1. `Event::convertSDLEvent(SDL_Event const&, CompactDeque<Event>&, float)` maps SDL event `0x216` to game event
   type `0x16` through the selected executable's decoded jump table.
2. `MainLoop::processEvents(bool)` handles that game event by setting the suppression byte on `global`.
3. The [official SDL 3 event enum](https://github.com/libsdl-org/SDL/blob/release-3.2.0/include/SDL3/SDL_events.h)
   identifies `0x216` as `SDL_EVENT_WINDOW_OCCLUDED`. As a cross-check, the conversion maps SDL minimized event
   `0x209` to the game event that reaches the previously verified `Window minimized` logging branch.
4. Exposed/shown/restored/maximized event handling clears the cached suppression and minimized flags. This is
   direct evidence that a one-time change may later be undone by normal game event handling.

The native C++ field name is not known. This event association does not establish all readers/writers, complete
layout validation, or the absence of unrelated effects. Those are implementation prerequisites, not completed work.
The exact addresses, jump tables, game event numbers, and field offsets in the research are evidence for this
selected executable only. Never copy them into product code. Documented SDK enum constants still require a
validated connection to the actual game's event/data flow; they are not substitutes for that proof.

Relevant raw files:

- `temp/render-control-20261030/convert-sdl-event.asm`
- `temp/render-control-20261030/convert-sdl-event-jump-table.txt`
- `temp/render-control-20261030/process-events-jump-table.txt`
- `temp/screenshot-render-state-20261030/process-events.asm`
- `temp/screenshot-render-state-20261030/pre-prepare.asm`
- `temp/screenshot-render-state-20261030/swap-render-buffers.asm`
- `temp/headless-resource-20261030/main-loop.asm` and `prepare.asm`

The observed normal loop handles events before `MainLoop::prepare`. The existing MCP frontend callback runs after
GUI logic and before subsequent rendering/worker work. This provides a promising place for the one-time write.
Confirm the relevant control flow and thread ownership for this write; a safe-point label alone is not proof.

### Historical runtime evidence, with a different mechanism

[The minimized-client experiment](../../temp/headless-resource-20261030/README.txt) used the full Linux client on
Xvfb/Openbox with llvmpipe and actual minimization. On rebuilt artifacts from source `8837ca8`, five explicit SDK
HTTP acceptance tests passed: input, loaded-world queries, UI actions, chat reading, and input bindings. Additional
world overview and terrain queries succeeded. Action tests checked authoritative local-server effects/full CRC.

For that small software-rendered fixture, CPU dropped from 1031.66% to 40.46% of one core in 15-second samples;
game RSS stayed about 3.05 GiB. Successful boundary reads were consistent with approximately 60 UPS. These facts
support the value of skipping rendering while retaining frontend logic. They do not prove the proposed direct
write works, predict hardware-GPU savings, or show graphics memory will be released.

The historical ordinary build/test run passed 757 tests. Preliminary stale-artifact decoder failures and queries
that overlapped another test's detach are explicitly excluded in that README. Preserve those qualifications.

### API and platform gaps

The reviewed [LuaRendering API](https://lua-api.factorio.com/latest/classes/LuaRendering.html) manages added
shapes/text/sprites. [GameViewSettings](https://lua-api.factorio.com/latest/concepts/GameViewSettings.html) controls
specific view/UI features. Neither reviewed interface supplies a whole-window stop-drawing operation. Complete a
brief current API assessment and document the concrete gap before using the native path; this initial review was
not an exhaustive API survey. Do not introduce a mutating Lua workaround for local rendering control.

No direct modification of the candidate flag has been tested. Windows field identity, matching PDB metadata,
equivalent execution path, and runtime behavior are unverified. Do not treat Linux event/layout evidence as proof
for Windows. Both platforms must implement the shared tool before claiming completed portable support.

## Implementation work

1. **Validate the narrow native action.** Inspect the established Windows frontend command execution and cleanup
   first, then follow the corresponding Linux path. Identify the game's drawing-suppression field and relevant
   readers/writers. Verify width, receiver/type identity, object bounds, lifetime, thread ownership, and whether it
   is strictly local frontend state rather than serialized world state. Verify normal worker synchronization is
   preserved when setting it after GUI logic. Use bounded local proofs, not recursive whole-program analysis.
2. **Resolve metadata dynamically.** Use the selected Windows executable's matching PDB and validated member
   types/bounds. On Linux, use matching symbols and bounded decoded data/control flow, including the observed
   guard/event relationship as appropriate. Reject ambiguous or unsafe candidates. Do not use remembered offsets,
   instruction fingerprints, assumed SDK layouts, or game-version tables. Reuse attachment-owned metadata.
3. **Implement one finite native command.** At the verified frontend safe point, reacquire/validate the live
   receiver, write the identified boolean flag once, and complete through the existing typed result transport.
   An already-set flag is a successful no-op. Do not introduce a frame hook, pixel readback, screenshot metadata
   dependency, or continuously serviced rendering state. Do not switch `GlobalContext::headlessMode`, which
   affects frontend behavior beyond drawing. Merely skipping final swap/presentation is not the researched path.
4. **Integrate the portable tool and lifecycle.** Keep tool schema, result serialization, scheduling, cancellation,
   and attachment policy in `commonMain`. Native code supplies only the validated state operation. Use existing
   liveness checks, command serialization, cooperative cancellation/detach, and outstanding-command reconciliation.
   Cancellation before native execution must not write; after execution it does not justify an invented rollback
   or replay. Complete native request ownership without waiting for MCP result consumption. Add no tool timeout.
5. **Keep scope narrow.** Do not turn ordinary state restoration into a product failure or add a public resume
   action for test convenience. Tests may use normal game/window events to restore drawing in an isolated setup.
   If evidence shows this simple write cannot meet the contract, record the concrete failure before proposing a
   different native mechanism. Persistence alone is not a reason to choose a more complicated mechanism.

Code entry points, relative to the repository root:

| Responsibility | Starting locations |
| --- | --- |
| Tool schema and descriptions | `src/commonMain/kotlin/com/hiczp/factorio/mcp/McpServer.kt` |
| Shared lifecycle and connection contract | `src/commonMain/kotlin/com/hiczp/factorio/mcp/GameSession.kt`, `Platform.kt` |
| Windows metadata and transport | `src/mingwX64Main/kotlin/com/hiczp/factorio/mcp/DebugSymbols.kt`, `ResidentConnection.kt` |
| Windows verified frontend execution | `src/mingwX64Main/native/resident.cpp`, especially `updateGui` and `service` |
| Linux metadata and transport | `src/linuxX64Main/kotlin/com/hiczp/factorio/mcp/FrontendSite.kt`, `ElfEvidence.kt`, `ResidentConnection.kt` |
| Linux verified frontend execution | `src/linuxX64Main/native/resident.cpp`, especially `fm_after_gui_logic`; `gui_hook.S` and `resident.h` |
| Common lifecycle/transport tests | `src/commonTest/kotlin/com/hiczp/factorio/mcp/GameSessionTest.kt`, `HttpTransportTest.kt` |

Follow the current wire definitions from these sources; allocate a command through the existing contract rather
than assigning an operation number from this plan. Do not add a wire version or cross-build compatibility layer.

## Verification and acceptance

Add meaningful metadata fixtures for equivalent compiler forms and unsafe/ambiguous receiver or field provenance.
Use synthetic layouts and keep native assertions enabled in optimized builds. Test the one-time write, already-set
case, validation rejection before mutation, and normal completion. Common tests should cover tool discovery/schema,
detached rejection, dispatch result, cancellation before admission, and continued queue/lifecycle operation.
Reuse existing regression coverage rather than creating a parallel test framework.

Use normal Kotlin/Gradle and CMake/CTest wiring for ordinary builds/tests, with Linux and Windows runs sequential.
Keep installed-game checks outside default Gradle tests, under the existing acceptance/offline package conventions.
For explicit real-game acceptance, use the official SDK Streamable HTTP endpoint with selected endpoint/PID.

| Scenario | Required evidence |
| --- | --- |
| Attached, initialized menu with an unminimized window | Tool executes without a loaded world; frontend/UI reads continue. |
| Running disposable world, window remains unminimized | Drawing stops through the native path while simulation and structured queries continue. |
| Paused world | Tool executes and UI/world observations remain available; no inference that simulation is running. |
| Halt-when-minimized setting disabled | Tool still works, demonstrating independence from that configuration. Restore test configuration afterward. |
| Repeated call while already suppressed | Completes without accumulating work or blocking later tools. |
| Normal exposed/restored event after stopping | Game is allowed to resume drawing; no automatic reapplication. Another explicit stop call works. |
| Gameplay while drawing is stopped | Input, UI actions, chat/bindings, terrain/world queries retain behavior; authoritative local-server effects/full CRC for action checks. |
| Detach, cancellation, process exit, and MCP restart reconciliation | Existing ownership/liveness guarantees hold; no new rendering watcher or retained command. No automatic visual restoration requirement. |

Keep the OS window unminimized for the defining test. Showing that actual minimization still works is not evidence
that this tool works. Observe the native state and drawing activity using test-only instrumentation as needed;
a successful dispatch, frozen screenshot, or world presence alone is insufficient. Measure comparable resource
intervals, but do not impose the historical llvmpipe percentage as a pass threshold. Separate CPU, GPU, and memory
claims according to what was measured. Product code must not poll for resource savings or effects.

Do not issue a screenshot while drawing is stopped merely to test this tool: the unchanged screenshot may wait
for a frame and block its queue. Restore drawing through a normal test-window event before any PNG verification.
This test discipline does not require implementing the separate screenshot-rejection plan.

Check existing processes before testing; run at most one graphical client and one isolated localhost server.
Reuse Steam, start a test graphical client without game arguments, and load disposable saves through normal UI.
Avoid forced focus, desktop capture, OS input simulation, and remote servers. Do not terminate externally managed
processes or modify existing user saves. Restart the game after updating MCP/resident artifacts. Xvfb/Openbox
window-event helpers may be used only in isolated tests, not as product implementation.

Record exact executable/build, artifact identities, test results, performance samples, unverified cases, and cleanup
under a new ignored `temp/` directory. Preserve all earlier research as historical evidence. Do not claim the new
native action passed because the earlier minimized-window acceptance passed.

## Documentation and completion

Add the tool to the concise catalogs in [README.md](../../README.md) and [README-zh-cn.md](../../README-zh-cn.md).
Document arguments, result semantics, frontend prerequisites, and the allowed restoration by later events in
[tools.md](../../tools.md); align `tools/list` and any relevant server guidance. Clearly describe the operation as
one-shot and nonpersistent. Keep implementation details and test results out of end-user guides. Update
[development.md](../../development.md) only for reusable test/build procedures that change.

Completion requires the new tool on both implemented platforms, validated native resolution and execution,
ordinary tests and explicit real-game acceptance, continued gameplay/query functionality without minimization,
and aligned documentation. Report platform or runtime gaps honestly. Do not trigger CI, create commits, or
publish packages. No product implementation or acceptance run was performed when creating this plan.

The referenced `temp/` evidence is local and ignored. An agent in a different checkout will need those files
transferred or the research repeated. Historical process IDs, display numbers, and endpoints are not live targets;
the original research processes were cleaned up and user configuration was restored.
