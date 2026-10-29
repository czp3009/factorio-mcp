-- Disposable acceptance scenario. All setup runs through normal synchronized scenario events.
script.on_init(function()
  local surface = game.surfaces[1]
  surface.request_to_generate_chunks({0, 0}, 5)
  surface.force_generate_chunk_requests()
  local tiles = {}
  for y = -96, 96 do
    for x = -96, 96 do
      tiles[#tiles + 1] = {name = "grass-1", position = {x, y}}
    end
  end
  surface.set_tiles(tiles)
  for _, entity in pairs(surface.find_entities_filtered{area = {{-96, -96}, {96, 96}}, type = {"tree", "simple-entity", "resource"}}) do
    entity.destroy()
  end
  for y = 0, 39 do
    for x = 0, 39 do
      surface.create_entity{name = "wooden-chest", position = {x * 2 + 0.5, y * 2 + 0.5}, force = "player"}
    end
  end
end)

script.on_event(defines.events.on_player_created, function(event)
  local player = game.get_player(event.player_index)
  player.admin = false
  if not player.character then
    player.create_character()
  end
  player.teleport({-4, -4})
  player.character_inventory_slots_bonus = 2000
  local frame = player.gui.screen.add{type = "frame", name = "factorio_mcp_query_capacity", caption = "MCP query capacity fixture", direction = "vertical"}
  frame.visible = false
  frame.tags = {payload = string.rep("x", 17 * 1024 * 1024 + 7)}
  for index = 1, 5000 do
    frame.add{type = "label", name = "entry_" .. index, caption = "MCP bulk label " .. index}
  end
  local options = {}
  for index = 1, 1200 do
    options[index] = "MCP bulk option " .. index
  end
  frame.add{type = "drop-down", name = "options", items = options}
  player.gui.top.add{type = "button", name = "factorio_mcp_capacity_action", caption = "MCP capacity action"}
  for index = 1, 400 do
    player.print("MCP query capacity history " .. index)
  end
  log("factorio-mcp query capacity fixture ready for player " .. player.index)
end)

script.on_event(defines.events.on_gui_click, function(event)
  if event.element and event.element.valid and event.element.name == "factorio_mcp_capacity_action" then
    log("QUERY_CAPACITY_ACTION " .. helpers.table_to_json{player_index = event.player_index, tick = game.tick})
  end
end)

script.on_event(defines.events.on_player_changed_position, function(event)
  local player = game.get_player(event.player_index)
  log("QUERY_CAPACITY_POSITION " .. helpers.table_to_json{player_index = player.index, position = player.position})
end)

script.on_event(defines.events.on_console_chat, function(event)
  log("QUERY_CAPACITY_CHAT " .. helpers.table_to_json{player_index = event.player_index, message = event.message})
end)

script.on_nth_tick(600, function()
  game.force_crc()
  log("factorio-mcp query capacity full CRC requested at tick " .. game.tick)
end)
