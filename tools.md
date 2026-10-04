# Tool reference

Reference for the implemented tools. For installation and connection setup, see [README](README.md).
The schemas advertised by `tools/list` describe arguments and supported property names. This reference also explains
the per-selection restrictions checked at runtime; unsupported combinations and unknown arguments fail.

## Choosing tools

Prefer `ui_read` for UI, `world_overview` for spatial summaries, and `world_query` for precise objects, player state,
inventories and catalogs. Narrow selectors, fields and pages to keep observations relevant. Use `screenshot` when
structured data cannot answer the question, visual verification is needed, or an image is requested; do not capture
after every action. Unsupported custom-painted content may require an image.

Prefer `ui_action` for widgets, including existing offscreen controls; use `set_text` on an available text field before
resorting to slider dragging. Use `input` for world controls and held mouse gestures. Discover current bindings with
`input_bindings`, including modifiers, instead of assuming default keys or mouse meanings. Control IDs name bindings;
they are not directly accepted by `input` or `ui_action`.

Every action reports client dispatch/execution and cleanup. Completion does not confirm server acceptance, delivery
or a gameplay outcome. Network latency and client prediction rollback can delay, change or undo visible effects;
observations describe the state available at the time of the read. Actions do not wait for an outcome or retry to
achieve one. Observe effects before dependent actions, preferably with a focused structured query. Do not blindly
replay a mutation with an uncertain or lost result.
All tools assume the player is not operating the game concurrently. Concurrent player actions can cause errors,
failures or unexpected results; tools do not detect or compensate for that interference.
Missing, null, unavailable and truncated data are distinct; interpret native values in their returned context.
Human-readable text retains the game's language; localization expressions remain untranslated. Use stable IDs and
structured values for programmatic operations, and preserve opaque values when passing them back.

## Attachment and process state

- `status` takes no arguments and never injects. It reports this MCP server's attachment and, when attached, its PID,
  observed game state and pause status. Unknown pause state is `null`.
- `attach` takes exactly one of `pid` (positive integer) or `process_name` (executable name, such as `factorio.exe`).
  It rejects ambiguous process names and initial game loading. Repeated attachment to the same process is supported;
  detach before selecting a different live process.
- `detach` takes no arguments, cancels pending tools across all clients and disconnects without closing Factorio.
  Repeated detach is harmless. A new MCP process requires explicit attach even if another MCP previously injected it.

When Factorio exits, the attachment is cleared. A call racing with exit may return an error; subsequent `status`
reports `attached: false` and `state: exited`. After an explicit detach, state is `detached`.
If detach reports a cleanup error, the attachment is retained so cleanup can be retried. A failed cleanup is not a
successful detach; retry `detach` before selecting another process.
If cleanup only partially completes, functional tools reject the connection until `detach` finishes the remaining
cleanup. A still-running unresponsive game is not treated as an exited process.

Observed states are `main_menu`, `loading`, `in_game`, `paused` or `unknown`; an inactive attachment reports `detached`
or `exited`. State is an observation, not a guarantee that every tool is available. Functional calls recheck their
requirements: world reads need a loaded world/local player, finite input needs running simulation, and screenshots
need rendering. UI availability is reported independently through `ui_ready`.

## UI observation

`ui_read` supports optional bounds, selected subtrees and a node limit. Large results indicate when
they are truncated. Use its text, native types and available prototype identities to build a selector;
node IDs apply only to that snapshot.
For a selected-subtree read, optional property budgets apply only to the returned subtrees. Unrelated widgets do not
consume their dropdown-option or icon-reference capacity. Selector discovery still requires a complete bounded tree; selecting
a subtree does not bypass the node limit or make an incomplete search safe.
The returned tree and properties describe native observations. MCP does not classify windows by purpose or infer
gameplay meaning from their hierarchy or values; interpreting them and choosing actions is the agent's responsibility.
`visible`, `render_enabled` and `hidden_by_search` report the widget's own flags, with
`visibility_basis: "own_widget_flags"`. Parent state is not propagated to children. Interpret these flags alongside
the returned parent relationships; they do not establish effective visibility, clipping or occlusion. Offscreen options
can still be directly selected. When metadata is unavailable these fields are omitted and`visibility_unavailable_reason`
explains why. Hidden widgets may retain stale property values. Visibility does not implicitly filter reads or actions.
Bounds describe widget geometry, including offscreen content; they do not establish visibility, clipping or whether
another window covers the widget. `enabled` is the widget's own state; actions also check its ancestors and the active
modal UI.
When available, `flagged_for_destruction` reports the widget's own native destruction flag.
Defaults are `bounds: false` and `max_nodes: 4096` (allowed range 1–4096). Native containers are preserved without
inferred roles or automatic layout pruning. Output is postorder: children precede parents. Parent IDs
refer to the same snapshot; a missing parent can mean a root, a selected subtree root or an omitted truncated ancestor.
The current snapshot limit is 4096 widgets. Selector reads and actions reject incomplete searches instead of choosing
a potentially ambiguous target. Truncated text and type names are marked separately.
An omitted `text` means an observed empty string. An unsupported text accessor instead reports `text: null`, with
`text_unavailable_reason` when available; it must not be treated as an empty string.

An abridged illustrative snapshot shape is:

```json
{
  "frame": 42,
  "state": "in_game",
  "truncated": false,
  "order": "postorder",
  "identity_scope": "snapshot",
  "visibility_basis": "own_widget_flags",
  "nodes": [
    {
      "id": 0,
      "depth": 1,
      "parent": 1,
      "type": "agui::Switch",
      "enabled": true,
      "visible": true,
      "properties": {
        "switch": {
          "state_value": 1,
          "state": "right",
          "allow_none": false
        }
      }
    },
    {
      "id": 1,
      "depth": 0,
      "type": "agui::Window",
      "enabled": true,
      "visible": false
    }
  ]
}
```

The child retains its own `visible: true` even when the parent reports false. IDs and the example state value are
observations, not reusable selectors or constants to assume in later snapshots.

### Control values

Supported controls also expose `properties`: `check_state` for checkboxes/radio buttons, `toggled` for toggle buttons,
`selected_index` for dropdowns, and slider `value`, `minimum`, `maximum` and `step`. Dropdown indices are zero-based;
null means no selection. Missing properties are not false or empty values. When property decoding is unavailable,
the snapshot includes a reason and retains the basic tree.
`check_state` is the original native enum integer on both platforms, preserving every state without converting it
to a boolean or inventing a name. Do not assume a numeric mapping to state names.

Progress widgets expose `properties.progress` with their raw `value`, lowercase native enum identifier `direction`, and `has_text`
flag. Values are not clamped or converted to a completion percentage. Unknown directions use `unknown_<number>`;
non-finite values become null with `value_non_finite: true`. An unavailable adapter is reported as
`progress_unavailable_reason`. A slot's custom-painted health or durability bar is not necessarily a progress widget.

Dropdown properties also include `options`, each with a zero-based `index` and `text`, plus `options_total` and
`options_truncated`. Empty labels are preserved. At most 64 options per widget and 1024 across a snapshot are returned,
in native list order; each label is limited to 511 UTF-8 bytes with `text_truncated: true` when shortened. List and
label truncation are independent. A selected index may lie outside the returned prefix. If option metadata cannot
be resolved, `options_unavailable_reason` is returned instead of an invented empty list. Reading options does not
open or change the dropdown.

Switch controls expose `properties.switch` with the raw `state_value`, its lowercase native `state` identifier, and `allow_none`.
Known state identifiers include `left`, `right` and `none`; unknown values retain their number and use `unknown_<value>`.
These are positions, not booleans or gameplay modes. Interpret neighboring labels in the returned tree, and use normal
widget clicks to interact. `switch_unavailable_reason` reports unavailable adapter metadata.

### Prototype identity and quality

Widgets that expose a prototype provider also return `properties.prototype`, with `name` (the internal prototype
name) and `native_type` (the prototype's native class). For example, an item slot can return
`{"name":"stone-furnace","native_type":"ItemPrototype"}` and a recipe slot can return `RecipePrototype`.
An absent `prototype` means no supported provider was observed; a present object with null fields means the
provider returned no prototype. This alone does not establish that an inventory is empty. Names are bounded to
255 UTF-8 bytes and native types to 159, with independent `name_truncated` and `type_truncated` flags when shortened.
If provider metadata is unavailable, `slot_identity_unavailable_reason` explains the failure. Do not infer quantity,
quality or durability from the prototype name.

The same provider returns `properties.quality`, using the same name/type/truncation structure. It is the quality
prototype returned by the widget itself. Null fields mean the provider returned no quality; an absent property means
no supported provider was observed. Base prototype and quality are independent observations, including for empty
slots. This quality reference alone does not encode a comparison condition.

Supported item-filter providers additionally expose `properties.quality_condition`: the native `quality_index`,
`comparison_value` and `comparison`, the game's original comparison string (for example `≥`). Unknown enum values
retain their number and use `unknown_<value>` as the string. The condition's own `quality_name` is resolved from the current prototype
registry;
`quality_lookup` distinguishes `present`, `null` and `index_out_of_range`. Names are limited to 255 UTF-8 bytes with
`quality_name_truncated` when shortened. Indices are native data, not stable cross-session IDs or action handles.
The separate `quality` property preserves its provider's result: that provider may return null for a non-equality
condition even when `quality_condition.quality_name` is present. Zero indices are preserved without inventing a default
quality or an "any" state. An absent condition means that no supported provider was observed, not equality.
`quality_condition_unavailable_reason` reports unavailable adapter metadata. This covers the common item-filter
provider used by inventory and other item slots; it does not claim every entity/signal filter or custom painter.

### Item state and image references

Supported element providers expose `properties.element`. A returned `ItemStack` has its raw `count` and an `item`
object or null; direct item providers return the item object itself. Item objects include native type and raw `health`,
plus `durability_left` or `magazine_left` when those native fields apply. Values are not clamped or converted into bar
percentages. A non-finite field is null with `<field>_non_finite: true`. Native type truncation uses `type_truncated`.
Null `element` means a supported provider returned no object; an absent property means no supported provider was
observed. A stack's null `item` does not imply any health, durability or ammunition default. The observation never
creates an item object to fill in missing data. `element_unavailable_reason` reports missing adapter metadata.

Supported icon buttons expose `properties.icons` with independent `normal`, `hovered` and `disabled` values.
Each non-null value contains an opaque `reference` and `identity_scope: "snapshot"`; equal references identify the
same native resource within this response. They are not reusable action handles or file-download endpoints.
Null means no referenced icon. An omitted reference because of the 512-resource bound has `reference: null` and
`truncated: true`. The adapter preserves the widget's own references without choosing a rendered state or substituting
one icon for another. An absent `icons` property means no supported component was observed; `icons_unavailable_reason`
reports missing adapter metadata. Image pixels, filenames, crops, colors and render layers are not reconstructed.
Use `screenshot` when missing visual information is needed.

For existing script GUI elements, `world_query` object inspection also reads the game's original `SpritePath` values,
such as `item/iron-plate`. Follow `player.gui.screen` (or another existing GUI root) by element name or index, then
request `sprite`, `hovered_sprite` and `clicked_sprite` where the element supports them. Empty configured strings
remain empty strings; they are not filled from a rendered fallback. `clicked_sprite` is a Lua configuration field,
not the native icon button's `disabled` reference. These observations do not provide a name for every native UI image
or correlate Lua elements with snapshot-local widget IDs.

```json
{
  "selection": {
    "kind": "inspect",
    "target": {"kind": "player"},
    "path": [
      {"property": "gui"},
      {"property": "screen"},
      {"index": "my_frame"},
      {"index": "my_sprite_button"}
    ]
  },
  "fields": ["sprite", "hovered_sprite", "clicked_sprite"]
}
```

Supported numeric overlays return `properties.number`: `draw_requested`, `value`, and, when drawing is requested,
`show_zero`, `unknown` and `infinite`. `value` is the raw numeric value before display formatting; its meaning follows
the widget, such as stack quantity or a recipe's craftable count. A suppressed, unknown or infinite number has a null
value. Non-finite numeric results also return null with `value_non_finite: true`. An absent `number` means that no
supported number interface was found; `number_unavailable_reason` reports unavailable adapter metadata.
`draw_requested` is the component's number-drawing preference, not effective visibility. Hidden components may hold
stale cached numbers; an inactive quickbar entry is not an inventory query. Custom-painted bars are not standalone
widgets; element properties expose existing object fields, not reconstructed rendering or implicit item defaults.

## UI selectors

Use the same selector structure for `ui_read` and `ui_action`. Reads return every matched subtree; actions require one
eligible target. `matched` marks directly selected nodes; descendants are included without becoming action handles.
`selector_nodes_omitted` counts nodes excluded by selection, not data lost to truncation.

Each selector has 1–16 steps. Each step requires `axis` (`child` or `descendant`) and `match`, whose optional predicates
are `native_type`, `text`, `enabled`, `visible` and `prototype`. A step's `position` selects within each preceding
context in preorder (parent before descendants), independently of the snapshot's postorder output.
`visible` accepts a boolean; widgets with unknown visibility match neither value.
Type and text predicates must contain fewer than 160 and 1024 UTF-8 bytes respectively, with no null characters.
`prototype` takes a required nonempty `name` and optional nonempty `native_type`, with limits of 255 and 159 UTF-8
bytes respectively and no null characters. For example, `match: {native_type: "InventoryGuiSlot", prototype:
{name: "stone-furnace"}}` matches that identity in a live slot. All predicates are exact matches; truncated identity
fields cannot match. Add ancestor or position constraints when the same prototype appears in several widgets.
Quality is not currently a selector predicate.
Overlapping contexts return each target once. Very broad repeated descendant steps can exceed the selector work bound;
use more specific ancestor predicates when that happens.

## UI actions

To select an option, click the dropdown, read the expanded tree and click the option's live widget selector. Existing
options can be clicked outside the visible scroll region; scrolling is not required. Expanding a long list also makes
its option widgets discoverable when the closed dropdown's bounded `options` prefix was truncated. Normal tree limits
still apply, and a widget that has not yet been created cannot be selected.

For example, to click a button whose displayed text is `Settings`:

```json
{
  "action": "click",
  "selector": {
    "path": [
      {
        "axis": "descendant",
        "match": {
          "native_type": "agui::TextButton",
          "text": "Settings"
        }
      }
    ]
  }
}
```

Use the text actually returned by your game, including its language. Add ancestor steps or a one-based `position` to
distinguish repeated controls. `click` supports left, right and middle buttons, optional modifiers and a relative
point within the widget. Click dispatch includes the corresponding button/modifier state, so controls that consult
the game's bindings can respond normally. It does not require the widget to be under the OS pointer.
Each click includes release; it does not leave a held gesture for the next call.
`set_text` takes a `text` value and respects the control's editing restrictions.
When a slider has an editable numeric field, prefer selecting that field and using `set_text` for precise entry.
The agent chooses the field from the observed UI; MCP does not infer a slider-to-field relationship or redirect actions.
If direct widget operations cannot express an interaction, viewport-positioned mouse input through `input` is a fallback
in a normally running world. It follows current geometry and hit testing, so observe the UI before choosing coordinates
and verify the result afterwards. `input` requires simulation ticks and is unavailable in menus or paused worlds.
Click modifiers are `alt`, `control` and `shift`; `position: {x, y}` uses fractions in 0–1, defaulting to the center.
Text replacement accepts up to 1024 Unicode scalar values, including line feeds. Tabs, carriage returns, nulls and
malformed surrogate pairs are rejected. The widget's own rules can filter or shorten the replacement.
`press_key` takes an uppercase key name from `input_bindings`, such as `TAB`, `ESCAPE` or `RETURN`, and optional
modifiers. It sends a complete press/release gesture, including in menus and paused games. Omit `selector` to use
current focus, or specify it to focus a widget first. Use `set_text` for character text; key gestures do not type text
or hold a key across simulation ticks.
Selector-based actions require exactly one enabled target within the active modal UI. A successful call returns
`{"dispatch":"completed"}`. Check the result with `ui_read` or `screenshot`:
successful dispatch does not guarantee a particular game effect.
In multiplayer, observe the changed UI before issuing a dependent action: a response can precede synchronized updates.

## Input bindings

Use `input_bindings` with `ids`, such as `["build", "confirm-gui"]`, or `search` to find controls. Results include mod
controls and linked binding sources for the primary and secondary keyboard/mouse slots, with `offset`/`limit`
pagination (64 entries by default). Empty binding slots are omitted; linked controls also report their effective
bindings. Snapshots contain up to 1024 controls and report whether
discovery was complete. Registration and a configured binding do not imply that an
action is permitted in the current game state. Control IDs and structured bindings are independent of the display
language; this tool does not return localized control names or descriptions. It only reads bindings and does not send
input.
`offset` defaults to 0 (range 0–1024); `limit` accepts 1–1024. `ids` accepts at most 1024 exact control IDs; `search`
matches control IDs case-insensitively. Every page is a new sorted snapshot, so binding changes between pages may change
the results. Inspect truncation before assuming a control is absent.

`has_binding` counts bindings of type `Keyboard`, `MouseButton` or `MouseWheel`, not controller availability. `input`
accepts keyboard and mouse only. Dedicated controller slots are not collected. Unexpected native types in the
keyboard/mouse slots remain observations and do not count toward `has_binding`.

`bindings` contains the control's own keyboard/mouse slots; linked controls also return `effective_bindings` and
`binding_owner`. `native_usage` is the game's raw usage enum integer, not a portable action category; its values may
vary between game builds. Binding `native_code` and `native_modifier_bits` likewise preserve native values. Use the
verified binding type, name and modifier names for input. Unknown types are reported as `Unknown(n)` and unknown
codes omit `name`; neither authorizes sending an unsupported input.
Use the effective binding's type, name and modifiers when constructing input, retaining the original control ID for
discovery. `ids` and `search` can be combined and both filters must match. Missing requested IDs appear in
`missing_ids` only for a complete snapshot; incomplete discovery uses `unobserved_ids` instead.

## Input timelines

`input` accepts `timeline` and optional `stop_previous` (default `false`). Each array entry is an independent action
with inclusive relative ticks. Use `input_bindings` for physical key names and add modifier keys as separate entries.
Tick `0` is the first eligible local-player input evaluation after the task is admitted, meaning start as soon as
possible. It is not the time the agent sends the request, a wall-clock deadline or an authoritative server tick.

```json
{
  "timeline": [
    {
      "device": "mouse",
      "motion": {
        "space": "viewport",
        "from": {"x": 100, "y": 200},
        "to": {"x": 200, "y": 200}
      },
      "tick": "0-100"
    },
    {"device": "mouse", "button": "left", "tick": "0-200"},
    {"device": "keyboard", "key": "R", "tick": "100"},
    {
      "device": "mouse",
      "motion": {
        "space": "viewport",
        "from": {"x": 200, "y": 200},
        "to": {"x": 200, "y": 300}
      },
      "tick": "101-200"
    }
  ]
}
```

This holds the left button while moving along two paths, with a one-tick R press between them. The first motion
entry precedes the button entry so the initial pointer position is dispatched before the press. Within each tick,
entries dispatch in array order, with intervals in their supplied order; the last active pointer position wins.
All scheduled presses/moves run before the local input evaluation, and interval-ending buttons release after it.

`tick: "0-100"` includes 101 evaluations. A single value such as `"100"` occupies one evaluation. Multiple segments
such as `"100-200,201,300-400"` stay separate; each interval owns and closes its hold, including adjacent intervals.
Each entry permits at most 64 intervals, with endpoints in `0..4294967295`. Intervals may overlap or repeat, and
their supplied order is preserved. Intervals within an entry and different entries may repeat the same physical
key or button. A shared button stays pressed until its last active owner ends; overlapping holds do not generate
extra down events. When no other holder remains, adjacent intervals release and press again.
Gaps before or between actions still advance the timeline.

Each entry specifies exactly one action:

- Keyboard `key`: an uppercase physical key name from `input_bindings`.
- Mouse `button`: `left`, `right`, `middle`, `button_4` or `button_5`.
- Mouse `position`: `{space, x, y}` applied on each active tick.
- Mouse `motion`: `{space, from: {x, y}, to: {x, y}}`.
- Mouse `wheel`: `up` or `down`, sent once at the start of each interval, with no release event.

For timed motion, each interval restarts the straight path, including both endpoints. A one-tick interval uses `to`.
Viewport paths round interpolated positions to the nearest integer pixel, with half ties toward the greater value.
World paths preserve fractional coordinates unless `snap: "tile_center"` is supplied inside `motion` or `position`.
Snapping uses `floor(x)+0.5, floor(y)+0.5`, including for negative coordinates, and snapped motion visits grid centers.
World coordinates use the same tile units as ordinary positions returned by `world_query` and `world_overview`.
They refer to the currently viewed surface, which can differ from the character's physical surface.
The current game view projects each world position to the nearest representable viewport pixel on that tick;
offscreen world positions fail and release held input. The tool does not move the camera.

For long paths with point-by-point dwell, replace `tick` with `per_point_ticks` and optional `start_tick` (default 0):

```json
{
  "timeline": [
    {
      "device": "mouse",
      "motion": {
        "space": "world",
        "snap": "tile_center",
        "from": {"x": 10, "y": 20},
        "to": {"x": 20, "y": 20}
      },
      "start_tick": 0,
      "per_point_ticks": 1
    },
    {"device": "mouse", "button": "left", "tick": "0-10"}
  ]
}
```

The generated path has `ceil(max(abs(dx),abs(dy)))+1` uniformly spaced points, including endpoints, after any
endpoint snapping. Viewport points are rounded to pixels; tile-center paths are snapped to centers. The example
visits 11 centers from `(10.5,20.5)` to `(20.5,20.5)` and occupies ticks `0-10`. Each point is held for the specified
number of evaluations. This mode is available only for motion and cannot be combined with `tick`; its final tick
must fit the same endpoint bound. Dwell counts client input evaluations, without guaranteeing placement, selection
or server effects. Use several entries for complex paths or varying speeds.

Viewport coordinates are nonnegative integer game-content pixels from the top-left, not desktop pixels. Choose
positions within the viewport; viewport coordinates are not clamped. Input changes the game's cursor without OS
input or focus. For UI actions, a same-tick move and press can precede hover processing; use an earlier position
entry or prefer `ui_action`. Wheel routing over UI is not reliable in all tested states.

A request permits at most 256 entries. Empty `timeline` completes immediately; with `stop_previous: true`, it cancels
and cleans up the previous task without starting another. Nonempty timelines require a running world.
Only one task may run in the attached game; busy calls fail unless `stop_previous` is true. Replacement validates
arguments and context before aborting the old task. Other observation and UI tools can run while input is pending.
Pause, unload, detach or cancellation stops further work and releases MCP-held buttons cooperatively. Original
player-held state and concurrent player operation are outside the contract.

Results contain `status` (`completed` or `aborted`), `completed_entries`, `evaluated_ticks` and an optional abort
`reason`. An entry is complete after its last interval and required release. Evaluations include timeline gaps.
These describe client execution and cleanup, never server acceptance or gameplay fulfillment. Client prediction,
rollback and network latency can alter observed effects. Explicit MCP request cancellation may suppress that
caller's response; detach and replacement return abort progress to a still-live original caller. If cleanup reports
an error, use `detach` to retry it before starting further work.

## World overviews

`world_overview` with no arguments summarizes the current native game viewport. It works in normal and remote views,
including while research or another UI covers the world. It returns the viewport's client-content pixel dimensions,
map bounds and surface index, plus the local player's controller and physical context. UI occlusion is explicitly
`not_evaluated`; world data may be hidden from the player and is not remembered map-chart content.

The default `detail: "grid"` aggregates cells. `detail: "entities"` returns individual objects with the same
`attributes`, `read_status`, `details` and `detail_status` as entity queries. It accepts `fields` and
`include: ["recipe", "fluids", "filters"]`; `entity_limit` then applies to the entire area. Related objects stay
references. This provides selected configuration information without reproducing the game's ALT overlay or inferring
what should be drawn. Grid mode rejects `fields`/`include`; entity mode rejects `cell_size`.

Grid grouping defaults to `group_by: ["name", "type", "quality"]`. Choose up to eight distinct entity fields;
`group_by: []` produces one group per cell. Equal projected values group together regardless of table key order.
Missing and failed grouping reads remain distinct in `read_status`.

Numeric aggregation is opt-in, with up to eight distinct `{operation,field}` requests. Operations are `sum`, `min`
and `max`; MCP applies the same calculation to any requested field without selecting rules by entity type.
For example, `aggregates: [{"operation":"sum","field":"amount"}]` sums numeric observed amounts. Each result retains
`operation`, `field`, `numeric_values`, `nil_values`, `read_errors`, `non_numeric_values` and `non_finite_values`.
Excluded values do not enter the calculation. `value` is absent when no numeric values were observed; an unrepresentable
calculated value is omitted with `value_non_finite:true`. These exclusions and scan truncation prevent an aggregate
from being treated as a complete total. No field is summed automatically. Grouping and aggregates are grid-only.

Both modes accept `name` and `type`, each a string or up to 64 distinct strings. Native types match mod entities too.
Entries in one list are OR; name and type conditions combine with AND. No category is expanded automatically:
`transport-belt`, `underground-belt` and `splitter` are separate native types. For example:

```json
{
  "detail": "entities",
  "type": [
    "transport-belt",
    "underground-belt",
    "splitter",
    "mining-drill"
  ],
  "fields": [
    "name",
    "type",
    "position"
  ],
  "include": [
    "recipe",
    "filters"
  ]
}
```

The following grid-specific details apply when `detail` is omitted or `grid`. To inspect another area without moving the
camera:

```json
{
  "area": {
    "left_top": {
      "x": -16,
      "y": -16
    },
    "right_bottom": {
      "x": 16,
      "y": 16
    }
  },
  "cell_size": 4,
  "entity_limit": 64
}
```

An explicit area may also specify `surface` by name or positive index; otherwise it uses the current controller's
surface. Coordinates must be finite and within ±1,000,000 tiles.
Areas are limited to 4096 tiles per side. If a greatly zoomed-out viewport exceeds that bound, select an explicit area.
`cell_size` is an integer from 1 to 4096 tiles; it defaults to at least 32, enlarged as needed to fit within 16 rows and
16 columns. Explicit granularity must produce at most 256 cells. Partial edge cells retain their actual bounds.

The row-major `objects` array contains cells with zero-based `row`/`column`, map `area`, `entity_groups`, a center
`tile_sample`, and `coverage`. Each group contains the original grouping values in `attributes`, their `read_status`, `count` and requested `aggregates`. Spatial discovery uses collision candidates, then assigns only entities whose centers lie within the
cell's half-open bounds. Groups sort by a structural key of the original grouping values and read statuses. The center tile is one sample, not the entire cell's
terrain distribution. Coverage reports counts of intersecting chunks that are generated, charted and currently visible;
the sample's availability remains explicit in ungenerated terrain. Reads do not generate chunks.

`entity_limit` defaults to 64 and permits 1–512 candidates per cell. A request examines at most 4096 collision
candidates in total, including lookahead used to detect truncation. `entity_scan` is `complete`, `partial` or
`not_scanned`; per-cell and overall `truncated` flags identify incomplete enumeration. Group counts and numeric aggregates in a
partial cell cover only observed members. Use smaller areas or finer cells to inspect omitted data. A cell may count a boundary
candidate against the work budget even if its center belongs to another cell.

Viewport coordinates use the game's conversion, including camera offsets and fixed-point rounding. `viewport.area`
describes pixel edges `(0,0)` through `(width,height)`; it may differ from an explicit queried `area`. If a native
viewport is unavailable, explicit-area queries still run and return its availability reason. Default-viewport queries
fail instead of inventing camera state. Use `world_query` for individual objects and additional properties.

## World queries

`world_query` reads a loaded world, including while another UI window is open. For example:

```json
{
  "selection": {
    "kind": "entities",
    "position": {
      "x": 0,
      "y": 0
    },
    "radius": 16
  },
  "fields": [
    "name",
    "type",
    "position",
    "quality",
    "health"
  ],
  "limit": 64
}
```

Use `kind: "tiles"` for tiles or `kind: "player"` for the injected client's own player. Spatial selections can instead
use `area: {left_top: {x, y}, right_bottom: {x, y}}`; entities can also filter by a current-world `unit_number`.
Combine that number with a position or area for entities the game does not index directly. A number alone uses the
game's limited unit-number index; failed lookup is an error because it does not prove the entity is absent. Spatial
unit-number resolution considers at most 4096 candidates and rejects an incomplete search; narrow the area if needed.
Spatial selections also accept exact `name` and `type` filters for entities, each as one string or a list of 1–64
distinct strings. Entries within a list are OR, and name/type conditions combine with AND. For example,
`"type":["transport-belt","mining-drill"]` selects either native type, including mod prototypes. Underground belts
and splitters have their own native types. To read belts, underground belts, splitters and mining drills in one area:

```json
{
  "selection": {
    "kind": "entities",
    "area": {
      "left_top": {
        "x": 0,
        "y": 0
      },
      "right_bottom": {
        "x": 32,
        "y": 32
      }
    },
    "type": [
      "transport-belt",
      "underground-belt",
      "splitter",
      "mining-drill"
    ]
  },
  "fields": [
    "name",
    "type",
    "position"
  ],
  "limit": 128
}
```

Filters apply before the result limit. Check truncation and narrow the area when necessary; a bounded response does
not promise every matching entity in a dense area. Each filter string is nonblank, at most 256 characters and contains
no null characters. Coordinates must be finite
and within ±1,000,000 tiles. The top-level `surface` accepts a nonblank name or positive index and defaults to the
current controller's surface. Results include the observation tick, controller/physical
positions, truncation and per-property nil/error status. Entity point/area queries use collision geometry; radius
queries use entity centers. Tile queries round to containing/intersecting cells.
These are **live world reads**, which may include hidden entities; chunk visibility/charting flags distinguish them
from what the player can currently see. Results are not remembered map-chart data. Queries do not generate chunks.
The result limit is 512 objects, the maximum area is 128 x 128 tiles, and the maximum radius is 64 tiles.
`limit` defaults to 128. `fields` accepts 1–32 distinct supported API property names; `tools/list` advertises the
allowed
names for each selection kind. Related objects are shallow references; use the selections below to inspect inventories
and prototypes. Entity order is unspecified; tile order is row-major. `candidates_observed` is not an entity total when
the
result is truncated. Use the containing world's identity scope when retaining entity unit numbers across calls.

### Entity configuration and related objects

Entity and character/vehicle selections accept `include` with any distinct subset of `recipe`, `fluids` and `filters`.
These return separate `details` entries with per-entry nil/error information in `detail_status`:

- `recipe`: configured recipe and quality references. A missing recipe is explicit; it does not imply craftability.
- `fluids`: native fluid-name to amount dictionary. Use object inspection of `fluidbox` for temperature or connections.
- `filters`: native slot count, up to 128 indexed filters, mode flags and per-property/per-slot errors. Unsupported
  filters/modes are not inferred from the entity's name.

```json
{
  "selection": {
    "kind": "entities",
    "position": {
      "x": 10,
      "y": 20
    }
  },
  "fields": [
    "name",
    "status",
    "crafting_progress"
  ],
  "include": [
    "recipe",
    "fluids",
    "filters"
  ]
}
```

For deeper reads, use `selection.kind: "inspect"` with a `target`. Targets include `game`, ordinary entity/player/
character/vehicle/physical_vehicle/force selectors, `surface` with an optional name, `planet`/`recipe`/`technology`
with a name, or `prototype` with catalog `type` and `name`. Entity targets must resolve exactly one entity. Optional
`player` selects player references or the force used for a recipe/technology. `game` provides roots such as players,
surfaces and forces. Inspection requires the selected installation's `doc-html/runtime-api.json`.

`mode` is one of:

- `members`: discover readable attributes and admitted query methods, including shipped API descriptions, parameter
  and return metadata, subclass restrictions and index/length capabilities. Availability in metadata does not mean
  a property applies to the current concrete object.
- `values` (default): read specified `fields`, or a page of all readable attributes. Native errors and nil values
  remain explicit. At most 64 distinct field names may be selected.
- `entries`: page a returned table or indexable collection. Each entry has its native `key`, bounded value preview,
  and any read status. Keys sort by native scalar type/value; array-like objects use one-based indices.

Use `selection.path` to follow up to 12 steps from a freshly resolved target. Each step is exactly
`{"property":"name"}`, `{"index":1}` (or a string key), or
`{"method":"get_recipe","arguments":[],"result":2}`. Methods come from a restricted passive-query set; arbitrary
Lua, mutation methods and insertion-capacity probes are not accepted. `arguments` contains up to eight JSON arguments;
`result` selects one of up to eight return positions and defaults to 1. Omit trailing optional arguments.

```json
{
  "selection": {
    "kind": "inspect",
    "target": {
      "kind": "entities",
      "unit_number": 123
    }
  },
  "mode": "members",
  "limit": 32
}
```

```json
{
  "selection": {
    "kind": "inspect",
    "target": {
      "kind": "entities",
      "unit_number": 123
    },
    "path": [
      {
        "method": "get_recipe"
      }
    ]
  },
  "fields": [
    "name",
    "ingredients",
    "products",
    "enabled"
  ]
}
```

```json
{
  "selection": {
    "kind": "inspect",
    "target": {
      "kind": "entities",
      "unit_number": 123
    },
    "path": [
      {
        "property": "fluidbox"
      }
    ]
  },
  "mode": "entries"
}
```

All inspection modes accept `offset` (0–65536) and `limit` (1–256, default 64), and report `total`, `truncated` and
`next_offset` where applicable. Properties returning native objects remain references: continue the path to inspect
them. Plain tables have bounded previews (depth 3, 64 entries each, 4096 total values); omitted/partial previews and
overlong strings are explicit. Collection scans stop at 65536 keys; select an index directly for larger collections.
This interface exposes supported native data, not arbitrary mod-private state, and does not compute game eligibility.

For script GUI elements exposed to the selected Lua context, prefer this inspection path for their API properties.
For example, a named radio button inside a named screen frame can be read with:

```json
{
  "selection": {
    "kind": "inspect",
    "target": {"kind": "player"},
    "path": [
      {"property": "gui"},
      {"property": "screen"},
      {"index": "frame_name"},
      {"index": "radio_name"}
    ]
  },
  "fields": ["name", "type", "state"]
}
```

These roots expose script GUI elements, not the complete native interface tree. Use `ui_read` for that tree.

### Multiplayer players

`selection: {"kind":"players"}` enumerates current-world players, including disconnected players. Optional
`connected`, `names` and `indices` filters combine with AND; entries within each list are OR. Results sort by player
index and support `offset` and `limit` (default 64, maximum 128). Names and indices must be distinct; names accept up
to 64 entries and indices up to 128. `fields` uses the same set as a single player query.

```json
{
  "selection": {
    "kind": "players",
    "connected": true
  },
  "fields": [
    "index",
    "name",
    "position",
    "surface",
    "physical_position",
    "physical_surface",
    "character"
  ]
}
```

Specify `player` by name or positive API index for `player`, `character`, `vehicle`, `physical_vehicle` and `force`
selections, and for these inventory owners. Omission still selects the injected client's local player. The shared
`player` envelope always describes the local observer, even when the selected object belongs to someone else.
Surface references include their native planet relation or explicit absence; a surface is not assumed to be a planet.
Direction and health can be read from the selected player's character. Other players' supported inventories are
direct reads; unavailable controllers/references retain errors or nil rather than substituting the local player.

```json
{
  "selection": {
    "kind": "player",
    "player": "Alice"
  },
  "fields": [
    "name",
    "connected",
    "position",
    "surface",
    "physical_position",
    "physical_surface"
  ]
}
```

```json
{
  "selection": {
    "kind": "inventory",
    "owner": {
      "kind": "character",
      "player": "Alice"
    },
    "inventory": "main"
  },
  "limit": 32
}
```

### Player context

Use `world_query` with `{"selection":{"kind":"player"}}` for current held-stack identity, held ghost, held blueprint
record, cursor temporariness, original hand location and crafting queue. The shared `player` envelope also includes
controller type, current `position`/`surface` and
`physical_position`/`physical_surface`; retain both pairs when using remote view. These are native API values, not
MCP predictions about which actions are allowed. Select fewer properties when only one fact is needed:

```json
{
  "selection": {
    "kind": "player"
  },
  "fields": [
    "cursor_stack",
    "cursor_ghost",
    "cursor_record",
    "cursor_stack_temporary",
    "hand_location"
  ]
}
```

An occupied `cursor_stack` includes name, count and quality. An empty stack reports `valid_for_read: false`; a missing
stack or ghost is reported in `read_status` as `nil`. These cases are distinct. A blueprint's stack identity does not
describe its blueprint contents. Optional player fields also include `selected`, `character`, `crafting_queue_size`
and the native reach/build distances; those distances alone do not determine action validity.
`physical_controller_type`, `vehicle`, `physical_vehicle` and `driving` are also available as player fields.

`cursor_record` is a separate blueprint-library reference; an empty stack alone does not mean an empty cursor.
`cursor_stack_temporary` is the native flag for a stack discarded on clearing the cursor. `hand_location` preserves
the original native inventory enum and one-based slot, not a newly inferred inventory location. Optional
`blueprint_to_setup` identifies the stack being configured; it is not an import-progress field.

Inspect the held stack directly for condition, label and type-specific properties. Unsupported property reads stay
in `read_status`; durability or ammunition is not meaningful for every item:

```json
{
  "selection": {
    "kind": "inspect",
    "target": {
      "kind": "player"
    },
    "path": [
      {
        "property": "cursor_stack"
      }
    ]
  },
  "fields": [
    "valid_for_read",
    "name",
    "count",
    "quality",
    "health",
    "durability",
    "ammo",
    "label",
    "is_blueprint",
    "is_blueprint_book"
  ]
}
```

Use the same path with `cursor_record` and `mode:"members"` to discover record properties such as
`is_blueprint_preview`, `type` and snapping settings. A record may initially be a preview; content reads can return
the game's preview-read error until it becomes available. The admitted `is_blueprint_setup` and
`get_blueprint_entity_count` methods can inspect a supported blueprint stack or record without expanding its entities.
For example, append `{"method":"get_blueprint_entity_count"}` after the cursor property path step.

Local `character`, `vehicle` and `physical_vehicle` selections follow the corresponding player references without
coordinates. They use entity fields, including `health`, `max_health`, `speed`, `selected_gun_index` and
`driver_is_gunner`. Missing references return `objects: []` and `availability: "nil"`; no character or vehicle is
invented.
These selections accept `kind` and optional `player`, and cannot override `surface`. Entity visibility flags use that
entity's own
surface.

```json
{
  "selection": {
    "kind": "character"
  },
  "fields": [
    "position",
    "surface",
    "health",
    "max_health",
    "selected_gun_index"
  ]
}
```

Read the main inventory without opening its UI or discovering it first:

```json
{
  "selection": {
    "kind": "inventory"
  },
  "fields": [
    "name",
    "count",
    "quality"
  ],
  "limit": 128
}
```

The actual inventory name and size are returned. Follow `next_offset` if truncated; do not assume a fixed backpack
size or merge stacks of different qualities. Separate calls are separate observations with their own ticks.
The default owner is the current player controller. In remote view its main inventory can be unavailable. To inspect
the attached character's inventory, use `owner: {kind: "character"}`. This follows the native character reference
within the observation rather than resolving a previously observed position. Vehicle owners work the same way:

```json
{
  "selection": {
    "kind": "inventory",
    "owner": {
      "kind": "character"
    }
  },
  "fields": [
    "name",
    "count",
    "quality"
  ]
}
```

### Quickbar

Quickbar slots are filters/references, not inventory storage. Query explicit one-based API indices without opening UI:

```json
{
  "selection": {
    "kind": "quickbar",
    "slots": [
      1,
      2,
      11
    ],
    "screen_pages": [
      1,
      2
    ]
  }
}
```

`objects` contains the requested slot indices and their `attributes.filter`; `active_pages` contains requested screen
indices and `attributes.page`. Missing values and rejected API indices have per-entry `read_status` nil/error values.
Filters retain native item/quality/comparator fields. They do not claim to reproduce blueprint instance contents.
At least one index list is required; slots allow 1–128 distinct indices and screen pages 1–16. Indices are bounded to
1..65536 by the tool, not an assertion that the game supports that many slots/pages. Input order is preserved.
This selection does not accept `fields`, `surface`, `offset` or `limit` and does not infer which rows are visible.

### Inventories

Use `selection: {kind: "inventories"}` to discover the local player's inventories. To inspect an entity, provide `owner`
using an ordinary `entities` selection; it must resolve exactly one current entity. Each inventory reports its actual
name, API index, size, filter support and bar support. A bar is the one-based first restricted slot.
Owners may also select `character`, `vehicle` or `physical_vehicle`. A missing owner is an error. Discover actual names
before reading weapon, ammunition, armor or vehicle inventories. Stack fields include `ammo` and `durability`;
unsupported
properties remain explicit read errors rather than fabricated defaults.

Use `kind: "inventory"` to read slots. `inventory` defaults to `main`, the selected owner's native main inventory.
The default player owner is controller-dependent; it is not an automatic fallback to the physical character.
For other inventories, use a name returned by discovery. Names are checked against the actual inventory: enum numbers
are reused across different entity types and are insufficient to identify the requested inventory.

```json
{
  "selection": {
    "kind": "inventory",
    "owner": {
      "kind": "entities",
      "position": {
        "x": 6.5,
        "y": 0.5
      },
      "unit_number": 30
    },
    "inventory": "chest"
  },
  "offset": 0,
  "limit": 32,
  "fields": [
    "name",
    "count",
    "quality",
    "health",
    "spoil_percent"
  ]
}
```

Slot indices are one-based; `offset` is a zero-based page offset. Empty slots remain present with
`valid_for_read: false`.
Occupied slots expose requested API attributes, including item quality. Slot filters are included when present.
`inventory` metadata describes the whole inventory, while `objects` contains only the requested slots. Discovery does
not accept `fields`; slot reads do. Missing inventories and ambiguous owners are errors. These queries do not insert,
remove, sort or test whether an inventory can accept an item.

### Prototype catalogs and force recipes

Use `selection: {kind: "prototypes", type: "item"}` to list prototype definitions. Supported types are `item`, `recipe`,
`entity`, `fluid`, `technology`, `quality`, `item_group` and `item_subgroup`. `names` accepts 1–64 distinct exact names
for batch reads, each with 1–256 characters; `search` accepts 1–128 characters
and filters internal names by a case-insensitive substring. Neither accepts null characters. Both may be combined.
Unresolved exact names appear in `missing_names`.

```json
{
  "selection": {
    "kind": "recipes",
    "names": [
      "iron-gear-wheel",
      "transport-belt"
    ]
  },
  "fields": [
    "name",
    "enabled",
    "category",
    "energy",
    "ingredients",
    "products"
  ]
}
```

`recipes` reads the local player's force recipes, including their current enabled state. A recipe prototype's `enabled`
attribute describes its default definition instead. Ingredient and product records retain the game's API fields,
including amounts, probabilities and temperature constraints when supplied. Localized names/descriptions are returned
as untranslated `LocalisedString` values; no translation request is sent. Related prototypes and groups are shallow
typed references. `tools/list` advertises each category's allowed properties; unavailable values retain nil/error
status.

Recipe names need not match product names. `product` and `ingredient` select recipes or recipe prototypes containing
at least one exact `{type: "item"|"fluid", name: "..."}` entry. They combine with names/search and with each other:

```json
{
  "selection": {
    "kind": "recipes",
    "product": {
      "type": "item",
      "name": "iron-plate"
    }
  },
  "fields": [
    "name",
    "enabled",
    "ingredients",
    "products"
  ]
}
```

Relation scanning stops with an error after 32768 entries; narrow names/search if needed. This is a raw relation query,
not a crafting plan or feasibility calculation. Prototype `type`, `group` and `subgroup` retain game-defined values:
an item's type may be `item` while its `place_result` entity's type is `mining-drill`. Groups/subgroups can be queried
for labels, ordering, parent and subgroup references. Prototype reads also expose `hidden_in_factoriopedia`,
`factoriopedia_description` and supported `factoriopedia_alternative` references. This is not full encyclopedia parity.

### Force and technology state

`selection: {kind: "force"}` reads the local player's force, including current/previous research, research queue,
progress and whether research is enabled. Its selection accepts optional `player`; use `fields` to choose force
properties.
It returns one force and does not accept `surface`, `offset` or selection filters.
Use `technologies` for current-force technology objects with names/search and normal catalog pagination:

```json
{
  "selection": {
    "kind": "technologies",
    "names": [
      "automation",
      "electronics"
    ]
  },
  "fields": [
    "name",
    "researched",
    "enabled",
    "level",
    "prerequisites",
    "saved_progress"
  ]
}
```

`researched`, `enabled`, current level and saved progress remain separate native properties. Prerequisites/successors
are name-keyed dictionaries of shallow technology references. Prototype defaults, force research state and recipe
availability are distinct; none alone proves that an action can be performed. Research effects can be read from the
related technology prototype. Native nil and per-field errors remain explicit, including an absent current research.

Inventory discovery, inventory slots, prototype catalogs, recipes and technologies default to `limit: 64` and
`offset: 0`. Inventory slots permit up to 512 results; discovery and catalogs permit up to 128. Quickbar and local
player/entity/force selections do not use this pagination contract. Offset ranges from 0 to 65536.
Results report `next_offset` when another page exists, and
catalogs sort by internal name. Each page is a fresh observation, so live inventory edits can shift contents between
pages. Catalog discovery scans at most 65536 definitions; larger catalogs require exact-name batches. A loaded world
and local player are required, including for prototype queries.

## Chat

`chat_read` takes optional `limit` (1–128, default 64), `offset` and `timeout` (nonnegative integer seconds, default 0).
It reads the local
client's retained console without opening chat UI, including received player messages, notifications and mod output.
It is not a global server log, channel filter or lossless event subscription.

Each message has a native update `tick`, zero-based `position_in_tick` within its storage, a continuation `offset`,
`storage` (`game_state` or `local`),
`player_index_raw`, `text`, `raw` and separate truncation flags. `player_index_raw` is the unmodified native unsigned
index, not the one-based Lua player selector; non-player messages can carry a sentinel. No sender/channel is parsed
from text. `text` is existing cached display text, which may be empty or stale. `raw` formats the localization
expression without requesting translation. Each string is bounded to 4095 UTF-8 bytes without splitting a character.

Omit `offset` or use `0` to return the latest requested observations. A positive integer selects the native tick
inclusively: its boundary messages are included so messages sharing a tick are not silently skipped. For exact
continuation, pass the returned `next_offset` or a message `offset`: `{"tick":2412,"counts":[3,1]}` means three
`game_state` records and one `local` record at tick 2412 have already been read. Later ticks start at zero counts.
Follow `has_more` to drain further pages. Separate storage counts also admit a new synchronized message at an
unchanged tick after local messages have been read, including in paused worlds. Reads do not acknowledge or remove
messages, and concurrent readers can use their own offsets.

The watermarks use native ticks and chronological retained-list positions, without process addresses, random
attachment identifiers or MCP observation counters. They survive MCP restarts with unchanged native history.
`offset_basis` is `native_tick_and_storage_position`. These are per-player console watermarks, not server-issued
globally unique message IDs. Different players can receive different history and local notifications; carry offsets
only between observations of the same player history in the same world. A native tick alone is the common boundary
when reading another player's console; its messages may repeat. Identical messages at one tick retain separate
positions instead of being merged by text.

With `timeout` omitted or `0`, the tool returns the next safe-point observation immediately. A positive timeout
waits while the selected history is empty, returning when messages appear or the wait expires. An expired wait
returns an empty `messages` array and `next_offset`. A history-loss notification also returns immediately.
Other tool calls can proceed between observations; detach or process exit aborts the wait. The timeout bounds
waiting for new messages, not completion or cleanup of an already admitted safe-point observation. If rendering
is suspended, even an immediate read can remain pending until its safe point resumes or the call is cancelled.

```json
{"offset": 0, "limit": 64}
```

Use the returned offset on the next call, for example:

```json
{"offset": {"tick": 2412, "counts": [3, 1]}, "timeout": 30, "limit": 64}
```

The adapter captures at most 128 entries from each native storage list per read. `retained_counts` reports the
native list counts; `snapshot_truncated` reports the capture bound. `boundary_truncated` reports a tick group whose
older entries are outside the capture bound; its counts cannot provide complete continuation through that group.
`history_lost` reports observed console replacement, missing boundary positions or an incomplete requested boundary.
It cannot detect every history edit or world change. `missed_between_reads_possible` is always true: messages can
disappear between reads, and the console may merge repeated output. Changing cached translations does not create
a new position. Reset `offset` after changing worlds or players, or after reported history loss. Messages are ordered
by tick, storage and chronological list position, not a claim of global server message chronology.

`chat_send` requires `text`: one nonblank line, at most 4096 UTF-8 bytes, with no NUL or slash commands (including
after leading whitespace). It submits ordinary chat as the local player without opening or overwriting a draft UI.
The result is `{"dispatch":"completed"}`. It does not guarantee server acceptance, delivery or a corresponding read
record; game/mod rules still apply. Do not retry after an uncertain result. Both tools require a loaded world/local
player.

## Screenshots

`screenshot` accepts `action: "capture"` (default) or `action: "cancel"`. Capture returns PNG image content plus its
width, height, UI frame and observed game state. Only one capture can be pending; another capture returns a busy error.
Cancel stops the active capture, waits for cooperative cleanup and returns `{"dispatch":"completed","cancelled":true}`.
It starts no new capture and returns `cancelled:false` when no capture is pending. Explicit MCP request cancellation
also cancels capture. After cancellation finishes, a new capture can be submitted.
It captures Factorio's DirectX output on Windows or OpenGL output in the Linux adapter, including UI and world,
without activating the window. It excludes desktop and Steam overlays. The source field is `factorio_rendered_frame` on
both platforms. Capture can wait for rendering to resume when minimized or suspended.
Capture supports at most 8192 pixels per side, 16,777,216 pixels in total and a 16 MiB encoded PNG; exceeding these
bounds returns an error.

## Completion and cancellation

Actions acknowledge completed dispatch; their gameplay effects depend on the current game state and rules.
Observe the result before submitting a dependent action, especially in multiplayer. Do not replay an action whose
response was lost: inspect current state first.

Tool results carry a JSON object in `structuredContent` and an equivalent MCP text content block; screenshots
additionally carry PNG image content. Rejected or failed tool calls use `isError: true` with `{"error":"description"}`.
An input's structured `status: "aborted"`
instead preserves partial progress. These are distinct from MCP transport/protocol errors.

Tools have no execution deadline. Use explicit MCP cancellation to stop a pending call. HTTP socket closure alone is
not guaranteed to cancel it. Single-phase requests and input admission share one attachment-wide queue; a waiting
screenshot delays later calls until capture or cancellation. `screenshot` with `action:"cancel"` bypasses that queue
to stop the pending capture. An admitted input sequence completes independently while
other calls proceed. `detach` cancels admitted input as well as active and queued requests.
