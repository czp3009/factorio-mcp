"""Space Age acceptance through public tools, with synchronized fixture preparation."""
import json
import sys
import time

from player_actions import server_state
from semantic_tools import console_synced


def verify_space_tools(api, fixture, server, client, records):
    results = []

    def call(name, arguments=None):
        print(f'Checking {name}: {arguments or {}}', file=sys.stderr, flush=True)
        value = api.call(name, arguments)
        results.append({'tool': name, 'arguments': arguments or {}, 'result': value})
        return value

    def wait_state(expression, predicate, timeout=40):
        deadline = time.monotonic() + timeout
        while True:
            state = server_state(fixture, server, expression)
            if predicate(state):
                return state
            assert time.monotonic() < deadline, state
            time.sleep(.2)

    player = 'game.connected_players[1]'
    silo = "game.surfaces.nauvis.find_entity('rocket-silo',{200,0})"
    console_synced(fixture, server, '''
        local p=game.connected_players[1]
        p.clear_cursor();p.exit_remote_view()
        p.surface.request_to_generate_chunks({200,0},2);p.surface.force_generate_chunk_requests()
        p.teleport({192,0},game.surfaces.nauvis)
        p.admin=false
        for _,tech in pairs(p.force.technologies) do tech.researched=true end
        for _,id in ipairs{defines.inventory.character_main,defines.inventory.character_ammo,defines.inventory.character_trash} do p.character.get_inventory(id).clear() end
        local surface=game.surfaces.nauvis
        for _,entity in pairs(surface.find_entities_filtered{area={{195,-10},{210,10}}}) do entity.destroy() end
        local tiles={};for x=190,210 do for y=-10,10 do tiles[#tiles+1]={name='grass-1',position={x,y}} end end
        surface.set_tiles(tiles)
        local silo=surface.create_entity{name='rocket-silo',position={200,0},force=p.force}
        surface.create_entity{name='substation',position={194,-5},force=p.force}
        local power=surface.create_entity{name='electric-energy-interface',position={194,-8},force=p.force}
        power.power_production=1000000000;power.electric_buffer_size=1000000000;power.energy=1000000000
        silo.use_transitional_requests=false;silo.rocket_parts=silo.prototype.rocket_parts_required
        silo.get_inventory(defines.inventory.rocket_silo_rocket).insert{name='iron-plate',count=100}
    '''.replace('\n', ' '))
    assert not call('player')['admin']
    for section in ('locations', 'connections', 'surfaces', 'platforms'):
        assert 'items' in call('inspect_space', {'section': section})
    call('remote_view', {'surface': 'nauvis', 'x': 200, 'y': 0})
    ordered = call('create_platform')
    platform_id = ordered['index']
    platform = f'game.forces.player.platforms[{platform_id}]'
    console_synced(fixture, server, f'{platform}.apply_starter_pack()')
    surface = wait_state(f'{{name={platform}.surface and {platform}.surface.name}}', lambda s: s.get('name'))['name']
    call('open_entity', {'x': 200, 'y': 0})
    ready_status = server_state(fixture, server, '{value=defines.rocket_silo_status.rocket_ready}')['value']
    wait_state(f'{{status={silo}.rocket_silo_status}}', lambda s: s['status'] == ready_status)
    call('launch_rocket', {'platform': platform_id})
    wait_state(f'{{count={platform}.hub.get_inventory(defines.inventory.hub_main).get_item_count("iron-plate")}}', lambda s: s['count'] >= 100)
    console_synced(fixture, server, f'''
        local platform={platform};local surface=platform.surface
        local tiles={{}};for x=-10,10 do for y=-8,8 do tiles[#tiles+1]={{name='space-platform-foundation',position={{x,y}}}} end end
        surface.set_tiles(tiles)
        local inventory=platform.hub.get_inventory(defines.inventory.hub_main)
        for _,name in ipairs{{'transport-belt','space-platform-foundation','thruster','firearm-magazine'}} do inventory.insert{{name=name,count=100}} end
        surface.create_entity{{name='gun-turret',position={{7,5}},force=platform.force}}
        surface.create_entity{{name='chemical-plant',position={{-6,0}},force=platform.force}}
        platform.force.chart(surface,{{{{-20,-20}},{{20,20}}}})
        local p={player};p.set_quick_bar_slot(1,'transport-belt');p.set_quick_bar_slot(2,'space-platform-foundation')
    '''.replace('\n', ' '))
    call('remote_view', {'surface': surface, 'x': 0, 'y': 0})
    call('quickbar', {'slot': 1})
    call('build', {'x': 4.5, 'y': -3.5})
    wait_state(f'{{count={platform}.surface.count_entities_filtered{{name="transport-belt",position={{4.5,-3.5}}}}}}', lambda s: s['count'] == 1)
    call('clear_cursor')
    call('mine', {'x': 4.5, 'y': -3.5})
    wait_state(f'{{count={platform}.surface.count_entities_filtered{{name="transport-belt",position={{4.5,-3.5}}}}}}', lambda s: s['count'] == 0)
    assert not server_state(fixture, server, f'{{mining={player}.mining_state.mining}}')['mining']
    call('quickbar', {'slot': 2})
    call('pave', {'x': 11, 'y': 0})
    wait_state(f'{{name={platform}.surface.get_tile(11,0).name}}', lambda s: s['name'] == 'space-platform-foundation')
    call('clear_cursor')
    api.call('configure_entity', {'x': 7, 'y': 5, 'settings': {'item_requests': [{'name': 'firearm-magazine', 'inventory': 'turret_ammo', 'slot': 1, 'count': 10}]}}, error=True)
    assert server_state(fixture, server, f'{{count={platform}.surface.find_entity("gun-turret",{{7,5}}).get_item_count("firearm-magazine")}}')['count'] == 0
    call('configure_entity', {'x': -6, 'y': 0, 'settings': {'recipe': 'thruster-fuel'}})
    assert call('inspect_entity', {'x': -6, 'y': 0})['recipe'] == 'thruster-fuel'
    call('clear_cursor')
    call('configure_entity', {'x': 0, 'y': 0, 'settings': {'request_missing_construction_materials': True}})
    console_synced(fixture, server, f"local section={platform}.hub.get_logistic_sections().add_section();section.set_slot(1,{{value={{type='item',name='copper-plate',quality='normal',comparator='='}},min=50}})")
    section = next(s['index'] for s in call('inspect_entity', {'x': 0, 'y': 0, 'view': 'configuration'})['settings']['logistic_sections'] if s['manual'])
    call('clear_cursor')
    call('open_entity', {'x': 0, 'y': 0})
    call('logistic_section', {'index': section, 'active': False})
    call('logistic_section', {'index': section, 'active': True})
    assert call('logistic_section', {'index': section, 'active': True})['unchanged']
    assert server_state(fixture, server, f'{{active={platform}.hub.get_logistic_sections().get_section({section}).active}}')['active']
    call('platform_schedule', {'action': 'append', 'location': 'nauvis'})
    call('platform_schedule', {'action': 'append', 'location': 'vulcanus'})
    removed = call('platform_schedule', {'action': 'remove', 'index': 1})
    assert removed['remaining'] == 1
    assert server_state(fixture, server, f'{{station={platform}.schedule.records[1].station}}')['station'] == 'vulcanus'
    call('platform_schedule', {'action': 'pause'})
    assert call('platform_schedule', {'action': 'pause'})['unchanged']
    call('clear_cursor')
    call('import_blueprint', {'data': {'entities': [{'entity_number': 1, 'name': 'thruster', 'position': {'x': 0, 'y': 0}}]}})
    call('place_blueprint', {'x': 0, 'y': 6.5})
    wait_state(f'{{count={platform}.surface.count_entities_filtered{{name="thruster"}}}}', lambda s: s['count'] == 1)
    console_synced(fixture, server, f'''
        local platform={platform};local engine=platform.surface.find_entity('thruster',{{0,6.5}})
        engine.insert_fluid{{name='thruster-fuel',amount=1000000}};engine.insert_fluid{{name='thruster-oxidizer',amount=1000000}}
        for _,entity in pairs(platform.surface.find_entities()) do if entity.health then entity.destructible=false end end
    '''.replace('\n', ' '))
    call('clear_cursor')
    call('open_entity', {'x': 0, 'y': 0})
    call('platform_schedule', {'action': 'go', 'index': 1})
    journey = wait_state(f'{{speed={platform}.speed,connection={platform}.space_connection and {platform}.space_connection.name,distance={platform}.distance}}', lambda s: s.get('connection') and s['speed'] > 0)
    call('platform_schedule', {'action': 'pause'})
    # Arrival setup isolates passenger descent and cargo downlink from travel duration.
    console_synced(fixture, server, f'''
        local platform={platform};platform.space_location=prototypes.space_location.nauvis;platform.speed=0
        local p={player};p.clear_cursor()
        for _,id in ipairs{{defines.inventory.character_main,defines.inventory.character_ammo,defines.inventory.character_trash}} do p.character.get_inventory(id).clear() end
        assert(p.enter_space_platform(platform))
    '''.replace('\n', ' '))
    landed = call('land_player')
    assert landed['planet'] == 'nauvis', landed
    assert server_state(fixture, server, f'{{landed={player}.physical_surface.name=="nauvis" and not {player}.character.cargo_pod}}')['landed']
    call('remote_view', {'surface': surface, 'x': 0, 'y': 0})
    call('open_entity', {'x': 0, 'y': 0})
    slots = call('inventory', {'name': 'hub_main', 'x': 0, 'y': 0, 'view': 'slots'})['items']
    slot = next(s['slot'] for s in slots if s['name'] == 'iron-plate')
    before = server_state(fixture, server, f'{{count={platform}.hub.get_item_count("iron-plate")}}')['count']
    call('inventory_slot', {'inventory': 'hub_main', 'slot': slot, 'action': 'send_to_planet'})
    assert server_state(fixture, server, f'{{count={platform}.hub.get_item_count("iron-plate")}}')['count'] < before
    fixture.verify_crc(server, client)
    records.append({'test': 'Space Age tools with authoritative effects and full CRC', 'journey': journey, 'actions': results})


def verify_planet_tools(api, fixture, server, client, records):
    console_synced(fixture, server, '''
        local surface=game.planets.gleba.create_surface()
        surface.request_to_generate_chunks({0,0},1);surface.force_generate_chunk_requests()
        local p=game.connected_players[1];p.clear_cursor();p.exit_remote_view()
        for _,entity in pairs(surface.find_entities_filtered{area={{-8,-8},{8,8}}}) do entity.destroy() end
        local tiles={};for x=-8,8 do for y=-8,8 do tiles[#tiles+1]={name='natural-yumako-soil',position={x,y}} end end
        surface.set_tiles(tiles);p.teleport({0,0},surface);p.insert{name='yumako-seed',count=2}
        p.force.chart(surface,{{-16,-16},{16,16}})
    '''.replace('\n', ' '))
    assert api.call('prototypes', {'kind': 'items', 'name': 'yumako-seed'})['items'][0]['plant_result'] == 'yumako-tree'
    api.call('take_item', {'name': 'yumako-seed'})
    planted = api.call('build', {'x': 2.5, 'y': .5})
    assert server_state(fixture, server, '{count=game.surfaces.gleba.count_entities_filtered{name="yumako-tree",position={2.5,.5}}}')['count'] == 1
    console_synced(fixture, server, '''
        local p=game.connected_players[1];p.clear_cursor()
        local surface=game.surfaces.nauvis
        local tiles={};for x=156,184 do for y=-6,6 do tiles[#tiles+1]={name='grass-1',position={x,y}} end end
        surface.set_tiles(tiles)
        for _,entity in pairs(surface.find_entities_filtered{area={{170,-5},{185,5}}}) do entity.destroy() end
        p.teleport({160,0},surface)
        p.get_inventory(defines.inventory.character_guns).clear();p.get_inventory(defines.inventory.character_ammo).clear()
        p.get_inventory(defines.inventory.character_guns).insert{name='rocket-launcher',count=1}
        p.get_inventory(defines.inventory.character_ammo).insert{name='capture-robot-rocket',count=1}
        p.character.selected_gun_index=1
        local spawner=surface.create_entity{name='biter-spawner',position={180,0},force=game.forces.enemy};spawner.active=false
    '''.replace('\n', ' '))
    fired = api.call('attack', {'x': 180, 'y': 0})
    assert fired['action'] == 'fired', fired
    assert server_state(fixture, server, '{released=game.connected_players[1].shooting_state.state==defines.shooting.not_shooting,ammo=game.connected_players[1].get_inventory(defines.inventory.character_ammo).get_item_count()}') == {'released': True, 'ammo': 0}
    deadline = time.monotonic() + 90
    while not server_state(fixture, server, '{count=game.surfaces.nauvis.count_entities_filtered{name="captive-biter-spawner",position={180,0},force="player"}}')['count']:
        assert time.monotonic() < deadline, 'Capture projectile did not convert the spawner'
        time.sleep(1)
    fixture.verify_crc(server, client)
    records.append({'test': 'Planting seeds and finite capture-rocket firing', 'planting': planted, 'firing': fired})
