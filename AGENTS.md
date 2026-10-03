# Project conventions

## Scope and language

- Work without subagents unless the user explicitly re-enables them.
- Keep user-facing conversation in the user's preferred language. Write documentation, comments and docstrings in
  English, except explicitly requested translations. Name translated READMEs `README-{language}.md`, such as
  `README-zh-cn.md`. Keep `README.md` and its translations aligned when changing user-facing requirements, usage,
  tools or limitations.
- Use half-width (ASCII) punctuation and symbols when writing Chinese, including user-facing conversation,
  documentation, comments and docstrings.
- When the user emphasizes or repeatedly requests a rule, assess whether it is a lasting convention rather than a
  task-specific instruction. If it is a lasting convention, consider whether it belongs in `AGENTS.md`; add it when
  appropriate, or refine an existing rule to capture the intent precisely without duplication.
- This is a Kotlin/Native application. Keep implemented tool coverage in README and unfinished designs under ignored
  `temp/`. Do not restore the old gameplay-specific catalog or superseded platform implementations.
- Preserve the default KMP source-set layout for future Windows, Linux and macOS implementations. Put portable tool
  definitions, lifecycle, cancellation, task models, serialization and UI projection in `commonMain`.
  The intended desktop targets are Windows x64 (`mingwX64`), Linux x64 (`linuxX64`) and macOS ARM64 (`macosArm64`).
  Do not expand the architecture matrix beyond these targets. Windows x64 and Linux x64 are implemented.
- Put process discovery, developer debug information, injection and platform IPC in the corresponding platform Kotlin
  source set. Use C/C++ only for necessary resident hooks, atomic wire helpers and game C++ ABI adapters.
- Keep one portable MCP tool contract, including schemas and result semantics. Every supported platform must
  implement every tool; platform-specific omissions or incompatible argument schemas are not a completed port.
  Treat missing metadata and different native layouts as engineering work to resolve, not grounds for dropping
  existing functionality. Report a platform capability as impossible only with evidence of a fundamental limitation.
  Use insights from any platform's game implementation to reassess tool abstractions, parameters, schemas and
  results. When research suggests a better design, improve the shared contract and update all implementations,
  tests and documentation together. Reuse the existing common game Lua readers and validated Windows behavior;
  keep only native entry/layout/ABI differences in the platform adapter.
- Before implementing or revising any tool on another platform, inspect the previously validated Windows
  implementation as the behavioral reference, including call paths, execution phases, cleanup and result semantics.
  Adapt platform ABI and layout differences without independently redesigning established behavior. Change the
  reference logic only when concrete evidence justifies it, and apply the improvement consistently across platforms.
  Extracting shared code is a separate refactoring concern; it does not itself justify changing implementation logic.
- Prefer the game Lua API for every tool's game-facing behavior. Check its documented contract and actual execution
  path before using game C++ or lower-level framework APIs. Reuse common Lua implementations wherever the API can
  provide the required behavior and synchronization. An existing native implementation is not a reason to bypass
  a suitable Lua API. Document concrete capability gaps before falling back. Input may require the game's SDL or
  other framework layer; this does not authorize OS input simulation or replacing normal input with world mutation.
  For chat, existing-history reading and normal synchronized player-message submission remain required; local
  console printing and manually raising script events are not substitutes for those behaviors.
  Read tools should use existing-object Lua observations wherever available; assess actual state effects when an
  API's read-only behavior is uncertain. For Lua writes, trace the call path to a permitted synchronized execution
  mechanism and verify authoritative local-server effects and full CRC. A game-thread/frontend/tick callback alone
  proves neither replication nor freedom from desynchronization; do not call a mutating Lua API only on one client.
- Let platform source sets identify the operating system. Prefer role-based names for equivalent platform classes and
  variables; keep OS, file-format or ABI names where they distinguish the actual responsibility or mechanism.
- Write README for end users: concise setup, usage, available tools and practical limitations. Keep detailed public
  arguments, examples and result semantics in `tools.md`, without duplicating the README catalog. Omit implementation
  architecture, language responsibilities and internal mechanisms from user guides. Keep build and test instructions in
  `development.md`. Keep research, experiments, comparisons and unverified plans only under ignored `temp/`; do not
  duplicate them in tracked documentation. Keep agent-facing `tools/list` descriptions aligned with the implemented
  contract as well. Preserve historical evidence with its original scope; use an explicit current index instead of
  rewriting old results as if they came from the latest code.
- Keep README instructions focused on user actions and configuration. Omit automatic package/platform selection,
  client-managed MCP startup and other mechanisms that require no user action. Keep agent operating guidance in
  server instructions and tool documentation rather than repeating it in installation instructions.
- For platform-specific user-facing configuration and command examples, show Linux paths and shell syntax first,
  followed by Windows/PowerShell differences. Do not label or duplicate platform-independent examples by operating
  system. This example ordering does not imply support for unimplemented platforms.
- Keep MCP server instructions, tool descriptions and argument help concise and agent-oriented: explain tool choice,
  prerequisites and result limits, with parameter rules in schemas rather than duplicated pseudo-schemas. Prefer
  structured observations and direct widget actions; screenshots are for missing visual information, verification or
  requested images, not a routine step after every action. Describe implemented behavior without gameplay inference.
- Remove empty source directories left by changes. Preserve original research and useful negative results.
- Do not describe the project as targeting or aligning with a specific Factorio version. Mention tested Factorio
  versions briefly in README and its translations only; preserve version identifiers in historical research and raw test
  evidence.

## Build and dependencies

- Keep the project version in `ProjectInfo.VERSION` only. Use `latest` in installation examples and Gradle's
  `printVersion` task in automation; do not duplicate the current release number in documentation or code.
  Dependency/protocol versions and historical test evidence retain their own explicit version identifiers.
- Reference third-party GitHub Actions by version tags, not commit hashes.
- Prefer Kotlin standard libraries and kotlinx-io, kotlinx-serialization-json and kotlinx-coroutines. Do not add
  expect/actual wrappers for APIs already provided by portable libraries.
- Use CIO for HTTP servers on every platform. HTTP clients currently exist only in tests against HTTP endpoints;
  use CIO for those clients in commonTest. If product clients or HTTPS are introduced, use Curl consistently across
  platforms instead of selecting a different client engine per platform.
- Prefer default KMP target registration, source sets, directory layouts and task wiring. Keep implemented targets
  registered on every host, but link native binaries only when the target OS and architecture match the host.
  Retain normal compilation and default host checks for tests. Keep npm packaging separate from ordinary builds;
  do not alter target declarations or npm task dependencies merely to suppress foreign linking.
  Configure platforms through the standard named target/source-set blocks, such as `mingwX64` and `linuxX64`, and
  preserve the default source-set hierarchy rather than adding manual hierarchy edges or replacement source roots.
- Keep native compilation/linking in CMake and reusable Gradle logic in `buildSrc`. Scope platform tasks and native
  output directories by host and target; never attach Windows dependencies to common or other platform compilations.
  Register platform build/test adapters within their target configuration, and connect host-only tooling only on
  supported hosts. A skipped task still has dependencies; do not use `onlyIf` as a substitute for isolating its task
  graph. Keep platform cinterop definitions with that platform's sources and select them explicitly. Derive task names
  and output scopes from the configured target; shared configuration must not assume DLLs, MSVC, WinHTTP or x64.
  Do not register unimplemented targets or add placeholder platform code just to advertise future support.
- Reuse Kotlin/Native's bundled compiler, assembler and linker. The Windows resident requires MSVC-compatible C++ ABI
  and
  standard-library headers; mention build prerequisites briefly in README and explain setup in `development.md`.
  Do not require the Visual Studio IDE.
- Each native artifact has one build-system owner. Declare inputs, dependencies and outputs. Do not create custom
  distribution or executable-renaming tasks. Do not overwrite an unchanged resident library while it is mapped by
  Factorio.
- Use this checkout directly for Windows and WSL development; do not copy project sources to another development
  directory. Run Windows and Linux builds/tests sequentially. Name local toolchain configuration by operating system
  and concrete purpose, rather than sharing ambiguous `native.*` keys across hosts.
- Prefix project-owned environment variables and other global configuration with `FACTORIO_MCP` and name their
  platform and purpose consistently with the corresponding configuration keys. Never use broad ambient names such
  as `INCLUDE` or `LIB` as project configuration fallbacks. Standard OS/toolchain variables belong only to their
  documented external interfaces; keep any translation into them scoped to the relevant subprocess.
- Treat project files and build outputs as exclusively owned by this project. Do not assume the user is running a
  particular build variant or add build/test workarounds to preserve externally occupied outputs. Tests should declare
  dependencies on the project artifacts they need and obtain their paths from the build model.
- Runtime injection must not depend on Frida, Python, LLVM utilities or an additional hooking library installed by
  users.
- Use `factorio-mcp` consistently as the product, executable base name, server name and diagnostic prefix.

## Data and implementation

- Use typed structures at the native boundary and serialize in `commonMain`. Never concatenate JSON in Kotlin or C/C++.
- Prefer imports over fully qualified names; use import aliases where useful.
- Follow conventional Kotlin and C++ formatting: separate functions with a blank line, put annotations on their own
  lines, and expand multiple statements into separate lines rather than compressing them with semicolons.
- Use one literal for one-line text and raw literals for multiline text. Use an encoder appropriate to each format.
- Read-only UI snapshots must execute at the verified frontend safe point, after GUI logic and before rendering/worker
  work. Reacquire the root on each frame; do not retain borrowed widget pointers across frames or world changes.
  Unfinished action cleanup may own stable native lifetime-reference records after their registration, invalidation
  and release behavior is verified. Reacquire their current targets at each safe point and validate the current root
  and tree; an address reused by a new widget must not revive a destroyed target. Retain these records on cleanup
  failure and release them before successful detach.
- Keep snapshots bounded and report truncation. IDs are snapshot-local. Preserve unknown types and unsupported
  properties instead of inventing semantic state, visibility or action capabilities.
- Preserve human-readable game text in its original language and form. Do not translate, interpret or rewrite it
  for the agent; preserve opaque values when reading and passing them back. Format encoding, bounded reads and
  reported truncation remain necessary. Use stable IDs and structured values for programmatic behavior rather than
  interpreting display text. `input_bindings` maps control IDs to bindings without localized names or descriptions;
  UI text and existing localization expressions remain available as supplied by the game.
  Preserve chat-specific text, rich-text markers, links and game-generated formatting unchanged as well; do not
  interpret, expand, normalize or recreate them. Encoding and explicit bounded truncation still apply.
- Input execution supports keyboard and mouse only on every platform; do not implement controller/gamepad input.
  Release the buttons pressed by MCP, including after cancellation or dispatch failure. Preserving keys already held
  by the user is outside this contract; do not add prerequisite user-input ownership detection.
  Keep `input_bindings` focused on keyboard/mouse slots. Controller-binding observation is not required and must
  not become a prerequisite for platform support. Native controller readers may still supply static evidence needed
  to distinguish keyboard/mouse fields; this does not authorize controller execution.
- Act as a transparent observation/action adapter. Do not interpret game rules, workflows or the meaning of UI hierarchy
  or properties. Knowing native types and accessors for safe traversal, serialization and dispatch does not authorize
  deriving gameplay conclusions or replacing native values with inferred defaults. Expose each widget's own flags
  without propagating ancestor state, computing effective visibility,
  inventing semantic roles or dropping containers by type. Agents interpret the returned data and parent relationships.
- Keep UI observations focused on structured contents, native state and game-provided image identifiers or references.
  Prefer logical icon identifiers such as `item/iron-plate` or `utility/close` when the game retains them; never infer
  them from filenames or pixels. Do not reconstruct rendering, expand image layers or process image contents for
  ordinary tools. Use `screenshot` as an optional fallback for missing visual information. Reassess and simplify
  excessive existing Windows behavior together with the portable contract and other platforms. Delete obsolete
  functions, classes, tests and layers rather than preserving compatibility scaffolding or unused abstractions.
  Apply this principle to every tool: simplify MCP-owned processing and presentation, while preserving the game's
  original values, text, relationships and repetitions. Do not deduplicate observations merely for presentation,
  interpret world contents or adapt results to gameplay workflows; the agent makes those decisions.
- Summary tools may group observed properties and perform general calculations, like database aggregation.
  Let arguments select grouping fields and numeric operations, with documented generic defaults. Never choose
  aggregation rules from entity types, prototype names or gameplay meaning. Report bounds, truncation and excluded
  values explicitly; ordinary observation tools still preserve individual values and repetitions.
- High-frequency player, inventory and catalog reads may use dedicated selectors or tools when they materially reduce
  calls or context. Prefer extending an existing coherent query contract. Read native references, properties and
  registries without vanilla-name tables, assumed inventory sizes or inferred crafting/research/action eligibility.
- Design direct structured tools to cover ordinary information reads across the game, not just machines or currently
  supported world fields. Agents should not have to navigate windows one by one to gather ordinary state, configuration,
  contents, catalogs or statistics. Missing ordinary reads are query-coverage gaps to address, not a reason to make UI
  navigation the normal workflow. Prefer coherent, discoverable queries and bounded expansion over a tool per fact.
  Reserve UI-based inspection primarily for interface state, unusual information or mod-specific content without a
  supported direct reader. Preserve safe-point execution and raw values; do not infer missing facts or promise access
  to all mod-private state.
- Action completion reports client dispatch/execution progress and cleanup, never server acceptance, delivery or
  fulfillment of gameplay intent. Network latency and client prediction rollback can change or undo observed effects.
  Design every tool assuming the player is not operating the game concurrently. Errors, failures and unexpected
  results caused by concurrent player actions are outside the contract; do not add interference detection,
  arbitration, restoration or compensating actions for them. MCP work must still satisfy its own lifecycle,
  cancellation, native lifetime and cleanup requirements.
  Apply this contract to every action, including chat; return after local submission and required cleanup, without
  waiting for a server acknowledgement or resulting state. Verify effects in acceptance tests only; do not add runtime
  effect polling, retries or compensating actions to achieve an outcome.
- Prefer shared UI interfaces for observation and actions, without interpreting inventory, crafting or machine
  workflows. Component-specific adapters may expose otherwise unavailable visible properties such as item identity,
  count or durability; keep these as UI properties and retain generic widget operations.
- Resolve action selectors against a complete live traversal at the UI safe point. Use widget event handlers rather
  than OS input. A successful dispatch is not a guarantee of gameplay effects; verify effects separately in acceptance.
  Finite mouse gestures must also release any native GUI capture they acquire, including capture transferred to another
  widget. Widget-level mouse-up alone does not prove GUI bookkeeping was cleared. Revalidate lifetimes after callbacks.
  For selected observations, resolve the selector before collecting bounded optional properties so unrelated widgets
  cannot consume the selected subtree's resource budget. Keep both phases within the same safe point.
- Keep queues, lifecycle and cancellation policy in Kotlin. The resident stays small, fixed, bounded and nonblocking.
  Native wire notifications must never wait for MCP to consume a result.
- All admitted work finishes or cleans up cooperatively. Detach aborts admitted and waiting observations, waits for
  their
  cleanup, removes hooks and then releases the attachment. Do not cancel handlers in a way that drops a live caller's
  terminal abort result.
  If native unhook or local resource cleanup fails, retain ownership so detach can retry; do not report successful
  detachment or discard the original failure before cleanup has completed.
- Do not add tool execution timeouts. Test watchdogs are allowed. Automatic HTTP disconnect cancellation is optional
  until verified end to end; do not claim it follows merely from response cancellation.
- Admit only one screenshot capture per MCP process. Explicit screenshot cancellation stops the current capture
  and waits for cleanup without starting another; repeat cancellation while idle remains successful.
- Keep stdio and HTTP sessions separate from the shared attachment. A new MCP reports detached until explicit attach,
  even when a resident exists. Do not version the resident wire contract or implement cross-build compatibility.
  Updating factorio-mcp requires restarting Factorio before attaching again; document this usage requirement.
- A newly acquired IPC mutex does not imply the resident is idle: MCP may have died after admission. Reconcile the
  outstanding resident command before writing another payload; never replay or overwrite an uncertain mutation.

## Injection and process boundaries

- Product code does not launch, terminate, restart or otherwise manage the Factorio process lifecycle. Attach targets an
  existing fully initialized client; reject initial startup loading. Process-launch helpers belong only in tests.
- `status` takes no parameters and never injects. `attach` takes exactly a PID or process name. Ambiguous names fail.
  `detach` is repeatable; an inert retained resident library is permitted after hook removal.
- Check target liveness before functional calls and reconcile exit during an operation. Process exit and detach must
  share cleanup, release local resources and reset the attachment without waiting for a dead resident. Event-driven
  OS exit notification is optional per platform; platforms without it may detect exit on the next tool call. Do not
  add background polling or treat a still-running, unresponsive process as exited.
- Read the selected executable's matching developer PDB on Windows and ELF/debug/unwind information on Linux before
  injection. Resolve symbols and unwind ranges dynamically.
  Do not use address tables, hardcoded game object offsets, version allowlists or instruction fingerprints.
- Keep attachment and injection fast through bounded work and reuse of validated metadata within the owned
  attachment. Remove demonstrated duplicate work without elaborate optimizations or weakening identity, ABI,
  lifetime or cleanup checks. Measure attach separately from first-tool metadata analysis and frontend latency.
- Prefer stable semantic boundaries for injection and call-site discovery: matching symbols, verified virtual entries,
  receiver/argument provenance and control flow. Accommodate unrelated instructions, register allocation and equivalent
  compiler output where the same proof holds. Do not depend on call ordinal, adjacent setup instructions or one observed
  prologue when those are incidental. Cover supported variations and ambiguous/unsafe cases in fixtures; retain strict
  ABI, bounds, lifetime and ownership validation on both Windows and Linux.
- Game member locations may be resolved dynamically from the target PDB. Validate declaring types, member types,
  widths and bounds before reading them; never substitute a remembered offset when metadata is missing or incompatible.
  Resolve virtual-method positions from the same matching debug metadata as well; never encode a researched vtable
  slot or infer one from declaration order. File-format constants, documented external SDK ABI constants and synthetic
  fixture layouts are not game offsets. SDK identity must still be connected to the decoded game data flow before use.
- On Linux, when matching debug information lacks game type layouts, derive necessary member and virtual-method
  locations from the selected executable's symbols and bounded, decoded machine instructions, with matching RTTI
  and vtable data where available. Validate receiver provenance, access widths, object bounds and the required ABI
  before use. Reject ambiguous or unsupported code shapes. This exception permits dynamic binary analysis, not
  remembered game offsets, version address tables, instruction fingerprints or inferred declaration-order slots.
- A symbol name alone does not establish its optimized ABI. Verify receivers, hidden return storage and arguments.
  Apply this verification to the native entries called by the adapter and to code used to derive layouts or values.
  Unchanged calls between game functions retain the game's own calling convention; register liveness alone does not
  create an additional adapter ABI boundary or require recursively proving the game's transitive call graph.
- Conservative detours must reject unsupported instructions or unwind data without patching the target. Test relocation,
  register/argument preservation, unwind behavior, transaction rollback and removal in native fixtures before game use.
  Decode only a bounded, readable copy of the entry point; validate function bounds before reading a relocated prefix.
- Before changing a detour, verify that its current bytes are still owned by that detour. Preserve each affected memory
  region's protection separately. Before calling resident exports, check the loaded module's path and require an
  initialized IPC mapping when reusing it. Do not compare resident build identities or maintain compatibility rules.
- Do not use console commands, RCON or server administrator privileges for product behavior. Do not replace normal game
  action submission with unsynchronized world mutation. Do not replay mutations after an uncertain result.
- Avoid forcing game focus or reading the desktop unless the user explicitly permits it for testing. Product tools
  must not require focus or OS input simulation. Run at most one graphical Factorio client;
  at most one isolated local multiplayer server may run alongside it. Check existing processes before starting
  either client or server; this development machine cannot accommodate multiple instances of either. Never test
  remote servers.

## Tests

- Before completing platform support, verify standard local build outputs, game-independent unit/native tests,
  explicit real-game acceptance, documentation matching the implemented contracts, and local npm package contents
  and installed execution. Maintain GitHub Actions for building, testing and publishing npm packages and GitHub
  Releases on every supported platform. Distinguish local workflow validation from an actual CI run.
- Do not trigger CI, create git commits or execute npm publication during this task, including publication to a local
  registry. Verify package contents, installation and execution using local tarballs; validate GitHub Actions locally
  without dispatching workflows. Report publication itself as unverified.
- Keep automatic tests in Kotlin/Gradle and native CMake/CTest fixtures. Do not restore an external Python test runner.
  Keep native fixture assertions enabled in optimized builds as well; a successful process exit is not evidence
  for checks or test operations compiled out by `NDEBUG`.
- Use standard Gradle/KMP tasks (`assemble`, `build`, `check`, `mingwX64Test`, `linuxX64Test`) for building and local
  automated testing,
  without manual fixture preparation or a running game. Build test dependencies automatically. Keep checks requiring
  an installed game, external endpoint or operator-prepared scenario outside the default Gradle test run; execute them
  explicitly from the built test executable after the normal build. Environment variables must not opt Gradle into
  real-game acceptance. Put real-game acceptance tests in the `com.hiczp.factorio.mcp.acceptance` package and
  installed-file-only checks in `com.hiczp.factorio.mcp.offline`; Gradle excludes these packages by package name, and
  external-game environment markers must not appear in other test packages.
- Use the official SDK's Streamable HTTP endpoint for acceptance and curl for exploration, with standard initialize,
  session and protocol-version headers. Keep a small stdio smoke test.
- Real-game acceptance is opt-in and explicitly selects its endpoint and PID. Never terminate an externally managed
  endpoint or game from the acceptance fixture. Keep temporary saves, scenario copies and logs under `temp/`.
  World tests may create dedicated single-player saves directly in the game, including its normal save directory,
  or use the one isolated local server. Never overwrite or modify the user's existing saves for testing. Use new test
  saves or disposable copies; new in-game test saves do not need to be confined to `temp/`.
  For long-running world acceptance, prefer dedicated peaceful saves with enemy-base generation disabled when
  combat is not under test, so hostile attacks do not interrupt unrelated checks.
- Separate native fixture, unit, real-game and multiplayer claims. Passing tool-count coverage does not establish full
  widget/property/state coverage. Report unverified cases and limitations explicitly.
- Reuse the running game for test/documentation changes. Restart it after updating MCP or a loaded resident library,
  or when deliberately testing startup and process exit. Residents must survive MCP restarts without changing the
  installed artifacts.
- Do not infer a running simulation from world presence, absent tick callbacks or UI appearance. Report unobserved pause
  state as unknown until a validated source exists.
- Reuse the user's already running Windows or WSL Steam client; do not start or manage Steam. Never run graphical
  Factorio clients in both systems at once. Account for slow WSL rendering when assessing safe-point and action latency;
  separate it from metadata-analysis costs. Behavior must not depend on resolution, fullscreen mode or window focus.
- Start graphical Factorio clients without game arguments, including test launches. Open disposable scenarios/saves
  through normal game UI; extra launch options can cause Steam to require unattended user confirmation.
- For writable paths, validate authoritative local-server effects and force a full CRC. Read-only-looking Lua APIs
  can still mutate serialized state; synthetic `LuaInventory.can_insert` and client-only translation requests previously
  caused desynchronization and must not be reused as passive queries.
