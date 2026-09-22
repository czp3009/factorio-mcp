"""Live semantic reads, bounded analysis and asynchronous wait acceptance."""
import json
import sys
import time

from player_actions import server_state


def console_synced(fixture, server, source):
    marker = f'SEMANTIC_FIXTURE_{time.monotonic_ns()}'
    fixture.console(server, f'{source};print({json.dumps(marker)})')
    fixture.wait_log('client', marker, fixture.graphical, 5)


def verify_semantic_tools(api, fixture, server, records):
    recipe = api.call('recipes', {'name':'iron-gear-wheel'})['items'][0]
    assert recipe['name'] == 'iron-gear-wheel' and recipe['ingredients'] and recipe['products'], recipe
    assert 'enabled' not in recipe and 'craftable_count' not in recipe, recipe
    matches = api.call('recipes', {'search':'IRON-GEAR', 'ingredient':'iron-plate', 'product':'iron-gear-wheel'})
    assert 'iron-gear-wheel' in [r['name'] for r in matches['items']], matches
    empty = api.call('recipes', {'search':'no-such-recipe'})
    assert empty['total'] == 0 and empty['items'] == [], empty
    assert not api.call('recipes', {'name':'iron-gear-wheel','offset':1})['items']
    api.call('recipes', {'limit':0}, error=True)
    api.call('recipes', {'name':'no-such-recipe'}, error=True)
    api.call('recipes', {'offset':2147483647}, error=True)
    name = 'assembling-machine-1'
    original = server_state(fixture, server, f'{{enabled=game.forces.player.recipes["{name}"].enabled}}')['enabled']
    static = api.call('recipes', {'name':name})['items'][0]
    try:
        for enabled in (False, True):
            console_synced(fixture, server, f'game.forces.player.recipes["{name}"].enabled={str(enabled).lower()}')
            expected = server_state(fixture, server, f'{{enabled=game.forces.player.recipes["{name}"].enabled}}')
            assert expected['enabled'] == enabled
            available = api.call('available_recipes', {'name':name})
            assert bool(available['items']) == enabled, available
            assert api.call('recipes', {'name':name})['items'][0] == static
    finally:
        console_synced(fixture, server, f'game.forces.player.recipes["{name}"].enabled={str(original).lower()}')
    check = api.call('crafting_check', {'recipe':'iron-gear-wheel','count':2})
    actual = server_state(fixture, server, '{craftable=game.connected_players[1].get_craftable_count("iron-gear-wheel")}')
    assert check['craftable_count'] == actual['craftable'], (check, actual)
    assert check['direct_ingredients'][0]['required'] == 4, check
    locked = api.call('crafting_check', {'recipe':'assembling-machine-1'})
    assert locked['unlock_technologies'], locked
    api.call('crafting_check', {'recipe':'iron-gear-wheel','count':0}, error=True)
    resources = api.call('inspect_area', {'mode':'resources','name':'iron-ore','radius':10,'limit':1})
    assert not resources['scan_truncated'] and resources['total_in_scan'] == 15, resources
    assert resources['summary'][0]['amount'] == 15000, resources
    empty_area = api.call('inspect_area', {'mode':'resources','name':'coal','radius':1})
    assert empty_area['items'] == [] and empty_area['summary'] == [], empty_area
    second = api.call('inspect_area', {'mode':'resources','name':'iron-ore','radius':10,'limit':1,'offset':1})
    assert resources['items'][0]['position'] != second['items'][0]['position']
    production = api.call('inspect_area', {'mode':'production','name':'assembling-machine-1','radius':10})
    assert production['total_in_scan'] == 2 and production['summary'][0]['statuses'], production
    api.call('inspect_area', {'radius':100}, error=True)
    preview = api.call('preview_build', {'placements':[{'name':'stone-furnace','x':6,'y':5}, {'name':'stone-furnace','x':8,'y':5}]})
    assert preview['materials'][0]['required'] == 2, preview
    actual = server_state(fixture, server, '{place=game.connected_players[1].can_place_entity{name="stone-furnace",position={6,5}},count=game.connected_players[1].get_main_inventory().get_item_count("stone-furnace")}')
    assert preview['placements'][0]['can_place'] == actual['place'] and preview['materials'][0]['available'] == actual['count']
    api.call('preview_build', {'placements':[]}, error=True)
    api.call('preview_build', {'placements':[{'name':'stone-furnace','item':'coal','x':6,'y':5}]}, error=True)
    absent = api.call('inspect_networks', {'x':7,'y':7})
    assert absent['electric'] is False and absent['logistic'] is False and absent['train'] is False, absent
    fixture.console(server, '''local s=game.surfaces[1]
        local roboport=assert(s.create_entity{name="roboport",position={12,8},force=game.forces.player}, "roboport creation failed")
        roboport.energy=roboport.electric_buffer_size
        roboport.insert{name="logistic-robot",count=2}
        roboport.insert{name="construction-robot",count=3}
        assert(s.create_entity{name="small-electric-pole",position={8,8},force=game.forces.player}, "pole creation failed")
        for y=-14,-10,2 do assert(s.create_entity{name="straight-rail",position={8,y},direction=defines.direction.north,force=game.forces.player}, "rail creation failed") end
        assert(s.create_entity{name="locomotive",position={8,-12},force=game.forces.player}, "locomotive creation failed")
        print("SEMANTIC_NETWORK_FIXTURE_READY")'''.replace('\n',' '))
    fixture.wait_log('client', 'SEMANTIC_NETWORK_FIXTURE_READY', fixture.graphical, 5)
    deadline = time.monotonic()+5
    while True:
        network = api.call('inspect_networks', {'x':12,'y':8})
        if network['logistic'] or time.monotonic() >= deadline: break
        time.sleep(.1)
    assert network['logistic']['logistic_robots'] == 2 and network['logistic']['construction_robots'] == 3, network
    assert network['logistic']['items'] == [], network
    assert api.call('inspect_networks', {'x':8.5,'y':8.5})['electric'], 'Electric network missing'
    rolling_stock = server_state(fixture, server, '{position=game.surfaces[1].find_entities_filtered{type="locomotive"}[1].position,manual=game.surfaces[1].find_entities_filtered{type="locomotive"}[1].train.manual_mode}')
    train = api.call('inspect_networks', rolling_stock['position'])
    assert train['train'] and train['train']['manual_mode'] == rolling_stock['manual'], (train, rolling_stock)
    assert api.call('wait_for', {'kind':'inventory_count','name':'coal','count':5})['matched']
    assert not api.call('wait_for', {'kind':'inventory_count','name':'coal','count':999999,'timeout_ms':400})['matched']
    assert api.call('wait_for', {'kind':'research_complete','name':'electronics'})['matched']
    condition = production['items'][0]
    assert api.call('wait_for', {'kind':'entity_status','name':condition['name'],'status':condition['status'],**condition['position']})['matched']
    assert isinstance(api.call('wait_for', {'kind':'crafting_empty','timeout_ms':400})['matched'], bool)
    pending = api.send('tools/call', {'name':'wait_for','arguments':{'kind':'inventory_count','name':'coal','count':11,'timeout_ms':5000}})
    assert api.call('player', {})['connected'], 'Read blocked by wait'
    console_synced(fixture, server, 'game.connected_players[1].insert{name="coal",count=1}')
    result = api.receive(pending)
    assert not result.get('isError') and json.loads(result['content'][0]['text'])['matched'], result
    console_synced(fixture, server, 'game.connected_players[1].remove_item{name="coal",count=1}')
    records.append({'test':'Live recipe content versus availability, feasibility, area/network diagnosis, previews and concurrent bounded waits',
        'crafting_check':check,'resources':resources,'preview':preview,'network':network,'train':train})


def verify_domain_tools(api, fixture, server, records):
    inventory = api.call('inventory')
    coal = next(item for item in inventory['items'] if item['name'] == 'coal')
    assert coal['quality'] == 'normal' and coal['count'] == 10, inventory
    assert inventory['item_count'] >= 10 and inventory['slots'] >= inventory['empty_slots'], inventory
    assert api.call('inventory', {'offset':10000})['items'] == []
    api.call('inventory', {'view':'unknown'}, error=True)
    overview = api.call('inspect_entity', {'x':3.5,'y':3.5})
    assert overview['name'] == 'wooden-chest' and overview['inventories'], overview
    ids = [entry['index'] for entry in overview['inventories']]
    assert len(ids) == len(set(ids)), overview
    assert all(entry['items'] == [] for entry in overview['inventories']), overview
    by_index = api.call('inventory', {'x':3.5,'y':3.5,'index':ids[0]})
    assert by_index['inventory_index'] == ids[0] and by_index['items'] == [], by_index
    api.call('inventory', {'index':1}, error=True)
    inserter = api.call('inspect_entity', {'x':-2.5,'y':.5})
    actual = server_state(fixture, server, '{pickup_position=game.surfaces[1].find_entity("inserter",{-2.5,.5}).pickup_position,drop_position=game.surfaces[1].find_entity("inserter",{-2.5,.5}).drop_position}')
    assert inserter['inserter'] == actual, (inserter, actual)
    assembler = api.call('inspect_entity', {'x':-2.5,'y':-2.5})
    assert assembler['crafting']['active'] is False and len(assembler['inventories']) > 1, assembler
    api.call('inspect_entity', {'x':12,'y':12}, error=True)
    technology = api.call('technologies', {'name':'automation'})['items'][0]
    assert technology['effects'] and technology['unit_count'] > 0, technology
    available = api.call('technologies', {'available':True})
    assert available['items'] and all(t['available'] and not t['missing_prerequisites'] for t in available['items']), available
    assert api.call('technologies', {'search':'no-such-technology'})['items'] == []
    data = {'label':'Read-only analysis','entities':[
        {'entity_number':1,'name':'transport-belt','position':{'x':0,'y':0}},
        {'entity_number':2,'name':'transport-belt','position':{'x':1,'y':0}}]}
    cursor = api.call('player', {'section':'cursor'})
    blueprint = api.call('inspect_blueprint', {'data':data,'include_data':True,'include_string':True})
    assert blueprint['entity_count'] == 2 and blueprint['summary'][0]['count'] == 2, blueprint
    assert blueprint['summary'][0]['placement_items'], blueprint
    decoded = api.call('inspect_blueprint', {'string':blueprint['string'],'include_data':True})
    assert decoded['data'] == blueprint['data'], (blueprint, decoded)
    after = api.call('player', {'section':'cursor'})
    assert {k:v for k,v in cursor.items() if k != 'tick'} == {k:v for k,v in after.items() if k != 'tick'}
    api.call('inspect_blueprint', {'string':'invalid'}, error=True)
    api.call('inspect_blueprint', {'data':data,'string':blueprint['string']}, error=True)
    tiles = api.call('inspect_area', {'mode':'tiles','radius':2})
    assert tiles['items'] and 'collides_with_player' in tiles['items'][0], tiles
    ghosts = api.call('inspect_area', {'mode':'ghosts'})
    assert ghosts['items'] == [], ghosts
    entities = api.call('inspect_area', {'type':'container','relation':'own'})
    assert entities['items'] and all(e['type'] == 'container' and e['force'] == 'player' for e in entities['items']), entities
    console_synced(fixture, server, 'assert(game.surfaces[1].create_entity{name="stone-wall",position={10,10},force=game.forces.enemy})')
    try:
        hostile = api.call('inspect_area', {'relation':'hostile','name':'stone-wall','radius':16})
        assert hostile['items'] and all(e['force'] == 'enemy' for e in hostile['items']), hostile
    finally:
        console_synced(fixture, server, 'game.surfaces[1].find_entity("stone-wall",{10.5,10.5}).destroy()')
    player = api.call('player')
    assert api.call('wait_for', {'kind':'position',**player['position']})['matched']
    assert not api.call('wait_for', {'kind':'position','x':100,'y':100,'timeout_ms':400})['matched']
    api.call('wait_for', {'kind':'position','x':0,'y':0,'tolerance':-1}, error=True)
    assert api.call('wait_for', {'kind':'entity_inventory','x':3.5,'y':3.5,'name':'coal','count':0})['matched']
    records.append({'test':'Domain read tools, inventory identity, technology details, read-only blueprint codec and extended waits',
                    'inventory':inventory,'entity':overview,'technology':technology,'blueprint':blueprint})


def verify_counted_transfer(api, fixture, server, records):
    api.call('clear_cursor')
    arguments = {'name':'coal','x':3.5,'y':3.5,'timeout_ms':10000}
    deposit = api.call('transfer_items', {**arguments,'direction':'deposit','count':7})
    if not deposit['complete']:
        print(deposit, file=sys.stderr, flush=True)
        print(api.call('player', {}), file=sys.stderr, flush=True)
        (fixture.folder/'transfer-failure.png').write_bytes(api.call('screenshot'))
    assert deposit['complete'] and deposit['transferred'] == 7, deposit
    assert api.call('wait_for', {'kind':'entity_inventory','x':3.5,'y':3.5,'name':'coal','count':7})['matched']
    state = server_state(fixture, server, '{count=game.surfaces[1].find_entity("wooden-chest",{3.5,3.5}).get_inventory(defines.inventory.chest).get_item_count("coal")}')
    assert state['count'] == 7, state
    withdraw = api.call('transfer_items', {**arguments,'direction':'withdraw','count':3})
    assert withdraw['complete'] and withdraw['transferred'] == 3, withdraw
    partial = api.call('transfer_items', {**arguments,'direction':'withdraw','count':10})
    assert not partial['complete'] and partial['transferred'] == 4 and not partial['uncertain'], partial
    state = server_state(fixture, server, '{count=game.surfaces[1].find_entity("wooden-chest",{3.5,3.5}).get_inventory(defines.inventory.chest).get_item_count("coal"),player=game.connected_players[1].get_main_inventory().get_item_count("coal"),cursor=game.connected_players[1].cursor_stack.valid_for_read}')
    assert state == {'count':0,'player':10,'cursor':False}, state
    console_synced(fixture, server, 'game.surfaces[1].find_entity("wooden-chest",{3.5,3.5}).get_inventory(defines.inventory.chest).set_bar(1)')
    full = api.call('transfer_items', {**arguments,'direction':'deposit','count':1})
    assert full['transferred'] == 0 and not full['uncertain'] and not full['complete'], full
    console_synced(fixture, server, 'game.surfaces[1].find_entity("wooden-chest",{3.5,3.5}).get_inventory(defines.inventory.chest).set_bar()')
    api.call('transfer_items', {**arguments,'direction':'deposit','count':0}, error=True)
    records.append({'test':'Counted normal slot transfers, source exhaustion, barred destination and cursor cleanup',
        'deposit':deposit,'withdraw':withdraw,'partial':partial,'full':full,'server':state})
