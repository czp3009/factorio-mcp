# Semantic interaction architecture research

Date: 2026-09-23. Status: research and proposed architecture, not implemented product behavior.

This document is in the project root at the user's explicit request. Experimental programs, raw disassembly, snapshots
and logs remain under Git-ignored `temp/`. The README continues to describe implemented functionality.

## Objective

Give an agent structured observations and semantic interactions that survive changes to recipes, entities, mod
interfaces and user key bindings. Avoid a separate tool for every gameplay feature, fixed screen coordinates, screenshot
recognition as the primary observation mechanism, and unrestricted direct simulation mutations.

The intended public boundary separates two kinds of interaction:

- **GUI interactions:** identify an actual control and activate it, edit text, choose an option, toggle a checkbox, or
  manipulate a control-local value. Left, right and middle buttons and modifiers are parameters of a control
  interaction.
- **World interactions:** identify a spatial target and use named game controls such as movement or the applicable
  interaction control. A semantic control must not be hardcoded to a physical key.

Both should return bounded, structured observations and meaningful completion states. The agent should not manage
low-level event sequences, pointer addresses, keycodes or delayed input release.

## Evidence and scope

The initial research inspected the locally installed Windows Steam Factorio 2.0.77 executable, its matching developer
PDB, bundled Lua scripts, and bundled runtime/prototype API documentation. Executable and PDB identity matched. Function
locations were resolved from debug information rather than address tables or instruction fingerprints.

Native experiments used one graphical client and a local headless server. The controlled player was not an
administrator. A test scenario logged authoritative effects and periodically forced full client/server CRC checks.
Separate native instrumentation fixtures were checked before game attachment. No production project build or execution
was needed.

These results establish behavior on the inspected Windows build. The follow-up validation below establishes a live
common widget-tree traversal for native, DLC, custom and mixed interfaces. It does not establish the Linux ABI,
version-independent binary compatibility, exhaustive semantic-property coverage, or production lifecycle safety.

## Follow-up validation: structured UI output

**Conclusion:** the common structural-output route is now demonstrated in the actual game, including already-existing
widgets. Native UI, DLC UI, mod-created controls, and mod content inside native windows can be represented through a
shared native widget tree, supplemented by the documented logical mod GUI roots. The collector does not need to
anticipate the mod's workflow or identify which mod owns each entry.

This conclusion concerns the game's widget interfaces. It does **not** mean that every pixel drawn inside a map, graph,
camera or sprite has a corresponding semantic control, or that every native control's specialized value has already been
decoded. There is no evidence supporting an unrestricted promise of complete semantic output for every possible mod and
every rendered surface. Common traversal solves discovery; family-specific metadata adapters still supply meaningful
values. These can stay internal to a small public observation/action API.

### What changed from the earlier investigation

The earlier candidate `agui::Widget::callRecursively(const std::function<void(Widget*)>&)` was invoked successfully
against live roots captured from the game's own `TopContainer` calls. Its executable implementation visits both private
and ordinary children in postorder without the visibility filtering that limited paint-only observation. Root selection
uses the game's `Widget::getGui`, distinguishing the active menu GUI from the background simulation GUI.

The callback is a real compiler-created `std::function`, built with the Microsoft MSVC/STL toolchain in an isolated
CMake research fixture. Its independent ABI fixture passed 1,000 iterations with four visited nodes per iteration before
game use. No game object fields, container layouts, or hand-built closure bytes were needed. Function locations and type
descriptors came from the installed executable's matching PDB. This is a Windows experiment, not a verified Linux
adapter.

The observer reads actual native RTTI, game-owned getters, and `Widget::getParentPathString`. The parent relationships
are reconstructed from the verified postorder traversal and game-provided path depth. Detaching and reattaching the
observer to the unchanged, already-built main menu rediscovered its 51 nodes. Observation therefore no longer depends on
witnessing each widget's construction.

### Live coverage and evidence

The client used the ordinary Steam installation, normal configuration and loading caches. A temporary fixture mod ran
with base, Quality, Elevated Rails and Space Age enabled, against an isolated local headless server. The controlled
client was a non-admin. Only the small research callback DLL was built; the MCP project was neither built nor run.

| Area                             | Verified result                                                                                                                                                                             | Retained evidence under `temp/ui-tree-verification/`                                                |
|----------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| Mod GUI API surface              | All six roots and all 25 `GuiElementType` values in the installed runtime API were instantiated and enumerated logically.                                                                   | `logical-all-types.json`, `control.lua`, `verify.py`                                                |
| Native traversal beyond painting | The initial fixture produced 557 native nodes versus 112 painted nodes; hidden descendants and offscreen collection content were discoverable. The final world snapshot contains 559 nodes. | `fulltree-all-mod-types.json`, `snapshot-initial-fixture.json`, `fulltree-final-world.json`         |
| Native plus mod extension        | A native chest window and the mod's relative frame/checkbox were present in one 786-node tree; the checkbox effect appeared on both peers.                                                  | `fulltree-final-mixed-settled.json`, peer logs                                                      |
| Native plus mod prototypes       | The technology tree exposed over 200 technology slots with prototype metadata, including the fixture's mod technology.                                                                      | `fulltree-final-research-settled.json`                                                              |
| DLC plus mod selector            | The native item selector contained native and mod items, the DLC quality controls, and a discoverable confirmation control. The full item/quality/confirm flow completed.                   | `fulltree-quality-selector.json`, `quality-confirmation-results.json`                               |
| Icon-only quality controls       | All five quality names and the current rare selection were obtained; transient tooltips were removed after each bounded observation.                                                        | `quality-tooltip-results.json`, five tooltip snapshots                                              |
| Game-generated mod settings      | The native `ModSettingsGui` tree exposed the fixture's setting labels, checkbox, dropdown, current text `first`, selected index, tabs and confirmation buttons.                             | `fulltree-mod-settings.json`                                                                        |
| Existing UI and world exit       | Reattachment discovered an already-existing menu. After leaving the multiplayer world, the same observer read the main menu and then the load-save dialog.                                  | `fulltree-reattach-existing-menu.json`, `fulltree-after-world-exit.json`, `fulltree-load-game.json` |
| Lifetime and replacement         | A destroyed control reference was rejected and its replacement was rediscovered. A separate address-reuse bug was found and corrected as described below.                                   | `live-action-results.json`, `fulltree-debug-missing-choose.json`                                    |

The retained verifier passes **48 evidence assertions**. It checks tree identities, parent references and agreement
between live RTTI and the game's path class for the final snapshots, as well as logical type coverage, selected values
and matching authoritative peer records. These are research checks, not production tool acceptance tests or an
exhaustive screen catalog.

Representative full-tree collection samples were 1–3 ms for menus and 8–12 ms for the tested gameplay windows, including
metadata reads in the research probe. These are individual samples, not a performance guarantee. Hundreds of internal
nodes should be reduced to grouped hosts and meaningful controls before sending observations to the agent.

### The original item-plus-quality case, end to end

The verified sequence was:

1. Discover the open item selector and its quality icon controls.
2. Read each quality's game-created tooltip and the actual button toggle state; discover five choices with rare
   selected.
3. Activate the legendary-quality control and the mod item `ui-structure-item` using fresh control observations.
4. Observe that selection alone leaves the picker open. Discover the icon-only confirmation control through its actual
   tooltip, which identifies confirmation and the current `E` binding.
5. Activate that control, then observe the picker closing and the chooser reporting the mod item with
   `[quality=legendary]`.
6. Independently confirm `{name: "ui-structure-item", quality: "legendary"}` and the mixed-window checkbox value in
   synchronized server and client fixture observations.

Matching peer snapshots continue from tick 67200 through tick 86400 in the retained evidence. Both peers recorded
subsequent forced full-CRC requests, including tick 86700, and continued without a reported desynchronization. This
supports the tested path; it does not prove all possible native readers or operations safe.

This sequence needs no hardcoded rule that selecting an item completes a dialog. Additional controls and validation
messages can appear in the next observation. The same public control-discovery contract can expose them without adding a
feature-specific tool.

### Metadata, transient UI and correctness findings

- **Multiple inheritance changes native receivers.** The technology and shortcut prototype getter paths require an
  actual `PrototypeProvider` receiver. Item controls use their corresponding `IDButtonProvider` base. The game runtime's
  `__RTDynamicCast`, using PDB-resolved type descriptors, returned the properly adjusted receiver; passing the original
  widget pointer was not equivalent. Prototype tags and localized labels then became readable through game-owned
  presentation getters.
- **Common values are accessible, but not all have been implemented.** The probe read text fields, multiline text,
  dropdown selection, list items and selection, slider value, switch state, button toggles, item quality and prototype
  identity. The documented logical GUI API supplies additional custom-control properties. Native-only checkbox/radio
  values and arbitrary specialized widget data are not universally exposed by this probe.
- **Tooltips are an operation, not a pure getter.** The base `createToolTip` returns null and is not a universal
  metadata API. `checkCreateTooltip` invokes the game's normal tooltip path. The finite probe request waits for
  attachment, records the resulting subtree, schedules removal through `removeToolTipWidget(0)`, and verifies cleanup on
  a subsequent frame. All five quality observations ended with zero tooltip widgets remaining. Production needs
  equivalent cleanup on failure and teardown.
- **Some content is detached or created lazily.** Closed relative extensions and inactive tab contents can exist in the
  logical mod tree without belonging to the active native root. Conversely, native popup contents may appear only after
  expansion. Combine the logical and native observations; report current applicability and discover the next state after
  an action. Do not equate logical existence with present interactivity.
- **Destructor hooks alone did not establish safe identity.** A reused address previously represented a `TechnologySlot`
  and later a `ChooseButton`. Cached class metadata consequently invoked the wrong reader. The corrected observer
  rechecks RTTI and expires all references after its own input submissions. Final snapshots pass class/path consistency
  checks. Same-type address reuse and changes caused outside the probe still require a production lifetime/epoch
  contract; raw addresses are not durable identifiers.
- **A submission is not a changed page.** Several immediate snapshots preceded the synchronized UI update. Observations
  were repeated until the intended state appeared; mutations were not replayed. A Windows response-file sharing error
  after confirmation was similarly reconciled by observation, without resubmitting the click.

Passive `Gui::recursiveDoLogic` hooks were also investigated and rejected as the complete collector: they can skip child
logic and initially observed the menu's background simulation instead of the active menu. Nested interceptor callbacks
did not reliably describe recursion initiated from another interceptor; the successful traversal uses the actual
compiled callback instead. These negative results remain in the research artifacts.

### What can and cannot now be claimed

The common discovery mechanism is verified for the installed game, including existing widgets, custom API types, mixed
interfaces, generated mod settings and menus outside a world. This supports adopting structured UI observation as the
architecture. The API's finite set of custom widget types and roots explains why new mods do not inherently require new
public tools.

The following remain separate engineering or validation work:

- Complete semantic adapters for native widget families, including graph data, map/camera contents, some icon metadata
  and specialized values. A structural node may legitimately carry `unknown` or an explicit capability limitation.
- Every control gesture and shortcut mapping, including native inventory middle-click and all popup-specific behavior.
  The research clicks still copy captured game event templates. Leaving the world invalidated those templates, so menu
  input required reseeding; structural observation continued independently. A fresh-event production constructor remains
  unverified.
- Full lifecycle safety under asynchronous mod changes, every allocator reuse case, player replacement and native
  exceptions. The finite tooltip experiment is not a finished resident teardown implementation.
- Linux ABI and production integration, plus acceptance coverage beyond the particular Windows screens and mod fixtures
  tested here.

It would therefore be inaccurate to label all UI semantics and operations universally validated. The verified statement
is narrower and useful: **native, DLC, mod and mixed widget interfaces share a workable structural observation
foundation; arbitrary visual content and remaining specialized semantics require explicit adapters.**

## Named game controls and raw input are different layers

`InputEventSender::sendEvent/sendEvents` accepts native game input events, such as key presses and mouse events. It
updates input state and routes through the game's input source and GUI processing. It does not accept a GUI widget as a
click target.

The installed implementation of `LuaSimulation::luaControlDown` demonstrates a useful underlying path:

```text
Named ControlInput
  -> getControlInputValuesForActiveInputMethod()
  -> eventsToTriggerThis(cursor position)
  -> InputEventSender::sendEvents()
```

An adapter can therefore expose `confirm-gui` or movement semantics while resolving the current client binding
internally. Actual behavior remains subject to focus, input consumption and other controls sharing a binding. Missing
bindings and unsupported input methods require explicit handling.

`LuaSimulation` itself exists only in simulations; it is not a normal-world mod API. Its implementation and bundled
demonstrations are evidence for native helpers, not permission to fabricate a simulation object.

Mods can register custom inputs, link them to game controls, and consume input. Jumping directly to an arbitrary
gameplay mutation can skip these semantics. The earlier input experiment verified ordinary GUI clicks, a linked
`confirm-gui` mod event, and normal multiplayer effects. It did not verify all bindings or continuous movement through
this new path.

## A direct widget-dispatch route exists

The inspected game contains:

```text
agui::Widget::dispatchClick(const agui::MouseEvent&)
agui::Widget::dispatchMouseDown(const agui::MouseEvent&)
agui::Widget::dispatchMouseUp(const agui::MouseEvent&)
agui::Widget::dispatchMouseEnter(const agui::MouseEvent&)
agui::Widget::dispatchMouseLeave(const agui::MouseEvent&)
```

Ordinary GUI mouse processing also calls these dispatchers. `dispatchClick` checks enabled state, with a game-defined
exception flag, and the accepted mouse-button mask. It uses `EventDispatchHelper`, invokes the widget's virtual click
implementation, and invokes registered listeners. It does not perform screen-space hit testing or enforce
visibility/modal membership itself.

This is a stronger route than invoking a button callback directly: the widget behavior and registered listener layer
remain involved, including the usual synchronized submissions in the tested cases. It is distinct from raising a Lua
event manually or modifying server state.

### Live results

| Case                        | Observed result                                                                                                                           |
|-----------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| Ordinary mod button         | Direct widget click reached the server's GUI event handler.                                                                               |
| Offscreen button            | A button at y=1440 in a 900-pixel-high window activated without scrolling.                                                                |
| Hidden button               | A `visible=false` button activated through direct dispatch.                                                                               |
| Disabled button             | No corresponding click event occurred.                                                                                                    |
| Overlapping ordinary window | The covered button activated while the covering window stayed open.                                                                       |
| Native modal popup          | A background button outside the popup's modal subtree activated while the popup stayed open.                                              |
| Automatic-toggle button     | Its toggled state changed and agreed on both peers.                                                                                       |
| Item/quality chooser        | Direct activation opened the native chooser. Complete item-plus-quality selection was not tested.                                         |
| Dropdown                    | Click alone did not expand it; widget-level down/up did. Direct option activation synchronized the selection.                             |
| Checkbox                    | Click alone and down/up alone failed to toggle. Enter/down/up/leave succeeded, with both a checked-state event and a click on the server. |
| Right button                | Direct dispatch preserved a right-button event on an accepting ordinary button.                                                           |
| Middle button               | The tested ordinary button filtered it. Native inventory-slot middle-click behavior remains unverified.                                   |

The relevant server and client events matched. Full CRC checks after the tested mutations did not reveal a
desynchronization.

### Why a single unconditional click recipe is insufficient

Checkbox behavior depends on the native click state, including pointer-enter state. Dropdowns respond to button-down.
Some widgets dispatch click during button-down; blindly sending down, up and another click can execute an action twice.

The public API can remain semantic and uniform while the adapter preserves each control family's native event process. A
hypothetical `set_checked(ref, true)` must use that process and verify the resulting state, rather than directly assign
a simulation-side property.

Direct widget activation intentionally differs from physical pointing: viewport inclusion and ordinary overlap need not
be preconditions. It can also bypass modal routing. The harmless background-button test does not prove that every
arbitrary background operation under every modal preserves all GUI assumptions.

### Experimental event construction is not a production contract

The prototype captured a real GUI event from a fixture button and used the game's `MouseEvent::copyWithNewSource` to
retarget it. A Win32 message bootstrap was used only to obtain that initial event; actual target activations used native
widget dispatch and did not move the mouse to the target.

A production adapter still needs verified construction, current timestamp/modifier semantics, lifetime ownership and
cleanup for fresh GUI events. Reusing a captured event or retaining its old source across world changes is not an
acceptable production design.

## Structured presentation

The game has a native `agui` widget system. Mod-facing `CustomGuiElement` objects are related to native widgets, but the
Lua GUI API alone is not a complete tree of vanilla interfaces.

Initial paint-based inspection retrieved frame titles, labels, button text, enabled states, dropdown selections and open
options. A test scroll pane exposed four painted rows out of 80, while a hidden sentinel was absent. The later common
traversal resolves that paint-only discovery limitation. Generic widget text alone still misses some controls: the
follow-up reads use appropriate native accessors, prototype metadata and bounded tooltip observation for the tested
item, quality and toolbar controls.

The agent-facing representation should therefore be a semantic projection of actual presentation objects:

- Process/menu state, current windows, modal relationships and focus.
- Meaningful groups and controls, current values and available interactions.
- Item/recipe/quality identities where represented by actual controls, rather than anonymous icon buttons.
- Bounded collections with explicit ranges and partial-result indicators.
- Stable-for-lifetime references and revisions, without exposing pointers or native class names.

Collapse layout-only containers and duplicate title labels. Keep disabled controls and relevant explanatory text.
Separate a control's existence, visibility, enabled state, and eligibility for direct activation: they are not the same
property.

Direct activation outside the viewport does not require dumping all hidden interface state into every observation.
Summaries and bounded expansion can expose relevant existing collections without overwhelming context. Uncreated
controls or simulation data not represented by the interface must not be presented as existing UI controls.

Arbitrary custom pictures, custom world rendering and coordinate-sensitive canvases may lack sufficient semantic
metadata. A widget tree is not automatically a complete interpretation of every visual element.

### Proposed agent-facing observation format

The following JSON is a proposed protocol example, not an implemented tool response or a literal game snapshot. Its
public model consists of pages/windows, controls, current state and supported actions. The raw native widget tree stays
inside the adapter. Collapse layout-only wrappers, decoration and duplicate labels while preserving meaningful groups
and relationships. Example labels are in English; actual observations retain the game's presentation language.

```json
{
  "view": "view-42",
  "revision": 18,
  "activeWindow": "item-dialog",
  "windows": [
    {
      "ref": "item-dialog",
      "role": "dialog",
      "label": "Select item",
      "modal": true,
      "children": [
        {
          "ref": "search",
          "role": "textbox",
          "label": "Search",
          "value": "",
          "actions": [
            "set-text"
          ]
        },
        {
          "ref": "items",
          "role": "selection",
          "label": "Item",
          "selected": null,
          "options": [
            {
              "ref": "item-17",
              "label": "Iron plate",
              "identity": {
                "type": "item",
                "name": "iron-plate"
              },
              "actions": [
                "select"
              ]
            },
            {
              "ref": "item-18",
              "label": "Structure test item",
              "identity": {
                "type": "item",
                "name": "ui-structure-item"
              },
              "actions": [
                "select"
              ]
            }
          ],
          "coverage": {
            "complete": false,
            "next": "items-page-2"
          }
        },
        {
          "ref": "quality",
          "role": "selection",
          "label": "Quality",
          "selected": "quality-rare",
          "options": [
            {
              "ref": "quality-rare",
              "label": "Rare",
              "actions": [
                "select"
              ]
            },
            {
              "ref": "quality-legendary",
              "label": "Legendary",
              "actions": [
                "select"
              ]
            }
          ],
          "coverage": {
            "complete": false
          }
        },
        {
          "ref": "confirm",
          "role": "button",
          "label": "Confirm",
          "intent": "confirm",
          "enabled": true,
          "actions": [
            "activate"
          ]
        }
      ]
    }
  ]
}
```

The fields have these intended meanings:

- `view` and `revision` identify the observed presentation context. References are opaque, scoped handles, never native
  addresses or assumed permanent identities. The actual validity and revision policy still needs implementation and
  lifecycle validation.
- `role` describes a control interaction category, such as a button, textbox, selection, checkbox, tab or item slot. A
  selection group must follow verified game grouping rather than an inferred business workflow.
- `label`, `value`, `selected` and `checked` describe observed presentation and state. `identity` supplements a
  corresponding control with a verified prototype identity; it does not substitute a prototype catalog for actual UI
  discovery.
- `actions` advertises only interactions supported by the adapter for that control. Discovery does not establish
  writability. `enabled` is an observed property, not the entire activation precondition: modal restrictions,
  attachment, lifetime and other relevant conditions must also be checked.
- `coverage` explicitly distinguishes complete collections from bounded subsets. A continuation token, when available,
  identifies further observation within the applicable view. Its presence must not imply access to controls the game has
  not created.
- `intent` is optional semantic metadata backed by game evidence. It must not be guessed from appearance or treated as
  permission to bypass the control's normal handler. Physical key bindings need not appear in action requests.

For example, the agent selects one discovered quality using:

```json
{
  "view": "view-42",
  "revision": 18,
  "target": "quality-legendary",
  "action": "select"
}
```

The adapter validates the target and current preconditions, submits the corresponding finite interaction, confirms its
effect, and returns an updated observation or an explicit failure/uncertain outcome. The next dependent action uses
fresh references. Never silently apply an old reference to a replacement control or replay an uncertain mutation.
Selecting an item does not implicitly confirm or close its window: the agent observes the resulting page and chooses the
next operation.

If a mod adds a checkbox, the same format can expose it without adding a mod-specific public tool:

```json
{
  "ref": "extra-option",
  "role": "checkbox",
  "label": "Also apply to existing devices",
  "checked": false,
  "enabled": true,
  "actions": [
    "set-checked"
  ]
}
```

Default observations should summarize hosts and expand the active window. Other hosts, such as top-left tools, the
quickbar and shortcut bar, expose bounded entries or counts where known and can be expanded on request. Large
collections use paging or explicit ranges. This keeps hundreds of internal layout nodes out of the agent's context
without hiding additional mod-created entry points.

Semantic normalization must remain evidence-based. Use actual control types, captions, tooltips, grouping and prototype
identities. If the adapter cannot establish a meaning, retain a generic control with available metadata and explicit
limitations. Do not invent required fields, confirmation semantics, validation messages or business actions. The example
proposes the shape of this contract; it does not claim that every role, metadata field or action has already been
implemented and verified.

## Proposed adapter boundary

The following are illustrative contracts, not registered tools:

```text
observe interface -> windows, controls, values, capabilities, bounded collections
inspect control -> details and supported interactions
activate control -> button/modifier semantics and observed result
edit control -> native text/selection/toggle interaction and observed result
perform world control -> named control + spatial target + finite duration
```

Keep semantic intent and presentation normalization portable. Platform adapters resolve actual native
functions/receivers and construct/dispatch events. All admitted input sequences must finish or release their transient
state without a later MCP request.

Do not infer a function's optimized ABI from its C++ name. One experiment incorrectly treated `Gui::render`'s entry
register as a usable receiver; disassembly showed the method loads the global GUI internally. The corrected probe
captured a receiver from a verified method instead. Preserve this lesson when extending coverage.

## Remaining verification

- Fresh native GUI-event construction without a seed interaction.
- Linux-specific debug information and optimized ABI validation.
- Inventory grids, crafting slots, quality choices, text editing, sliders, drag and scroll controls, and modifier/button
  combinations.
- Stable identity, destruction during dispatch, world/VM replacement, modal transitions, focus restoration, and cleanup
  after interruption.
- Binding changes, missing bindings, and finite continuous world controls.
- Production lifecycle handling for menu operation, save loading and observation through world switches; a limited live
  route is established below.
- Live validation of the proposed spatial-component adapter described below.

Coverage must be established by actual control behavior and authoritative effects, not by tool count or successful
function returns.

## Extending the component model

The requested "belt" means the on-screen quickbar and shortcut toolbar. It does not mean transport-belt entities on the
map.

The inspected PDB and disassembly support a shared public component vocabulary, with different execution adapters:

| Surface                   | Native evidence                                                                     | Suitable public representation                                               | Execution boundary                                                   |
|---------------------------|-------------------------------------------------------------------------------------|------------------------------------------------------------------------------|----------------------------------------------------------------------|
| Quickbar                  | `QuickBarGui`, `QuickBarItemSlot::mouseClick`, slot identity/count accessors        | A paged slot collection with item, quality, count and supported interactions | Native slot event processing, including relevant input state         |
| Shortcut toolbar          | `ShortcutButton`, `getBasePrototype`, `updateFromBehavior`, `mouseClick`            | Named actions, enabled/toggled state and optional associated game control    | Native shortcut activation; mod shortcuts remain mod-defined         |
| Research                  | `TechnologyGui`, `TechnologySlot::mouseClick`, technology references                | Technology nodes, selection, details, prerequisites and research queue       | Native node selection and actual available buttons                   |
| Minimap/map canvas        | `MinimapWidget::mouseDown`, `GameView`, cursor/map projection helpers               | A spatial view with a surface, position, zoom and bounded targets            | View-local or world-space interaction, not an arbitrary child button |
| Main menu and load dialog | `MainMenuGui`, `SinglePlayerMainMenuGui`, `LoadMapGui`, `InLoadGameDialog::process` | Windows, save rows, selection, load/confirmation controls and progress       | Native GUI dispatch followed by application-state transitions        |
| World entities/tiles      | `Entity`, `LuaEntity.selection_box`, player selection and input submission          | Bounded spatial targets with identity, location and capabilities             | Normal targeting, player constraints and synchronized game actions   |

These are adaptation families, not a proposal for one public MCP tool per native class or gameplay feature.

### Additional live results

The component experiment reused one Windows graphical process for the successful multiplayer and subsequent
single-player/menu sequence. It did not build or run the MCP project.

| Experiment                     | Result and limit                                                                                                                                                                                                                                                               |
|--------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Quickbar slot activation       | A direct left-event dispatch picked up 30 normal transport belts. The non-admin multiplayer client's cursor change matched the server at tick 5572; subsequent forced-CRC checkpoints showed no desynchronization.                                                             |
| Quickbar middle-event dispatch | Retargeting a middle-button seed did not reproduce the expected clear-filter operation. An empty slot instead received the held item, and the populated first-slot filter remained unchanged. This is a negative result for treating the event's button field as sufficient.   |
| Research entry                 | Direct activation of the HUD research control opened the native technology interface.                                                                                                                                                                                          |
| Research node selection        | In the later single-player world, direct activation of a technology slot changed the detail panel from Electronics to Steam power. The panel exposed its crafting trigger and a disabled Start research button. Starting research and queue mutations were not verified.       |
| Multiplayer exit               | Direct activation of Quit game disconnected the controlled client and returned to the menu. The server continued running. A temporary file-IPC response-write race lost the immediate probe response; game logs and later observations independently confirmed the transition. |
| Menu navigation                | Direct widget activation traversed About/Confirm, Single player and Load game. Menu observation and dispatch worked without a player-world Lua VM.                                                                                                                             |
| Save selection                 | Direct activation of the `component-a` save row changed the selected save's detail label before Load was activated.                                                                                                                                                            |
| World loading                  | Native Load activation loaded the local `component-a.zip`; both scenario logs and the structured GUI identified world A.                                                                                                                                                       |
| World replacement              | From world A's pause menu, direct Load game, `component-b`, and Load activations loaded world B in the same process. A subsequent structured observation identified world B.                                                                                                   |
| Stale control reference        | The old world A seed reference was rejected after world B loaded. This verifies one destruction case, not every allocator-reuse or queued-action race.                                                                                                                         |

The menu sequence still used experimental seed events. A menu seed was copied onto a live, observed version-label widget
before its original About button was destroyed. This avoided retaining a destroyed source while navigating menus. A
fresh native event-construction contract remains required. Raw Escape/T input in the scaffolding only opened test
interfaces; it is not the proposed binding-independent public contract.

### Quickbar and toolbar implications

`QuickBarItemSlot::mouseClick` invokes `ControlInput::isActive` through the current control settings. Its behavior is
not determined solely by `MouseEvent.button`. This explains why direct dispatch can work for ordinary pickup while a
synthetic middle event fails to request clearing a filter. The matching failure is evidence consistent with this
explanation; the full corrected input-state adapter has not been tested.

A production control interaction must preserve the native interpretation of the requested slot operation and current
bindings. It may need an operation-scoped, correctly constructed input state in addition to a widget-targeted event,
with guaranteed cleanup. Exposing keycodes to the agent or assuming every slot accepts the same generic click recipe
would defeat the intended abstraction.

The official Lua quickbar APIs can supply slot/page metadata, but their existence does not make client-only writes
synchronized. They also do not turn a toolbar shortcut into an inventory item. Read and interaction capabilities must
reflect the actual control.

### Research is structured, but not uniformly a list of buttons

The technology interface supplies native technology slots plus ordinary labels and controls. Its selected detail panel
already explains differing research mechanisms: the tested Steam power node required crafting iron plates, and Start
research was disabled. A projection should expose these live conditions rather than assume every technology starts
through the same action.

For context efficiency, expose the selected technology, current research/queue, available actions, and a bounded set of
neighboring or searched nodes. Expand prerequisites/effects separately. A complete dump of every painted node and
dependency line is unnecessary.

An attempted technology-icon identity reader produced incorrect identities and caught access violations because the
prototype getter was called with the wrong subobject receiver. The class has multiple native bases; a method's name and
a valid outer widget pointer are insufficient ABI evidence. That reader was removed. Any `semanticText` in the initial
`snapshot-research.json` is invalid experimental output and must not be used as evidence. The clean snapshots and
node-selection result do not depend on it. Capture the game's actual getter receiver/result or validate a game-provided
conversion before implementing icon metadata; do not repair this with hardcoded field offsets.

## Spatial components for map interaction

Map operations can share the *public* component model, but the inspected `Entity` representation is not an`agui::Widget`
hierarchy. A chest is a simulation entity; the chest window is a GUI. Clicking empty terrain, placing a ghost, selecting
an area and interacting with a rendered mod overlay are also different target kinds.

Use a spatial view with bounded expansion into entities, tiles, positions and areas. A target reference can carry a
world generation, surface, identity and observed revision. Entity names are not unique identities, and `unit_number` is
optional for some entity kinds. Position alone is insufficient when an entity is removed and another is built at the
same location.

The game adapter should resolve a target again at execution time and follow the corresponding ordinary targeting/action
route:

```text
Spatial reference or explicit world position
  -> validate current world, surface, identity and observation bounds
  -> establish the game's actual target/cursor context
  -> submit the named game control or corresponding normal action
  -> confirm the result, rejection or bounded completion
  -> release temporary input state
```

`PlayerInputSource::processOpenGui` provides concrete static evidence: it calls `clientCheckCanOpenEntityGui`,
constructs/submits an `InputAction` through the input source, and has alternate chart/cursor-position paths. It does not
invoke an entity as if it were a GUI button. The existing project's world-input adapter likewise uses the game's
MapPosition parser, `GameView` projection, named-control lookup, and scoped cursor-position handling. This is supporting
code evidence, not a new acceptance test of map actions in this research run.

Selection boxes and collision boxes differ. Overlapping entities, selection priorities, reach, ghosts, remote view,
fog/chart knowledge, and the held tool affect the chosen action. The wrapper must verify the game's selected target
rather than promise that every entity reference can always be directly activated. Coordinates remain necessary for empty
space and areas; they can be world coordinates or component-local coordinates instead of screen pixels.

The minimap is a useful boundary example: it is a GUI widget, but its mouse handler consumes a position within a spatial
canvas. Native component identity alone does not specify which map location was intended. Keep a spatial target in the
interaction contract.

Arbitrary mod rendering can draw pictures, text, circles or other shapes without declaring an independently clickable
semantic object. Available render-object metadata may help describe them, but does not establish their action meanings.
Full compatibility with every possible visual or custom-input convention cannot be promised without additional mod
metadata or a fallback. This limitation does not prevent broad compatibility with standard mod GUI controls and ordinary
prototype-based entities.

## Menus and world lifetime

The successful save-loading sequence establishes that world switching can use ordinary GUI controls.
`InLoadGameDialog::process` obtains the selected map name/path from `LoadMapGui`, checks mod synchronization
information, and advances application state. The adapter should activate these controls and observe the resulting state,
allowing the game's load warnings and mod decisions to appear naturally.

Process-level presentation readiness must be separate from player-world readiness. A GUI dispatch phase such as the
verified `agui::Gui::logic` boundary runs in menus and while single-player simulation is paused. A scheduler that
depends exclusively on world ticks or `PlayerInputSource::sendStateChanges` cannot cover those states. This can coexist
with the existing requirement that world actions need explicit attachment to the current world: menu access needs its
own presentation readiness, without silently binding Lua.

Two live observations constrain lifecycle detection:

- Main-menu background simulations create and destroy Map objects. The first prototype incorrectly invalidated menu
  references on every Map destruction. The corrected experiment kept GUI lifetime tracking separate. The historical
  `worldEpoch` field in these raw snapshots is only a Map-destruction counter, not a validated player-world generation.
- `AppManager::isAppInMenu` returned true both at the main menu and in the in-game pause menu. It is not sufficient to
  determine whether an attached player world exists.

A production design therefore needs distinct process, presentation, player-world and Lua-binding identities. World
replacement invalidates world actions, Lua references and world-bound controls. A menu transition invalidates controls
actually destroyed or replaced; a background simulation transition must not invalidate an unrelated menu. Mod
synchronization may restart the entire process, which requires rediscovery rather than treating it as an ordinary world
replacement.

Completion of Load means the new world is ready and identifiable, not merely that its button handler returned. An
uncertain response must trigger observation, never automatic replay. The local single-player load tests do not
demonstrate changing the authoritative world on a remote multiplayer server.

## Recommended first boundary

Keep the public interface small: observe/expand a component, inspect its current capabilities, activate/edit it, or
submit a named finite game control against a spatial target. Return the observed result and invalidation information.
Implement native adapters for control families and application lifetimes behind that boundary.

Default observations should contain the active window/modal, meaningful HUD summaries, selected details, available
actions and bounded collections. Omit layout plumbing and repeated captions. Keep stable prototype identity separate
from localized presentation and from the current value. Report unknown icon semantics explicitly until the getter path
is verified.

The evidence supports extending this architecture to quickbars, research and menus. It also supports a spatial-component
abstraction for maps, with a separate native execution path. It does not establish that a single `dispatchClick` call, a
raw widget dump, or the official Lua GUI API alone provides full, safe coverage of every interaction.

## General UI hosts and mod extension mechanisms

Quickbars and research are examples, not the coverage boundary. A broader scan of the installed PDB identified 559
named, non-template classes whose recorded inheritance reaches `agui::Widget`, including the base class and
presentation-only classes. This is a discovery catalog, not 559 verified interactive controls. The inheritance catalog
is retained in `temp/interaction-components-research/widget-class-catalog.json`.

The important distinction is between a **host**, which contains or generates interface content, and a **control**, which
the agent observes and interacts with. Discover hosts and their current children dynamically; project them into a small
number of semantic roles. Do not turn the native class catalog into a public tool catalog.

### Top-left mod tools

The installed `data/core/lualib/mod-gui.lua` provides the exact mechanism used by many top-left mod buttons:

```lua
local mod_gui = require("mod-gui")
mod_gui.get_button_flow(player).add{
  type = "button",
  name = "example_tool",
  caption = "Example tool"
}
```

This is an illustrative mod-side creation call, not an MCP observation operation. `get_button_flow` obtains
`player.gui.top`, reuses a legacy `mod_gui_button_flow` when present, or creates/reuses `mod_gui_top_frame` and
`mod_gui_inner_frame`. `get_frame_flow` similarly creates/reuses `mod_gui_frame_flow` under `player.gui.left`. The game
handles ordinary flow/frame layout; the mod supplies its controls and contents. The bundled wave-defense and
team-production scripts use these same helpers.

These helpers are not side-effect-free getters: they create containers when absent. A reader must inspect existing GUI
roots and children instead of calling them to discover whether a mod toolbar exists. Do not require their conventional
container names; mods can add controls directly or use other containers.

### Shortcut buttons beside the quickbar

This is a different extension mechanism. A mod declares a `ShortcutPrototype` with `type = "shortcut"`, a stable name,
icons and an action. The game constructs the shortcut-bar buttons, lays them out and manages their
selection/configuration interface. `action = "lua"` causes normal shortcut activation to raise `on_lua_shortcut`,
carrying the player index and shortcut prototype name. Other declared actions include spawning an item or invoking
supported built-in behavior.

The installed `data/base/prototypes/shortcuts.lua` defines the base game's alt-mode, undo, redo, copy, blueprint and
other shortcuts this way. Native disassembly adds direct evidence:

- `ShortcutBarGui::createShortcutButtons` constructs actual `ShortcutButton` objects.
- `ShortcutBarGui::reattachShortcutButtons` assigns their current behavior and calls
  `ShortcutButton::updateFromBehavior`.
- `ShortcutButton::mouseClick` delegates to the base button, invokes the attached behavior, then refreshes its state.
- The class also owns the shortcut selection frame, search bar, scroll pane and reorderable rows. `QuickPanelGui`
  provides a different host for controller input.

This means a surviving widget pointer can represent a different shortcut after reconfiguration. References must validate
semantic identity/revision as well as object lifetime; a destructor-only stale-reference check is insufficient.

The runtime `prototypes.shortcut` dictionary supplies registered shortcut identities and metadata. Custom Lua shortcuts
expose availability/toggle queries on `LuaPlayer`. Keep this catalog separate from actual instantiated/docked controls
and the current shortcut-selection popup. A registered prototype is not proof that its button is currently present or
usable.

`associated_control_input` is explicitly documented as **tooltip keybinding information only**. It does not itself wire
shortcut activation to a custom-input handler. Do not replace clicking a shortcut with emitting that named input or
manually raising `on_lua_shortcut` and assume equivalence. The two routes can intentionally execute different code.

### Other documented custom-GUI roots

The installed `LuaGui` API exposes six roots:

| Root       | Intended host                                            | Discovery consequence                                            |
|------------|----------------------------------------------------------|------------------------------------------------------------------|
| `top`      | Top flow inside a scroll pane; commonly mod tool buttons | Summarize persistent tools and expand their controls             |
| `left`     | Left flow inside a scroll pane                           | Discover mod/scenario panels, including helper-created frames    |
| `center`   | Center flow                                              | Include centered custom dialogs                                  |
| `screen`   | Freely located screen widgets/windows                    | Discover floating windows and their current placement/lifetime   |
| `relative` | Widgets anchored beside native game interfaces           | Associate the extension panel with its currently applicable host |
| `goal`     | Flow in the objectives window                            | Include scenario objective controls/content when present         |

`LuaGuiElement.anchor` / `GuiAnchor` specifies a native GUI type and relative position, with optional name/type/ghost
restrictions. The installed `defines.relative_gui_type` lists 75 host types, including containers, assembling machines,
player inventory, logistics, trains, blueprints, production, equipment grids and space-platform hubs. Mods can therefore
extend native windows without computing their screen coordinates. A relative panel may exist in the custom tree while
its matching native host is closed; report that distinction.

`LuaGuiElement` supplies children, type, index, name, enabled/visible state, captions/tooltips, values and mod
ownership, subject to the attributes supported by each element type. Its index is unique among that player's current GUI
elements, not a promised permanent cross-world identifier. Arbitrary `tags` are mod-defined metadata, not standardized
action semantics.

The installed GUI element vocabulary contains buttons, sprite buttons, checkboxes, radio buttons, text fields/boxes,
dropdowns, lists, sliders, switches, prototype selectors, tabs, and layout containers. It also contains cameras,
minimaps and entity previews, which require spatial or preview semantics rather than assuming every visual child is
another button.

### Wider native interface inventory

The following families were found through actual widget inheritance/type information. Except for the live cases
documented earlier, this establishes their presence and structure, not complete interaction acceptance coverage.

| Family                            | Native examples                                                                                                 | Useful semantic projection                                          |
|-----------------------------------|-----------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------|
| Inventory, equipment and crafting | `ControllerGui`, `InventoryGuiSlot`, `EquipmentGridGui`, `RecipeSlot`, `CraftingQueueSlot`                      | Tabs, bounded slots, held stack, recipes, queue entries             |
| Entity configuration              | `AssemblingMachineGui`, `InserterGui`, `SplitterGui`, `DisplayPanelGui`, circuit-control GUIs                   | Current window, controls and dependent sections                     |
| Logistics                         | `LogisticGuiBase`, `LogisticGuiSection`, `LogisticNetworksGui`                                                  | Sections, filters, requests, network tabs                           |
| Trains and vehicles               | `TrainGui`, `TrainsGui`, `TrainStopGui`, `WaitConditionsList`, `EditInterruptGui`                               | Schedules, condition rows, interrupts and embedded map views        |
| Blueprints and planners           | `BlueprintLibraryGui`, `BlueprintBookGui`, `BlueprintSetupGui`, `BlueprintParametrisationGui`, `UpgradeItemGui` | Collections, selected document, filters, parameters and actions     |
| Selection dialogs                 | `QualityGui`, `QualitySelector`, `SignalOrNumberSelectGui`, `ItemAndDoubleCountSelectGui`, `GhostPickerGui`     | Dynamically present fields, choices and confirmation state          |
| Research and reference            | `TechnologyGui`, `TechnologySlot`, `Factoriopedia`, `TipsAndTricksGui`                                          | Selected entry, linked entries, details and actions                 |
| Statistics                        | `ProductionGui`, `GlobalElectricNetworkGui`, graph controls                                                     | Tabs, filters, timespan and structured series where supported       |
| Remote view and space             | `MapViewOptionsGui`, `UniverseWidget`, space-location/platform child widgets, `PinsGui`                         | Surfaces/locations, pins, view controls and spatial canvases        |
| HUD information and notifications | `AlertGui`, `AlertsOverview`, `AchievementNotificationContainer`, `HotkeySuggestionsGui`                        | Bounded alerts, actionable notifications and contextual hints       |
| Settings and mod management       | `ControlSettingsGui`, `ModSettingsGui`, `ModsGui`, load-error/mismatch dialogs                                  | Typed settings, choices, apply/restart actions and errors           |
| Menus, saves and multiplayer      | `LoadMapGui`, game menus, lobby/browse/connect dialogs                                                          | Available entries, selection, navigation, progress and confirmation |
| Transient overlays                | `FloatingGuiWindow`, dropdown/list popups, tooltip classes, `MessageDialog`, `ConsoleInput`                     | Focus, modal stack, expanded content and transient lifetime         |

A native control's presence does not grant permissions or expand product scope. For example, discovering console input
does not authorize a console-command execution path, and discovering editor/admin controls does not imply they are
usable by an ordinary player.

### Where unified discovery should attach

The PDB records `GameView` hosts such as `topLeftCustomGuiHolder`, `bottomContainer`, `bottomLeftContainer`,
`rightContainer`, `rightBottomContainer`, `activeWindow`, notifications, alerts, pins, research and remote-view panels.
`CustomGui` separately records root elements, root holders and widget-to-custom-element mappings. In the actual
executable, `CustomGui::loadWidgets` iterates roots, and `loadWidget` calls `CustomGuiElement::buildWidgetRecursively`to
obtain native widgets, including scroll-pane wrappers. These are concrete links between mod-owned logical elements and
the native presentation system.

At the general widget layer, debug types declare parent/children/private-children relationships and`agui::TopContainer`;
the GUI manager tracks focus, modal routing, tooltips and anchored widgets. A complete observer should therefore
combine:

1. Process/menu presentation roots and overlays.
2. Current player HUD, active windows and native popup/tooltip relationships.
3. All existing custom-GUI roots, preserving relative-host metadata; mod ownership is optional diagnostic metadata.
4. Prototype-backed catalogs only as supplemental semantics for the corresponding controls.
5. Spatial views as a separate target model.

The follow-up now verifies `Widget::callRecursively` as a common traversal of the current native root. Several other
child/parent getters declared in the PDB had no separate callable public symbol in this optimized executable; they are
unnecessary for this route. No reconstructed C++ vector or copied field offsets were used. The remaining distinction is
attached native content versus detached logical or not-yet-created content. Painting hooks alone miss hidden/unpainted
controls; creation hooks alone miss objects that existed before attachment.

The agent should initially see a small host summary, active/modal windows and actionable entries, then request bounded
expansion of a host or collection. Preserve native shared interactions underneath, while allowing family-specific
metadata readers. This directly addresses the examples of mod-added top-left tools and right-side shortcuts without
hardcoding either region as the complete set of supported UI.

### Clarified discovery contract: built-in host collectors

The requested discovery capability is concrete: MCP knows the game's supported UI attachment mechanisms and uses
built-in collectors to report what is currently inside those hosts. For example, an observation reports the buttons
currently present in the top-left tool area. It does not need to identify which mod created each button.

Implement collectors by game extension mechanism, with dynamic contents:

| Built-in collector                     | Runtime content to discover                                                                                                                                                                                  |
|----------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Existing custom-GUI roots              | Read existing `LuaGui.children`, then bounded `LuaGuiElement.children` traversal across top, left, center, screen, relative and goal. Follow nested containers; do not require `mod-gui` naming conventions. |
| Shortcut hosts                         | Read the native bar/quick-panel's current shortcut bindings, visible entries and selection popup; enrich actual entries with shortcut prototype metadata.                                                    |
| Native HUD hosts                       | Read current alerts, notifications, objectives, side-menu entries and other attached HUD controls, including content populated by mods through game APIs.                                                    |
| Active native windows and transient UI | Read current windows, anchored extensions, selectors, dropdown popups and dialogs after any interaction. Mod-supplied prototypes/data can change their contents.                                             |
| Application menus                      | Read the currently active menu/dialog and its available entries independently of world Lua readiness.                                                                                                        |

These collectors can share a compact output: a region/window reference, meaningful control references, labels/tooltips
or available semantic identities, current values, visibility/enabled state and verified interactions. Mod ownership need
not appear. Re-read or invalidate affected collections when content is added, removed, rebound or replaced; a
startup-only inventory is insufficient.

A typical interaction is: observe the top-left host, find a newly added tool button, activate that control, then observe
the newly opened window. No prior mod-specific button name or workflow is necessary. A relative extension should appear
with its matching open native window; a registered but undocked shortcut belongs in the shortcut chooser/catalog state,
not the current bar's list of buttons.

The research goal is to account for the game's extension mechanisms and their discovery paths. Acceptance should then
exercise representative additions through those mechanisms, including nested containers, late creation, removal, hidden
controls, anchored panels and shortcut rebinding. It does not require anticipating every future mod's business logic.
Current research has identified these host categories; a complete implemented collector suite and its acceptance
coverage have not been established.

An additional native inspection found `Widget::callRecursively`: its inspected body traverses both private and ordinary
children and calls the visitor without the descendant flag filtering seen in `getWidgetRecursively`. The follow-up
validation above subsequently verified a real compiled `std::function` callback and live traversal against game-owned
roots, including existing unpainted controls. `LuaGui::luaReadChildren` and `LuaGuiElement::luaReadChildren` also
provide concrete native implementations of the documented logical-tree reads. Traversal coverage remains distinct from
complete semantic metadata coverage.

## UI extension-point audit and discovery validation

The practical design is a finite set of built-in host collectors with dynamic contents. Mods use the game's attachment
mechanisms; MCP discovers the resulting entries without needing mod names or mod-specific workflows. The audit below
combines the installed 2.0.77 runtime/prototype documentation, bundled `mod-gui.lua`, PDB types, executable inspection
and a two-mod live fixture. It is an extension-point inventory, not a claim that every native collector has been
implemented or validated.

### Mod-provided entries and their discovery paths

| Extension mechanism                       | How mods populate it                                                                                             | How MCP should discover current content                                                                      | Evidence and boundary                                                                                                                                                                                          |
|-------------------------------------------|------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Top-left tools and panels                 | `player.gui.top`, `player.gui.left`; optionally the bundled `mod-gui` helper                                     | Traverse existing roots and nested `children`; retain button captions, sprites and tooltips                  | Two independent mods discovered live, including one that does not use `mod-gui`. Do not call helper creation functions during observation.                                                                     |
| Floating, centered and objective UI       | `gui.screen`, `gui.center`, `gui.goal`; `set_goal_description` for objective text                                | Traverse those roots; supplement the objective host with `get_goal_description`                              | All six roots enumerated live. A floating window need not be assigned to `player.opened`.                                                                                                                      |
| Extensions beside native windows          | `gui.relative` plus `GuiAnchor`                                                                                  | Enumerate logical children and anchor restrictions, then associate them with the applicable open native host | Installed API lists 75 relative host types. Live fixture confirms `visible=true` does not imply the host is open.                                                                                              |
| Shortcut buttons beside the quickbar      | Data-stage `shortcut` prototypes; runtime availability/toggle APIs for Lua shortcuts                             | Enumerate the actual native shortcut bar and chooser; enrich with `prototypes.shortcut` metadata             | Custom shortcut found live in the native chooser. Prototype registration alone does not establish docking, order or current presentation. The chooser's toggle changes docking; it is not shortcut activation. |
| Game-generated mod settings               | Startup, runtime-global and runtime-per-user setting prototypes                                                  | Discover the current `ModSettingsGui` controls; enrich with setting type, allowed values and bounds          | Live native tree verified for fixture per-user settings, labels, controls and dropdown selection. Settings values/prototypes alone do not describe current tab, unsaved edits or restart dialogs.              |
| Alerts                                    | `add_alert`, `add_custom_alert`                                                                                  | Use `get_alerts`, enabled/muted state and actual native `AlertGui` / `AlertsOverview` entries                | Documented data path and native hosts; a registered alert is not necessarily an onscreen actionable entry.                                                                                                     |
| Recipe notifications                      | `add_recipe_notification`                                                                                        | Use `get_recipe_notifications` to enrich the corresponding current native notification controls              | Documented runtime list; complete native control binding remains untested.                                                                                                                                     |
| Pins and map tags                         | `add_pin`; `LuaForce.add_chart_tag`                                                                              | Native pins host; `find_chart_tags` scoped to the current force/surface and chart presentation               | The installed `LuaPlayer` API has no general pins enumeration accessor identified by this audit. Do not assume newer online pin APIs exist in 2.0.77. Chart markers also require spatial semantics.            |
| Messages and chat                         | `show_message_dialog`; game/force/player `print`                                                                 | Current native message-dialog and console/chat presentation, including actionable links where supported      | `show_message_dialog` is restricted to maps with exactly one player. Text output is not automatically a button or permission to execute console commands.                                                      |
| Prototype-backed native content           | Modded items, qualities, recipes, technologies, entities, signals and related prototypes                         | Inspect the actual selector, technology tree or configuration window that is open                            | Data changes can add choices, fields and constraints. A static prototype catalog cannot replace the current UI observation.                                                                                    |
| Mod-triggered native windows              | Inherited `LuaControl.opened`, `open_technology_gui`, `open_factoriopedia_gui` and other documented entry points | Native active-window and overlay collectors, then any matching relative extensions                           | `opened` and `opened_gui_type` are useful hints, not a registry of all displayed GUI.                                                                                                                          |
| Custom inputs without buttons             | `custom-input` prototypes                                                                                        | Expose supported named controls separately; discover their settings UI when open                             | A custom input need not create any GUI entry. `ShortcutPrototype.associated_control_input` supplies tooltip information; it does not establish equivalent activation behavior.                                 |
| Rendered world content and embedded views | Rendering API, flying text, camera/minimap/entity-preview GUI elements                                           | Treat supported views as spatial components; report available structured metadata separately                 | Arbitrary rendered content is not a general custom-button registry. Do not infer a click handler from appearance alone.                                                                                        |

The first three rows cover the documented custom `LuaGuiElement` attachment roots. Later rows matter because mods can
also populate game-owned interfaces without creating a custom GUI tree. Native menus, HUD, transient popups and
prototype-backed windows therefore remain part of discovery even when all custom roots have already been scanned.

The native class audit found 559 named, non-template widget-derived classes. This includes base classes and
presentation-only widgets, so it is neither a tool count nor a tested capability count. It supports the broader family
inventory above; collectors should follow actual attachment and presentation relationships rather than maintaining
hundreds of feature-specific public tools.

### Live discovery experiment

The fixture used two independent mods and a scenario observer on a local multiplayer server, with a non-admin graphical
client. Mod A used `mod-gui` for its top-left tool; Mod B used an unrelated nested flow and an icon-only button. The
observer recursively enumerated existing `player.gui.children` and `LuaGuiElement.children`, with a 300-node budget and
depth limit of 24. It did not filter by mod ownership or require either producer's container names.

Retained logs and snapshots establish the following:

- All six roots were found. Top-left entries from both mods appeared in the same observation, including the icon-only
  button's sprite and tooltip.
- A button created after startup appeared in the next observation. Its later removal and replacement were also
  reflected. Initial attachment cannot be the only discovery pass.
- Disabled controls remained identifiable. A child with `visible=true` under a hidden parent was correctly distinguished
  from an effectively visible control.
- Twelve logical scroll-pane rows were available, while only three were painted. Render hooks alone would omit valid
  collection contents.
- A relative panel and its button existed with `visible=true` while the matching container window was closed; neither
  was painted. Ancestor visibility alone is insufficient for relative-host applicability.
- Both top-left tools opened new floating windows. Those windows and their next-step buttons appeared in subsequent
  logical/native observations even though `player.opened` remained empty.
- The native shortcut chooser displayed the mod's `Discovery shortcut` entry with its label.
- Passive observation of the game's normal `CustomGuiElement::buildWidgetRecursively` calls, followed by the verified
  `getIndex` getter, linked newly created native controls to logical GUI indices. Clicking the mapped A next-step widget
  produced the authoritative `on_gui_click` for logical index 41 at tick 22669.
- Both peers agreed on the observations and click. A forced full CRC after the final window observation was recorded on
  both peers at tick 23379, with no desynchronization reported during the experiment.

The retained verification script passes 24 assertions over these artifacts. These are fixture assertions, not production
MCP acceptance coverage. Logical enumeration ran in synchronized scenario code on both peers; this experiment does not
independently establish the safety of every equivalent injected client-only Lua read. Native clicks reused captured
event templates, with the construction limits described earlier. The MCP project was neither built nor run.

### Logical controls, native bindings and lifetime

`LuaGuiElement.index` identifies a current logical element for a player; zero is valid, as demonstrated by the root
indices 0 through 5. Names and captions need not be unique. Mod ownership can remain diagnostic metadata and is
unnecessary in normal agent output.

The original live native binding experiment established a route for controls created after observation begins. The
follow-up traversal now also discovers pre-existing native controls. The native
`CustomGuiElement::buildWidgetRecursively` method is a constructor-like operation: the inspected implementation rejects
an already built element. Never call it as a getter to obtain an existing widget. Passive capture of actual game calls
is different from invoking construction again.

A live game-owned enumeration route for existing widgets is now verified through `Widget::callRecursively`. A production
collector still needs robust invalidation on destruction, replacement, player/world changes and process exit, and
complete association with logical metadata where required. The follow-up address-reuse failure demonstrates why this
remains necessary. Logical indices and raw native pointers must not be exposed as durable public references.

One early fixture error retained Frida's transient return-value wrapper as a pointer; a later dispatch then failed.
Copying the returned pointer value fixed that probe error, after which logical-to-native binding and the authoritative
click passed. This is not evidence of a game ABI guarantee. The failure and corrected evidence remain in the experiment
artifacts.

### Compact observations and refresh rules

Use a two-level observation rather than returning every widget:

1. Summarize hosts, active/modal windows and meaningful entry controls. For example: top-left tools with two buttons;
   objectives; quickbar; shortcut bar; open window; relative extensions.
2. Expand a selected host, window or collection within explicit bounds. Flatten uninformative layout wrappers while
   retaining grouping, tabs, selection, validation messages and required fields. Lists and slots should support paging
   or bounded ranges, with total/partial status where known.

Each actionable entry should carry an opaque reference, role, available caption/tooltip or semantic identity, current
value, enabled state and verified interactions. Keep existence, logical visibility, current presentation, relative-host
applicability and modal restrictions distinct. A compact public `available` result can summarize these conditions, but
diagnostics should explain why an entry cannot currently be used. Being outside a scroll viewport is different from
belonging to a closed relative host.

Icon-only buttons require sprite/tooltip metadata; neither a blank caption nor an unrecognized mod name should make an
entry disappear. Do not obtain localized labels through client-only `request_translation`, which can mutate synchronized
counters. Prefer verified game presentation getters or preserve localized-string/prototype identities until a safe
localization path is available.

Refresh after an action's confirmed effect and when an observation is requested. `on_gui_opened` / `on_gui_closed` do
not announce every custom element creation or deletion; the installed API has no general element-created/destroyed event
that can replace discovery. Native hooks can accelerate invalidation, but periodic or request-driven bounded
reconciliation is still needed. An immediate snapshot after submission may show the previous state; the fixture observed
this before the synchronized replacement arrived. Do not replay the mutation because the next page is not yet visible.

The resulting agent interaction is concrete: discover current entries in a known host, choose a returned control
reference, perform one semantic control operation, then discover the resulting page. The follow-up experiment verifies
the common native traversal. Remaining engineering concerns include semantic adapters, binding/lifetime safety and
acceptance coverage, rather than anticipating each mod's UI workflow.

## Sources and local evidence

- [Wube: native widgets and deterministic mod GUI state](https://www.factorio.com/blog/post/fff-305).
- [Wube: GUI event callbacks and end-to-end input tests](https://www.factorio.com/blog/post/fff-366).
- [Custom input prototype](https://lua-api.factorio.com/latest/prototypes/CustomInputPrototype.html).
- [LuaSimulation](https://lua-api.factorio.com/latest/classes/LuaSimulation.html).
- [LuaGuiElement](https://lua-api.factorio.com/latest/classes/LuaGuiElement.html).
- [LuaPlayer: quickbar, selection and player context](https://lua-api.factorio.com/latest/classes/LuaPlayer.html).
- [LuaEntity: identity and selection bounds](https://lua-api.factorio.com/latest/classes/LuaEntity.html).
- [Wube: technology tree presentation](https://direct.factorio.com/blog/post/fff-238).
- [LuaGui: documented custom-GUI roots](https://lua-api.factorio.com/latest/classes/LuaGui.html).
- [ShortcutPrototype: game-generated shortcut buttons](https://lua-api.factorio.com/latest/prototypes/ShortcutPrototype.html).
- [on_lua_shortcut: normal custom-shortcut event](https://lua-api.factorio.com/latest/events.html#on_lua_shortcut).
- [GuiAnchor: relative interface attachment](https://lua-api.factorio.com/latest/concepts/GuiAnchor.html).
- [LuaControl: inherited opened-window and native-window APIs](https://lua-api.factorio.com/latest/classes/LuaControl.html).
- [LuaForce: chart tags](https://lua-api.factorio.com/latest/classes/LuaForce.html).
- [Mod setting prototypes](https://lua-api.factorio.com/latest/prototypes/ModSettingPrototype.html).

The installed 2.0.77 bundled API documentation was used to avoid treating newer online APIs as available locally.

Raw local evidence is retained under `temp/ui-static-research/`, `temp/ui-live-research/`,
`temp/ui-widget-click-research/`, `temp/interaction-components-research/`, `temp/ui-discovery-research/`, and
`temp/ui-tree-verification/`. The discovery directory contains the earlier two-mod fixture and 24-assertion report. The
tree-verification directory contains the follow-up native CMake fixture, disassembly, all-type mod fixture,
complete-tree snapshots, peer logs and 48-assertion `verified-results.json`. These ignored artifacts are supplementary;
this document summarizes their material conclusions and limits.
