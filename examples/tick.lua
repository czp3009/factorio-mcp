local event = ...
return {
  tick = game.tick,
  event_tick = event.tick,
  players = #game.connected_players,
  surface = game.surfaces[1].name
}
