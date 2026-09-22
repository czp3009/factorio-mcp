# Project conventions

## Documentation language

- Write all project documentation, code comments, KDoc, and other docstrings in English, including updates to existing
  documents. Keep user-facing conversation in the user's preferred language.

- Keep the README focused on implemented behavior, usage and limitations. Store research notes, reference-project
  comparisons, experiments and unverified designs only under the Git-ignored `temp/` directory. Do not place research
  content in tracked or trackable documentation.

## Source sets and dependencies

- Prefer the default KMP directory layout and source sets. Keep portable MCP, protocol, task descriptions, and game
  logic in `commonMain`.
- Put only actual platform differences in platform source sets: process discovery, developer debug information parsing,
  injection, and platform IPC. The current target is Linux x86-64.
- Prefer the Kotlin standard library and kotlinx libraries: kotlinx-io for files, kotlinx-serialization-json for JSON,
  and kotlinx-coroutines for asynchronous work. Do not add expect/actual wrappers for functionality already covered by
  portable libraries.
- Prefer imports over fully qualified Kotlin names. Use an import alias to resolve naming conflicts where practical;
  retain a fully qualified name only when it improves clarity or is required.
- Use CMake, Gradle, and existing build infrastructure rather than custom compilation scripts.
- Give each generated native artifact one build-system owner. Declare its dependencies and required outputs so
  incremental builds cannot silently reuse stale or missing artifacts. Keep compiler and linker recipes in CMake rather
  than duplicating them in Gradle.

## Strings and structured data

- Never hand-assemble JSON or other structured data. Use kotlinx-serialization-json serializers, `buildJsonObject`,
  `buildJsonArray`, and related APIs for Kotlin JSON.
- Prefer typed protocol structures at the native boundary and serialization in commonMain. Do not concatenate JSON in
  C/C++.
- Write text that is semantically one line as one string literal. Do not split it into fragments joined with `+`.
- Use triple-quoted raw strings, with `trimIndent()` where appropriate, for multiline Kotlin text. Use raw string
  literals in C++. Do not emulate multiline text with concatenation or repeated `\n` escapes.
- Use language interpolation or an encoder appropriate to the target format for dynamic content. JSON, Lua source, and
  shell commands have different escaping rules; never substitute one format's encoding for another without verification.
- Lua calls can return multiple values. Do not place `assert(value, message)` directly as the last array entry or
  forwarded argument: bind the validated value first, so the diagnostic text cannot become data.
- Use explicit source templates for embedded Lua. Generate JSON inside the game through its official
  `helpers.table_to_json` API, never by concatenating JSON text.
- Write this AGENTS.md in English.

## Operations and execution boundaries

- Ordinary tools always target the injected client's local player, resolved internally from the game's client ownership.
  Never select the first/only connected player or accept a player override for ordinary tools. A separate player-summary
  tool may expose only shallow identity, online/life status, surface, and position.
- Current product scope is logically basic player operations and observations. An operation may require several internal
  submissions, but do not expand it into autonomous navigation, production planning, gathering workflows or batch
  construction without a new scope decision.
- A basic operation must use the corresponding game action. Blueprint inspection/import/placement belongs only to
  blueprint tools; never implement another operation through a temporary blueprint or unrelated game feature.
  Investigate and validate a direct path; reject unsupported parameters explicitly instead of substituting an indirect
  workaround. Keep shared settings schemas and per-entity writable capabilities consistent.
- Agent-facing tools express game intent. They must not require keycodes, mouse events, or low-level input sequences.
- Give read tools coherent domain schemas. Do not keep duplicate public aliases for the same query, or split one
  snapshot into multiple tools solely to mirror a reference project. Preserve distinct tools when preconditions or side
  effects differ materially.
- When auditing another implementation, inspect its actual registered methods and execution paths. Document partial
  equivalents and deferred capabilities explicitly; a server-side mutation is not evidence that a client-side
  implementation is synchronized.
- Inventory constants can alias across entity types. Enumerate actual entity inventory indices with the official API for
  complete inspections instead of applying every named constant to every entity.
- Product functionality must not execute in-game console commands, depend on RCON, or require server administrator
  rights. Server-console use is confined to isolated test setup and authoritative assertions; test the controlled client
  as a non-admin.
- Implementation priority: official Lua API, then game C++ action submission APIs, then game-internal input. Prefer
  keyboard shortcuts over mouse input when a fallback is needed; do not default to OS- or driver-level input simulation.
- Validate player constraints and multiplayer synchronization for writable APIs. A successful client-side call does not
  prove server acceptance. Calling a C++ state mutation implementation directly is not a substitute for normal
  synchronized submission.
- Do not assume a query-like API is free of simulation side effects. In this game, `LuaInventory.can_insert` with a
  synthetic numbered item can allocate an item number and desynchronize a client. Client-only
  `LuaPlayer.request_translation` likewise increments a serialized map counter. Avoid these mutations; prefer inspection
  of existing stacks and locale-independent GUI selection. Force a full client/server CRC in real-game acceptance tests,
  since periodic partial checks can miss mismatches.
- The resident inside Factorio executes and finishes basic operations autonomously. MCP submits complete tasks and
  receives their IPC results; it does not drive intermediate phases or release inputs later.
- Keep recipe prototype content, current force availability, and current inventory/hand-crafting feasibility distinct.
  Use bounded live Lua queries; do not add a recipe cache without measured need and an explicit invalidation contract.
- Put snapshot sorting, aggregation, material analysis, passive polling waits, and compound-action orchestration in
  commonMain. Reuse verified finite submissions for mutations; never replay a mutation after an uncertain result.
  Serialize compound mutations against other player actions while permitting reads and passive waits.
- Report bounds and partial results explicitly. A truncated area scan is not a global nearest search or complete
  resource total. A placement preview does not reserve materials or guarantee a future build. Preserve quality, product
  probabilities, and alternative placement items.
- Keep planning, parameter translation, compound-operation orchestration, and queues of not-yet-admitted work in
  MCP/commonMain. Keep the game side thin and fixed: nonblocking IPC, a bounded queue for admitted low-level tasks,
  minimal completion/input-release state, and necessary game adapters. World-bound state belongs to the current Lua VM;
  do not duplicate the scheduler or add business planning in C++. Already-admitted finite actions must still finish or
  release input without MCP.
- Store bridge runtime state in private, world-local Lua closures or globals, never in save-persistent `storage`.
  Recreate it in a new world rather than persisting or replaying old operations. Keeping scheduling in Lua does not make
  unsynchronized Lua world mutations safe in multiplayer.
- Use one bounded FIFO queue for basic operation submission. Execute submissions in delivery order at the appropriate
  game phase.
- Represent a basic Lua task as an ID, a callback, and an optional completion response type. After a successful
  callback, reply immediately if no response type is specified; otherwise enqueue its ID with that type's listener. A
  failed callback replies with an error and must not occupy a completion slot. Release the executed callback rather than
  retaining it while waiting for a response.
- Keep tick dispatch fixed and small. Put operation-specific behavior in task callbacks and completion adapters; reuse
  the scheduler rather than adding per-tool branches to the tick loop.
- Return immediately when an operation's result is available. If confirmation requires later ticks, register a bounded
  one-shot observer in the game; reply after the corresponding synchronized state or game event is observed, then remove
  that observer. Handle rejection, timeout, and world changes. Completion notifications may arrive out of submission
  order.
- Use separate completion listeners when operation types expose different completion events. Each persistent listener
  may hold a bounded FIFO of pending callbacks and must process every available matching completion in the current tick,
  including multiple completions in that tick. Keep these completion queues separate from the single submission queue.
  Use FIFO correlation only after verifying that the observed game events correspond one-to-one with the submitted
  operations; transport ordering alone does not establish that correspondence.
- Every basic operation has a definite end. A task may span game phases but must not leave held input dependent on a
  future MCP message.
- After MCP exits unexpectedly, fully admitted tasks continue. IPC notifications must never block the game. Do not
  retain result history or replay tasks automatically; reconnecting MCP inspects current game state.
- Keep game task execution independent of MCP connections and restarts. MCP assigns task IDs that are not reused across
  restarts; the game echoes each ID in its completion notification without interpreting it. MCP owns the pending-request
  lookup and discards unknown IDs. The game does not track MCP connection generations or recover undelivered results.
- On world unload or replacement, clear all world-bound submission and completion queues, including already-started
  operations. Lua callbacks disappear with their VM, but native queues require explicit cleanup. Never access a previous
  world's Lua state or game objects; bind callbacks afresh in the next world. Release any bridge-owned transient input
  as part of teardown rather than keeping the old task alive.
- A synchronized action can replace Lua API objects such as automatic logistic sections without changing worlds.
  Completion observers must re-query replaceable objects by stable identity/type instead of retaining an invalidated
  wrapper.
- Keep game-event correlation separate from IPC request lookup: a late game event must not be mistaken for another
  operation's completion. World lifetime changes must invalidate old observers even when an allocator reuses the same
  address.
- Version changes to the resident protocol and native adapter contract. Reject incompatible residents rather than
  silently advertising tools they cannot execute. A compatible reconnect must keep admitted work independent of the new
  MCP session.
- `status` identifies the target before modification, installs/reuses only the process observer, and reports observation
  and world-binding readiness separately. It must not bind Lua or enable player actions. Unsupported targets return a
  structured unrecognized result.
- `attach` requires an existing observer and an active world. Missing prerequisites return actionable MCP errors. Every
  functional tool checks the current world's Lua readiness and directs the agent to attach when absent.
- Repeated status/attach calls and MCP crash recovery use the same resident discovery and idempotence checks. Never
  re-register an existing world callback or clear admitted tasks on reconnect. World/VM replacement invalidates the Lua
  binding and requires an explicit attach for the new world.
- Check the process-resident observer and current-world callback independently. A marker's presence alone does not prove
  initialization completed. Treat pending startup or missing world callbacks as explicit states rather than waiting for
  gameplay until timeout.

## Debug information and validation

- Read the developer-provided debug information from the target game's actual executable before injection. Resolve
  functions and locations dynamically. Do not use game address tables, object field offsets, version allowlists, or
  instruction fingerprints.
- A debug symbol's C++ name alone does not establish its optimized ABI. Never guess receivers, object layouts, or
  arguments without verification.
- Use the installed game's bundled runtime API documentation when current online documentation describes a different
  release. Normalize documented default values before comparing structured API results.
- Test changes to native detours, register preservation, relocation, and ABI boundaries in isolated fixtures before
  applying them to Factorio. Report native fixture results separately from actual game and multiplayer validation.
- Reuse prior research under `temp`. Explore uncertain approaches in isolated experiments there. Preserve concise
  conclusions and evidence for negative results; do not integrate failed approaches into production.
- Validate against the locally installed Steam Factorio with an isolated local server. Do not test remote servers. The
  product does not launch Factorio.
- Keep real-game tests within this machine's memory budget: run at most one graphical Factorio client at a time. A local
  headless server may run alongside it. Run menu and multiplayer-client scenarios sequentially, waiting for the previous
  client to exit before launching another.
- Preserve original research and useful evidence. Remove disposable experiments, caches, and empty directories created
  by this work. Never claim unfinished tools or unpassed tests are ready.
- Track advertised MCP tool coverage in the acceptance suite, and independently check writable effects on the local
  server. Tool-count coverage does not prove coverage of every player action or argument combination.
- Investigate and experiment with uncertain basic-operation paths before omitting them. Expose only verified
  capabilities, and report genuinely unsupported operations with the evidence and reason; do not advertise placeholders
  as working tools.

## Game input adapters

- Capture actual constructor or method receivers. Identify GUI targets through the game's own layout APIs; never store
  screenshot coordinates or reconstruct closure/object layouts.
- Similar register layouts do not imply identical semantics. In particular, distinguish a bounding box's maximum
  coordinates from a rectangle's width and height.
- A GUI click can read cached game cursor state rather than its supplied pixel coordinates. Use a verified,
  operation-scoped game getter when needed, with game-owned cleanup on success, failure and world teardown; do not
  replace state confirmation with a fixed sleep.
- A successful input submission is not a completion. Confirm the synchronized state or matching game event, and allow
  client latency state to catch up before the next dependent submission.
- Any temporary game input state must have game-owned cleanup on completion, failure, and Lua/world teardown. Do not
  depend on MCP remaining alive to release it.

## Testing workflow

- Use the SDK's standard Streamable HTTP endpoint for acceptance scripts and interactive experiments. Do not reintroduce
  a custom stdio JSON-RPC client as the main test path. Retain a small stdio smoke test because stdio is a supported
  production transport.
- For ordinary tool changes, reuse the existing graphical Factorio client and local headless server. Build with
  `./gradlew linkDebugExecutableLinuxX64`, restart only MCP with `--no-stdio --http-port PORT`, initialize a new HTTP
  session, then call `status` and `attach` again. Do not restart Factorio solely because MCP was rebuilt.
- Restart Factorio when the injected resident ABI changes, when testing startup/world lifecycle explicitly, or when the
  process is no longer usable. A compatible resident and current-world hook must survive MCP restarts.
- Use `curl` with standard MCP initialization, `Mcp-Session-Id` and `MCP-Protocol-Version` headers for exploratory tool
  calls. Generate request JSON with a serializer or `jq`. The README includes the protocol sequence.
- `tests/mcp_http.py` supports an externally managed `url` as well as a managed native MCP process. External endpoints
  are disconnected with HTTP DELETE, never terminated by the helper. Integration and lifecycle suites use HTTP requests.
- Run `python3 tests/transports.py --injector PATH [--pid EXISTING_FACTORIO_PID]` for transport changes. It checks
  simultaneous stdio/HTTP sessions, shared game attachment when a PID is supplied, session deletion, stdin EOF, shutdown
  and reconnecting a fresh MCP to the same game.
- Run focused operations and authoritative server assertions during development; force a full CRC after new mutation
  paths. Reserve complete catalog/lifecycle runs for changes that affect those contracts, rather than rerunning them
  after every edit.
- Keep stdout exclusively for MCP frames when transports run together. Ktor Native installs signal handlers during
  startup and its default logger writes to stdout; preserve the application's nonblocking signal cancellation and stderr
  diagnostics.
- Start Ktor with `wait = false` and await a coroutine completion signal in common code. Native signal handlers must
  only set signal-safe state; perform server shutdown and resource cleanup from coroutines, never directly from a POSIX
  signal handler. Preserve a pending shutdown when reinstalling signal handlers.
- Use `factorio-mcp` consistently as the product, executable, MCP server and diagnostic name. The standard Gradle
  distribution installs `bin/factorio-mcp`; keep Kotlin/Native compiler artifact naming conventions for intermediate
  builds. Do not introduce alternative product names.
