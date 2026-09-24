# factorio-mcp generic interaction refactor

Status: agreed direction and implementation plan; implementation has not started under this plan.

## Objective and scope

Replace the gameplay-specific tool catalog with a small set of observation and interaction tools. The agent interprets
the game, chooses actions and verifies outcomes. MCP exposes UI structure, game data, effective input bindings and
finite
input execution. It understands engine/API types and lifetimes, but does not encode recipes, machine workflows, mod
strategies or a mapping from gameplay intent to private action functions.

The goal is compatibility with unfamiliar mod content through shared engine interfaces, not an unverified promise that
every mod interface or custom rendering technique is observable. Report missing capabilities explicitly. Structured
interaction must not depend on image recognition. An optional game-rendered screenshot lets the agent inspect visual
content when needed; MCP does not implement visual recognition. Do not depend on OS input injection, desktop capture,
foreground activation, RCON, console commands or administrator privileges.

Keep Linux x86-64 as the implementation target, Kotlin/Native as the executable platform, the official Kotlin MCP SDK,
and existing stdio/Streamable HTTP transports. The executable remains factorio-mcp. Connect to an already-running local
client; never launch Factorio as a product feature. Windows research informs mechanisms but does not establish Linux ABI
or execution safety.

This plan is explicitly requested under `.codex/plans`. Further experiments and evidence belong under ignored `temp/`.

## Research authority and evidence boundaries

The [architecture research](../../interaction-architecture-research.md) is the primary reference. Its
[current input contract](../../interaction-architecture-research.md#current-input-contract-bindings-and-finite-sequences)
supersedes historical proposals to activate `mine`, `build` or other named controls independently of physical bindings.
Preserve earlier evidence without implementing rejected matching, active-state or cursor-getter overrides.

| Capability             | Established path                                                                                                                                | Boundary before production use                                                                                                                                        |
|------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| UI enumeration         | Linux captures actual GUI/root receivers at GUI logic calls and uses `Widget::callRecursively` with a compiler-created callback.                | Complete foreground selection and safe-phase validation on Linux; eliminate dependence on constructor history and private object offsets.                             |
| UI execution phase     | Windows reacquires the foreground root and reads or performs limited button actions at a scoped `Gui::logic` return inside `MainLoop::prepare`. | Verify corresponding Linux scheduling, worker exclusion, ABI and lifecycle. Do not simply hook an arbitrary return with the same symbol name.                         |
| Stateless UI selection | A unified document and structured path locate current controls; duplicate windows, reordering, deletion and recreation were exercised.          | Integrate supported property readers and action families; never infer a property or action from a class name alone.                                                   |
| Binding discovery      | Linux late attachment enumerated registered controls, verified names by reverse lookup and read game binding text.                              | Verify complete structured device/modifier extraction, custom localized names and changes to settings/prototypes.                                                     |
| Physical input         | Ordinary in-process SDL events exercised finite movement and mining with authoritative observations and matching full CRC checks.               | Validate chords, pointer state, release processing, cancellation and human-input coexistence. Overrides used in older probes are not evidence for this path.          |
| Screenshot fallback    | Existing `Screenshot.kt` uses `game.take_screenshot` and returns PNG through MCP. Research obtained GUI images without requesting focus.        | Requires the game's graphical renderer. The API re-renders a scene and misrepresents the actual remote chart; it is not universally a capture of the presented frame. |
| World observations     | Bundled API inspection and live tests establish tile/entity overlap and bounded projections; client camera transforms were sampled.             | Server-side query evidence is not a certified client query adapter. Verify side effects, visibility and serialization on the injected client.                         |
| Lifecycle              | Existing process/world separation and request correlation provide reusable infrastructure.                                                      | An older research scheduler left native work pending after world exit. Explicit teardown and release are mandatory acceptance gates.                                  |

Refer to [Linux traversal](../../interaction-architecture-research.md#linux-traversal-path),
[foreground acquisition](../../interaction-architecture-research.md#windows-foreground-root-acquisition),
[safe UI scheduling](../../interaction-architecture-research.md#why-this-is-a-safe-ui-phase-including-the-main-menu),
[late binding discovery](../../interaction-architecture-research.md#linux-discovery-after-startup),
[map observations](../../interaction-architecture-research.md#map-observation-spatial-selection-and-api-shaped-results)
and [remaining integration work](../../interaction-architecture-research.md#remaining-work-before-production-integration).

## Agent-facing tool surface

Names below define the planned responsibilities; finalize schemas before registration. Avoid duplicate aliases and
per-machine or per-mod tools.

| Tool             | Responsibility                                                                                                                                                                     | Readiness                                                                                                                                                       |
|------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `status`         | Select by PID or process name, identify support before modification, install/reuse the minimal observer and report process, UI, input and world readiness separately.              | Process identification; unsupported targets return a structured result.                                                                                         |
| `attach`         | Idempotently bind observation adapters to the current active world; require the observer first.                                                                                    | Existing observer and active world.                                                                                                                             |
| `detach`         | Disconnect MCP resources without terminating Factorio or silently cancelling admitted finite work.                                                                                 | Existing connection when present; repeat calls are harmless.                                                                                                    |
| `ui_read`        | Read the whole current document or matching subtrees through one selector format.                                                                                                  | Current process-level UI phase; no world Lua requirement.                                                                                                       |
| `ui_action`      | Resolve one current widget, validate eligibility, then perform a supported widget-family operation.                                                                                | Current UI phase and verified action adapter.                                                                                                                   |
| `input_bindings` | Read registered control names and their current effective bindings, including mod controls.                                                                                        | Current registry; expose availability independently of world Lua.                                                                                               |
| `input`          | Execute ordered finite physical-input steps against either UI or world context, optionally cancelling older sequences first.                                                       | Verified input source and duration clock. Initial tick-based gameplay execution requires an active world; do not reinterpret ticks as menu frames.              |
| `screenshot`     | Optional image observation when structured data is insufficient or the agent needs to understand the current presentation. Include capture source, scope and fidelity limitations. | Verified game-rendering path, without desktop capture or focus manipulation. Existing API path requires a world; menu/paused capture needs separate validation. |
| `world_overview` | Describe the current viewport or requested bounded area at a specified observation scale.                                                                                          | Current world binding and valid observation context.                                                                                                            |
| `world_query`    | Select tiles/entities or supported related objects and project requested API-shaped details.                                                                                       | Current world binding and valid observation context.                                                                                                            |

Make local-player identity implicit. Shared observations must include enough context to distinguish active controller,
surface/view and physical character. Do not require an agent to choose among connected players for ordinary tools.

Provide bounded prototype/catalog reads, including recipe definitions, through the shared read interface where they fit
coherently; use a distinct generic read tool only if its selection or lifetime contract warrants it. Keep prototype
data,
force availability and runtime instances distinct. Do not preserve dozens of old aliases or add a recipe cache by
default.

## Stable paths to implement

### Agent guidance and choice of interaction path

The agent may use either direct UI actions or physical input to operate UI. For example, pressing the configured E key
may close an open window; its actual effect still depends on current bindings and context. Do not reject input because
it targets UI, require a failed `ui_action` first, or make MCP choose tools on the agent's behalf. Preference belongs in
MCP server instructions and relevant tool descriptions, not in gameplay-aware routing or restrictions.

Use guidance equivalent to the following when the corresponding refactored tools are implemented:

> Prefer structured UI observations and direct UI component operations when they cover the interaction. You may also
> use keyboard/mouse input to operate UI or the world; inspect current bindings and context to choose the input. Use
> screenshots when structured observations are unclear or omit visual content, including embedded views or mod-drawn
> graphics. Check capture limitations before treating an image as the current screen. Observe the result after an
> action;
> completed input does not guarantee the intended gameplay effect.

Screenshots and physical UI input form a complementary fallback, not an automatic MCP vision-and-click workflow. The
agent decides when they help. Neither path bypasses normal game input routing or widget eligibility.

### 1. Process observation and readiness

Read developer debug information from the actual target image before injection. Resolve required entry points and verify
their ABI without address tables, private field offsets, instruction fingerprints or version allowlists. Retain the
existing minimal resident and IPC design where its safety and lifecycle contracts hold.

Separate process observation, frontend/UI availability, input-source availability and world Lua binding. A menu can have
usable UI while no world exists. A world transition invalidates world adapters without necessarily removing the frontend
root. `status` must not implicitly bind world operations; `attach` remains explicit and repeatable. UI tools use process
readiness rather than the old universal `active()` world guard.

Reconnect to compatible residents and current-world hooks without reinstalling hooks or clearing admitted tasks. Reject
incompatible resident protocols explicitly. Startup, loading, stalled phases and unsupported targets produce actionable
states/errors rather than stale access or indefinite waiting. UI coverage starts once a supported frontend phase exists;
very early application startup is not presumed readable.

### 2. UI observation and direct operation

Implement
the [single-document selector contract](../../interaction-architecture-research.md#current-contract-one-dom-and-one-selector-model).
Acquire the active foreground root at a verified frontend boundary for each request. Traverse actual widgets through
game APIs, extract typed records, and construct the public synthetic `document` tree. Include ordinary private children
when the verified traversal includes them; do not collect background-world roots into the foreground document.

Expose semantic role, text/label, verified prototype metadata, bounds, supported values and relevant state. Preserve
unknown values as unknown. Disabled widgets remain readable with their state. Detached/destroying widgets must not be
presented as valid targets. Clipping or being outside the viewport alone does not make a widget inoperable, but actual
game routing/eligibility restrictions still apply and should be observable when known.

Use the same structured path for reads and actions. Reads may have zero or multiple matches; an action requires exactly
one final match. Re-resolve at execution, check membership, target/ancestor lifetime and enabled/routing state, finish
traversal, then invoke the verified widget action without yielding. Never invoke actions from inside a traversal
visitor.
No public pointer, persistent component ref, window registry or snapshot lease is required. A recreated semantically
matching widget may be selected by the same path.

Adapters operate on widget families: activation, text editing, check/toggle, selection, numeric values and supported
slot
interaction. Derive applicability from verified type APIs; report unsupported families instead of guessing or
substituting
screen clicks. Do not call arbitrary setters just because they appear writable: UI dispatch must preserve the normal
synchronized action path. Native and mod-created UI both require real-game validation.

The initial correctness collector may reuse bounded traversal from research; measure repeated-subtree cost before
optimizing. Output/work limits must report incomplete coverage. An incomplete selector search cannot prove uniqueness.
Special tooltip layers and embedded scene-preview contents remain outside the agreed initial scope.

### 3. Binding discovery

Enumerate the current registry after late attachment. Reuse verified game getters and reverse lookup of stable control
names, with official custom-input prototype names where appropriate. Read effective bindings, not only default values,
locale strings or saved configuration. Return primary/alternative combinations and explicit unbound states.

Define a stable device/key/button/modifier vocabulary shared with `input`. Validate conversions against actual game
getters and SDL event processing. Handle unsupported device/input kinds explicitly; raw binding text can supplement
structured fields but must not conceal a lossy parser. Re-read after settings changes instead of retaining an
unversioned
cache. Binding discovery explains possibilities, not current action eligibility or guaranteed outcomes.

### 4. Finite physical-input sequences

Implement the agreed `operations`, `controls`, `ticks` and `stop_previous` contract in the research document. Each step
holds its combination together for positive `ticks` (default 1), releases it, then starts the next step. An empty
control
set waits. An empty sequence without cancellation succeeds immediately. Ordinary calls queue in order without
interleaving input sequences.

Use the installed game's in-process SDL event path and normal event processing. Preserve input matching, contextual
consumption, internal held state and normal action submission. Do not translate `mine` into a private handler or
MiningState mutation. Do not synthesize a new key-down every tick to approximate a hold. Count distinct validated client
input ticks, not callback invocation count, wall time or assumed server movement ticks.

MCP sends an admitted sequence as one complete finite task. The resident keeps only the current sequence, progress and
input-release state needed to execute independently. Never require MCP to send the next step or release after a delay.
UI/input scheduling must remain available for release and cancellation when simulation ticks pause; paused duration must
not silently advance on another clock. Bound resources without interpreting gameplay rules or forbidding multi-tick
input based on a control's assumed meaning.

`stop_previous` is a control operation that can interrupt pending execution. At a defined admission boundary, cancel
older active and queued sequences, release bridge input, then start the replacement. An empty replacement is stop-only
and returns after cleanup. Do not hold the existing global mutation lock while waiting for a sequence if that prevents
the cancellation request from running. Reads and control-plane messages remain responsive. Serialize direct UI mutations
against active sequences initially; never silently cancel a sequence just because a UI request arrives.

Track input ownership and validate interaction with real user input, especially both sources holding the same key.
SDL enqueueing alone does not solve source ownership. Do not claim this requirement complete until tests establish a
normal-event solution that neither releases user-held input nor leaves bridge input stuck. Document any unresolved
limitation rather than reverting to predicate overrides.

Mouse targeting is a required companion capability: validate pointer-motion/target updates through the game's normal
event or callable input path so cached cursor position, selection and GUI hover are consistent. Support explicit current
viewport coordinates and/or verified world-to-view conversion as the schema requires; reject invalid contexts instead of
overriding getters. Direct UI tools still use selectors. Validate drag, wheel and text input separately;
wheel/analog/text
are not automatically holdable keys. Final parameter shapes for these input kinds require focused experiments.

Completion means processed finite input and final release, not a successful game action. Report completed steps and
known
within-step progress on failure/cancellation; distinguish enqueueing, execution and cleanup uncertainty. A game-rule
no-op
is not a tool error. Never replay uncertain work. Game effects already produced cannot be rolled back by cancellation.

### 5. World observation

Use bounded official Lua readers for the injected local player's current world, with explicit applicable-type and
side-effect checks. Use verified native camera/presentation readers where Lua does not expose the required view state.
Run each access at the appropriate world or frontend phase; owning an object pointer does not grant cross-thread access.

`world_overview` defaults to the actual viewport, reports basic spatial content and supports explicit aggregation
without
changing the camera. `world_query` separates selection (point, containing tile, area, game identity) from projection
(requested attributes and bounded related objects). Preserve separate tiles/entities, overlap, hidden tile fields,
quality and train/inventory relationships. Collision queries and native cursor hit testing are distinct operations.

Follow bundled API names/types rather than inventing schemas for each prototype. Use documented API metadata to guide
projection, not to blindly execute every getter/method. Preserve nil, unsupported, unavailable and failed reads as
different outcomes. Resolve related objects against current-world identities or fresh descriptions without an MCP object
registry, and detect ambiguity. Bound recursive relationships and work/output size.

Report world/surface/context, observation tick, bounds and coverage. Distinguish live visible data, charted information
and unknown regions. `get_chunk_chart` supplies cached raster data, not remembered semantic entities; do not substitute
hidden live-world data for chart memory. Validate normal and remote views, zoom, stacked rail/train/tile content and
fog.
Do not require a UI canvas node: the main world view and UI tree have separate rendering/observation paths.

### 6. Optional game-rendered screenshot fallback

Retain the existing screenshot tool where its game-owned render path meets this scope. Reuse official screenshot APIs
first; investigate native frame capture only if necessary to represent the actual current presentation, with verified
ABI, render-thread ownership and lifetime. Do not introduce X11/Wayland capture, desktop automation or window activation
as a product dependency. This does not promise operation without Factorio's own graphical renderer or graphics context.

The
research [screenshot comparison](../../interaction-architecture-research.md#screenshots-without-requesting-window-focus)
shows that `game.take_screenshot` can include GUI but constructs a separate scene render. In remote chart mode it showed
terrain instead of the actual chart/fog/icons; some overlays were also absent. Preserve this negative evidence. The
XComposite comparison is research evidence only, not an accepted fallback implementation for this tool.

Return the image using MCP image content, plus capture-source/context metadata and known limitations. Distinguish a
fresh current-frame capture from a re-rendered view; do not present a stale or differently projected image as the
current
screen. Report unavailability or limited fidelity explicitly. Verify normal UI, remote/chart view, embedded cameras,
mod-drawn content and capture during relevant UI states. An image path supports the fallback only for content it
actually
captures; screenshot availability does not close all structured-observation gaps.

## Code ownership and migration

- Keep typed schemas, tool registration, selector semantics, validation, request correlation, external queues and result
  formatting in `commonMain`. Selector evaluation that must be atomic with native UI dispatch runs in the resident
  against its current traversal; do not add an IPC round trip between resolving and invoking a widget.
- Keep process discovery, debug parsing, injection, IPC and native ABI adapters in the appropriate platform source set.
  Use a thin native layer for unavoidable engine interfaces. Use portable kotlinx libraries rather than extra
  expect/actual wrappers. Retain CMake/Gradle ownership and default KMP layout.
- Keep world-local query state scoped to the current Lua VM. UI and input safety cannot depend solely on a world Lua
  callback because menus, pause and teardown must remain serviceable. Do not duplicate business orchestration in
  Lua/C++.
- Extend/version the resident protocol with typed capabilities and operations. IPC remains nonblocking; IDs are unique
  across MCP restarts, unknown replies are discarded, and the game retains no undelivered result history.
- Audit `McpServer.kt` registration and readiness/locking, `ResidentClient.kt` correlation, `ResidentScheduler.kt` and
  the
  Linux resident/bridge before reuse. Their current world-wide guard and mutation lock do not satisfy the new UI and
  cancellation contracts unchanged. Preserve transports and shutdown handling unless a concrete change requires work.
- Migrate reusable side-effect-free readers into the shared observation layer. After replacements pass acceptance,
  remove superseded gameplay tool registrations, scripts, per-operation adapters and obsolete tests. Do not retain
  temporary blueprints, game-rule substitutes, or duplicate compatibility aliases as hidden implementations.

## Implementation sequence and acceptance gates

1. **Contracts and inventory.** Map existing tools/helpers to retained infrastructure, shared readers or removal. Define
   typed schemas, readiness states, capability/error reporting and the resident protocol version. Keep old implemented
   behavior distinct from planned tools until each replacement is ready.
2. **Linux phases and lifetimes.** Validate native ABI fixtures, late process observation, foreground UI phase/root,
   input phase/clock and world invalidation. Exercise menu, gameplay, pause and world replacement. No stale pointers,
   duplicate hooks or native tasks surviving their invalid world are acceptable.
3. **Vertical observation and UI slice.** Implement full/selected DOM reads and one verified button family end to end
   via
   actual MCP HTTP calls. Test a native menu button and a synchronized mod widget; then expand widget families and state
   coverage. Keep client/server CRC validation separate from UI appearance or fixture success.
4. **Bindings and sequences.** Implement live bindings, finite keys/buttons and ordered waits, then chords,
   cancellation,
   replacement and autonomous cleanup. Complete cursor targeting and other required input forms through focused probes.
   Test a real binding change, shared bindings and custom input dispatch. Do not advertise unsupported shapes.
5. **World readers.** Implement overview and API-shaped queries using the verified local-client path. Cover overlapping
   objects, related inventories/prototypes, invalidated identities, remote view and observation coverage. Verify that
   reads leave authoritative state and full CRC unchanged.
6. **Integrated coverage and removal.** Exercise representative play through UI, bindings, sequences and world reads,
   without gameplay-specific tool shortcuts. Remove obsolete catalog/helpers once replacement coverage is demonstrated.
   Retain/validate the optional screenshot fallback, test agent-selected physical UI input, and update MCP instructions
   to prefer direct UI controls without forbidding alternatives. Advertise screenshot fidelity limits explicitly.
   Update README to implemented behavior, update AGENTS.md when old guards/rules conflict, and remove generated useless
   or empty directories. Keep research evidence under `temp/`.

Use one graphical Steam client and a local headless server with a non-admin client. Reuse them for ordinary iterations;
restart Factorio when native resident ABI or lifecycle testing requires it. Rebuild with
`./gradlew linkDebugExecutableLinuxX64`, restart MCP with `--no-stdio --http-port PORT`, initialize a standard MCP HTTP
session, then call `status` and `attach` as needed. Keep a stdio smoke test and existing transport tests. Do not
reintroduce
a custom persistent stdio client as the main test path.

Required focused acceptance includes:

- UI: full/selected reads; duplicate matches; recreation/reordering; disabled and destroying targets; clipped/covered
  targets; main menu, inventory/quickbar, research, train schedule, remote-view selector and mod widgets. Unknown
  properties
  remain explicit. Never claim a generic action family based only on one successful button.
- Input: default one tick; multiple steps; simultaneous keys/buttons; release/repress; empty wait/no-op; active/queued
  cancellation; stop-only and replacement; partial failure; pause; MCP termination/reconnect; human-input overlap; world
  unload and target death. Verify the resident releases independently and does not mistake enqueueing for completion.
- Fallbacks: close a window through its currently bound key and interact through mouse input while UI is open. Verify
  that tools do not impose a UI-only/direct-action-only policy. Inspect actual screenshot content and metadata against
  normal, remote and mod/custom-rendered scenarios; no desktop capture or focus operation is required by the tool.
- World: normal/remote camera context; point/cell/area distinctions; multiple occupants; API-shaped bounded details;
  prototype reads; unsupported properties; fog/chart limitations; no simulation mutation from read-only tools.
- Multiplayer: assert representative writable effects on the local server and force full CRC after each new mutation
  path. Report native fixture results separately. Transport ordering alone is not completion correlation.

Completion of the refactor requires a coherent implemented tool surface, removal of replaced business-specific paths,
passing lifecycle/transport/representative multiplayer checks and an explicit capability matrix of remaining gaps. Tool
count or successful SDL delivery alone does not prove universal gameplay or mod coverage.
