"""Direct entity settings: synchronized effects, patch semantics and cursor preservation."""
import json

from player_actions import server_state
from semantic_tools import console_synced


def verify_entity_settings(api, fixture, server, client, records):
    def setup(source):
        console_synced(fixture, server, source.replace('\n', ' '))

    results = []

    def configure(x, y, settings):
        arguments = {'x': x, 'y': y, 'settings': settings}
        result = api.call('configure_entity', arguments)
        results.append({'arguments': arguments, 'result': result})
        return result

    player = 'game.connected_players[1]'
    surface = f'{player}.surface'
    inserter = f'{surface}.find_entity("inserter",{{-2.5,.5}})'
    splitter = f'{surface}.find_entity("splitter",{{5,-.5}})'
    assembler = f'{surface}.find_entity("assembling-machine-1",{{-2.5,-2.5}})'
    setup(f'''
        local p={player};p.exit_remote_view();p.get_main_inventory().remove{{name='stone',count=2147483647}};p.clear_cursor();p.teleport({{0,0}},game.surfaces.nauvis);p.admin=false
        p.force.inserter_stack_size_bonus=4
        local e={inserter};e.inserter_stack_size_override=0;e.set_filter(1,'iron-plate');e.use_filters=false
        e.get_or_create_control_behavior().circuit_condition={{constant=23,comparator='>'}}
        local s={splitter};s.splitter_input_priority='none';s.splitter_output_priority='none';s.splitter_filter='iron-plate'
    ''')
    assert not api.call('player')['admin']
    blueprints = server_state(fixture, server, f'{{count={player}.get_item_count("blueprint")}}')['count']
    configure(-2.5, .5, {'stack_size': 2, 'use_filters': True, 'filter_mode': 'blacklist'})
    state = server_state(fixture, server, f'{{size={inserter}.inserter_stack_size_override,enabled={inserter}.use_filters,mode={inserter}.inserter_filter_mode,filter={inserter}.get_filter(1).name,constant={inserter}.get_control_behavior().circuit_condition.constant}}')
    assert state == {'size': 2, 'enabled': True, 'mode': 'blacklist', 'filter': 'iron-plate', 'constant': 23}, state
    configure(-2.5, .5, {'stack_size': 0, 'filter_mode': 'whitelist'})
    configure(-2.5, .5, {'use_filters': False})
    assert server_state(fixture, server, f'{{size={inserter}.inserter_stack_size_override,enabled={inserter}.use_filters,filter={inserter}.get_filter(1).name}}') == {'size': 0, 'enabled': False, 'filter': 'iron-plate'}
    for input_priority, output_priority in [('left', 'right'), ('right', 'left'), ('none', 'none')]:
        configure(5, -.5, {'input_priority': input_priority, 'output_priority': output_priority})
        state = server_state(fixture, server, f'{{input={splitter}.splitter_input_priority,output={splitter}.splitter_output_priority,filter={splitter}.splitter_filter and {splitter}.splitter_filter.name}}')
        assert state['input'] == input_priority and state['output'] == output_priority, state
        assert state.get('filter') == (None if output_priority == 'none' else 'iron-plate'), state
    setup(f'{player}.teleport({{0,-3}})')
    station = server_state(fixture, server, f'{{position={surface}.find_entities_filtered{{type="train-stop"}}[1].position}}')['position']
    for limit in (2, 0, 1):
        configure(station['x'], station['y'], {'train_limit': limit})
        assert server_state(fixture, server, f'{{limit={surface}.find_entities_filtered{{type="train-stop"}}[1].trains_limit}}')['limit'] == limit
    setup(f'{player}.teleport({{0,0}})')
    configure(-2.5, -2.5, {'recipe': 'iron-gear-wheel'})
    assert configure(-2.5, -2.5, {'recipe': 'iron-gear-wheel'})['unchanged']
    api.call('open_entity', {'x': -2.5, 'y': -2.5})
    # No spare inventory slot or temporary item is needed, including an occupied cursor.
    setup(f'''
        local p={player};p.cursor_stack.set_stack{{name='stone',count=5}}
        for i=1,#{player}.get_main_inventory() do local s=p.get_main_inventory()[i];if not s.valid_for_read then s.set_stack{{name='stone',count=100}} end end
    ''')
    before = server_state(fixture, server, f'{{stone={player}.get_item_count("stone"),cursor={player}.cursor_stack.count}}')
    result = api.call('set_recipe', {'recipe': 'copper-cable'})
    assert result['recipe'] == 'copper-cable'
    assert server_state(fixture, server, f'{{recipe={assembler}.get_recipe().name}}')['recipe'] == 'copper-cable'
    assert server_state(fixture, server, f'{{stone={player}.get_item_count("stone"),cursor={player}.cursor_stack.count}}') == before
    for settings in ({'recipe': 'iron-gear-wheel', 'parameters': {}}, {'inventory_bar': 4}, {'stack_size': '2'}, {'use_filters': 'true'}, {'recipe_quality': 'uncommon'}, {'requests': []}, {'item_requests': []}):
        api.call('configure_entity', {'x': -2.5, 'y': -2.5, 'settings': settings}, error=True)
    assert server_state(fixture, server, f'{{recipe={assembler}.get_recipe().name}}')['recipe'] == 'copper-cable'
    writable = api.call('inspect_entity', {'x': -2.5, 'y': -2.5, 'view': 'configuration'})['writable_settings']
    assert writable == ['recipe'], writable
    setup(f'{player}.get_main_inventory().remove{{name="stone",count={before["stone"]}}};{player}.clear_cursor();{player}.get_main_inventory().remove{{name="stone",count=5}}')
    assert server_state(fixture, server, f'{{count={player}.get_item_count("blueprint")}}')['count'] == blueprints
    assert not api.call('player', {'section': 'cursor'}).get('stack')
    fixture.verify_crc(server, client)
    records.append({'case': 'direct_entity_settings', 'results': results, 'full_crc': True})
    (fixture.folder/'entity-settings-result.json').write_text(json.dumps(records, indent=2))


def verify_space_settings(api, fixture, server, client, records):
    def setup(source):
        console_synced(fixture, server, source.replace('\n', ' '))

    setup('''
        local p=game.connected_players[1];p.clear_cursor();p.exit_remote_view()
        p.surface.request_to_generate_chunks({200,0},1);p.surface.force_generate_chunk_requests()
        p.teleport({192,0},game.surfaces.nauvis)
        for _,e in pairs(p.surface.find_entities_filtered{area={{195,-8},{205,8}}}) do e.destroy() end
        local tiles={};for x=190,210 do for y=-10,10 do tiles[#tiles+1]={name='grass-1',position={x,y}} end end;p.surface.set_tiles(tiles)
        local silo=p.surface.create_entity{name='rocket-silo',position={200,0},force=p.force};silo.use_transitional_requests=false
        for _,tech in pairs(p.force.technologies) do tech.researched=true end
        local platform=p.force.create_space_platform{name='Settings fixture',planet='nauvis',starter_pack='space-platform-starter-pack'}
        platform.name=string.format('factorio-mcp settings %d',platform.index);platform.apply_starter_pack()
    ''')
    platform = server_state(fixture, server, '(function() local result;for _,p in pairs(game.forces.player.platforms) do if p.name:find("factorio-mcp settings ",1,true)==1 and (not result or p.index>result.index) then result={index=p.index,surface=p.surface.name} end end;return result end)()')
    api.call('remote_view', {'surface': 'nauvis', 'x': 200, 'y': 0})
    for value in (True, False):
        api.call('configure_entity', {'x': 200, 'y': 0, 'settings': {'use_transitional_requests': value}})
        assert server_state(fixture, server, '{value=game.surfaces.nauvis.find_entity("rocket-silo",{200,0}).use_transitional_requests}')['value'] == value
    api.call('remote_view', {'surface': platform['surface'], 'x': 0, 'y': 0})
    for value in (False, True):
        api.call('configure_entity', {'x': 0, 'y': 0, 'settings': {'request_missing_construction_materials': value}})
        expression = f'''(function() for _,section in pairs(game.forces.player.platforms[{platform['index']}].hub.get_logistic_sections().sections) do if section.type==defines.logistic_section_type.request_missing_materials_controlled then return {{value=section.active}} end end;return {{value=false}} end)()'''
        assert server_state(fixture, server, expression)['value'] == value
    setup(f"local p=game.forces.player.platforms[{platform['index']}];local tiles={{}};for x=-8,-4 do for y=-2,2 do tiles[#tiles+1]={{name='space-platform-foundation',position={{x,y}}}} end end;p.surface.set_tiles(tiles);p.surface.create_entity{{name='chemical-plant',position={{-6,0}},force=p.force}}")
    api.call('configure_entity', {'x': -6, 'y': 0, 'settings': {'recipe': 'thruster-fuel'}})
    assert server_state(fixture, server, f'{{recipe=game.surfaces[{json.dumps(platform["surface"])}].find_entity("chemical-plant",{{-6,0}}).get_recipe().name}}')['recipe'] == 'thruster-fuel'
    assert not server_state(fixture, server, '{occupied=game.connected_players[1].cursor_stack.valid_for_read}')['occupied']
    fixture.verify_crc(server, client)
    records.append({'case': 'direct_space_settings', 'full_crc': True})
    (fixture.folder/'entity-settings-result.json').write_text(json.dumps(records, indent=2))
