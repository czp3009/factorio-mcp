script.on_init(function()
  game.surfaces[1].request_to_generate_chunks({0,0}, 1)
  game.surfaces[1].force_generate_chunk_requests()
  local surface = game.surfaces[1]
  for _, entity in pairs(surface.find_entities_filtered{area={{-16,-16},{16,16}}}) do entity.destroy() end
  local tiles = {}
  for x=-16,16 do for y=-16,16 do tiles[#tiles+1]={name="grass-1", position={x,y}} end end
  surface.set_tiles(tiles)
  surface.create_entity{name="character",position={12,12},force=game.forces.player}
  game.forces.player.technologies["electronics"].researched=true
  game.forces.player.technologies["automation-science-pack"].researched=true
  for x=2,6 do for y=0,2 do surface.create_entity{name="iron-ore",position={x,y},amount=1000} end end
end)
script.on_event(defines.events.on_tick, function(event)
  storage.original_ticks=(storage.original_ticks or 0)+1
  storage.original_tick=event.tick
end)
rawset(_G, '__native_test_original', script.get_event_handler(defines.events.on_tick))
script.on_event(defines.events.on_pre_player_crafted_item, function(event)
  storage.queued_crafts=(storage.queued_crafts or 0)+event.queued_count
end)
script.on_event(defines.events.on_player_created, function(event)
  local p=game.get_player(event.player_index)
  assert(p.character, "test requires a real character")
  p.admin=false
  p.teleport({0,0})
  p.insert{name="iron-plate",count=100}
  p.insert{name="stone-furnace",count=2}
  p.insert{name="iron-ore",count=10}
  p.insert{name="coal",count=10}
  p.insert{name="repair-pack",count=2}
  p.insert{name="stone-brick",count=10}
  p.insert{name="blueprint",count=1}
  p.get_main_inventory().find_item_stack("blueprint").set_blueprint_entities{{entity_number=1,name="transport-belt",position={0,0}}}
  p.insert{name="transport-belt",count=4}
  p.set_quick_bar_slot(1, "stone-furnace")
  p.set_quick_bar_slot(2, "transport-belt")
  p.surface.create_entity{name="wooden-chest",position={3,3},force=p.force}
  p.surface.create_entity{name="inserter",position={-3,0},force=p.force}
  p.surface.create_entity{name="splitter",position={5,-1},force=p.force}
  p.surface.create_entity{name="arithmetic-combinator",position={5,-2},force=p.force}
  p.surface.create_entity{name="decider-combinator",position={7,-2},force=p.force}
  p.surface.create_entity{name="train-stop",position={2,-6},force=p.force}
  p.surface.create_entity{name="assembling-machine-1",position={-3,-3},force=p.force}
  local car=p.surface.create_entity{name="car",position={0,-3},force=p.force}
  car.insert{name="coal",count=5}
  p.insert{name="raw-fish",count=2}
  p.character.health=p.character.health-30
  p.surface.create_entity{name="assembling-machine-1",position={-3,4},force=p.force}
  p.cursor_ghost="stone-furnace"
  p.get_inventory(defines.inventory.character_guns).clear()
  p.get_inventory(defines.inventory.character_ammo).clear()
  p.get_main_inventory().insert{name="pistol",count=1}
  p.get_main_inventory().insert{name="firearm-magazine",count=10}
  p.get_main_inventory().insert{name="shotgun",count=1}
  p.get_main_inventory().insert{name="shotgun-shell",count=10}
  p.get_main_inventory().insert{name="power-armor",count=1}
  p.get_main_inventory().find_item_stack("power-armor").grid.put{name="solar-panel-equipment",position={0,0}}
  -- Keep the cancellation fixture alive while semantic read tests run.
  p.character_crafting_speed_modifier=-0.9
  p.begin_crafting{recipe="iron-gear-wheel",count=40}
end)
