# factorio-mcp generic interaction refactor

Status: agreed direction and implementation plan; implementation has not started under this plan.

## Objective and scope

Replace the gameplay-specific tool catalog with a small set of observation and interaction tools. The agent interprets
the game, chooses actions and verifies outcomes. MCP exposes UI structure, game data, effective input bindings and
finite input execution. It understands engine/API types and lifetimes, but does not encode recipes, machine workflows,
mod
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

The first version implements content-independent tools only. Gameplay-specific tools are deferred. Existing source,
adapters and tests may be replaced or removed outright; preserving the old catalog or demonstrating equivalent gameplay
coverage is not a migration prerequisite. Reuse infrastructure only where it satisfies this contract. Updating this plan
does not itself delete or rebuild the implementation.

## Research authority and evidence boundaries

The [architecture research](../../interaction-architecture-research.md) is the primary reference. Its
[current input contract](../../interaction-architecture-research.md#current-input-contract-bindings-and-finite-sequences)
supersedes historical proposals to activate `mine`, `build` or other named controls independently of physical bindings.
Preserve earlier evidence without implementing rejected matching, active-state or cursor-getter overrides.

The latest user decisions recorded here override older FIFO input admission, human-input isolation, elapsed execution
timeouts and migration-parity requirements in research or project guidance. They also replace the old lifecycle tool
contract: `status` only reports, `attach` identifies and instruments a process, and `detach` removes instrumentation.
World adapters activate automatically as the attached process changes state; entering another world does not require
another explicit attach. Physical binding discovery remains the chosen input model. Mechanism evidence does not
establish that the execution contract below has already been tested.

Automated acceptance will live in the project's Kotlin/Gradle test infrastructure, replacing the external Python test
entry points. Tests may launch Factorio through Steam and invoke the independently running MCP executable over HTTP;
neither tests nor exploratory curl calls require registering the executable in an agent's installed tool list.

| Capability             | Established path                                                                                                                                                                                                                                                                               | Boundary before production use                                                                                                                                             |
|------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| UI enumeration         | Linux captures actual GUI/root receivers at GUI logic calls and uses `Widget::callRecursively` with a compiler-created callback.                                                                                                                                                               | Complete foreground selection and safe-phase validation on Linux; eliminate dependence on constructor history and private object offsets.                                  |
| UI execution phase     | Windows now verifies root reacquisition and reads with two hooks: scoped `GlobalContext::updateGui` entry/return and `TopContainer::processTriggersToResize` entry. Earlier limited button actions used the scoped `Gui::logic` return.                                                        | Direct actions at the new boundary, Linux scheduling, worker exclusion, ABI and lifecycle remain acceptance gates.                                                         |
| Stateless UI selection | A unified document and structured path locate current controls; duplicate windows, reordering, deletion and recreation were exercised.                                                                                                                                                         | Integrate supported property readers and action families; never infer a property or action from a class name alone.                                                        |
| Binding discovery      | Linux late attachment enumerated registered controls, verified names by reverse lookup and read game binding text.                                                                                                                                                                             | Verify complete structured device/modifier extraction, custom localized names and changes to settings/prototypes.                                                          |
| Physical input         | Ordinary in-process SDL events exercised finite movement and mining with authoritative observations and matching full CRC checks. New Windows tests verify persistent held state and processed key/mouse release during single-player menu and Pause-key pause, before any resumed input tick. | Validate remaining controls, exact finite/chord timing, cancellation integration, exclusive admission and Linux/multiplayer pause. Human-input isolation is outside scope. |
| Screenshot fallback    | Existing `Screenshot.kt` uses `game.take_screenshot` and returns PNG through MCP. Research obtained GUI images without requesting focus.                                                                                                                                                       | Requires the game's graphical renderer. The API re-renders a scene and misrepresents the actual remote chart; it is not universally a capture of the presented frame.      |
| World observations     | Bundled API inspection and live tests establish tile/entity overlap and bounded projections; client camera transforms were sampled.                                                                                                                                                            | Server-side query evidence is not a certified client query adapter. Verify side effects, visibility and serialization on the injected client.                              |
| Lifecycle              | Windows single-player menu pause reaches `Map::stop`; a resident finite wait was aborted and reported without another input tick. Follow-up tests cleared D, Ctrl and mouse-left while menu/ Pause-key pause stopped input and scenario updates.                                               | Integrated task cancellation/detach, foreground-world filtering, unload, multiplayer pause and Linux integration remain acceptance gates.                                  |

Refer to [Linux traversal](../../interaction-architecture-research.md#linux-traversal-path),
[foreground acquisition](../../interaction-architecture-research.md#windows-foreground-root-acquisition),
[safe UI scheduling](../../interaction-architecture-research.md#why-this-is-a-safe-ui-phase-including-the-main-menu),
[late binding discovery](../../interaction-architecture-research.md#linux-discovery-after-startup),
[map observations](../../interaction-architecture-research.md#map-observation-spatial-selection-and-api-shaped-results)
and [remaining integration work](../../interaction-architecture-research.md#remaining-work-before-production-integration).

## Minimal resident entry points: Windows investigation, 2026-09-24

The resident is a library invoked at a few verified engine boundaries. IPC admission, queues, bounded task dispatch,
completion reporting and the finite input state machine live in that library. Woven game entry points only delegate
to it and preserve normal execution. An idle resident performs a bounded readiness/queue check; it does not replace
game logic, predicates, arguments or return values. This minimizes changes but does not establish zero overhead or risk.
There is no reason to weave each query, widget class, mod, or gameplay action separately.

The installed Steam executable is Factorio 2.0.77, build 84539, expansion, Windows x64. Its PE debug record and bundled
PDB were matched afresh before probing. Evidence and scripts are under
[temp/minimal-hooks-windows](../../temp/minimal-hooks-windows/findings.md). The tested mechanism is Frida's interceptor
plus a previously compiled MSVC callback helper; a production resident installer was not built or certified here.

### Dynamic discovery and installation

1. Read the selected process's actual loaded image and PE debug record. Match PDB GUID and age; resolve fully qualified
   symbols from that PDB, rejecting absent or ambiguous entries. Resolve current addresses from the loaded module base
   and freshly obtained RVAs. The research JSON address lists are evidence, never a product address database.
2. Resolve the few named engine roles below, together with required typed accessors. Use PE exception/unwind function
   ranges matched to those entries for caller scoping; the next public symbol is not a reliable function boundary.
   Disassemble actual implementations and call sites to verify optimized ABI, return storage and scheduling. A method
   in a PDB type record may be inlined and have no independently callable public entry. Fail unsupported capabilities
   explicitly rather than falling back to private offsets or byte signatures.
3. Load the library, initialize IPC and install hooks outside `DllMain`. Use a proven interceptor/relocator with a safe
   concurrent patch-installation protocol, instruction relocation, register/stack preservation and instruction-cache
   handling. Do not implement this as a guessed five-byte jump or suspend a thread and call game code on it. A custom
   installer needs its own native fixture and unwind/exception validation before game testing.
4. Capture actual receivers on the appropriate thread, and call verified engine accessors/traversal APIs. Use a real
   compiler-created `std::function` at the MSVC boundary; do not reconstruct closure or object layouts. Keep hook
   installation/discovery idempotent across MCP reconnects. Quiesce callbacks before detaching; preserve and restore
   original instructions. Do not unload code while a game-owned callback still references it.

Knowing a small set of engine function names and their verified roles is necessary adapter knowledge. It is distinct
from hardcoding game addresses, field offsets, per-version tables or instruction fingerprints. Windows evidence does
not automatically establish the Linux ABI or equivalent safe boundaries.

### Two hooks for the foreground UI boundary

1. At `GlobalContext::updateGui(bool)` entry, accept only calls whose return PC belongs to the dynamically resolved
   `MainLoop::prepare` function range. Establish a thread-local invocation scope and read the named static
   `agui::Gui::instance`. Retain nested invocation scopes correctly.
2. At `agui::TopContainer::processTriggersToResize()` entry, capture its actual receiver only within that scope and
   when its return PC belongs to `agui::Gui::logic(bool)`. This supplies the live root without reading `Gui` fields or
   depending on a constructor having run after attachment.
3. At the accepted `updateGui` return, recheck `Gui::instance` and `Widget::getGui(root)`. GUI logic has returned while
   the caller is still in `MainLoop::prepare`, before draw-data preparation and the subsequent worker phase. Traverse
   with `Widget::callRecursively`, then execute bounded eligible work. Discard invocation-local pointers afterward.

This uses two patched function entries, not three hooks for `prepare`, `Gui::logic` and the root receiver. Main-menu
background simulations also invoke GUI/world code, so globally capturing whichever GUI/root ran last is incorrect.
In the measured sessions this scoped boundary had no observed overlap with `gameUpdateLoop`. Static scheduling review
and that observation support the boundary; they are not a universal race proof for untested execution paths.

The same two-hook collector read the main menu with null game/player, a loaded world with two custom windows, and the
single-player paused menu. A loading-progress UI was also observed. The minimized test client remained readable.
Direct button actions at this newly consolidated boundary were not successfully retested: the setup click did not
produce the event needed by that experimental action adapter. Earlier scoped-`Gui::logic` action evidence remains
separate. Joining, disconnect/error dialogs and world replacement still require focused coverage of this exact path.

Do not drain an indefinitely replenished queue until empty. Process a bounded admitted batch/work budget, then return
to Factorio. Revalidate the current document after every mutation; a previous action may replace the GUI or destroy
the next target. No task retains widget pointers for a later phase. UI reads and actions use the same safe dispatcher.

### Input ticks, event processing and world reads are different phases

`MainLoop::processEvents(bool)` and `InputState::postProcess()` run on the frontend thread in this binary.
`PlayerInputSource::sendStateChanges()` runs on the game-update worker, before `Scenario::updateStep()`. There can be
many frontend iterations between input ticks. `MainLoop::gameUpdateStep` still runs during menu pause and can return
false; counting its invocations would incorrectly advance a finite sequence while paused.

A resident empty-control sequence of five input ticks followed by two input ticks completed on seven consecutive
`sendStateChanges` callbacks. This validates the candidate clock for that wait, not real button holds or release
timing. Enqueueing an SDL event at the worker callback does not prove the frontend processed it for that same tick.
The input adapter must coordinate a verified event-processing boundary and consumption clock, with explicit thread
ownership. Do not pretend one arbitrary Lua `on_tick` callback provides both responsibilities.

`InputEventSender::sendEvent` is not just a raw-event queue: inspected code updates input state, dispatches through an
input source, sends state changes and runs GUI logic. Calling it from an arbitrary hook may re-enter those systems.
Preserve the existing ordinary event path until exact phase and release behavior is verified. World Lua observations
also need a verified current-VM idle phase; these UI and wait experiments do not certify arbitrary Lua calls from
`updateGui` or `sendStateChanges`. Use a separate world dispatch boundary only where required by ownership/safety.

### Lifecycle notifications: native signals where sufficient, narrow hooks elsewhere

There is no verified process-wide public callback covering every pause, world replacement and exit. The bundled
2.0.77 runtime API does not provide one. Native `EntityUpdatePausedState::onPausedChanged` and
`GameSpeed::onSpeedChanged` signals exist, and `Signal<>::connect`/`operator()` have callable entries. The inspected
`LuaGameScript::luaWriteTickPaused` emits the former. This entity-update pause state must not be conflated with every
form of simulation stopping. No generic `Signal<>` emission was observed during the tested single-player menu pause.

Prefer native subscription only after obtaining the actual owning signal through a verified accessor/receiver and
verifying the `SignalConnection` ABI and lifetime. A PDB member offset is not an accepted way to discover the object.
An emission hook alone cannot initialize a subscriber attaching while already paused. Keep callbacks small: invalidate
resident work and enqueue a nonblocking notification; defer game calls and subscription teardown to a suitable phase.

| Boundary                                                          | Purpose and present evidence                                                                                          | Required constraint                                                                                                                                                                                                                         |
|-------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Map::stop(bool)` return                                          | Verified single-player menu stop; aborts a resident wait without another input tick.                                  | Match the bound foreground Map. Verified ABI is Map receiver in RCX, hidden `StopLock` return storage in RDX, bool in R8. Observe the original call; do not call it to manufacture pause.                                                   |
| `Map::resume()`                                                   | Stop-lock release path found in code and observed on menu resume.                                                     | Stops can nest. One resume does not establish running state, and a late-attached observer cannot reconstruct an initial stop count from events alone. Reconfirm input readiness; never resume an aborted task.                              |
| `Scenario::~Scenario()` entry                                     | Static ownership path for invalidating the world before child Game/Map teardown.                                      | Capture and match the bound foreground scenario; menu-background scenarios must not abort unrelated work. Runtime unload validation remains required.                                                                                       |
| `LuaState::~LuaState()` entry                                     | Static owning-VM teardown boundary; `LuaState::operator lua_State*()` obtains its current VM through a game accessor. | Compare the bound VM and invalidate before destruction. This destructor directly reaches internal close code, so hooking only public `lua_close` is insufficient. Runtime coverage, including other VM replacement paths, remains required. |
| `AppManager::changeStateInternal()` / `AppManager::process()`     | Observed frontend transitions and a continuing process-level pump.                                                    | Optional reconciliation/fallback boundaries, not automatic reasons to abort. Opening a menu in multiplayer can leave the world running.                                                                                                     |
| `ClientMultiplayerManager::receiveBeginPause` / `receiveEndPause` | Resolved native handlers; static candidates for multiplayer pause coverage.                                           | Verify actual application rather than merely receipt, common lower-level paths, and local-server behavior before adding hooks.                                                                                                              |

Do not install every diagnostic hook from the experiment. Keep a lifecycle hook only where a verified native signal or
already-required boundary cannot supply the notification. `Map::~Map` was a diagnostic candidate; do not retain it
alongside a sufficient scenario/VM ownership boundary without a demonstrated coverage need. Thus two UI entries are
concretely verified, but a globally minimal fixed hook count for all lifecycle/input modes is not yet established.

At stop/unload, record a cooperative cancellation request for matching tasks and prevent new steps from being admitted.
At its next eligible safe checkpoint, each task follows its own abort/cleanup path and reports the partial-progress
failure. A lifecycle callback must not forcibly unwind another thread or run a task's game accesses on the wrong thread.
Provide a frontend/teardown checkpoint for prompt pause notification and cleanup before an owner disappears; do not
require simulation to resume just to acknowledge cancellation. At the first resumed input evaluation, a cancelled
sequence must contribute no held controls. Whether this requires an explicit release depends on the verified input
adapter. Keep replacement/admission blocked until its cleanup contract is satisfied. Never call a destroyed VM or replay
a task after resume. Use explicit lifetime generations, not pointer equality alone across allocator reuse.

The original measured pause test aborted after five ticks, from the frontend thread, while the input-tick counter
remained at

25. It sent a host notification immediately from the stop callback. The task held no key, so this does not yet prove
    paused key-release correctness by itself. The follow-up paused-input tests below supply separate held-state release
    evidence. A stopped or crashed process may never execute another callback; OS process-death
    observation must fail pending MCP requests independently. A live frozen process cannot promise immediate
    notification.

### Follow-up: retained input state and release while paused

The [paused-input investigation](../../temp/paused-input-windows/findings.md) freshly resolved the same installed image
and tested its ordinary in-process SDL event path. Input is not a choice between events and ticks: frontend events
update retained `InputState`, and later input evaluation reads that state. One key-down remained active for six sampled
input ticks without another down event. `InputState::update` clears retained key/button state on the matching up event;
merely ceasing to send more down events does not release this path.

The verified path is queued SDL event -> ordinary event conversion -> `MainLoop::processEvents` ->
`InputState::update` -> normal event routing and post-processing. The frontend pump continues during the tested
single-player menu pause and Pause-key pause. In both tests, D release cleared `move-right`, and Ctrl/mouse-left release
cleared their game getters while input-evaluation and scenario-update counters remained unchanged. The first six
resumed input evaluations, including the very first one, observed all tested controls up. No input reset was used.

For this adapter, the input task's cooperative cleanup submits matching release events at the frontend input boundary
and verifies their processing before declaring cleanup complete. It does not wait for gameplay to resume or synthesize
a simulation tick. No new per-key hook is needed beyond the planned frontend dispatcher. `SDL_PushEvent` success only
means enqueueing; do not acknowledge release from that return value alone. SDL's
[documentation](https://wiki.libsdl.org/SDL2/SDL_PushEvent) also distinguishes queued events from SDL's own device-state
array; these tests observed Factorio's state after engine processing, not that device-state array.

This resolves the previous uncertainty for the tested Windows keyboard/mouse path. It is not full finite-sequence,
detach, Linux or multiplayer certification. World/VM destruction still requires lifetime-safe cleanup. "No delayed
cleanup" here means no delay until simulation resumes; cooperative cleanup still waits for its safe frontend boundary
and processed release events, rather than interrupting the game at an arbitrary instruction.

### Verification status and remaining gates

Passed: fresh binary/PDB identity; isolated interceptor integer/floating return preservation over 1,000 iterations and
instruction restoration; compiled callback traversal fixture; repeated real-game attach/detach with restoration;
two-hook reads in main menu/gameplay/pause; consecutive finite wait steps; immediate single-player pause abort;
processed D/Ctrl/mouse-left release during both tested pause forms and clean first resumed input evaluation.
The probes did not build the production project or run a multiplayer/CRC acceptance test. Extra diagnostic hooks,
research-only window messages and startup focus guards are not product requirements or validated input adapters.

Before advertising the whole mechanism, verify exact sequence press/hold/release timing; integrated cancellation,
replacement and detach using the verified paused-release path; foreground-world/VM lifetime filtering; late attachment
while already stopped; world replacement;
multiplayer pause/disconnect with a local server and full CRC; direct actions at the consolidated UI boundary; and Linux
equivalents. Missing callable state getters or coverage cannot be papered over with guessed offsets or execution
timeouts. This is a concrete reduced entry-point design with explicit remaining validation, not a completed injector.

## Agent-facing tool surface

Names below define the planned responsibilities; finalize schemas before registration. Avoid duplicate aliases and
per-machine or per-mod tools.

| Tool             | Responsibility                                                                                                                                                                                                                                          | Readiness                                                                                                                                                                           |
|------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `status`         | No arguments. Report MCP attachment state, selected PID/process identity when applicable, observed game state and current capabilities. Never select, inject or install hooks.                                                                          | Always callable, including before attach and after detach.                                                                                                                          |
| `attach`         | Require exactly one of `pid` or `process_name`. Identify Factorio and reject initial startup loading; load/reuse a compatible resident, install fixed instrumentation and activate applicable dynamic adapters on a best-effort basis.                  | No prior status call or active world required. A supported initialized frontend, including the main menu, is eligible.                                                              |
| `detach`         | No arguments. Stop admission and abort all outstanding functional tools through the shared lifecycle-abort path, then finish cleanup, remove hooks/subscriptions and clear attachment. Unload the library if safely possible; otherwise leave it inert. | Repeatable in every attached game state, including pause and world transitions; already detached is a successful no-op.                                                             |
| `ui_read`        | Read the whole current document or matching subtrees through one selector format.                                                                                                                                                                       | Current process-level UI phase; no world Lua requirement.                                                                                                                           |
| `ui_action`      | Resolve one current widget, validate eligibility, then perform a supported widget-family operation.                                                                                                                                                     | Current UI phase and verified action adapter.                                                                                                                                       |
| `input_bindings` | Read registered control names and their current effective bindings, including mod controls.                                                                                                                                                             | Current registry; expose availability independently of world Lua.                                                                                                                   |
| `input`          | Execute one complete finite sequence; reject concurrent input unless `stop_previous` explicitly requests cancellation/replacement. An empty replacement is stop-only.                                                                                   | Verified input source and duration clock. New execution requires normally running gameplay; stop-only also works during lifecycle cleanup. Do not reinterpret ticks as menu frames. |
| `screenshot`     | Optional image observation when structured data is insufficient or the agent needs to understand the current presentation. Include capture source, scope and fidelity limitations.                                                                      | Verified game-rendering path, without desktop capture or focus manipulation. Existing API path requires a world; menu/paused capture needs separate validation.                     |
| `world_overview` | Describe the current viewport or requested bounded area at a specified observation scale.                                                                                                                                                               | Attached process, automatically established current world binding and valid observation context.                                                                                    |
| `world_query`    | Select tiles/entities or supported related objects and project requested API-shaped details.                                                                                                                                                            | Attached process, automatically established current world binding and valid observation context; main menu returns an MCP tool error.                                               |

Make local-player identity implicit. Shared observations must include enough context to distinguish active controller,
surface/view and physical character. Do not require an agent to choose among connected players for ordinary tools.

Every functional tool requires a successful process attachment plus its own current-state and adapter readiness.
The table groups UI reads/actions, input bindings, finite input, world reads at useful granularities and auxiliary
observations such as screenshots. Detailed names and schemas within these groups remain refinable; the previous
catalog is not a compatibility requirement.

Provide bounded API-shaped prototype/catalog reads through the shared read interface where they fit coherently; use a
distinct generic read tool only if its selection or lifetime contract warrants it. Keep prototype data, force
availability
and runtime instances distinct. This does not require preserving recipe-specific tools, aliases or caches.

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

### 1. Process attachment, weaving lifecycle and readiness

Read developer debug information from the actual target image before injection. Resolve required entry points and verify
their ABI without address tables, private field offsets, instruction fingerprints or version allowlists. Retain the
existing minimal resident and IPC design where its safety and lifecycle contracts hold.

#### Status and MCP-owned attachment state

`status` accepts no arguments and has no attachment side effects. MCP maintains a shared attachment record across its
stdio/HTTP sessions: attachment phase, target process identity, resident compatibility and observed readiness. A PID
alone is insufficient identity because it can be reused. Before any attach, and after a successful detach, report
`attached: false`; do not select a process or discover/reconnect to a resident implicitly. A fresh MCP instance starts
detached. An explicit attach can subsequently discover and reuse a compatible resident left by an earlier instance.

When attached, report at least PID, process name, attachment phase, game state and tool capability readiness/reasons.
Separate attachment from game state: main menu, loading a world, joining, gameplay, paused and unavailable/unknown are
observations of the target, not attachment phases. Do not infer paused simulation just from an open menu. A process can
stay attached while worlds change. If a live process cannot report fresh state, expose unknown/stale observation rather
than inventing a state; if it exits, invalidate the attachment and fail pending work using OS process observation.

Library presence is a separate diagnostic from active attachment. After successful detach, an inert DLL remaining
mapped must not make `status` report attached or injected. After a failed or incomplete detach, report the actual
cleanup state and remaining instrumentation instead of falsely reporting successful removal.

#### Attach and best-effort activation

`attach` accepts exactly one selector: `pid` or `process_name`. Reject missing/conflicting selectors, no match and
ambiguous process-name matches with actionable errors; never pick an arbitrary matching process. Identify the actual
executable and debug information before modification. An unrecognized target or the initial game-startup loading phase
returns an MCP tool error without committing attachment. Distinguish that initial phase from later world-loading UI
after the frontend is initialized. The reliable initial-readiness recognition path remains an implementation gate;
a readable loading widget or a discovered executable name alone does not establish readiness.

No preceding `status` call and no loaded world are required. Repeated attach to the same process reuses compatible
active instrumentation and reconciles missing applicable capabilities without duplicate hooks or resetting admitted
work. Reattaching to an inert retained library reinitializes its attachment resources. Reject incompatible residents.
Keep one selected target per MCP server in the first version; attaching a different process while attached returns an
error directing the caller to detach first, rather than silently replacing a live target.

Classify instrumentation by lifetime:

- **Fixed instrumentation:** installed once initial game loading has completed, and retained until explicit detach
  or process exit. It supplies the process/frontend dispatcher, lifetime observation and safe access to currently
  available UI. It must continue working without a foreground world or simulation ticks.
- **Dynamic instrumentation:** adapters, callbacks, subscriptions and any genuinely state-dependent hooks activated
  when their owner/context becomes available. Bind them on entry to that state; invalidate and safely remove their
  world-bound resources on exit. Establish them automatically for a newly loaded world without another attach call.

An executable function address often remains valid across worlds. A fixed hook can therefore dispatch only when a
current dynamic binding exists; do not physically repatch it on every pause or menu transition just to model readiness.
Conversely, subscriptions and callbacks owned by a world/VM must follow that owner's lifetime. The fixed/dynamic split
is a lifetime contract, not a rule that every unavailable tool must have its machine-code detour physically absent.
Finalize classification of the researched candidate entries after validating their ownership and safe installation
points. The current experiments do not certify this complete attach/detach manager.

Best effort means retaining working independent capabilities while reporting others as unavailable with a reason.
Absence of a world at the main menu is expected, not attach failure. Missing optional adapters must not disable a
working UI. However, identity, ABI, safe dispatch and the lifecycle/cleanup dependencies required for an enabled
capability are mandatory: never enable a capability partially if that could leave dangling callbacks or held input.
If the resident cannot establish a safe minimum attachment, roll back that attempt and return an error, reporting any
incomplete cleanup honestly. Return a capability summary instead of waiting for the user to load a world.

State-entry processing should establish applicable dynamic bindings proactively, not depend on a world tool call.
If bootstrap needs the next safe callback, report that capability as initializing until it finishes. At most one
activation per context generation may be in flight. A failed adapter can be retried on an appropriate new lifecycle
transition or explicit repeated attach; do not reinstall it continuously from every status call or frame.

#### Functional-tool admission

First check the MCP attachment record, then the observed state and required capability. A detached call returns an
MCP tool error immediately. `world_query` in the main menu returns a world-unavailable error; new tick-based input
while paused returns an input-state error. A suitable game state with an adapter still initializing or unsupported
returns an adapter-readiness error rather than pretending the game state itself is wrong. UI can remain usable when
world tools are unavailable. Return errors through the SDK's normal tool-error path, with current state and a useful
reason, rather than presenting failure as a successful empty observation.

MCP's state precheck is an optimization, not authorization to dereference old objects. The resident must revalidate
attachment state, current context generation and adapter readiness when executing at the safe phase. State can change
between the check and dispatch. Reject stale queued work; never carry it into a replacement world. A functional tool
does not implicitly attach, switch processes or wait for an incompatible game state to become suitable.

#### Detach and repeated attachment

All tool cancellation is cooperative. A cancellation source records a request and reason; the tool's own logic observes
it at a safe checkpoint, stops producing further work, cleans up resources it owns and completes its terminal result.
The common lifecycle machinery signals, schedules eligible continuations and tracks completion; it does not duplicate
tool-specific cleanup or forcibly interrupt game calls. This is the same ownership model as a coroutine's
cleanup/finally
path, whether implemented as a coroutine or a small resident state machine.

Explicit `detach` withdraws instrumentation. It is not transport disconnection and no longer means leaving admitted
work running. Reuse the same task-abort, cleanup and terminal-result mechanism used when game-state changes invalidate
an operation. Detach supplies the reason `detached` and targets all outstanding functional calls for this attachment,
across sessions: queued reads/actions, admitted input, pending screenshot work and other unfinished tool work. A game
state transition instead targets the operations whose preconditions it invalidates. Do not implement a separate input
stop/release/result path inside detach. The coordinating detach call and observational status are not cancellation
targets; concurrent attach/detach calls are serialized by the lifecycle manager.

The shared path must be idempotent and arbitrate races with normal completion, protocol cancellation and other abort
causes. Each task has one terminal outcome and cleanup runs once. Preserve any outcome already committed; do not report
an already completed action as retroactively undone. When detach wins, the original tool call receives an aborted
result with its reason and available progress through the normal IPC/request-completion path. Do not merely cancel
its SDK handler coroutine: that can discard the response the agent needs. If the agent already cancelled the protocol
request or its transport is gone, still perform cleanup without requiring delivery of a terminal result.

Serialize attach/detach transitions across sessions and perform cleanup in this order:

1. Mark detaching and stop new functional-task admission in MCP and the resident. Request shared abort for every
   outstanding functional task, including tasks not yet admitted to the game. Cancel queued work before execution;
   an already executing single-phase call must reach the end of its bounded critical section before teardown. Do not
   interrupt an arbitrary game call or imply that completed effects can be rolled back.
2. Let each task reach its cancellation checkpoint and run its own existing cleanup logic, including any input-state
   cleanup required by the verified adapter at a safe phase while paused.
   Complete original pending calls with their abort descriptions and wait for cleanup to quiesce before removing its
   dispatchers or IPC resources. Disconnect dynamic subscriptions and remove callbacks before their owners or resident
   code can disappear. No cleanup may require another gameplay tick. Keep cleanup resident-owned if the detach
   requester is cancelled; notification delivery must never block the game or be required for cleanup to finish.
3. Quiesce concurrent hook callbacks and in-flight return trampolines, then remove dynamic and fixed hooks safely and
   restore original instructions. Do not remove the only cleanup dispatcher before cleanup completes. Detachment must
   also synchronize with a concurrent world transition or activation callback so it cannot reinstall a hook afterward.
4. Unload the injected library only if no thread, callback, trampoline or game object can reach it. Otherwise leave
   an inert reusable module with no active game hooks, subscriptions or task execution. Library-unload failure alone
   does not make an otherwise complete detach fail.
5. After acknowledged cleanup, clear active attachment and return success. Repeated detach while already detached is
   a no-op. If the target exits during cleanup, discard process resources and report it gone; no code restoration in
   that dead process is required. A live target whose hooks could not be removed is a cleanup error, not a successful
   detach. Do not promise immediate cleanup in a frozen process or impose a hidden execution timeout.

Reattach rediscovers the current state and creates fresh dynamic bindings, without reviving old tasks or handles.
Validate attach/detach in the main menu, gameplay, pause, world loading/joining after frontend initialization and world
exit, including repeated cycles, active input and retained-library reuse. Existing probe restoration tests are useful
mechanism evidence but do not establish this full lifecycle contract. An MCP crash or transport disconnect does not
implicitly detach: fully admitted finite work retains the previously agreed autonomous completion/lifecycle behavior.

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

#### Schema and tick execution

Use `operations`, `controls`, `ticks` and `stop_previous`. Each operation contains a combination of device controls and
a positive integer `ticks` (default 1). Normalize the illustrative spelling to `device`, not `devide`. Keyboard controls
use the key vocabulary shared with `input_bindings`; mouse controls specify supported buttons and an optional position.
The agent chooses physical combinations from discovered bindings. MCP does not infer gameplay intent from them.

The user's minimal example becomes the following tool arguments (hold W for two input ticks, then release):

```json
{
   "operations": [
      {
         "controls": [
            {
               "device": "keyboard",
               "key": "W"
            }
         ],
         "ticks": 2
      }
   ]
}
```

`stop_previous` defaults to `false`. After discovering the effective bindings, a keyboard/mouse combination followed by
another step can be expressed as:

```json
{
   "stop_previous": false,
   "operations": [
      {
         "controls": [
            {
               "device": "keyboard",
               "key": "W"
            },
            {
               "device": "mouse",
               "button": "left",
               "position": {
                  "space": "viewport",
                  "x": 640,
                  "y": 360
               }
            }
         ],
         "ticks": 5
      },
      {
         "controls": [
            {
               "device": "keyboard",
               "key": "D"
            }
         ],
         "ticks": 2
      }
   ]
}
```

This is the proposed schema, not a claim that these mouse fields or adapters are already implemented. Viewport positions
refer to client-content pixels with an explicit origin and scale; establish the precise conversion through experiments.
Do not expose OS desktop coordinates. Resolve each position in the current view when its step starts; do not pin a
controller or old camera transform across steps. A mouse position change can jump without moving the user's OS cursor.
Reject conflicting positions within one combination. Direct UI tools continue to locate targets through selectors.

At a verified input-processing boundary, position the game cursor if requested, press the combination together, hold it
for the specified number of distinct input ticks, and release it. Individual event delivery may be ordered internally,
but the combination must be established before the tick's input evaluation. In this example the first combination covers
ticks 1–5; its release and the next combination's press occur before tick 6 evaluation. Do not insert an idle tick or
round-trip through MCP. The second combination covers ticks 6–7 and is released before subsequent input evaluation.
Release and re-press a repeated control at a step boundary; do not silently merge adjacent steps. Validate how the
game's
event pump observes these edges. An empty `controls` set waits for its ticks.

Before producing input for each tick or advancing a step, check the task's cooperative cancellation state. Once
cancellation is observed, take the task's cleanup path and do not produce another pressed/held contribution. In
particular, after pause and resume the next input tick must not receive a held signal from the cancelled sequence.
The Windows event path is now verified to retain button-down state. Its task cleanup must send matching up events for
its submitted controls and establish that the ordinary frontend path processed them. This works while the tested
single-player pauses stop simulation, so cleanup need not wait for another game tick. Do not send repeated down events
each tick merely to represent a hold, or assume silence means release. Preserve distinct press/release edges at sequence
boundaries. Verify equivalent behavior on each supported adapter, the first resumed evaluation and detach while held;
cleanup must not depend on a future callback that detach has already removed.

Use the installed game's in-process SDL event path and normal event processing. Preserve input matching, contextual
consumption, internal held state and normal action submission. Do not translate `mine` into a private handler or
MiningState mutation. Do not synthesize a new key-down every tick to approximate a hold. Count distinct validated client
input ticks, not callback invocation count, wall time or assumed server movement ticks.

MCP submits the entire sequence, including waits and all later steps, as one finite resident task. After admission, the
game owns its progress, transitions, release and terminal IPC notification. Neither step timing nor final release may
depend on another MCP message, a live tool coroutine or an external orchestration queue. The resident is a small fixed
sequence executor, not a gameplay planner. Bound payload size, step count and numeric representation; these resource
limits are not elapsed-time execution deadlines.

Validate game cursor updates through the normal event/callable input path so cached cursor position, selection and GUI
hover remain consistent. Do not override getters. Wheel, analog motion and text are not automatically holdable buttons;
verify and specify their event semantics separately before advertising them. Finite combinations do not automatically
provide continuous drag across steps because every step releases its controls; validate required drag behavior
explicitly.

#### Exclusive admission and replacement

There is at most one admitted input sequence per target client, across all MCP sessions, transports and reconnects.
There is no backlog of ordinary input calls. Check and claim admission atomically in the resident; a local MCP check
alone cannot enforce this across reconnects. A bounded IPC command inbox is distinct from an input execution queue.

| Incoming request                         | Behavior                                                                                                                                         |
|------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| Ordinary input while idle                | Validate readiness and admit the whole sequence. An empty sequence returns immediately.                                                          |
| Ordinary input while active or releasing | Return `input_busy` immediately; do not wait or enqueue, including an empty ordinary call.                                                       |
| `stop_previous: true` with steps         | Validate the replacement first, cancel the prior task, finish its release, recheck readiness, then admit the replacement.                        |
| `stop_previous: true` with no steps      | Stop-only: cancel the active task and return after release; succeed immediately if idle. No active world is required for this control operation. |

An invalid replacement must not cancel valid existing work. Lifecycle changes during replacement can still prevent the
new task from starting after the old task has stopped. Serialize admission/cancellation transitions without accepting a
queue of replacement sequences; concurrent replacement attempts can return busy while a replacement is being settled.
Cancellation of the replacement request prevents its not-yet-started steps from being admitted. Report separate prior
cleanup and replacement-admission status when this distinction matters.

Use task IDs for request-scoped cancellation. A late cancellation for the previous task must not stop its replacement.
Release completion, rather than receipt of a cancel command, makes the input slot available. Retain enough minimal
resident state to finish release even if the requester has gone away. Stop/control messages must not be trapped behind
ordinary inbox capacity or a lock held for the active task's entire duration.

Reads, UI actions and cancellation remain serviceable during input. Serialize only the actual engine accesses at their
safe boundaries; do not hold a global mutation mutex while awaiting the entire sequence. Direct UI actions do not
implicitly cancel input, although their resulting lifecycle changes may do so. Verify that SDK request dispatch permits
a second input call to reach the busy/replacement logic while the first tool call is suspended.

Exclusivity concerns MCP input calls only. Human/MCP source arbitration, suppressing physical input and preserving an
independent human-held copy of a shared key are outside scope. Mixed input can change gameplay or interfere with the
sequence's effects. Still track and release the resident's submitted controls on every terminal path; do not claim that
the sources have independent held-key state or change input predicates to simulate isolation.

#### Lifecycle aborts and terminal results

New tick-based input requires normally running gameplay. Any actual transition away from that state terminates the
task cooperatively: simulation pause, world unload/replacement, leaving gameplay, loss of the required player/input
context or process
exit. Pausing is failure, not suspension; never resume the remainder when the game unpauses or another world loads.
Do not infer pause just because a menu, research window or remote view is open. View/controller/UI changes alone do not
abort a sequence if normal input ticks and the required execution context remain available. Whether input has an effect
in the new context is the game's decision.

Tick execution and lifecycle servicing need complementary safe hooks. Observe pause/unload/exit transitions at verified
process/frontend or lifecycle boundaries and request cancellation there. The task's own cancellation continuation runs
at an eligible safe phase, stops further input contributions, performs its required cleanup and notifies MCP without
depending on another simulation tick. Do not resume the normal gameplay body merely to run cleanup. Never call old
world objects after unload. If adapter cleanup must finish at a subsequent safe frontend boundary, keep the slot
unavailable and report cleanup as pending rather than claiming it already happened. Validate this path on Linux; its
existence and correct ordering are implementation gates, not assumptions about `on_tick`.

"Immediately" means the first safe boundary observing the transition, without a polling timeout or another simulation
tick. It cannot promise execution inside a frozen process. Normal IPC stays nonblocking. A lifecycle failure can be
reported as soon as known, including cleanup state; successful input and successful stop-only completion require final
release to have been processed. Process death is detected externally and makes further release in that process moot.

Detach invokes this same input abort/cleanup/completion path with reason `detached`; it does not create another input
executor or release implementation. The path is shared with cancellation and invalidating game-state changes, while
preserving their distinct reasons and protocol response behavior. All other tools use the same task-lifecycle mechanism
with their own necessary cleanup and available progress fields; they do not need an input-style tick scheduler.

Terminal results distinguish completed execution, explicit cancellation, explicit detach, lifecycle failure and adapter
failure. Include the reason, number of fully completed steps, the active zero-based step if any, known consumed ticks
within it, and
release state (`released`, `pending`, `unknown` or `process_exited`). Do not invent exact progress when delivery or
processing is uncertain. Completion means processed finite input and final release, not a successful game action. A
game-rule no-op is not an error. Do not poll for a desired gameplay effect or replay uncertain work. Already-produced
effects cannot be rolled back.

#### No execution deadlines and cooperative cancellation

All tools have no MCP-imposed elapsed execution timeout. Remove inherited tool, IPC-result and resident-task deadlines
that abort otherwise valid work solely because time elapsed. Known invalid states, resource-limit violations and
process/lifecycle failures still return errors. A pending request can remain pending if a live target never progresses
and no cancellation or failure is observed; the absence of timeouts cannot also guarantee eventual completion.

When an SDK tool-handler coroutine is cancelled, propagate cancellation to the matching resident task. Cancellation
before admission prevents admission; cancellation racing admission must resolve against the same task ID. Keep a
nonblocking cancellation path independent of the cancelled coroutine, and do not spend an unbounded `NonCancellable`
section awaiting the target. In-game cancellation is cooperative at a safe phase, with the tool-owned cancellation and
cleanup path above covering pause and teardown. An already-running single-phase UI action cannot be undone, but
cancellation prevents a
not-yet-executed action where possible.

For the first version, assume the agent successfully sends an explicit MCP cancellation notification when it abandons
a call. Handling that notification through the tool coroutine, resident cancellation and release is required. Automatic
HTTP-disconnect cancellation is desirable but may be deferred; it is not a first-version acceptance blocker. If
deferred,
a disconnected request without an explicit cancellation signal may continue its already-admitted finite input sequence.
Do not add a server execution timeout as a substitute.

For optional disconnect handling, use Ktor's `HttpRequestLifecycle` with `cancelCallOnClose = true` on the relevant
route. The
[plugin documentation](https://ktor.io/docs/server-http-request-lifecycle.html) specifies cancellation of the HTTP call
and its child coroutines on supported engines. The project already uses CIO; the plugin is present in the locally
cached Ktor `3.5.1` source. A client-side timeout with neither a disconnect nor a cancellation notification remains
unobservable to the server.

Do not equate the Ktor route handler with the SDK tool handler. Inspection of the cached Kotlin MCP SDK `0.15.0`
`Protocol.kt` shows `dispatchRequest` launching work in a connection-owned `handlerScope`, created with `SupervisorJob`,
rather than as a child of the HTTP call. `HttpRequestLifecycle.kt` cancels `call.coroutineContext`. Therefore installing
the plugin alone does not establish cancellation propagation into the tool. Explicit protocol cancellation already
targets the handler job by request ID. Connect premature HTTP request closure to that same request-scoped cancellation
path through supported SDK integration, or a narrowly scoped transport adapter if needed; do not close the entire shared
session to stop one request. Verify this against the actual SDK code and on Linux/Native before claiming it works. Defer
this adapter if it complicates the first version; the explicit cancellation path is sufficient for that version.

If implementing automatic disconnect cancellation, keep its behavior explicit when negotiating protocol versions:

- The current HTTP acceptance client uses `2025-11-25`. Its
  [transport specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) recommends that a
  disconnected stream not imply cancellation. Disconnect-as-cancel is an intentional product policy here, not behavior
  to assume from that protocol or SDK. Do not advertise resumable continuation for requests cancelled on disconnect.
- The
  [2026-07-28 Streamable HTTP contract](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2026-07-28/basic/transports/streamable-http.mdx)
  defines request SSE response closure as cancellation. A protocol/SDK upgrade may align the behavior, but must be an
  explicit compatibility change, not an assumed consequence of enabling the Ktor plugin.
- Correlate only the disconnected in-flight request with its tool task. Normal response completion, an idle keep-alive
  socket closing, an unrelated GET notification stream, or another request's connection must not cancel active input.
  Do not apply one session's cancellation to another session's task. Test SSE and JSON response modes if both are
  exposed.
- Verify explicit stdio/HTTP cancellation, real HTTP disconnect, HTTP session closure and server shutdown separately.
  A cancelled protocol request normally receives no tool response; release still runs and late IPC replies may be
  discarded. Catch cancellation to initiate cleanup, then preserve coroutine cancellation rather than converting it
  into an ordinary successful tool result.

An abrupt MCP crash cannot send cancellation. Fully admitted finite work remains autonomous until completion or a game
lifecycle abort; reconnect must not replay or reset it. An orderly shutdown should propagate cancellation for live tool
handlers before closing IPC. Explicit `detach` follows the instrumentation-removal procedure above, including
terminating
all outstanding functional calls through their shared abort path and releasing input before removing execution hooks;
it does not require a separate `stop_previous` call. Original live tool requests receive their abort results before
request correlation is discarded, unless the requester has already cancelled or disconnected.
Closing a transport or deleting an HTTP session is not an implicit call to detach. A new MCP instance must explicitly
attach before using an existing compatible resident; reuse must not replay or reset that resident's admitted work.

Only `input` needs a gameplay-tick sequence executor in the first version. Other ordinary tools perform their game
access in one eligible phase; do not invent gameplay completion observers for them. Waiting for that phase or IPC does
not make the operation a multi-step input task. Screenshot rendering/encoding can be asynchronous and must have its own
renderer/lifecycle completion path rather than falsely promising an image in the submission tick.

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

- Keep typed schemas, tool registration, selector semantics, validation, request correlation and result
  formatting in `commonMain`. Selector evaluation that must be atomic with native UI dispatch runs in the resident
  against its current traversal; do not add an IPC round trip between resolving and invoking a widget.
- Keep process discovery, debug parsing, injection, IPC and native ABI adapters in the appropriate platform source set.
  Use a thin native layer for unavoidable engine interfaces. Use portable kotlinx libraries rather than extra
  expect/actual wrappers. Retain CMake/Gradle ownership and default KMP layout.
- Keep the server-wide selected target, attachment phase and state/capability reporting in MCP/commonMain. The resident
  owns authoritative game-phase validation, fixed hook lifetime, dynamic binding generations and autonomous cleanup.
  Status is observational; only explicit attach establishes a target, and explicit detach removes instrumentation.
- Share task abort, cleanup and terminal-result handling across invalidating state changes, explicit cancellation and
  detach. Detach aborts all outstanding functional work; state changes select affected work. Keep reasons and per-tool
  progress distinct without duplicating input release or completion handling. Every tool observes cancellation
  cooperatively and owns its resource cleanup; the common dispatcher must not forcibly terminate its execution.
  Preserve original tool replies on detach.
- Keep world-local query state scoped to the current Lua VM. UI and input safety cannot depend solely on a world Lua
  callback because menus, pause and teardown must remain serviceable. The resident owns the one admitted input sequence
  and its tick transitions. Do not add a second scheduler in MCP or gameplay planning in Lua/C++.
- Extend/version the resident protocol with typed capabilities and operations. IPC remains nonblocking; IDs are unique
  across MCP restarts, unknown replies are discarded, and the game retains no undelivered result history.
- Audit `McpServer.kt` registration and readiness/locking, `ResidentClient.kt` correlation, `ResidentScheduler.kt` and
  the Linux resident/bridge before reuse. Their world-wide guard and whole-operation mutation lock do not satisfy this
  contract. `ResidentClient.request` currently uses `withTimeout` and removes request correlation on cancellation
  without
  cancelling the game task; replace both behaviors. Audit wire/native deadlines too. Keep standard SDK transports and
  signal-safe shutdown. Replace status-driven injection, explicit per-world attach and connection-only detach; do not
  retain those old semantics behind the new names. Explicit cancellation must reach the resident; Ktor disconnect
  propagation can follow later.
- Remove gameplay tool registrations, scripts, per-operation adapters and obsolete tests as part of the rewrite without
  a compatibility layer or per-tool replacement requirement. Reuse side-effect-free readers only where useful to generic
  observations. Do not preserve temporary blueprints, game-rule substitutes or duplicate aliases as hidden behavior.
- Replace the Python runners and HTTP helper under `tests/` with project-owned Kotlin tests. Migrate useful transport,
  lifecycle, authoritative-state and CRC checks into the new contract; delete obsolete business-tool and old scheduler
  assertions. Move necessary Lua fixture resources alongside their owning tests, then remove the superseded `tests/`
  contents. Retain CMake-owned native fixtures. This plan update does not delete the current tests before the rewrite.

## Project-owned automated tests

Use the project's test framework for both unit and integration tests. Pure schema, selector, state-machine and
correlation tests belong in the default portable test source sets and run without Steam or Factorio. Tests that launch
real processes, inject a game and make MCP calls are integration tests, even though they use the same test runner and
assertion framework. Make real-game execution explicitly selectable so ordinary unit-test runs do not open a game.
Do not introduce another Python CLI or a shell/curl regression framework alongside Gradle.

Keep portable MCP client setup, assertions and test orchestration in `commonTest` where practical; place actual process
discovery, Steam URI launching and OS process control in `linuxX64Test` for the current implementation target. Prefer
the standard Kotlin MCP client when supported by the target; any necessary HTTP test helper must be thin and independent
of tool names and attachment policy. Use serializers and structured request/response types. Do not repeat the current
Python helper's hardcoded legacy catalog or implicit status-driven injection in a new language.

The real-game fixture owns the following sequence:

1. Reuse a suitable explicitly selected running Factorio client, or request launch through Steam's supported URI
   mechanism. Independently discover and verify the actual game process; never treat the URI launcher PID, URI return
   or a fixed sleep as game readiness. Verify the Steam launch path and focus behavior on the target platform. Do not
   force the game into the foreground or launch another graphical client alongside one already running.
2. Launch the normal Gradle-produced MCP executable with HTTP enabled and stdio disabled, or connect to an externally
   managed test endpoint. Perform standard MCP initialization and explicit attach. Exercise public tools through the
   real HTTP endpoint, not calls directly into tool implementations. The test runner can stop/restart its own MCP
   process between builds without reloading any agent tool registration.
3. Use test-owned saves/scenarios and, for multiplayer assertions, a local headless server with a non-admin client.
   Perform independent server-state and full-CRC verification through the test fixture. Keep server-console setup out
   of the product path. Retain the small stdio transport smoke test under the same project test infrastructure.
4. Manage pending calls, explicit cancellation, pause/resume and detach tests with structured concurrency and
   assertions.
   Bound the test runner's waits to detect a hung test; these diagnostic deadlines are not product tool execution
   timeouts. Do not require a test clock to substitute for actual game input ticks in real-game timing assertions.
5. Always clean up fixture-owned sessions, tasks and processes. Do not terminate an externally managed MCP or a user's
   pre-existing Factorio process. Reuse the graphical client and local server for ordinary focused cases; restart only
   when resident ABI or lifecycle coverage requires it. Keep test evidence in build outputs or ignored `temp/`.

Keep Gradle wiring small, use default source sets and artifact locations, and put any necessary reusable task logic in
`buildSrc`. CMake/CTest continues to own native ABI, relocation and detour fixtures, invoked by Gradle. Preserve
distinct
reports for unit tests, native fixtures and real-game/multiplayer acceptance; one category cannot stand in for another.
Interactive investigation remains free to use curl against the same endpoint without involving this test harness.

## Implementation sequence and acceptance gates

1. **Contracts and reset.** Finalize generic tool schemas, readiness states, cancellation and partial-progress results,
   resident protocol version and exclusive input admission. Remove obsolete gameplay-specific code/tests as needed;
   retain infrastructure only after review. Define no-argument observational status, process-selecting attach,
   instrumentation-removing detach and per-tool state checks. Align conflicting project guidance with these user
   decisions, including replacing Python test-runner instructions with project-owned Kotlin/Gradle tests. Keep planned
   capability distinct from implemented behavior.
2. **Linux phases and lifetimes.** Validate native ABI fixtures, late process observation, foreground UI phase/root,
   input phase/clock and world invalidation. Exercise menu, gameplay, pause and world replacement, including
   cancellation
   when simulation ticks have stopped. Require non-tick lifecycle notification and safe input release before integrating
   the sequence executor. Implement fixed hooks and automatic dynamic binding activation, then verify repeated
   attach/detach and safe removal with a retained or unloaded library. No stale pointers, duplicate hooks or tasks
   surviving their invalid world are acceptable.
3. **Vertical observation and UI slice.** Implement full/selected DOM reads and one verified button family end to end
   via
   actual MCP HTTP calls. Test a native menu button and a synchronized mod widget; then expand widget families and state
   coverage. Keep client/server CRC validation separate from UI appearance or fixture success.
4. **Bindings and sequences.** Implement live bindings, finite keys/buttons and ordered waits, then chords,
   exclusive admission, cancellation, replacement and autonomous cleanup. Verify adjacent tick boundaries and terminal
   progress without gameplay-effect polling. Complete cursor targeting through focused probes. Test binding changes,
   shared bindings and custom input dispatch. Remove execution deadlines throughout the tool path. Verify explicit MCP
   cancellation reaches the resident. If adding automatic HTTP disconnect cancellation, verify end-to-end propagation,
   not merely cancellation of the response writer; otherwise document its deferral. Do not advertise unsupported shapes.
5. **World readers.** Implement overview and API-shaped queries using the verified local-client path. Cover overlapping
   objects, related inventories/prototypes, invalidated identities, remote view and observation coverage. Verify that
   reads leave authoritative state and full CRC unchanged.
6. **Integrated generic coverage.** Exercise representative play through UI, bindings, sequences and world reads,
   without gameplay-specific shortcuts or a requirement to replicate the old catalog.
   Retain/validate the optional screenshot fallback, test agent-selected physical UI input, and update MCP instructions
   to prefer direct UI controls without forbidding alternatives. Advertise screenshot fidelity limits explicitly.
   Update README to implemented behavior, update AGENTS.md when old guards/rules conflict, and remove generated useless
   or empty directories. Keep research evidence under `temp/`.

Use one graphical Steam client and a local headless server with a non-admin client. Reuse them for ordinary iterations;
restart Factorio when native resident ABI or lifecycle testing requires it. Rebuild with
`./gradlew linkDebugExecutableLinuxX64`, restart MCP with `--no-stdio --http-port PORT`, initialize a standard MCP HTTP
session, then call `attach` with the existing client's PID to reuse its compatible resident. `status` may inspect
attachment before or afterward but never installs anything. Keep a stdio smoke test and migrate applicable transport
assertions into the project's tests. Use curl for exploratory calls and the Kotlin integration fixture for repeatable
acceptance. Do not reintroduce a custom persistent stdio client as the main test path or retain a Python runner as a
required test entry point.

Required focused acceptance includes:

- Attachment: status before attach and after detach has no injection side effects; PID/name selection, ambiguous names,
  non-Factorio targets and initial startup rejection; attach in the main menu and paused gameplay; automatic binding
  after world entry/replacement; best-effort capability reporting with safe dependency handling; repeated attach without
  duplicate hooks or task reset. Repeated detach/reattach must work across game states, active input, concurrent world
  changes, process exit and retained-library reuse. Confirm original instructions are restored and no callbacks remain
  reachable after successful detach; do not equate library unload with hook cleanup. Test races between detach and
  functional admission, dynamic activation and other lifecycle calls. Fresh MCP status remains detached until explicit
  attach, even if an older compatible resident exists. Check MCP preconditions and resident execution-time revalidation.
  With concurrent pending tools across sessions, detach must abort all outstanding functional work through the shared
  lifecycle path, deliver abort descriptions to still-live requests and finish cleanup before unhooking. Cover pending
  reads/actions/screenshots as well as input, including a race with normal completion or a pause notification; require
  one terminal result and one cleanup, with no dropped reply merely because detach cancelled a handler coroutine.
- UI: full/selected reads; duplicate matches; recreation/reordering; disabled and destroying targets; clipped/covered
  targets; main menu, inventory/quickbar, research, train schedule, remote-view selector and mod widgets. Unknown
  properties
  remain explicit. Never claim a generic action family based only on one successful button.
- Input: default one tick; exact adjacent-step boundaries; simultaneous keys/buttons; mouse position; release/repress;
  empty wait/no-op; busy rejection across sessions; invalid replacement leaving old work intact; stop-only; valid and
  competing replacements; cancellation before/during admission and during release; stale task-ID cancellation; partial
  progress; UI/controller changes while ticks continue; pause without another tick; world unload and process exit.
  Verify cooperative checkpoints, tool-owned cleanup, autonomous release, no resumed remainder, and no confusion
  between enqueueing and processed input. The first resumed input evaluation must contain no contribution from the
  cancelled sequence; distinguish omitted per-tick contributions from any engine-latched input that needs clearing.
  Human-input isolation is not an acceptance gate.
- Cancellation/transports: a long input remains pending beyond the former execution deadline; explicit stdio/HTTP
  cancellation releases its input; another live request/session is unaffected. Test graceful server shutdown separately
  from abrupt MCP death, reconnect and deliberate detach. A crash must not strand held input. Reads and UI actions
  remain
  serviceable while input is active, and replacement does not wait behind the old task. Automatic HTTP-disconnect
  cancellation is optional in the first version. If implemented, test real disconnect, normal response completion, idle
  connection closure and the CIO `Connection: close` case with a client still awaiting a response; only abandonment of
  the
  corresponding in-flight request should cancel that task.
- Fallbacks: close a window through its currently bound key and interact through mouse input while UI is open. Verify
  that tools do not impose a UI-only/direct-action-only policy. Inspect actual screenshot content and metadata against
  normal, remote and mod/custom-rendered scenarios; no desktop capture or focus operation is required by the tool.
- World: normal/remote camera context; point/cell/area distinctions; multiple occupants; API-shaped bounded details;
  prototype reads; unsupported properties; fog/chart limitations; no simulation mutation from read-only tools.
- Multiplayer: assert representative writable effects on the local server and force full CRC after each new mutation
  path. Report native fixture results separately. Transport ordering alone is not completion correlation.

Completion of the refactor requires a coherent implemented generic tool surface, removal of obsolete business-specific
paths,
passing lifecycle/transport/representative multiplayer checks and an explicit capability matrix of remaining gaps. Tool
count or successful SDL delivery alone does not prove universal gameplay or mod coverage.
