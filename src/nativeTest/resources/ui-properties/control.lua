-- Disposable UI-only acceptance fixture, created by synchronized scenario events.
script.on_event(defines.events.on_player_created, function(event)
  local player = game.get_player(event.player_index)
  player.admin = false
  local frame = player.gui.screen.add{
    type = "frame", name = "factorio_mcp_radio_fixture", caption = "MCP radio fixture", direction = "vertical"
  }
  frame.auto_center = true
  for _, state in ipairs{"left", "right", "none"} do
    frame.add{
      type = "switch", name = "state_" .. state, switch_state = state, allow_none_state = true,
      left_label_caption = "MCP left", right_label_caption = "MCP right"
    }
  end
  local progress = frame.add{type = "progressbar", name = "progress", value = 0.375}
  progress.style.width = 240
  log("factorio-mcp UI properties fixture ready for player " .. player.index)
end)

script.on_nth_tick(300, function()
  game.force_crc()
  log("UI_PROPERTIES_FULL_CRC " .. game.tick)
end)
