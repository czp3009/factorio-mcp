local function create_fixture(record)
  local surface=game.create_surface("mcp-query-expansion",{
    width=128,height=128,peaceful_mode=true,
    autoplace_controls={["enemy-base"]={frequency=0,size=0,richness=0}}
  })
  surface.request_to_generate_chunks({0,0},2)
  surface.force_generate_chunk_requests()
  for _,entity in pairs(surface.find_entities_filtered{area={{-40,-40},{40,40}}}) do entity.destroy() end
  local tiles={}
  for x=-40,40 do for y=-40,40 do tiles[#tiles+1]={name="grass-1",position={x,y}} end end
  surface.set_tiles(tiles)
  local force=game.forces.player
  local function create(name,x,y,quality)
    return assert(surface.create_entity{name=name,position={x,y},force=force,quality=quality})
  end
  local machine=create("assembling-machine-2",-8,0)
  machine.set_recipe("iron-gear-wheel","uncommon")
  machine.get_inventory(defines.inventory.crafter_input).insert{name="iron-plate",count=34,quality="uncommon"}
  machine.get_output_inventory().insert{name="iron-gear-wheel",count=5,quality="uncommon"}
  machine.get_module_inventory().insert{name="speed-module",count=1}
  local second=create("assembling-machine-2",0,0)
  second.set_recipe("copper-cable")
  second.get_inventory(defines.inventory.crafter_input).insert{name="copper-plate",count=23}
  local chest=create("steel-chest",8,0)
  local inventory=chest.get_inventory(defines.inventory.chest)
  inventory.insert{name="iron-plate",count=17}
  inventory.insert{name="copper-plate",count=9,quality="uncommon"}
  inventory.set_bar(8)
  local other_chest=create("steel-chest",12,0,"rare")
  other_chest.get_inventory(defines.inventory.chest).insert{name="iron-plate",count=11,quality="rare"}
  local tank=create("storage-tank",8,8)
  tank.insert_fluid{name="water",amount=1234,temperature=25}
  local belt=create("transport-belt",-8,8)
  belt.get_transport_line(1).insert_at_back{name="iron-plate"}
  belt.get_transport_line(2).insert_at_back{name="copper-plate",quality="uncommon"}
  for y=-20,20,2 do
    assert(surface.create_entity{name="straight-rail",position={24,y},direction=defines.direction.north,force=force})
  end
  local locomotive=assert(surface.create_entity{name="locomotive",position={24,0},direction=defines.direction.north,force=force})
  for i,name in ipairs{"MCP query alpha","MCP query beta"} do
    local stop=assert(surface.create_entity{name="train-stop",position={26,-16+12*i},direction=defines.direction.north,force=force})
    stop.backer_name=name
  end
  local schedule=locomotive.train.get_schedule()
  schedule.set_records{
    {station="MCP query alpha",wait_conditions={{type="time",compare_type="and",ticks=300}}},
    {station="MCP query beta",wait_conditions={{type="inactivity",compare_type="and",ticks=120}}}
  }
  local stats_surface=game.create_surface("mcp-query-statistics",{width=64,height=64,peaceful_mode=true})
  stats_surface.request_to_generate_chunks({0,0},1)
  stats_surface.force_generate_chunk_requests()
  stats_surface.create_global_electric_network()
  local generator=assert(stats_surface.create_entity{name="electric-energy-interface",position={0,0},force=force})
  generator.power_production=100000
  generator.power_usage=1000
  generator.electric_buffer_size=1000000
  local observer_force=game.create_force("mcp-query-observer")
  storage.query_expansion={surface=surface.name,machine=machine.unit_number,second=second.unit_number,
    chest=chest.unit_number,other_chest=other_chest.unit_number,tank=tank.unit_number,belt=belt.unit_number,
    locomotive=locomotive.unit_number,train_id=locomotive.train.id,statistics_surface=stats_surface.name,
    observer_force=observer_force.name}
  record("query_expansion",nil,storage.query_expansion)
end

local function prepare_player(player,record)
  local inventory=player.get_main_inventory()
  local index
  for i=1,#inventory do if not inventory[i].valid_for_read then index=i break end end
  local stack=assert(index and inventory[index])
  stack.set_stack{name="blueprint"}
  stack.set_blueprint_entities{
    {entity_number=1,name="assembling-machine-1",position={0,0},recipe="iron-gear-wheel",tags={source="MCP original"}},
    {entity_number=2,name="steel-chest",position={4,0}}
  }
  stack.set_blueprint_tiles{{name="stone-path",position={0,4}},{name="stone-path",position={1,4}}}
  record("query_expansion_player",player,{blueprint_slot=index,blueprint_entities=stack.get_blueprint_entities(),blueprint_tiles=stack.get_blueprint_tiles()})
end

return {create=create_fixture,prepare_player=prepare_player}
