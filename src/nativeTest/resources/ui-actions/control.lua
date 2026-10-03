local function record(kind, player, data)
  log("UI_ACTION_ACCEPTANCE " .. helpers.table_to_json{kind=kind,tick=game.tick,player=player and player.index,admin=player and player.admin,data=data})
end

local function build(player)
  if player.gui.screen.mcp_actions then player.gui.screen.mcp_actions.destroy() end
  local frame=player.gui.screen.add{type="frame",name="mcp_actions",caption="MCP action fixture",direction="vertical"}
  frame.location={350,200}
  frame.add{type="button",name="normal",caption="MCP normal"}
  frame.add{type="button",name="disabled",caption="MCP disabled",enabled=false}
  frame.add{type="button",name="toggle",caption="MCP toggle",auto_toggle=true}
  frame.add{type="checkbox",name="checked",caption="MCP checkbox",state=false}
  frame.add{type="drop-down",name="selection",items={"MCP first","MCP second","MCP third"},selected_index=1}
  local options={}
  for i=1,80 do options[i]="MCP option "..i end
  frame.add{type="drop-down",name="long_selection",items=options,selected_index=1}
  frame.add{type="textfield",name="text_field",text="MCP original"}
  frame.add{type="textfield",name="numeric_field",text="123",numeric=true,allow_decimal=false,allow_negative=false}
  frame.add{type="text-box",name="readonly",text="MCP read-only"}.read_only=true
  frame.add{type="slider",name="slider",minimum_value=0,maximum_value=100,value=50,value_step=1}
  frame.add{type="choose-elem-button",name="choose",elem_type="item-with-quality"}
  frame.add{type="sprite-button",name="sprite_fixture",sprite="item/iron-plate",hovered_sprite="item/copper-plate",clicked_sprite="item/steel-plate"}
  frame.add{type="button",name="recreate",caption="MCP recreate"}
  local speed=frame.add{type="flow",direction="horizontal"}
  speed.add{type="button",name="speed_one",caption="MCP speed 1"}
  speed.add{type="button",name="speed_four",caption="MCP speed 4"}
  speed.add{type="button",name="reset_position",caption="MCP reset position"}
  local queries=frame.add{type="flow",direction="horizontal"}
  queries.add{type="button",name="query_remote",caption="MCP query remote"}
  queries.add{type="button",name="query_vehicle",caption="MCP query vehicle"}
  queries.add{type="button",name="query_spectator",caption="MCP query spectator"}
  frame.add{type="switch",name="script_switch",switch_state="none",allow_none_state=true,left_label_caption="MCP left",right_label_caption="MCP right"}
  local scroll=frame.add{type="scroll-pane",name="scroll",direction="vertical"}
  scroll.style.maximal_height=70
  for i=1,30 do scroll.add{type="label",caption="MCP row "..i} end
  scroll.add{type="button",name="offscreen",caption="MCP offscreen"}
  if player.gui.screen.mcp_resource_filler then player.gui.screen.mcp_resource_filler.destroy() end
  if player.gui.screen.mcp_resource_target then player.gui.screen.mcp_resource_target.destroy() end
  local filler=player.gui.screen.add{type="frame",name="mcp_resource_filler",caption="MCP resource filler",direction="vertical"}
  filler.visible=false
  local choices={}
  for i=1,64 do choices[i]="MCP budget filler "..i end
  for i=1,17 do filler.add{type="drop-down",items=choices,selected_index=1} end
  local target=player.gui.screen.add{type="frame",name="mcp_resource_target",caption="MCP resource target",direction="vertical"}
  target.visible=false
  target.add{type="drop-down",name="target",items={"MCP budget target first","MCP budget target second","MCP budget target third"},selected_index=1}
  target.add{type="sprite-button",sprite="item/copper-plate"}
  record("created",player)
end

script.on_init(function()
  game.surfaces[1].request_to_generate_chunks({0,0},1)
  game.surfaces[1].force_generate_chunk_requests()
  game.forces.player.technologies["electronics"].researched=true
  game.forces.player.technologies["steam-power"].researched=true
  game.forces.player.technologies["automation-science-pack"].researched=true
  game.forces.player.technologies["quality-module"].researched=true
  local surface=game.create_surface("mcp-query-details",{width=64,height=64})
  surface.request_to_generate_chunks({0,0},1)
  surface.force_generate_chunk_requests()
  for _,entity in pairs(surface.find_entities_filtered{area={{-16,-16},{16,16}},force="neutral"}) do entity.destroy() end
  local tiles={}
  for x=-16,16 do for y=-16,16 do tiles[#tiles+1]={name="grass-1",position={x,y}} end end
  surface.set_tiles(tiles)
  local function create(name,x,y)
    return assert(surface.create_entity{name=name,position={x,y},force=game.forces.player})
  end
  local machine=create("assembling-machine-2",-8,0)
  machine.set_recipe("iron-gear-wheel","uncommon")
  local drill=create("electric-mining-drill",0,0)
  local belt=create("transport-belt",8,0)
  local inserter=create("inserter",0,8)
  inserter.use_filters=true
  inserter.set_filter(1,"iron-plate")
  local tank=create("storage-tank",8,8)
  tank.insert_fluid{name="water",amount=1234,temperature=25}
  record("query_details",nil,{machine=machine.unit_number,drill=drill.unit_number,belt=belt.unit_number,
    inserter=inserter.unit_number,tank=tank.unit_number,surface=surface.name})
end)

script.on_event(defines.events.on_player_created,function(event)
  local player=game.get_player(event.player_index)
  player.admin=false
  if not player.character then player.create_character() end
  player.insert{name="iron-plate",count=50}
  player.insert{name="iron-plate",count=3,quality="uncommon"}
  player.insert{name="nutrients",count=10}
  player.insert{name="repair-pack",count=1}
  player.insert{name="firearm-magazine",count=1}
  player.insert{name="stone-furnace",count=1}
  local repair=player.get_main_inventory().find_item_stack("repair-pack")
  local ammo=player.get_main_inventory().find_item_stack("firearm-magazine")
  local furnace=player.get_main_inventory().find_item_stack("stone-furnace")
  repair.durability=137.5
  ammo.ammo=3
  furnace.health=0.375
  record("item_properties",player,{durability=repair.durability,ammo=ammo.ammo,health=furnace.health})
  player.get_main_inventory().set_filter(10,{name="coal",quality="uncommon",comparator="="})
  local condition_filters={}
  for i,comparator in ipairs{">","<","=",">=","<=","!="} do
    player.get_main_inventory().set_filter(10+i,{name="copper-ore",quality="rare",comparator=comparator})
    condition_filters[i]=player.get_main_inventory().get_filter(10+i)
  end
  record("condition_filters",player,condition_filters)
  player.force.recipes["transport-belt"].enabled=false
  player.set_quick_bar_slot(1,{name="iron-plate",quality="uncommon"})
  player.set_quick_bar_slot(2,nil)
  player.set_active_quick_bar_page(1,3)
  player.get_inventory(defines.inventory.character_guns).insert{name="pistol",count=1}
  player.get_inventory(defines.inventory.character_ammo).insert{name="firearm-magazine",count=10}
  player.character.selected_gun_index=1
  if not storage.query_chest then
    storage.blueprint_anchor=player.surface.create_entity{name="assembling-machine-1",position={12.5,-8.5},force=player.force}
    storage.query_chest=player.surface.create_entity{name="steel-chest",position={6,0},force=player.force}
    local inventory=storage.query_chest.get_inventory(defines.inventory.chest)
    inventory.insert{name="coal",count=20}
    inventory.set_bar(4)
    storage.empty_chest=player.surface.create_entity{name="steel-chest",position={8,0},force=player.force}
    storage.query_vehicle=player.surface.create_entity{name="car",position={20,0},force=player.force}
    storage.query_remote_surface=game.create_surface("mcp-query-remote",{width=64,height=64})
    storage.train_surface=game.create_surface("mcp-train-ui",{width=64,height=64})
    storage.train_surface.request_to_generate_chunks({0,0},1)
    storage.train_surface.force_generate_chunk_requests()
    -- Keep normal world-input targets free of generated entities on this disposable surface.
    for _,entity in pairs(storage.train_surface.find_entities_filtered{area={{-16,-24},{16,24}}}) do
      entity.destroy()
    end
    local tiles={}
    for x=-16,16 do for y=-24,24 do tiles[#tiles+1]={name="grass-1",position={x,y}} end end
    storage.train_surface.set_tiles(tiles)
    for y=-20,20,2 do
      assert(storage.train_surface.create_entity{name="straight-rail",position={0,y},direction=defines.direction.north,force=player.force})
    end
    storage.train_locomotive=assert(storage.train_surface.create_entity{name="locomotive",position={0,0},direction=defines.direction.north,force=player.force})
    for i,name in ipairs{"MCP Alpha","MCP Beta"} do
      local stop=assert(storage.train_surface.create_entity{name="train-stop",position={2,-16+12*i},direction=defines.direction.north,force=player.force})
      stop.backer_name=name
    end
    record("train_fixture",player,{unit=storage.train_locomotive.unit_number,position=storage.train_locomotive.position,surface=storage.train_surface.name})
  end
  record("world_fixture",player,{contents=player.get_main_inventory().get_contents(),chest=storage.query_chest.unit_number,
    empty_chest=storage.empty_chest.unit_number,chest_contents=storage.query_chest.get_inventory(defines.inventory.chest).get_contents()})
  player.gui.top.add{type="button",name="mcp_top",caption="MCP top tool"}
  player.gui.top.add{type="button",name="mcp_train",caption="MCP train fixture"}
  build(player)
end)

script.on_event(defines.events.on_gui_click,function(event)
  local player=game.get_player(event.player_index)
  local name=event.element.name
  record("click",player,{name=name,button=event.button,control=event.control,shift=event.shift,alt=event.alt})
  if name=="recreate" or name=="mcp_top" then build(player) end
  if name=="speed_one" then game.speed=1 end
  if name=="speed_four" then game.speed=4 end
  if name=="reset_position" then assert(player.teleport({0,0},game.surfaces[1])) end
  if name=="mcp_train" then
    if player.surface==storage.train_surface then
      player.teleport(storage.train_origin.position,storage.train_origin.surface)
      player.gui.screen.mcp_actions.visible=true
    else
      storage.train_origin={position=player.position,surface=player.surface}
      player.teleport({4,2},storage.train_surface)
      player.gui.screen.mcp_actions.visible=false
    end
  end
  if name=="query_remote" then
    player.set_controller{type=defines.controllers.remote,surface=storage.query_remote_surface,position={0,0}}
  end
  if name=="query_vehicle" then
    if player.vehicle then
      player.driving=false
      player.teleport(storage.query_vehicle_origin)
    else
      storage.query_vehicle_origin=player.position
      storage.query_vehicle.set_driver(player)
    end
  end
  if name=="query_spectator" then
    if player.character then
      storage.query_character=player.character
      player.set_controller{type=defines.controllers.spectator}
    else
      player.set_controller{type=defines.controllers.character,character=storage.query_character}
    end
  end
end)

script.on_event(defines.events.on_tick,function()
  storage.previous_walking=storage.previous_walking or {}
  for _,player in pairs(game.connected_players) do
    local walking=player.walking_state
    if walking.walking or storage.previous_walking[player.index] then
      log("INPUT_ACCEPTANCE " .. helpers.table_to_json{tick=game.tick,player=player.index,walking=walking.walking,direction=walking.direction,position=player.position})
    end
    storage.previous_walking[player.index]=walking.walking
  end
end)

local events={
  [defines.events.on_gui_checked_state_changed]="checked",
  [defines.events.on_gui_selection_state_changed]="selection",
  [defines.events.on_gui_text_changed]="text",
  [defines.events.on_gui_value_changed]="slider",
  [defines.events.on_gui_elem_changed]="element"
}
for event,kind in pairs(events) do
  script.on_event(event,function(e) record(kind,game.get_player(e.player_index),{name=e.element.name,
    selected_index=kind=="selection" and e.element.selected_index or nil}) end)
end
script.on_event(defines.events.on_research_started,function(event) record("research",nil,{name=event.research.name}) end)
script.on_event(defines.events.on_console_chat,function(event)
  record("chat",event.player_index and game.get_player(event.player_index),{message=event.message})
  if string.sub(event.message,1,20)=="MCP chat acceptance " then
    game.print{"","MCP chat fixture echo: ",event.message}
  end
end)
script.on_event(defines.events.on_train_schedule_changed,function(event)
  record("train_schedule",event.player_index and game.get_player(event.player_index),{id=event.train.id,schedule=event.train.schedule})
end)
script.on_event(defines.events.on_gui_opened,function(event)
  if event.entity then record("opened_entity",game.get_player(event.player_index),{name=event.entity.name,unit=event.entity.unit_number}) end
end)
script.on_event(defines.events.on_built_entity,function(event)
  local entity=event.entity
  record("built",game.get_player(event.player_index),{name=entity.name,position=entity.position,
    ghost_name=entity.type=="entity-ghost" and entity.ghost_name or nil})
end)
script.on_nth_tick(300,function()
  if storage.train_locomotive and storage.train_locomotive.valid then
    local train=storage.train_locomotive.train
    record("train_checkpoint",nil,{id=train.id,schedule=train.schedule,manual_mode=train.manual_mode})
  end
  for _,player in pairs(game.connected_players) do
    local f=player.gui.screen.mcp_actions
    if f then record("checkpoint",player,{checked=f.checked.state,selection=f.selection.selected_index,text=f.text_field.text,numeric=f.numeric_field.text,readonly=f.readonly.text,slider=f.slider.slider_value,element=f.choose.elem_value,toggle=f.toggle.toggled,location=f.location,research=player.force.current_research and player.force.current_research.name,quickbar=player.get_quick_bar_slot(1) and player.get_quick_bar_slot(1).name}) end
  end
  game.force_crc()
  log("UI_ACTION_FULL_CRC "..game.tick)
end)
