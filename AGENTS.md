# Project conventions

## Scope and language

- Keep user-facing conversation in the user's preferred language. Write documentation, comments and docstrings in
  English, except explicitly requested translations. Name translated READMEs `README-{language}.md`, such as
  `README-zh-cn.md`. Keep `README.md` and its translations aligned when changing user-facing requirements, usage,
  tools or limitations.
- When the user emphasizes or repeatedly requests a rule, assess whether it is a lasting convention rather than a
  task-specific instruction. If it is a lasting convention, consider whether it belongs in `AGENTS.md`; add it when
  appropriate, or refine an existing rule to capture the intent precisely without duplication.
- This is primarily a Kotlin/Native application targeting Windows x64. Keep implemented tool coverage in README and
  unfinished designs under ignored `temp/`. Do not restore the old gameplay-specific catalog or Linux implementation.
- Preserve the default KMP source-set layout for future Windows, Linux and macOS implementations. Put portable tool
  definitions, lifecycle, cancellation, task models, serialization and UI projection in `commonMain`.
  The intended desktop targets are Windows x64 (`mingwX64`), Linux x64 (`linuxX64`) and macOS ARM64 (`macosArm64`).
  Do not expand the architecture matrix beyond these targets; only Windows x64 is currently implemented.
- Put process discovery, developer debug information, injection and platform IPC in the corresponding platform Kotlin
  source set. Use C/C++ only for necessary resident hooks, atomic wire helpers and game C++ ABI adapters.
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
- Keep native compilation/linking in CMake and reusable Gradle logic in `buildSrc`. Scope platform tasks and native
  output directories by host and target; never attach Windows dependencies to common or other platform compilations.
  Register platform build/test adapters within their target configuration, and connect host-only tooling only on
  supported hosts. A skipped task still has dependencies; do not use `onlyIf` as a substitute for isolating its task
  graph. Keep platform cinterop definitions with that platform's sources and select them explicitly. Derive task names
  and output scopes from the configured target; shared configuration must not assume DLLs, MSVC, WinHTTP or x64.
  Do not register unimplemented targets or add placeholder platform code just to advertise future support.
- Reuse Kotlin/Native's bundled compiler, assembler and linker. The resident requires MSVC-compatible C++ ABI and
  standard-library headers; mention build prerequisites briefly in README and explain setup in `development.md`.
  Do not require the Visual Studio IDE.
- Each native artifact has one build-system owner. Declare inputs, dependencies and outputs. Do not create custom
  distribution or executable-renaming tasks. Do not overwrite an unchanged resident DLL while it is mapped by Factorio.
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
  work. Reacquire the root on each frame; do not retain widget pointers across frames or world changes.
- Keep snapshots bounded and report truncation. IDs are snapshot-local. Preserve unknown types and unsupported
  properties instead of inventing semantic state, visibility or action capabilities.
- Act as a transparent observation/action adapter. Do not interpret game rules, workflows or the meaning of UI hierarchy
  or properties. Knowing native types and accessors for safe traversal, serialization and dispatch does not authorize
  deriving gameplay conclusions or replacing native values with inferred defaults. Expose each widget's own flags
  without propagating ancestor state, computing effective visibility,
  inventing semantic roles or dropping containers by type. Agents interpret the returned data and parent relationships.
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
- Action completion reports dispatch/execution progress and cleanup, never fulfillment of gameplay intent. Verify
  effects in acceptance tests; do not add runtime effect polling, retries or compensating actions to achieve an outcome.
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
- Keep stdio and HTTP sessions separate from the shared attachment. A new MCP reports detached until explicit attach,
  even when a resident exists. Do not version the resident wire contract or implement cross-build compatibility.
  Updating factorio-mcp requires restarting Factorio before attaching again; document this usage requirement.
- A newly acquired IPC mutex does not imply the resident is idle: MCP may have died after admission. Reconcile the
  outstanding resident command before writing another payload; never replay or overwrite an uncertain mutation.

## Injection and process boundaries

- Product code does not launch, terminate, restart or otherwise manage the Factorio process lifecycle. Attach targets an
  existing fully initialized client; reject initial startup loading. Process-launch helpers belong only in tests.
- `status` takes no parameters and never injects. `attach` takes exactly a PID or process name. Ambiguous names fail.
  `detach` is repeatable; an inert retained DLL is permitted after hook removal.
- Check target liveness before functional calls and reconcile exit during an operation. Process exit and detach must
  share cleanup, release local resources and reset the attachment without waiting for a dead resident. Event-driven
  OS exit notification is optional per platform; platforms without it may detect exit on the next tool call. Do not
  add background polling or treat a still-running, unresponsive process as exited.
- Read the selected executable's matching developer PDB before injection. Resolve symbols and unwind ranges dynamically.
  Do not use address tables, hardcoded game object offsets, version allowlists or instruction fingerprints.
- Game member locations may be resolved dynamically from the target PDB. Validate declaring types, member types,
  widths and bounds before reading them; never substitute a remembered offset when metadata is missing or incompatible.
  Resolve virtual-method positions from the same matching debug metadata as well; never encode a researched vtable
  slot or infer one from declaration order. File-format constants and synthetic fixture layouts are not game offsets.
- A symbol name alone does not establish its optimized ABI. Verify receivers, hidden return storage and arguments.
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
  isolated local multiplayer servers may run alongside it. Never test remote servers.

## Tests

- Keep automatic tests in Kotlin/Gradle and native CMake/CTest fixtures. Do not restore an external Python test runner.
- Use standard Gradle/KMP tasks (`assemble`, `build`, `check`, `mingwX64Test`) for building and local automated testing,
  without manual fixture preparation or a running game. Build test dependencies automatically. Keep checks requiring
  an installed game, external endpoint or operator-prepared scenario outside the default Gradle test run; execute them
  explicitly from the built test executable after the normal build. Environment variables must not opt Gradle into
  real-game acceptance.
- Use the official SDK's Streamable HTTP endpoint for acceptance and curl for exploration, with standard initialize,
  session and protocol-version headers. Keep a small stdio smoke test.
- Real-game acceptance is opt-in and explicitly selects its endpoint and PID. Never terminate an externally managed
  endpoint or game from the acceptance fixture. Keep temporary saves, scenario copies and logs under `temp/`.
- Separate native fixture, unit, real-game and multiplayer claims. Passing tool-count coverage does not establish full
  widget/property/state coverage. Report unverified cases and limitations explicitly.
- Reuse the running game for test/documentation changes. Restart it after updating MCP or a loaded resident DLL,
  or when deliberately testing startup and process exit. Residents must survive MCP restarts without changing the
  installed artifacts.
- Do not infer a running simulation from world presence, absent tick callbacks or UI appearance. Report unobserved pause
  state as unknown until a validated source exists.
- For writable paths, validate authoritative local-server effects and force a full CRC. Read-only-looking Lua APIs
  can still mutate serialized state; synthetic `LuaInventory.can_insert` and client-only translation requests previously
  caused desynchronization and must not be reused as passive queries.
