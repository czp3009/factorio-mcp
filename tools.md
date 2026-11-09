# Tool reference

Reference for the implemented tools. For installation and connection setup, see [README](README.md).
The schemas advertised by `tools/list` describe argument syntax and selection constraints. Discover native properties
and admitted methods with inspection `members`. This reference also explains
the per-selection restrictions checked at runtime; unsupported combinations and unknown arguments fail.

## Choosing tools

Prefer `ui_read` for UI, `world_overview` for spatial summaries, and `world_query` for precise objects, player state,
inventories and catalogs. Narrow selectors, fields and pages to keep observations relevant. Use `screenshot` when
structured data cannot answer the question, visual verification is needed, or an image is requested; do not capture
after every action. Unsupported custom-painted content may require an image.

Prefer `ui_action` for widgets, including existing offscreen controls; use `set_text` on an available text field before
resorting to slider dragging. Use `input` to combine key chords, mouse holds and timed action sequences in one call,
including continuous drags with multiple path segments and turns. Discover current bindings with
`input_bindings`, including modifiers, instead of assuming default keys or mouse meanings. Control IDs name bindings;
they are not directly accepted by `input` or `ui_action`.

Every action reports client dispatch/execution and cleanup. Completion does not confirm server acceptance, delivery
or a gameplay outcome. Network latency and client prediction rollback can delay, change or undo visible effects;
observations describe the state available at the time of the read. Actions do not wait for an outcome or retry to
achieve one. Plan known input sequences together; read results before making decisions that depend on them,
preferably with a focused structured query. Do not blindly
replay a mutation with an uncertain or lost result.
All tools assume the player is not operating the game concurrently. Concurrent player actions can cause errors,
failures or unexpected results; tools do not detect or compensate for that interference.
Missing, null, unavailable and truncated data are distinct; interpret native values in their returned context.
Human-readable text retains the game's language; localization expressions remain untranslated. Use stable IDs and
structured values for programmatic operations, and preserve opaque values when passing them back.

## Query sizes

Explicit query counts use positive 32-bit integers, and collection offsets use nonnegative 32-bit integers.
Input bindings, players, inventory kinds/slots, inspect member catalogs and explicit name/field selections return
all matches by default. Set `limit` to page actual collections. Other defaults are recommended starting sizes: increase `limit`, `entity_limit`,
`cell_limit` for bulk reads. UI trees use live selectors and relative `max_depth`, without a node-count prefix.
Each snapshot is collected at one safe point. Internal transfer chunks do not create additional observations or ticks.
Pages are fresh observations. Use the documented continuation positions; spatial world selections and whole UI trees
have no artificial offsets. Count, subtree, field and text-slice selection happens before observations leave the game
process. Chat transfers retained message metadata for watermark/loss detection, but text only for the selected page
and slice. Screenshot resizing and encoding operate on a complete captured frame in MCP.

Reads support dynamic result sizes, including responses larger than 16 MiB. Owned byte arrays and strings must
fit their signed 32-bit representations; an individual byte array can hold at most 2,147,483,647 bytes.
Strings, final message encoding and available memory impose their own practical limits.
Allocation and representation failures are errors, not silent reductions of the requested count. Native UI labels,
option labels, widget type names and retained chat strings have no separate 16 MiB capture ceiling. Identity fields
retain the bounds documented below. Table previews leave references at the selected depth boundary and at cycles.

Incoming HTTP and stdio JSON-RPC messages have a 16 MiB framing limit, including their envelope. There is no separate
fixed world-query argument buffer. Outgoing responses have no MCP-owned 16 MiB ceiling, but clients may impose their
own limits; the SDK stdio client also limits received frames to 16 MiB. Prefer focused fields, pages and string slices
for large values, or an HTTP client that accepts the result size. JSON escaping, duplicate text/structured result
content and image base64 encoding increase wire size.

Collection limits count entries, not bytes inside each entry's values. Follow property/index paths and use string
slices for large nested text. Slices bound returned text; read cost can still depend on the complete source value.
Paged collections expose `has_more` and `next_offset`. A nonzero offset identifies a skipped prefix;
selected collection/string `truncated` flags concern an unread suffix. Per-field truncation and partial spatial scans
remain independent: `has_more: false` does not establish that every nested value is complete. Spatial world queries
have no continuation and report `selection_complete`; their `truncated` flag also includes nested depth omission.

The following defaults are also the recommended starting limits for agents. Count limits have no smaller
product ceiling than the positive signed 32-bit representation (2,147,483,647); available memory and result encoding
still constrain a call. A finite game collection is not necessarily a small collection, especially with mods.

| Tool or selection | Default / recommended size | Remaining maximum | Pagination | Typical size behavior |
|-------------------|----------------------------|----------------------------|------------|-----------------------|
| `status`, `attach`, `detach` | One state/result | One state/result | No | Constant-size state. |
| `ui_read` | UI depth 65535; GUI table depth 8; selected collection page 64 | Effective tree/table depth 65535; integer and memory representation | Selected options, GUI collections and strings: `offset` / `next_offset`; widget trees: live selectors and relative `max_depth` | Start with `max_depth:1` or 2; `fields:[]` optionally limits contents. No node-list clipping. |
| `input_bindings` | All matching controls | Integer representation | Optional sorted ID `offset` / `limit` | Finite loaded control registry; independent of factory size, but mods add controls. |
| `world_query`: spatial `entities`, area `tiles` | 128 objects | Integer representation | No; refine the area/filters or increase `limit` | Can be very large as the queried area grows. |
| `world_query`: single object/reference or one tile | One selected object | No count parameter; table depth 65535 | No | One object can expose large queues or related collections. |
| `world_query`: `players`, `inventories` | All matching entries | Integer representation | Optional `offset` / `limit` | Current-world players and available inventory kinds. |
| `world_query`: `inventory` | All native slots | Integer representation | Optional native slot `offset` / `limit` | Inventory discovery reports its native size. |
| `world_query`: `prototypes`, `recipes`, `technologies` | All explicit `names`; otherwise 64 entries | Integer representation | Optional `offset` / `next_offset` | Modded catalogs can be large; names sort by internal identifier. |
| `world_query`: `inspect` | All requested values/members; entries 64; slices 4096 UTF-8 bytes; table depth 3 | Integer representation; depth 65535; native/cycle references | Members/entries/slice only: `offset` / `next_offset` | Values retain the selected structure within table depth; referenced objects require paths. |
| `world_query` / entity `world_overview`: `reads` | Ordered explicit paths for each selected root/entry | Request size and integer representation | Each read's explicit collection/text mode has independent `offset` / `next_offset` | One safe point; a path failure preserves sibling reads. No hidden batch-count cap. |
| `world_query`: `quickbar` | Explicit requested locations | No separate location-count cap; game validates each index | Explicit page/slot or screen-page indices | Normally small and fixed for the selected player. |
| `world_overview` | 256 cells; 64 entity candidates per cell or per area in entity mode | Integer page/entity limits | Grid: `start_cell` / `next_cell` | Automatic cells start at 32 tiles and target at most 16 by 16 cells. |
| `chat_read` | Latest 64 messages; optional text slice 4096 bytes | Integer representation | Forward tick/storage watermark; optional `text_slice` | Limited by retained history. Evicted history is unavailable. |
| `ui_action` | One widget action | 16384 Unicode scalar values for `set_text` | No | One dispatch result; selectors have no separate step ceiling. |
| `input` | Explicit timeline | 256 entries; 64 intervals per entry; relative tick at most 4,294,967,295 | No | One execution result, not a collection query. |
| `chat_send` | One explicit message | 4096 UTF-8 bytes | No | One dispatch result. |
| `screenshot` | One next rendered frame | 8192 pixels per side, 16,777,216 total pixels; integer encoded size | No | Optional proportional height reduction. |

Spatial coordinates remain within ±1,000,000 tiles;
query areas have no separate width/height caps. UI selectors, inspection paths and action inputs retain their
documented validation rules. These input constraints are separate from query result-count limits.

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

`ui_read` supports optional bounds, live subtree selectors, relative depth and native/script field projection.
Large results indicate when they are truncated. Use text, native types, prototype identities and script attributes to build a selector;
node IDs apply only to that snapshot.
For a selected read, the selector searches the complete live tree before relative depth bounds returned nodes and
optional properties. Unrelated widgets do not consume the selected projection's property storage. A shallow result
therefore does not prevent locating a deeply nested control. Traversal, selection and property collection occur
in the same observation; a failed or incomplete search never dispatches an action.
The returned tree and properties describe native observations. MCP does not classify windows by purpose or infer
gameplay meaning from their hierarchy or values; interpreting them and choosing actions is the agent's responsibility.
`visible`, `render_enabled` and `hidden_by_search` report the widget's own flags, with
`visibility_basis: "own_widget_flags"`. Parent state is not propagated to children. Interpret these flags alongside
the returned parent relationships; they do not establish effective visibility, clipping or occlusion. Offscreen options
can still be directly selected. When metadata is unavailable these fields are omitted and `visibility_unavailable_reason`
explains why. Hidden widgets may retain stale property values. Visibility does not implicitly filter reads or actions.
Bounds describe widget geometry, including offscreen content; they do not establish visibility, clipping or whether
another window covers the widget. `enabled` is the widget's own state; actions also check its ancestors and the active
modal UI.
When available, `flagged_for_destruction` reports the widget's own native destruction flag.
Defaults are `bounds:false` and effective `max_depth:65535`. Start exploration with `max_depth:1` or 2, then select
a branch. No selector means the actual game UI root. Every selector match starts an independent subtree at depth 0;
overlapping ancestor/descendant matches repeat their subtrees. Empty matches return empty `roots` and `nodes` arrays.
Native containers are preserved without inferred roles or automatic layout pruning. Output is flat preorder:
parents precede children, and `roots` lists each returned root's ID. Parent IDs refer only to this response;
only returned roots omit `parent`. IDs are never accepted as cross-call addresses. Every call resolves its path anew.
`max_depth:0` returns roots only; 1 includes direct children. Omitted or larger depths use 65535. Reaching the hard
fallback is ordinary depth truncation, not an error. At a boundary, `children_truncated:true` means actual native
children exist but were omitted. A boundary leaf has no marker, so increasing depth will not reveal children there.
Top-level `truncated` is true when any returned widget has children omitted by depth. Property/text truncation
remains separately reported. There is no returned-node count limit or node-list prefix truncation.
`sibling_index` is the one-based position among observed native siblings when the parent is known. Append a
`child` selector step with `match: {}` and `position: sibling_index` to a live parent selector to inspect that child.
`gui_depth` separately controls
plain Lua table expansion. Depth-bounded trees still report truncation; selectors resolve over the full live tree.
Selector reads and actions reject incomplete searches instead of choosing a potentially ambiguous target.
Truncated text and type names are marked separately. Empty text is returned as `text: ""`.
An unsupported text accessor instead reports `text: null`, with
`text_unavailable_reason` when available; it must not be treated as an empty string.

Use `fields: []` to explore structure, script identity and each widget's own flags without optional contents.
Omitting `fields` captures all supported paths. The response echoes the requested paths in `fields`.
Select paths directly from the result shape:

- `text`
- `properties.check_state`, `properties.toggled`, `properties.toggle_mode`, `properties.selected_index`
- `properties.value`, `properties.minimum`, `properties.maximum`, `properties.step`
- `properties.options`, `properties.prototype`, `properties.quality`, `properties.number`, `properties.progress`
- `properties.element`, `properties.icons`, `properties.quality_condition`, `properties.switch`, `properties.tooltip`
- `properties.image`, `properties.graph`, `properties.achievement`
- `gui.properties`, `gui.children`

For example, `fields: ["text", "properties.icons", "gui.properties"]` reads labels, image references and the
attributes selected by `gui_fields`. Unrequested paths are omitted, rather than reported as unavailable. Native
text/prototype predicates still read the values needed to resolve a selector. Other optional native properties
are collected only for the bounded selected nodes. Structure-only reads omit GUI child arrays; read
`collection: {"source": "gui", "path": [{"property": "children"}], "limit": 64}` to page those relationships.
Fields are selected before optional native capture, not just removed from the final JSON. A page or text slice can
still require a full source read, and selectors still traverse the live tree. Depth is not a byte budget; wide
levels can contain many nodes. Narrow the selector or optional fields when needed.

An abridged illustrative snapshot shape is:

```json
{
  "frame": 42,
  "state": "in_game",
  "truncated": false,
  "order": "preorder",
  "identity_scope": "snapshot",
  "max_depth": 65535,
  "roots": [0],
  "visibility_basis": "own_widget_flags",
  "nodes": [
    {
      "id": 0,
      "depth": 0,
      "type": "agui::Window",
      "enabled": true,
      "visible": false
    },
    {
      "id": 1,
      "depth": 1,
      "parent": 0,
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
    }
  ]
}
```

The child retains its own `visible: true` even when the parent reports false. IDs and the example state value are
observations, not reusable selectors or constants to assume in later snapshots.

### Existing script UI attributes

When a native widget is registered to an existing `LuaGuiElement`, its `gui` object supplements the native tree:

```json
{
  "index": 9,
  "name": "my_button",
  "type": "sprite-button",
  "parent": 8,
  "children": [],
  "properties": {
    "sprite": "item/iron-plate",
    "tooltip": ["my-mod.help"],
    "tags": {"opaque_key": 12},
    "toggled": true,
    "auto_toggle": false
  },
  "read_status": {}
}
```

`index`, `parent` and `children` retain the game's script element indices, including zero when present. They are
separate from native node IDs and may refer to elements outside the returned native subtree. Script and native
ancestry can differ; neither replaces the other. These indices are observations of existing objects, not durable
action handles. Names and types retain their API values, including empty names and unknown types.

When `gui.properties` is selected, `gui_fields` selects readable `LuaGuiElement` attributes. Defaults are `caption`, `tooltip`, `elem_tooltip`, `tags`,
`style`, `sprite`, `hovered_sprite`, `clicked_sprite`, `toggled`, `auto_toggle`, `number`, `elem_type` and `elem_value`.
Use `[]` for no optional script attributes, or `["*"]` for all readable attributes in the selected installation's
API catalog. The response echoes the expanded `gui_fields`. Unsupported attributes for an element report the game's
error under `read_status`; nil attributes report `status: "nil"`. Missing values are not synthesized.

`gui_depth` is a nonnegative table expansion depth for each attribute value, default 8, with effective maximum 65535. Nonempty tables at the
depth boundary remain `depth_bound` references with `children_truncated:true`; empty tables stay empty. Ancestor
cycles remain explicit `cycle` references. Sharing between separate branches is preserved. Plain table previews
read stored entries without invoking indexing, iteration or length metamethods. `gui.values_truncated:true` marks
depth omission in that widget's attribute values; selected GUI collections also report `depth_truncated` independently
of `has_more`. Lua objects remain shallow references with their existing identity fields; further API inspection is
available through `world_query`. Only the response envelopes' arrays are standardized; attribute table values retain
the game's encoding, repetitions, original language and rich-text/localization expressions. Tags remain opaque and
are not promoted to native flags, action capabilities or gameplay identities. `style` is an existing `LuaStyle`
reference, not reconstructed rendering.

These attributes require a loaded local world. When unavailable,
native observations remain available with `gui_unavailable_reason`. A widget without a registered script element has
no `gui`; many original game windows are entirely native. Script attributes do not expose arbitrary mod-private state.

### Selected collections and text slices

Use `collection` with a selector resolving exactly one widget to read one value instead of a subtree. This mode
does not accept `fields`, `max_depth`, `bounds` or `gui_fields`; `gui_depth` still bounds returned GUI value previews.
`source` is `options`, `graph`, `gui`, `text`, `tooltip_title` or `tooltip_text`. `offset` defaults to 0 and `limit` to 64.
The result contains `native_type`, `source`, observation context and a `collection` envelope.

For native dropdown options, `entries` retains zero-based `key` values and original label strings, with `total`,
`offset`, `has_more`, `truncated` and any `next_offset`. Reading a page does not open the dropdown. For example:

```json
{
  "selector": {"path": [{"axis": "descendant", "match": {"gui": {"name": "options"}}}]},
  "collection": {"source": "options", "offset": 64, "limit": 64}
}
```

To slice one option's label, add `path: [{"index": 64}]` with its zero-based native option index. In that case,
`offset` and `limit` count UTF-8 bytes of that label, and the result uses the string envelope below.

For `source: "graph"`, `path: [{"property":"series"}]` pages original series records, including each retained label
and its values. `path: [{"property":"series"},{"index":0},{"property":"values"}]` pages only that series' numeric
samples. Both use zero-based keys and the same collection envelope as options. An invalid series index is an error;
an offset beyond a collection's end returns no entries. Samples outside the requested slice are not copied or
transferred. These are native array pages, independent of widget-tree depth.

For `source: "gui"`, `path` starts at the matched existing `LuaGuiElement` and accepts property/index steps only,
such as `[{"property":"items"}]` or `[{"property":"tags"},{"index":"opaque_key"}]`. Methods and callbacks are
not invoked. Tables and indexable objects return paged `entries` with original keys and bounded value previews;
unavailable properties retain read status. Use `next_offset` for continuation without changing the path.
Plain-table numeric indices retain zero, negative and fractional keys; native API index operators enforce their
own domains.

String sources return `value`, `value_type: "string"`, `unit: "utf8_bytes"`, `offset`, `total`, `has_more`, `truncated` and any
`next_offset`. This also applies when a GUI path reaches a string. Offsets must start on UTF-8 character boundaries;
limits must fit the next character. Offsets at or beyond the end return an empty value. Text sources read stored
values only and do not invoke dynamic tooltip providers. Missing values report unavailable status.

Every page is a fresh observation. Changing UI, table order or text can affect continuation; no historical widget
or string snapshot is retained between requests. Tree reads use live selectors and relative `max_depth`.

### Control values

Supported controls also expose `properties`: `check_state` for checkboxes/radio buttons, independent stored native
`toggled` and `toggle_mode` flags for buttons,
`selected_index` for dropdowns, and slider `value`, `minimum`, `maximum` and `step`. Dropdown indices are zero-based;
null means no selection. Missing properties are not false or empty values. When property decoding is unavailable,
the snapshot includes a reason and retains the basic tree.
`check_state` is the original native enum integer on both platforms, preserving every state without converting it
to a boolean or inventing a name. Do not assume a numeric mapping to state names.
`toggled` is returned even when `toggle_mode` is false. Script-controlled selection need not use automatic native
button toggling. A script element's `auto_toggle` is retained separately in `gui.properties`.

Progress widgets expose `properties.progress` with their raw `value`, lowercase native enum identifier `direction`, and `has_text`
flag. Values are not clamped or converted to a completion percentage. Unknown directions use `unknown_<number>`;
non-finite values become null with `value_non_finite: true`. An unavailable adapter is reported as
`progress_unavailable_reason`. A slot's custom-painted health or durability bar is not necessarily a progress widget.

Dropdown properties also include `options`, each with a zero-based `index` and `text`, plus `options_total` and
`options_truncated`. Empty labels are preserved. All observed options for each selected widget are returned in native
list order. Reported list and label truncation are independent. A selected index may lie outside a returned page. If option metadata cannot
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
255 UTF-8 bytes, with `name_truncated` when shortened. Native type text is copied in full, subject to representation
and allocation checks; it has no separate small field capacity.
If provider metadata is unavailable, `slot_identity_unavailable_reason` explains the failure. Do not infer quantity,
quality or durability from the prototype name.

Native widgets that directly retain a supported prototype pointer or typed ID also use `properties.prototype`.
Starmap connection widgets expose their connection prototype, and achievement cards expose their achievement
prototype. Select these by the same observed `prototype.name` predicate used for slots. Use the ordinary
`world_query` prototype catalog and requested attributes for connection endpoints, location coordinates or other
prototype details. The UI observation does not infer topology from screen geometry. Missing metadata for these
retained references or card state is reported by `stored_identity_unavailable_reason`.

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

### Graphs and achievement cards

Native `agui::Graph` components expose `properties.graph`: `sample_count`, `minimum_value`, `maximum_value`,
`index_base: 0`, and `series`. Each series retains its zero-based `index`, original `label` and ordered `values`.
Empty and repeated labels and differing sample counts remain unchanged. Values are the stored native floats;
they are not reconstructed from prototypes, pixels or graph formulas. Non-finite numbers use
`{"value_type":"non_finite_number","value":"nan"}`, `"infinity"` or `"-infinity"` instead of invalid JSON numbers.
Axis captions and formatted tick labels remain ordinary child widgets. Their formatting can differ from the raw
sample units; no formatter or tooltip callback is invoked to convert them. An absent graph property means no
supported graph component was observed; `graph_unavailable_reason` reports unavailable adapter metadata.

For example, read a graph subtree through a live selector, then request a sample slice:

```json
{
  "selector": {"path": [{"axis":"descendant","match":{"native_type":"agui::Graph"},"position":1}]},
  "collection": {
    "source":"graph",
    "path":[{"property":"series"},{"index":0},{"property":"values"}],
    "offset":0,
    "limit":16
  }
}
```

Native achievement cards expose `properties.achievement` with `state_type: "AchievementState"`, raw `state_value`,
`shown_state_value` and the card's `in_sidebar` flag. These are the card's cached native enums and context, preserving
unknown values without inventing completion booleans or unlock eligibility. Names, descriptions, any progress bars
and explanatory messages remain original child widgets and text. Global achievement history, a card's shown state
and whether the current save can earn an achievement are distinct observations; no combined status is inferred.

### Item state and image references

Supported element providers expose `properties.element`. A returned `ItemStack` has its raw `count` and an `item`
object or null; direct item providers return the item object itself. Item objects include native type and raw `health`,
plus `durability_left` or `magazine_left` when those native fields apply. Values are not clamped or converted into bar
percentages. A non-finite field is null with `<field>_non_finite: true`. Native type text has no separate small
field capacity; representation and allocation failures are errors.
Null `element` means a supported provider returned no object; an absent property means no supported provider was
observed. A stack's null `item` does not imply any health, durability or ammunition default. The observation never
creates an item object to fill in missing data. `element_unavailable_reason` reports missing adapter metadata.

Supported icon buttons expose `properties.icons` with independent `normal`, `hovered` and `disabled` values.
Each non-null value contains an opaque `reference` and `identity_scope: "snapshot"`; equal references identify the
same native resource within this response. They are not reusable action handles or file-download endpoints.
Null means no referenced icon. A reference omitted by a native capture capacity has `reference: null` and
`truncated: true`. The adapter preserves the widget's own references without choosing a rendered state or substituting
one icon for another. An absent `icons` property means no supported component was observed; `icons_unavailable_reason`
reports missing adapter metadata. Image pixels, filenames, crops, colors and render layers are not reconstructed.
Use `screenshot` when missing visual information is needed.

Each referenced icon also has `name`: the game's utility-resource registration key when its existing lookup can
identify the resource, otherwise null. Names are copied verbatim, without adding a namespace or deriving names from
pixels or filenames. For example, a native button may report `"name": "side_menu_production_icon"`.
`name_truncated: true` reports a bounded name; `icon_names_unavailable_reason` reports unavailable lookup metadata.
Opaque references remain available when names cannot be read.

Native image widgets also expose `properties.image`. Null means the widget holds no image; an absent property means
no supported image component was observed. A non-null value has the existing image object's `native_type`.
For `GuiImage`, `sprite` is the stored static sprite, using the same snapshot-local references and optional game
registration names as `properties.icons`; null means no static sprite. Other image types, including `GuiAnimation`,
retain their original type without expanding frames or selecting a sprite from an animation. For example:

```json
{"image":{"native_type":"GuiImage","sprite":{"reference":7,"identity_scope":"snapshot","name":"technology_white"}}}
```

An unidentified resource has `name: null`. A dynamic image may return only `{"native_type":"GuiAnimation"}`.
`image_unavailable_reason` reports unavailable adapter metadata. No pixels, animation frames, simulation
initialization/update scripts or inferred media names are returned. Simulation widgets remain visible in the tree
by their native type; a widget type does not identify the subject of its animation.

Widgets with a stored tooltip provider expose `properties.tooltip`. `status: "stored"` includes the provider's
original `title` and `text`, including empty strings, rich-text markers and line breaks. `title_truncated` and
`text_truncated` report bounded copies. An unsupported provider reports `status: "unsupported_provider"`; a failed
stored-text read reports `status: "unavailable"`. An absent property means no stored provider was observed or its
metadata is unavailable; `tooltips_unavailable_reason` reports the latter. This does not establish that hovering
would show no tooltip: style defaults and dynamically generated tooltips are outside this passive read.
The reader never hovers, creates a tooltip or invokes a tooltip callback.

For script GUI elements, `gui.properties` preserves original `SpritePath` values such as `item/iron-plate`,
`recipe/iron-plate` or a mod's own identifier. Empty configured sprite strings remain empty; they are not filled from
a rendered fallback. `clicked_sprite` is an API configuration field, separate from a native icon's `disabled`
reference. `tooltip` and `elem_tooltip` retain existing localization expressions or element descriptors without
generating hover content. These observations do not name every native image or expand image layers.
The same attributes are also available through `world_query` inspection, independently of native widget registration:

```json
{
  "query": {
    "selection": {
      "kind": "inspect",
      "target": {
        "kind": "player"
      },
      "path": [
        {
          "property": "gui"
        },
        {
          "property": "screen"
        },
        {
          "index": "my_frame"
        },
        {
          "index": "my_sprite_button"
        }
      ]
    },
    "fields": [
      "sprite",
      "hovered_sprite",
      "clicked_sprite"
    ]
  }
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

A selector has one or more steps, without a separate step-count ceiling. Each step requires `axis` (`child` or `descendant`) and `match`, whose
optional predicates are `native_type`, `text`, `enabled`, `visible`, `prototype` and `gui`. A step's `position` selects within each preceding
context in preorder (parent before descendants), following native sibling order.
`visible` accepts a boolean; widgets with unknown visibility match neither value.
String predicates contain no null characters; type and prototype predicates must be nonempty, while text may be
empty. There is no separate small byte-length ceiling.
`prototype` takes a required `name` and optional `native_type`. For example, `match: {native_type: "InventoryGuiSlot", prototype:
{name: "stone-furnace"}}` matches that identity in a live slot. All predicates are exact matches; truncated identity
fields cannot match. Add ancestor or position constraints when the same prototype appears in several widgets.
Native quality is not a dedicated selector predicate. Script attributes can be matched through `gui`.
Overlapping contexts select each target once without removing repeated nodes from observations. Selector resolution
uses the complete live tree before observation-depth projection; unsafe or incomplete traversal is an error.

`match.gui` compares existing `LuaGuiElement` attributes, including those returned inside `gui.properties`. Attribute
names are direct in the predicate: `{"gui":{"name":"my_button","sprite":"item/iron-plate"}}`.
Object predicates recursively require the supplied fields; arrays and scalar values match exactly. For example,
`{"gui":{"tags":{"opaque_key":12}}}` tests that stored value without interpreting the tag. Nested readable API
attributes can also be matched. Unavailable attributes do not match, null predicates are rejected, and no methods or
tooltip callbacks are invoked to evaluate predicates. Add native ancestors or positions to distinguish repetitions.

## UI actions

To select an option, click the dropdown, read the expanded tree and click the option's live widget selector. Existing
options can be clicked outside the visible scroll region; scrolling is not required. Expanding a list makes its
option widgets discoverable for selectors. A widget that has not yet been created cannot be selected.

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
and verify the result afterwards. Nonempty `input` timelines require simulation ticks and are unavailable in menus or paused worlds.
Click modifiers are `alt`, `control` and `shift`; `position: {x, y}` uses fractions in 0–1, defaulting to the center.
Native text command storage holds up to 16384 Unicode scalar values, including line feeds. Other C0 controls
(U+0000 through U+001F) and malformed surrogate pairs are rejected. The widget's own rules can filter or shorten
the replacement.
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
pagination when requested. All matching controls are returned by default. Empty binding slots are omitted; linked controls also report their effective
bindings. Snapshots report whether discovery was complete and capture the full existing registry. Registration and a
configured binding do not imply that an
action is permitted in the current game state. Control IDs and structured bindings are independent of the display
language; this tool does not return localized control names or descriptions. It only reads bindings and does not send
input.
`offset` defaults to 0; omitting `limit` returns all matches after that offset. `has_more` and `next_offset` describe
an explicitly bounded page. `ids` accepts exact control IDs; `search`
matches control IDs case-insensitively. Every page is a new sorted snapshot, so binding changes between pages may change
the results. Inspect truncation before assuming a control is absent. Native control IDs and linked-owner IDs
must fit 255 UTF-8 bytes; a longer native identifier produces an explicit capacity error.

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

`input` coordinates keyboard and mouse actions in one call: multi-key chords, simultaneous holds, repeated presses
and timed sequences. A single timeline can hold the build button while moving through several path segments and
pressing rotation at corners, for example to lay a long belt route with turns. Keep an entire held gesture in one
call: its keys and buttons are released when that timeline completes or aborts.

`timeline` entries share one relative tick clock. Overlapping ranges run together; later ranges schedule subsequent
actions. Array order controls dispatch within a tick, not sequential execution of whole entries. Give each key,
modifier, button and pointer action its own entry. Discover current bindings with `input_bindings`.
`stop_previous` defaults to `false`.
Tick `0` is the first eligible local-player input evaluation after the task is admitted, meaning start as soon as
possible. It is not the time the agent sends the request, a wall-clock deadline or an authoritative server tick.

For example, this holds Ctrl and Shift while briefly pressing C. The physical keys are illustrative; select keys and
modifiers from the current bindings for the intended action. Put modifiers before the key they modify:

```json
{
  "timeline": [
    {"device": "keyboard", "key": "LCTRL", "tick": "0-7"},
    {"device": "keyboard", "key": "LSHIFT", "tick": "0-7"},
    {"device": "keyboard", "key": "C", "tick": "2-3"}
  ]
}
```

For a drag with a turn, combine one button hold, two motion segments and a rotation press. The following example
assumes the intended item is already held, its initial orientation follows the first leg, build is bound to left
mouse and rotate to R. Choose world coordinates inside the current viewport. Each motion uses `per_point_ticks:2`
to dwell at each tile center:

```json
{
  "timeline": [
    {
      "device": "mouse",
      "motion": {
        "space": "world",
        "snap": "tile_center",
        "from": {"x": -4, "y": -3},
        "to": {"x": 3, "y": -3}
      },
      "start_tick": 0,
      "per_point_ticks": 2
    },
    {"device": "mouse", "button": "left", "tick": "0-31"},
    {"device": "keyboard", "key": "R", "tick": "16"},
    {
      "device": "mouse",
      "motion": {
        "space": "world",
        "snap": "tile_center",
        "from": {"x": 3, "y": -3},
        "to": {"x": 3, "y": 4}
      },
      "start_tick": 16,
      "per_point_ticks": 2
    }
  ]
}
```

The first leg visits eight centers from `(-3.5,-2.5)` to `(3.5,-2.5)` during ticks `0-15`; the second visits eight
from `(3.5,-2.5)` to `(3.5,4.5)` during `16-31`. R is pressed at the shared corner while the mouse button remains
held throughout both legs. Longer routes use additional motion entries and appropriately timed rotation presses.
The tool sends these inputs; placement still depends on the held item, orientation, available space and game rules.

The first motion entry precedes the button entry so the initial pointer position is dispatched before the press.
Within each tick, entries dispatch in array order, with intervals in their supplied order; the last active pointer
position wins. All scheduled presses/moves run before the local input evaluation, and interval-ending buttons
release after it. Motion alone does not press a mouse button.

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

For point-by-point dwell, replace `tick` with `per_point_ticks` and optional `start_tick` (default 0), as above.
The generated path has `ceil(max(abs(dx),abs(dy)))+1` uniformly spaced points, including endpoints, after any
endpoint snapping. Viewport points are rounded to pixels; tile-center paths are snapped to centers. Duration is the
point count times `per_point_ticks`; a segment starting at `s` occupies `s` through `s + duration - 1`. Schedule the
next segment explicitly; separate entries do not concatenate automatically. This mode is available only for motion
and cannot be combined with `tick`; its final tick must fit the same endpoint bound. Dwell counts client input
evaluations, without guaranteeing placement, selection
or server effects. Use several entries for complex paths or varying speeds.

Viewport coordinates are nonnegative integer game-content pixels from the top-left, not desktop pixels. Choose
positions within the viewport; viewport coordinates are not clamped. Input changes the game's cursor without OS
input or focus. For UI actions, a same-tick move and press can precede hover processing; use an earlier position
entry or prefer `ui_action`. Wheel input over UI can be unreliable.

A request permits at most 256 entries. Empty `timeline` completes without input when idle; with `stop_previous: true`,
it cancels and cleans up the previous task without starting another. All input calls require attachment, including
empty timelines. Nonempty timelines require a running world.
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
`attributes`, `read_status`, `details` and `detail_status` as entity queries. It accepts native `fields`, per-root `reads`,
table `depth` and
`include: ["recipe", "fluids", "filters"]`; `entity_limit` then applies to the entire area. Related objects stay
references. This provides selected configuration information without reproducing the game's ALT overlay or inferring
what should be drawn. Both modes accept additional native entity `filters`.
Grid mode rejects `fields`/`include`/`reads`; entity mode rejects `cell_size`, `cell_limit`, `start_cell`, `group_by`
and `aggregates`. In either mode, `depth` bounds ordinary table values in fields or grouping keys.

Grid grouping defaults to `group_by: ["name", "type", "quality"]`. Choose distinct entity fields;
`group_by: []` produces one group per cell. Equal projected values group together regardless of table key order.
Missing and failed grouping reads remain distinct in `read_status`.

Numeric aggregation is opt-in, with distinct `{operation,field}` requests. Operations are `sum`, `min`
and `max`; MCP applies the same calculation to any requested field without selecting rules by entity type.
For example, `aggregates: [{"operation":"sum","field":"amount"}]` sums numeric observed amounts. Each result retains
`operation`, `field`, `numeric_values`, `nil_values`, `read_errors`, `non_numeric_values` and `non_finite_values`.
Excluded values do not enter the calculation. `value` is absent when no numeric values were observed; an unrepresentable
calculated value is omitted with `value_non_finite:true`. These exclusions and scan truncation prevent an aggregate
from being treated as a complete total. No field is summed automatically. Grouping and aggregates are grid-only.

Both modes accept `name` and `type`, each a string or a nonempty list of distinct strings. Native types match mod entities too.
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
Areas must have positive dimensions. `cell_size` is a positive integer; it defaults to at least 32, enlarged as needed
to fit within 16 rows and 16 columns. Partial edge cells retain their actual bounds.

`cell_limit` defaults to 256 returned cells. If cells remain, `next_cell` contains the inclusive zero-based
`{row,column}` for `start_cell`. Continue with the original explicit `area`, `surface` and `cell_size`; default-viewport
queries must first be repeated with explicit bounds and cell size. Order is row-major, with columns increasing first.
`total_cells` counts the entire grid, including grids larger than a signed 32-bit count. A page's `truncated` also
includes partial entity scans; only `next_cell` indicates remaining cells. Each page reads current world state.

The row-major `objects` array contains cells with zero-based `row`/`column`, map `area`, `entity_groups`, a center
`tile_sample`, and `coverage`. Each group contains the original grouping values in `attributes`, their `read_status`, `count` and requested `aggregates`. Spatial discovery uses collision candidates, then assigns only entities whose centers lie within the
cell's half-open bounds. Groups sort by a structural key of the original grouping values and read statuses. The center tile is one sample, not the entire cell's
terrain distribution. Coverage reports counts of intersecting chunks that are generated, charted and currently visible;
the sample's availability remains explicit in ungenerated terrain. Reads do not generate chunks.

`entity_limit` defaults to 64 candidates per cell, or per whole area in entity mode. Increase it to inspect more
candidates. There is no separate overall candidate-scan budget. Lookahead detects truncation; `entity_scan` is
`complete` or `partial`, and per-cell and overall `truncated` flags identify incomplete enumeration. Group counts and
numeric aggregates in a partial cell cover only observed members. A boundary candidate can consume a cell's requested
limit even when its center belongs to another cell.

Viewport coordinates use the game's conversion, including camera offsets and fixed-point rounding. `viewport.area`
describes pixel edges `(0,0)` through `(width,height)`; it may differ from an explicit queried `area`. If a native
viewport is unavailable, explicit-area queries still run and return its availability reason. Default-viewport queries
fail instead of inventing camera state. Use `world_query` for individual objects and additional properties.

## World queries

`world_query` takes one `query` object and reads a loaded world without moving the player or opening entity windows,
including on another surface or while another UI window is open. All selection, projection and read options belong
inside `query`. For example:

```json
{
  "query": {
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
}
```

Use `kind: "tiles"` for tiles or `kind: "player"` for the injected client's own player. Spatial selections can instead
use `area: {left_top: {x, y}, right_bottom: {x, y}}`; entities can also filter by a current-world `unit_number`.
Combine that number with a position or area for entities the game does not index directly. A number alone uses the
game's limited unit-number index; failed lookup is an error because it does not prove the entity is absent. Spatial
unit-number resolution scans the selected area completely.
Spatial selections also accept exact `name` and `type` filters for entities, each as one string or a nonempty list of
distinct strings. Entries within a list are OR, and name/type conditions combine with AND. For example,
`"type":["transport-belt","mining-drill"]` selects either native type, including mod prototypes. Underground belts
and splitters have their own native types. To read belts, underground belts, splitters and mining drills in one area:

```json
{
  "query": {
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
}
```

Additional `selection.filters` pass native `EntitySearchFilters` such as `force`, `quality`, `ghost_name`,
`to_be_deconstructed` or `has_item_inside` to the game. They cannot override geometry, `name`, `type` or the result
limit. Unknown filter names fail against the installed runtime API instead of being silently ignored.

Filters apply before the result limit. Check truncation and narrow the area when necessary; a bounded response does
not promise every matching entity in a dense area. Each `name`/`type` filter string is nonblank and contains
no null characters. Coordinates must be finite
and within ±1,000,000 tiles. `query.surface` accepts a nonempty name or positive index and defaults to the
current controller's surface. Results include the observation tick, controller/physical
positions, truncation and per-property nil/error status. Entity point/area queries use collision geometry; radius
queries use entity centers. Tile queries round to containing/intersecting cells.
These are **live world reads**, which may include hidden entities; chunk visibility/charting flags distinguish them
from what the player can currently see. Results are not remembered map-chart data. Queries do not generate chunks.
Spatial `limit` defaults to 128 and can be increased. Exact `unit_number`, player/force/entity-reference selections
and one-tile reads reject `limit`; they already select at most one object. Areas require positive dimensions and radius must be finite and
nonnegative. `fields` accepts distinct native readable property names, including names outside the branch defaults.
Discover applicable attributes with inspection `members`; absent/unsupported reads remain explicit nil/error statuses.
`fields:[]` omits optional attributes while retaining identities and requested `reads`.
Related objects are shallow references; use paths or the selections below to inspect inventories
and prototypes. Entity order is unspecified; tile order is row-major. `candidates_observed` is not an entity total when
the
result is truncated. Use the containing world's identity scope when retaining entity unit numbers across calls.
Player and surface numeric selectors accept positive unsigned 32-bit API indices. Entity `unit_number` accepts
positive unsigned 64-bit integers exactly representable by the game's Lua number type; nonrepresentable integers
fail before execution. Above 2^53, only appropriately spaced integers are representable. Clients must preserve
the requested integer instead of rounding it during JSON encoding.

Spatial results report `selection_complete`: false means matching entities or selected tiles were omitted by the
count bound. They have no `has_more`, offset or continuation position. Choose a smaller area, stronger filters or a
larger count for another current observation; calls do not share a frozen snapshot. Tiles are returned in row-major
order, x first. `total` is the rounded rectangle's tile count and may exceed a signed 32-bit count.
`candidates_observed` is the observed candidate count, not an entity total for an incomplete scan.

`depth` controls ordinary table-valued attributes, starting at 0 for each selected value. The default is 3 and the
effective maximum is 65535. Nonempty boundary tables carry `reason:"depth_bound"` and `children_truncated:true`;
empty tables remain empty and cycles become references. `depth_truncated:true` marks nested depth omission and
contributes to top-level `truncated`, independently of `selection_complete`. Native object references remain shallow.
Non-finite numbers and unsupported Lua value kinds have explicit observation markers. Unsupported table key kinds
report read errors rather than silently dropping entries.

### Entity configuration and related objects

Entity and character/vehicle selections accept `include` with any distinct subset of `recipe`, `fluids` and `filters`.
These return separate `details` entries with per-entry nil/error information in `detail_status`:

- `recipe`: configured recipe and quality references. A missing recipe is explicit; it does not imply craftability.
- `fluids`: native fluid-name to amount dictionary. Use object inspection for individual storage temperatures,
  capacities, filters and connections. Discover the available properties and methods with `mode: "members"`.
- `filters`: native slot count, indexed filters, mode flags and per-property/per-slot errors. Unsupported
  filters/modes are not inferred from the entity's name.

```json
{
  "query": {
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
}
```

For deeper reads, use `selection.kind: "inspect"` with a `target`. Targets include `game`, `prototypes`, `defines`, ordinary entity/player/
character/vehicle/physical_vehicle/force selectors, `surface` with an optional name, `planet`/`recipe`/`technology`
with a name, or `prototype` with catalog `type` and `name`. Entity targets must resolve exactly one entity. Optional
`player` selects player references or the force used for a recipe/technology. `force` selects a force by name/index
for force, recipe and technology targets, mutually exclusive with `player`. `game` provides roots such as players,
surfaces, forces and the train manager. `prototypes` exposes the native prototype registry; `defines` exposes the
game's stored enum tables, including inventory indices and status codes. No mod or vanilla-name catalog is inferred.

`mode` is one of:

- `members`: discover admitted passive attributes and query methods, including shipped API descriptions, parameter
  and return metadata, subclass restrictions and index/length/iterator capabilities. Optional `search` filters native
  member names by a case-insensitive literal substring before paging. Availability in metadata does not mean
  a property applies to the current concrete object.
- `values` (default): read the specified `fields`, or all admitted passive attributes of a native object when omitted.
  Without `fields`, a selected plain table or scalar is returned as `value`, bounded by table depth. Explicit fields
  read stored properties of a plain table; `fields:[]` omits optional values/attributes. No entry paging or attribute
  count is applied. Native errors and nil values remain explicit. Select distinct field names.
- `entries`: page a returned table or indexable collection. Each entry has its native `key`, bounded value preview,
  and any read status. Keys sort by native scalar type/value; array-like objects use one-based indices. A fresh
  `LuaChunkIterator` from `get_chunks` can also be consumed: entries have one-based ordinal `index` values, and
  `total` is present only after exhaustion. Calls recreate the iterator; offsets are not persistent cursors.
- `slice`: read a string reached by the target/path, using `offset` and `limit` as UTF-8 byte positions/counts.
  The result preserves bytes and reports `value`, `total`, `unit: "utf8_bytes"`, `truncated` and any `next_offset`.
  Offsets must start on a character boundary; the limit must fit the next character. Continuations never split a
  character. Offsets at or beyond the end return an empty value.

Use `selection.path` to follow an ordered path from a freshly resolved target, without a separate step-count cap.
Each step is exactly
`{"property":"name"}`, `{"index":1}` (or a string key), or
`{"method":"get_recipe","arguments":[],"result":2}`. Methods come from a restricted passive-query set; arbitrary
Lua, mutation methods, insertion-capacity probes and statistics reads that can create missing serialized data
are not accepted. A documented readable attribute or a `get_` method name alone does not establish passivity.
`arguments` contains positional JSON data
matching the selected method's signature; `result` is a positive one-based return position and defaults to 1.
Omit trailing optional arguments. Paths and argument data must fit the request buffer; actual API contracts still apply.

```json
{
  "query": {
    "selection": {
      "kind": "inspect",
      "target": {
        "kind": "entities",
        "position": {
          "x": 10,
          "y": 20
        },
        "unit_number": 123
      }
    },
    "mode": "members",
    "limit": 32
  }
}
```

```json
{
  "query": {
    "selection": {
      "kind": "inspect",
      "target": {
        "kind": "entities",
        "position": {
          "x": 10,
          "y": 20
        },
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
}
```

```json
{
  "query": {
    "selection": {
      "kind": "inspect",
      "target": {
        "kind": "entities",
        "position": {
          "x": 10,
          "y": 20
        },
        "unit_number": 123
      },
      "path": [
        {
          "method": "get_fluid",
          "arguments": [
            1
          ]
        }
      ]
    },
    "mode": "entries"
  }
}
```

Fluid storage indices range from 1 through the observed `fluids_count`. Inspect `get_fluid_capacity`,
`get_fluid_filter`, `get_fluid_box_neighbours` or `get_fluid_box_pipe_connections` with the same index.
`has_fluid_segment` and admitted `get_fluid_segment_*` methods expose existing segment observations.
No fluid mutation methods are admitted.

Only `members`, `entries` and `slice` accept `offset` (default 0) and `limit`. `members` returns the complete catalog
unless a count is supplied; `entries` defaults to 64 and `slice` to 4096 UTF-8 bytes. `values` rejects both parameters.
Paged results report `has_more` and `next_offset` when more entries/bytes remain, plus `total` when the collection
size is known; continuation is also
available on the outer result. Properties returning native objects remain references: continue the path to inspect
them. After resolving the path, the selected plain value, each attribute or each collection entry starts at preview depth 0.
`depth` controls its expansion (default 3, effective maximum 65535).
Nonempty boundary tables remain `depth_bound` references with `children_truncated:true`; empty tables stay empty.
Ancestor cycles are `cycle` references, while shared branches are expanded independently. Plain table previews
read stored entries without table indexing, iteration or length callbacks. An ordinary `object_name` key remains data.
`depth_truncated:true` reports omitted nested children and contributes to `truncated`, even on a final collection page
with `has_more:false`. There is no separate per-table entry count, string length or total preview-value
budget. Indexed lengths must fit a 32-bit signed integer.
This interface exposes supported native data, not arbitrary mod-private state, and does not compute game eligibility.
Index steps preserve string keys, including empty strings and embedded NUL, or finite numeric keys, including zero,
negative and fractional plain-table keys.
An indexed native object still applies its own API rules.

### Batched relative reads

`query.reads` is an ordered array of independent relative reads. It applies to each selected entity, generated tile,
player, force, catalog value, inventory kind or inventory slot. Ungenerated tile placeholders have no live object to
read. With `inspect` in `values` mode it applies to the available selected root;
in `entries` mode it applies to each selected entry before its value becomes a shallow reference. Each read has
`path` (default `[]`), `mode` (default `values`) and its own projection/depth or collection/text bounds. Reads cannot
contain further `reads`. One path error produces that read's `read_status`, preserving the other reads and their order.
Repeated paths and values remain repeated. All selected roots and reads observe the same safe point.

For example, read recipes, output contents and module contents for every selected machine in one call:

```json
{
  "query": {
    "selection": {
      "kind": "entities",
      "area": {"left_top": {"x": 0, "y": 0}, "right_bottom": {"x": 32, "y": 32}},
      "type": "assembling-machine"
    },
    "fields": ["name", "crafting_speed", "products_finished"],
    "reads": [
      {"path": [{"method": "get_recipe"}], "fields": ["name", "ingredients", "products"]},
      {"path": [{"method": "get_recipe", "result": 2}], "fields": ["name"]},
      {"path": [{"method": "get_output_inventory"}, {"method": "get_contents"}]},
      {"path": [{"method": "get_module_inventory"}, {"method": "get_contents"}]}
    ]
  }
}
```

Rows for native objects retain their `reference`; scalar catalog values and empty inventory slots need no reference.
Available selected roots add ordered `reads[]`. A read returns attributes, `value`,
entries/members, a slice, explicit `availability:"nil"` or a read error. Its public path preserves the original
arguments. Native runtime `object_name` values remain unchanged; `api_class` records a declared documented interface
when the concrete runtime class has no separate metadata. This type information follows documented properties,
method returns and container entries; it does not guess concrete class aliases or game meaning.

Native references remain references regardless of depth. To read a related object's properties, extend its path
or add a relative read; raising depth expands ordinary tables only. Native empty tables retain the game's `{}`
encoding; only MCP envelope arrays such as `objects`, `reads`, `entries` and paths normalize empty arrays to `[]`.
Nested truncation contributes to the overall `truncated` flag but never turns a complete spatial selection into
an incomplete one or creates a spatial continuation.

The same paths cover quality-dependent prototype methods, blueprint entities/tiles/tags, schedule records and
wait conditions, train-manager collections, transport-line contents, surface properties and generated chunks.
Discover their applicable signatures with `members`. No UI navigation, player reach check or workflow interpretation
is added. Force statistics getters and `LuaFlowStatistics` count attributes/methods are unavailable because they can
allocate synchronized game state when the underlying statistics are empty. Safe statistics references and their
native identity remain observable; this does not promise access to their counters.

For script GUI elements exposed to the selected Lua context, `ui_read` returns selected attributes alongside native
widgets. Use this inspection path for additional object traversal or script elements without native registration.
For example, a named radio button inside a named screen frame can be read with:

```json
{
  "query": {
    "selection": {
      "kind": "inspect",
      "target": {
        "kind": "player"
      },
      "path": [
        {
          "property": "gui"
        },
        {
          "property": "screen"
        },
        {
          "index": "frame_name"
        },
        {
          "index": "radio_name"
        }
      ]
    },
    "fields": [
      "name",
      "type",
      "state"
    ]
  }
}
```

These roots expose script GUI elements, not the complete native interface tree. Use `ui_read` for that tree.

### Multiplayer players

`selection: {"kind":"players"}` enumerates current-world players, including disconnected players. Optional
`connected`, `names` and `indices` filters combine with AND; entries within each list are OR. Results sort by player
index and support optional `offset` and `limit`; all matches are returned by default. Names and indices must be
distinct. `fields` uses the same set as a single player query.

```json
{
  "query": {
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
  "query": {
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
}
```

```json
{
  "query": {
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
}
```

### Player context

Use `world_query` with `{"query":{"selection":{"kind":"player"}}}` for current held-stack identity, held ghost, held blueprint
record, cursor temporariness, original hand location and crafting queue. The shared `player` envelope also includes
controller type, current `position`/`surface` and
`physical_position`/`physical_surface`; retain both pairs when using remote view. These are native API values, not
MCP predictions about which actions are allowed. Select fewer properties when only one fact is needed:

```json
{
  "query": {
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
  "query": {
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
}
```

Use the same path with `cursor_record` and `mode:"members"` to discover record properties such as
`is_preview`, `type` and snapping settings. A record may initially be a preview; content reads can return
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
  "query": {
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
}
```

Read the main inventory without opening its UI or discovering it first:

```json
{
  "query": {
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
}
```

The actual inventory name and size are returned. Follow `next_offset` if truncated; do not assume a fixed backpack
size or merge stacks of different qualities. Separate calls are separate observations with their own ticks.
The default owner is the current player controller. In remote view its main inventory can be unavailable. To inspect
the attached character's inventory, use `owner: {kind: "character"}`. This follows the native character reference
within the observation rather than resolving a previously observed position. Vehicle owners work the same way:

```json
{
  "query": {
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
}
```

### Quickbar

Quickbar slots contain filters or references, not inventory storage. Query explicit one-based page/slot locations:

```json
{
  "query": {
    "selection": {
      "kind": "quickbar",
      "slots": [
        {
          "page_index": 1,
          "slot_index": 1
        },
        {
          "page_index": 1,
          "slot_index": 2
        },
        {
          "page_index": 2,
          "slot_index": 1
        }
      ],
      "screen_pages": [
        1,
        2
      ]
    }
  }
}
```

`objects` preserves each `page_index` and `slot_index`. `attributes.value` contains the native slot's `type` and nested
`filter`, `item`, `record` or `selection` when provided. Missing slots and invalid game locations have explicit
`read_status.value` nil/error results. Object references do not reproduce blueprint instance contents.
`page_width` reports the game's actual row width. `active_pages` contains requested screen indices and `attributes.page`,
with per-entry `read_status.page` for nil/error results.
Provide at least one nonempty list: `slots` accepts distinct locations, and `screen_pages` accepts distinct indices.
Page and slot indices are bounded to 1..256; screen indices accept the API's unsigned 32-bit range, 1..4294967295.
These argument domains do not assert that the
game has that many pages or slots. Input order is preserved.
This selection does not accept `fields`, `surface`, `offset` or `limit` and does not infer which rows are visible.

### Inventories

Use `selection: {kind: "inventories"}` to discover the local player's inventories. To inspect an entity, provide `owner`
using an ordinary `entities` selection; it must resolve exactly one current entity. Each inventory reports its actual
name, API index, size, filter support and bar support. A bar is the one-based first restricted slot.
Discovery and slot reads return all matches unless a `limit` is supplied.
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
  "query": {
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
}
```

Slot indices are one-based; `offset` is a zero-based page offset. Empty slots remain present with
`valid_for_read: false`.
Occupied slots expose requested API attributes, including item quality. Slot filters are included when present.
`inventory` metadata describes the whole inventory, while `objects` contains only the requested slots. Discovery does
not accept `fields`; slot reads do. Missing inventories and ambiguous owners are errors. These queries do not insert,
remove, sort or test whether an inventory can accept an item.

### Prototype catalogs and force recipes

Use `selection: {kind: "prototypes", type: "item"}` to list prototype definitions. `type` can name any native
`LuaCustomTable` catalog attribute of `LuaPrototypes`, including equipment, tiles, virtual signals and space locations.
Inspect the `prototypes` root with `mode:"members"` to discover catalog names/types. Scalar catalog entries retain
their native value rather than being fabricated into prototype objects. `names` accepts a nonempty list of distinct exact names
for batch reads; `search` accepts a nonempty string
and filters internal names by a case-insensitive substring. Neither accepts null characters. Both may be combined.
Unresolved exact names appear in `missing_names`.

```json
{
  "query": {
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
      "categories",
      "energy",
      "ingredients",
      "products"
    ]
  }
}
```

`recipes` reads the local player's force recipes, including their current enabled state; optional `selection.force`
selects another force by name/index. `technologies` supports the same force selection. A recipe prototype's `enabled`
attribute describes its default definition instead. `categories` retains the game's category collection.
Ingredient and product records retain the game's API fields,
including amounts, probabilities and temperature constraints when supplied. Localized names/descriptions are returned
as untranslated `LocalisedString` values; no translation request is sent. Related prototypes and groups are shallow
typed references. Native property discovery uses inspection `members`; unavailable values retain nil/error
status.

Recipe names need not match product names. `product` and `ingredient` select recipes or recipe prototypes containing
at least one exact `{type: "item"|"fluid", name: "..."}` entry. They combine with names/search and with each other:

```json
{
  "query": {
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
}
```

This is a raw relation query,
not a crafting plan or feasibility calculation. Prototype `type`, `group` and `subgroup` retain game-defined values:
an item's type may be `item` while its `place_result` entity's type is `mining-drill`. Groups/subgroups can be queried
for labels, ordering, parent and subgroup references. Prototype reads also expose `hidden_in_factoriopedia`,
`factoriopedia_description` and supported `factoriopedia_alternative` references. This is not full encyclopedia parity.

### Force and technology state

`selection: {kind: "force"}` reads the local player's force, including current/previous research, research queue,
progress and whether research is enabled. Its selection accepts optional `player` or `force` name/index, mutually
exclusive; use `fields` to choose force
properties.
It returns one force and does not accept `surface`, `offset` or selection filters.
Use `technologies` for current-force technology objects with names/search and normal catalog pagination:

```json
{
  "query": {
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
}
```

`researched`, `enabled`, current level and saved progress remain separate native properties. Prerequisites/successors
are name-keyed dictionaries of shallow technology references. Prototype defaults, force research state and recipe
availability are distinct; none alone proves that an action can be performed. Research effects can be read from the
related technology prototype. Native nil and per-field errors remain explicit, including an absent current research.

Inventory slots and inventory-kind discovery return all matches from `offset:0` unless a limit is supplied. Native
slot indices remain meaningful when contents change; a later range still observes the current contents.
Prototype catalogs, recipes and technologies return all explicitly named matches by default; other catalog queries
default to `limit:64`. Increase the limit for larger pages.
Quickbar and local player/entity/force selections do not use this
pagination contract.
Results report `next_offset` when another page exists, and
catalogs sort by internal name. Each page is a fresh observation, so live inventory edits can shift contents between
pages. Catalog discovery scans the selected catalog without a separate definition-count budget. A loaded world
and local player are required, including for prototype queries.

## Chat

`chat_read` takes optional `limit` (positive integer, default 64), `offset` and `timeout` (nonnegative integer seconds, default 0).
It reads the local
client's retained console without opening chat UI, including received player messages, notifications and mod output.
It is not a global server log, channel filter or lossless event subscription.

Each message has a native update `tick`, zero-based `position_in_tick` within its storage, a continuation `offset`,
`storage` (`game_state` or `local`),
`player_index_raw`, `text`, `raw` and separate truncation flags. `player_index_raw` is the unmodified native unsigned
index, not the one-based Lua player selector; non-player messages can carry a sentinel. No sender/channel is parsed
from text. `text` is existing cached display text, which may be empty or stale. `raw` formats the localization
expression without requesting translation. Original rich-text markers and formatting are preserved.
`player_name` and `player_name_truncated` preserve a separately retained sender when available, and
`text`/`raw` describe the stored message body. MCP does not
reconstruct a formatted message from these fields or refresh the game's translation/rendering cache.

Omit `offset` or use `0` to return the latest requested observations. A positive integer selects the native tick
inclusively: its boundary messages are included so messages sharing a tick are not silently skipped. For exact
continuation, pass the returned `next_offset` or a message `offset`: `{"tick":2412,"counts":[3,1]}` means three
`game_state` records and one `local` record at tick 2412 have already been read. Later ticks start at zero counts.
Follow `has_more` to drain further pages. Separate storage counts also admit a new synchronized message at an
unchanged tick after local messages have been read, including in paused worlds. Reads do not acknowledge or remove
messages, and concurrent readers can use their own offsets.

For a long message field, use `text_slice: {field: "text", offset: 0, limit: 4096}`. `field` is `text` (default),
`raw` or `player_name`. Only that textual field is emitted, alongside metadata and `<field>_range` with UTF-8 byte
`offset`, `total`, `truncated` and any `next_offset`. Character-boundary rules match inspection string slices.
Each message's `read_from` watermark points immediately before that record: repeat with `offset: read_from`,
`limit: 1` and the next text byte offset to continue the same field. A page is a fresh history read; eviction or
changed cached text can affect continuation. Omitting `text_slice` retains the ordinary complete-field projection.

The watermarks use native ticks and chronological retained-list positions, without process addresses, random
attachment identifiers or MCP observation counters. They survive MCP restarts with unchanged native history.
`offset_basis` is `native_tick_and_storage_position`. These are per-player console watermarks, not server-issued
globally unique message IDs. Different players can receive different history and local notifications; carry offsets
only between observations of the same player history in the same world. Identical messages at one tick retain separate
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

Retained metadata from both native storage lists is captured at one safe point; only the selected page and text slice
are transferred with message text. `retained_counts` reports the native list counts; `snapshot_truncated` reports
incomplete capture. `boundary_truncated` reports an incomplete tick group whose counts cannot provide complete
continuation through that group.
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

## Rendering control

`set_rendering` requires a boolean `enabled` and returns `{"dispatch":"completed"}` after local execution.
Use `{"enabled":false}` to suppress drawing once or `{"enabled":true}` to clear that suppression once,
without minimizing, hiding or focusing the game window. It requires an
attached, initialized graphical client and works in menus, paused worlds and gameplay. It does not pause simulation
or change persistent settings. Structured observations and actions remain available.

This is not a persistent mode: later user, window or system events may change drawing, even before the result is
received. There is no monitoring, automatic reapplication or restoration on detach. Repeated explicit calls are
allowed, including when the requested flag is already set. Enabling does not override minimization with
`halt-rendering-when-minimized` enabled or force the graphics backend to produce a frame. Completion reports the
local operation, without waiting for a rendered frame. Do not replay a call whose result is uncertain.

The tool shares the command queue with screenshots. An earlier screenshot completes, fails or is cancelled before
the change executes. A later screenshot checks the current native state and fails promptly at the frontend safe point
while rendering is suppressed; if drawing is available again, it captures normally. `set_rendering` does
not cancel a pending screenshot; use `screenshot` with `action:"cancel"` if needed.

## Screenshots

`screenshot` accepts `action: "capture"` (default) or `action: "cancel"`. Capture returns standard MCP Base64 image
content plus the format, effective JPEG `quality` (JPG only), output `width`/`height`, original
`source_width`/`source_height`, UI frame and observed game state. Only one capture can be pending; another capture
returns a busy error.

- `format`: `"jpg"` (default) or `"png"` (lossless encoding).
- `quality`: optional integer from 1 to 100, default 85. Applies only to JPG capture; specifying it with PNG is an
  error. Higher values retain more detail and generally produce larger images. A value of 100 is still lossy.
- `max_height`: optional positive 32-bit integer in pixels. Taller frames are reduced proportionally using area
  resampling; width is rounded to the nearest integer, with a minimum of one pixel. Smaller frames are never enlarged.
  Omit to retain original dimensions. The full frame is preserved, without cropping, stretching or changing the game
  window, rendering resolution or field of view. PNG is lossless relative to the resized pixels when resizing is used.

For ordinary visual inspection, start with `{"format":"jpg","max_height":720}`. Request `{"format":"png"}` for a
lossless original-size image. The image MIME type is `image/jpeg` or `image/png`, matching the encoded bytes.
For example, `{"format":"jpg","max_height":720,"quality":60}` requests a smaller JPEG with more compression.
Output pixel coordinates must be scaled by `source_width/width` and `source_height/height` to map to the original
frame. Capture options are rejected with `action:"cancel"`.

Cancel stops the active capture, waits for cooperative cleanup and returns `{"dispatch":"completed","cancelled":true}`.
It starts no new capture and returns `cancelled:false` when no capture is pending. Explicit MCP request cancellation
also cancels capture. After cancellation finishes, a new capture can be submitted.
It captures the rendered game and UI using OpenGL on Linux or DirectX on Windows, without activating the window.
It excludes desktop and Steam overlays. The source field is `factorio_rendered_frame`.
If the game's native condition suppresses rendering at frontend admission, capture returns
`Screenshot unavailable while rendering is suspended.` and releases its queue reservation. Minimization alone does
not reject capture: drawing may continue when `halt-rendering-when-minimized` is disabled. Paused worlds can be
captured while rendering continues. A stalled frontend or graphics backend can still leave capture pending; this
check does not detect every rendering stall.
Capture supports at most 8192 pixels per side and 16,777,216 pixels in total. These source-frame bounds protect
capture memory. Encoded output must fit a signed 32-bit byte-array length; there is no separate 16 MiB image ceiling.
Exceeding these bounds returns an error.

## Completion and cancellation

Actions acknowledge completed dispatch; their gameplay effects depend on the current game state and rules.
Observe the result before submitting a dependent action, especially in multiplayer. Do not replay an action whose
response was lost: inspect current state first.

Tool results carry a JSON object in `structuredContent` and an equivalent MCP text content block; screenshots
additionally carry JPEG or PNG image content with the corresponding MIME type. Rejected or failed tool calls use
`isError: true` with `{"error":"description"}`.
An input's structured `status: "aborted"`
instead preserves partial progress. These are distinct from MCP transport/protocol errors.

Tools have no execution deadline. Use explicit MCP cancellation to stop a pending call. HTTP socket closure alone is
not guaranteed to cancel it. Single-phase requests and input admission share one attachment-wide queue; a waiting
screenshot delays later calls until capture, rejection or cancellation. Rendering suspension is checked when the
frontend handles the request; a frontend that cannot reach that point can still delay it.
`screenshot` with `action:"cancel"` bypasses that queue
to stop the pending capture. An admitted input sequence completes independently while
other calls proceed. `detach` cancels admitted input as well as active and queued requests.
