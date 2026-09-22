"""Semantic action acceptance with independent server observations."""
import json
import re
import sys
import time
import uuid


def server_state(fixture, server, expression):
    marker = f'ACTION_STATE_{time.monotonic_ns()}:'
    fixture.console(server, f'print({json.dumps(marker)}..helpers.table_to_json({expression}))')
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        match = re.search(rf'^{re.escape(marker)}([^\n]+)', fixture.log('server'), re.M)
        if match:
            return json.loads(match[1])
        time.sleep(.05)
    raise AssertionError(f'Server did not answer: {expression}')


def verify_player_actions(api, fixture, server, client, records):
    player = 'game.connected_players[1]'
    surface = f'{player}.surface'
    furnace = f'{surface}.find_entity("stone-furnace",{{3,0}})'
    chest = f'{surface}.find_entity("wooden-chest",{{3.5,3.5}})'
    assembler = f'{surface}.find_entity("assembling-machine-1",{{-2.5,-2.5}})'
    results = []

    def call(name, arguments=None):
        print(f'Checking {name}: {arguments or {}}', file=sys.stderr, flush=True)
        try:
            result = api.call(name, arguments)
        except AssertionError:
            try:
                print(api.call('player', {}), file=sys.stderr, flush=True)
                if name=='configure_entity':
                    print(api.call('inspect_entity', {'view':'configuration','x':arguments['x'],'y':arguments['y']}),file=sys.stderr,flush=True)
                    print(api.call('inspect_blueprint', {'include_data':True}),file=sys.stderr,flush=True)
                print(server_state(fixture, server, f'{{position={player}.position,cursor={player}.cursor_stack.valid_for_read and {{name={player}.cursor_stack.name,count={player}.cursor_stack.count}} or nil}}'), file=sys.stderr, flush=True)
                (fixture.folder/'failed-action.png').write_bytes(api.call('screenshot'))
            except Exception as diagnostic:
                print(f'Diagnostic collection failed: {diagnostic}', file=sys.stderr, flush=True)
            raise
        results.append({'tool': name, 'arguments': arguments or {}, 'result': result})
        return result

    assert call('cancel_crafting', {'index':1, 'count':2})['cancelled'] == 2
    call('clear_cursor')
    for item, inventory in [('pistol','character_guns'), ('firearm-magazine','character_ammo'), ('shotgun','character_guns'), ('shotgun-shell','character_ammo'), ('power-armor','character_armor')]:
        call('equip', {'name':item})
        assert server_state(fixture, server, f'{{count={player}.get_inventory(defines.inventory.{inventory}).get_item_count("{item}")}}')['count'] > 0
    equipment = call('player', {'section':'equipment'})['items']
    assert equipment[0]['equipment'][0]['name'] == 'solar-panel-equipment', equipment
    call('inventory_slot', {'inventory':'character_armor','slot':1,'action':'open'})
    call('equipment', {'action':'take','x':0,'y':0})
    grid = f'{player}.get_inventory(defines.inventory.character_armor)[1].grid'
    assert server_state(fixture, server, f'{{count=#{grid}.equipment}}')['count'] == 0
    call('equipment', {'action':'put','x':1,'y':1})
    assert server_state(fixture, server, f'{{name={grid}.get{{1,1}}.name}}')['name'] == 'solar-panel-equipment'
    call('inventory_slot', {'inventory':'character_armor','slot':1,'action':'take'})
    assert server_state(fixture, server, f'{{count={player}.get_inventory(defines.inventory.character_armor).get_item_count("power-armor")}}')['count'] == 0
    call('inventory_slot', {'inventory':'character_armor','slot':1,'action':'put'})
    assert server_state(fixture, server, f'{{count={player}.get_inventory(defines.inventory.character_armor).get_item_count("power-armor")}}')['count'] == 1
    call('inventory_slot', {'inventory':'character_armor','slot':1,'action':'transfer'})
    assert server_state(fixture, server, f'{{count={player}.get_inventory(defines.inventory.character_armor).get_item_count("power-armor")}}')['count'] == 0
    call('equip', {'name':'power-armor'})
    split = call('inventory_slot', {'inventory':'character_ammo','slot':1,'action':'take_half'})
    assert split['cursor']['count'] == 5, split
    call('inventory_slot', {'inventory':'character_ammo','slot':1,'action':'put_one'})
    assert server_state(fixture, server, f'{{count={player}.get_inventory(defines.inventory.character_ammo).get_item_count("firearm-magazine")}}')['count'] == 6
    call('clear_cursor')
    call('equip', {'name':'firearm-magazine'})
    assert server_state(fixture, server, f'{{count={player}.get_inventory(defines.inventory.character_ammo).get_item_count("firearm-magazine")}}')['count'] == 10
    slots = call('inventory', {'view':'slots','limit':128})['items']
    coal_slot = next(item['slot'] for item in slots if item['name']=='coal')
    call('inventory_slot', {'slot':coal_slot,'action':'set_filter'})
    assert server_state(fixture,server,f'{{name={player}.get_main_inventory().get_filter({coal_slot}).name}}')['name'] == 'coal'
    call('inventory_slot', {'slot':coal_slot,'action':'clear_filter'})
    call('inventory_slot', {'slot':coal_slot,'action':'take_half'})
    slots = call('inventory', {'view':'slots','limit':128})['items']
    plate_slot = next(item['slot'] for item in slots if item['name']=='iron-plate')
    swapped = call('inventory_slot', {'slot':plate_slot,'action':'swap'})
    assert swapped['cursor']['name'] == 'iron-plate', swapped
    call('clear_cursor')
    assert server_state(fixture, server, f'{{count={player}.get_main_inventory().get_item_count("coal")}}')['count'] == 10
    gun = server_state(fixture, server, f'{{index={player}.character.selected_gun_index}}')['index']
    call('switch_weapon')
    assert server_state(fixture, server, f'{{index={player}.character.selected_gun_index}}')['index'] != gun
    call('switch_weapon')
    assert server_state(fixture, server, f'{{index={player}.character.selected_gun_index}}')['index'] == gun
    initial = server_state(fixture, server, f'{player}.position')
    call('move', {'direction':'right'})
    moved = server_state(fixture, server, f'{{position={player}.position,walking={player}.walking_state.walking}}')
    assert moved['position']['x'] > initial['x'] and not moved['walking'], moved
    page=server_state(fixture,server,f'{{page={player}.get_active_quick_bar_page(1)}}')['page']
    call('quickbar_page')
    assert server_state(fixture,server,f'{{page={player}.get_active_quick_bar_page(1)}}')['page'] != page
    call('quickbar_page', {'previous':True})
    assert server_state(fixture,server,f'{{page={player}.get_active_quick_bar_page(1)}}')['page'] == page
    call('quickbar', {'slot':1})
    call('build', {'x':3, 'y':0})
    assert server_state(fixture, server, f'{{exists={furnace}~=nil}}')['exists']
    call('build', {'x':5, 'y':4, 'ghost':True})
    assert server_state(fixture, server, f'{{count={surface}.count_entities_filtered{{name="entity-ghost",ghost_name="stone-furnace",position={{5,4}}}}}}')['count'] == 1
    call('clear_cursor')
    call('pipette', {'x':3, 'y':0})
    assert call('pipette', {'x':3, 'y':0})['unchanged']
    call('drop_item', {'x':.5, 'y':.5})
    call('pickup')
    assert server_state(fixture, server, f'{{count={surface}.count_entities_filtered{{type="item-entity",position={{0,0}},radius=1}}}}')['count'] == 0
    call('clear_cursor')
    call('take_item', {'name':'stone-brick'})
    call('pave', {'x':1.5, 'y':4.5})
    assert server_state(fixture, server, f'{{name={surface}.get_tile(1,4).name}}')['name'] == 'stone-path'
    call('clear_cursor')
    call('take_item', {'name':'iron-ore'})
    call('transfer', {'x':3, 'y':0})
    assert server_state(fixture, server, f'{{count={furnace}.get_item_count("iron-ore")}}')['count'] == 10
    call('clear_cursor')
    call('take_item', {'name':'coal'})
    call('transfer', {'x':3, 'y':0})
    assert server_state(fixture, server, f'{{count={furnace}.get_item_count("coal")}}')['count'] > 0
    call('clear_cursor')
    inserter = f'{surface}.find_entity("inserter",{{-2.5,.5}})'
    original = server_state(fixture, server, f'{{direction={inserter}.direction}}')['direction']
    call('rotate', {'x':-2.5, 'y':.5})
    assert server_state(fixture, server, f'{{direction={inserter}.direction}}')['direction'] != original
    call('rotate', {'x':-2.5, 'y':.5, 'reverse':True})
    assert server_state(fixture, server, f'{{direction={inserter}.direction}}')['direction'] == original
    assert call('craft', {'recipe':'iron-gear-wheel', 'count':3})['queued'] == 3
    assert server_state(fixture, server, '{count=storage.queued_crafts}')['count'] == 43
    call('mine', {'x':3, 'y':0})
    assert not server_state(fixture, server, f'{{exists={furnace}~=nil,mining={player}.mining_state.mining}}')['exists']
    call('mine', {'x':2.5, 'y':.5})
    assert not server_state(fixture, server, f'{{mining={player}.mining_state.mining}}')['mining']
    call('clear_cursor')
    call('take_item', {'name':'iron-plate'})
    count = server_state(fixture, server, f'{{count={player}.cursor_stack.count}}')['count']
    call('transfer', {'x':3.5, 'y':3.5, 'half':True})
    deposited = server_state(fixture, server, f'{{count={chest}.get_item_count("iron-plate")}}')['count']
    assert 0 < deposited < count, (count, deposited)
    call('clear_cursor')
    inventory = call('inventory', {'view':'slots','x':3.5,'y':3.5})
    assert inventory['items'][0]['count'] == deposited, inventory
    call('open_entity', {'x':3.5,'y':3.5})
    call('inventory_slot', {'inventory':'chest','slot':1,'action':'take_half'})
    assert server_state(fixture, server, f'{{count={chest}.get_item_count("iron-plate")}}')['count'] < deposited
    call('inventory_slot', {'inventory':'chest','slot':1,'action':'put'})
    assert server_state(fixture, server, f'{{count={chest}.get_item_count("iron-plate")}}')['count'] == deposited
    call('transfer', {'x':3.5, 'y':3.5})
    assert server_state(fixture, server, f'{{count={chest}.get_item_count("iron-plate")}}')['count'] == 0
    call('open_entity', {'x':-3.5, 'y':-3.5})
    for recipe in ('iron-gear-wheel', 'copper-cable'):
        call('set_recipe', {'recipe':recipe})
        assert server_state(fixture, server, f'{{recipe={assembler}.get_recipe().name}}')['recipe'] == recipe
    call('copy_entity_settings', {'source':{'x':-2.5,'y':-2.5},'target':{'x':-2.5,'y':4.5}})
    destination = f'{surface}.find_entity("assembling-machine-1",{{-2.5,4.5}})'
    assert server_state(fixture, server, f'{{recipe={destination}.get_recipe().name}}')['recipe'] == 'copper-cable'
    call('attack', {'x':3.5, 'y':3.5})
    damaged = server_state(fixture, server, f'{{health={chest}.health,shooting={player}.shooting_state.state}}')
    assert damaged['health'] < 100 and damaged['shooting'] == 0, damaged
    call('take_item', {'name':'repair-pack'})
    call('repair', {'x':3.5, 'y':3.5})
    healed = server_state(fixture, server, f'{{health={chest}.health,repairing={player}.repair_state.repairing}}')
    assert healed['health'] > damaged['health'] and not healed['repairing'], healed
    # A callback exception removes that observer. Cleanup must still release the action gate.
    marker = f'INPUT_FAILURE_ARMED_{time.monotonic_ns()}'
    fixture.console(server, f'''local b=rawget(_G,"__factorio_mcp_resident_v1"); if b then
        local original=b.native; b.native=function(operation,...)
            if operation=="aim" then b.native=original;return "fixture input failure" end
            return original(operation,...)
        end; print({json.dumps(marker)}) end'''.replace('\n', ' '))
    fixture.wait_log('client', marker, client, 5)
    failed = api.call('attack', {'x':3.5, 'y':3.5}, error=True)
    assert 'cannot scope world position' in failed['content'][0]['text'], failed
    call('move', {'direction':'left'})
    call('clear_cursor')
    assert call('vehicle')['driving']
    assert server_state(fixture, server, f'{{driving={player}.driving}}')['driving']
    vehicle_before=server_state(fixture,server,f'{player}.vehicle.position')
    call('drive', {'direction':'forward'})
    driven=server_state(fixture,server,f'{{position={player}.vehicle.position,riding={player}.riding_state,neutral=defines.riding.acceleration.nothing}}')
    assert driven['position']!=vehicle_before and driven['riding']['acceleration']==driven['neutral'],driven
    vehicle = call('inspect_entity', driven['position'])
    assert vehicle['burner']['currently_burning'] == {'name':'coal','quality':'normal'}, vehicle
    for direction in ('left','right','backward'):
        call('drive', {'direction':direction})
    assert not call('vehicle')['driving']
    health=server_state(fixture,server,f'{{health={player}.character.health}}')['health']
    call('take_item', {'name':'raw-fish'})
    point=server_state(fixture,server,f'{player}.position')
    call('use_item',point)
    assert server_state(fixture,server,f'{{health={player}.character.health}}')['health'] >= health
    assert server_state(fixture,server,f'{{count={player}.cursor_stack.count}}')['count'] == 1
    call('clear_cursor')
    call('inspect_entity', {'x':3.5,'y':3.5})
    call('inspect_area', {'mode':'tiles','radius':2})
    call('take_item', {'name':'blueprint'})
    assert call('inspect_blueprint', {'include_data':True})['data']['entities'][0]['name'] == 'transport-belt'
    call('place_blueprint', {'x':5.5, 'y':-4.5})
    assert server_state(fixture, server, f'{{count={surface}.count_entities_filtered{{name="entity-ghost",ghost_name="transport-belt",position={{5.5,-4.5}},radius=1}}}}')['count'] == 1
    ghosts = call('inspect_area', {'mode':'ghosts','x':5.5,'y':-4.5,'radius':1})
    assert ghosts['items'][0]['ghost_name'] == 'transport-belt', ghosts
    call('clear_cursor')
    created = call('import_blueprint', {'data':{'label':'MCP acceptance','entities':[
        {'entity_number':1,'name':'transport-belt','position':{'x':0,'y':0}},
        {'entity_number':2,'name':'transport-belt','position':{'x':1,'y':0}}]}})
    assert created['entities'] == 2, created
    assert server_state(fixture, server, f'{{count=#{player}.cursor_stack.get_blueprint_entities()}}')['count'] == 2
    encoded = call('inspect_blueprint', {'include_string':True})['string']
    call('clear_cursor')
    call('import_blueprint', {'string':encoded})
    call('place_blueprint', {'x':4.5,'y':-7.5})
    assert server_state(fixture, server, f'{{count={surface}.count_entities_filtered{{name="entity-ghost",ghost_name="transport-belt",position={{4.5,-7.5}},radius=2}}}}')['count'] == 2
    call('clear_cursor')
    call('import_blueprint', {'data':{'tiles':[{'name':'stone-path','position':{'x':0,'y':0}}]}})
    call('place_blueprint', {'x':3.5,'y':-5.5})
    assert server_state(fixture,server,f'{{count={surface}.count_entities_filtered{{name="tile-ghost",position={{3.5,-5.5}},radius=2}}}}')['count'] == 1
    call('clear_cursor')
    from entity_settings import verify_entity_settings
    verify_entity_settings(api, fixture, server, client, records)
    # A partially initialized task must neither block the FIFO nor leave observers behind.
    source = '''return {callback=function()
        local b=__factorio_mcp_resident_v1; b.player_action=true
        b.observe("tick",function() b.fixture_orphan=true;return true end,function() end)
        error("fixture initialization failure")
    end}'''.replace('\n',' ')
    fixture.console(server, f'local submit=rawget(_G,"__factorio_mcp_submit_v1");if submit then submit({json.dumps(str(uuid.uuid4()))},{json.dumps(source)}) end')
    api.call('take_item', {'name':'nonexistent-item'}, error=True)
    api.call('craft', {'recipe':'nonexistent-recipe','count':1}, error=True)
    assert call('move', {'direction':'left'})['walking']['walking'] is False
    marker = f'INITIALIZATION_ROLLBACK_{time.monotonic_ns()}'
    fixture.console(server, f'local b=rawget(_G,"__factorio_mcp_resident_v1");if b then assert(not b.fixture_orphan);print({json.dumps(marker)}) end')
    fixture.wait_log('client',marker,client,5)
    records.append({'test':'Player operations with independent server state', 'operations':results})
