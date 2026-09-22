#!/usr/bin/env python3
"""Exercise staged observation and world binding against a normal Steam installation."""
import argparse
import json
from pathlib import Path
import socket
import subprocess
import time

from integration import GameFixture, ROOT
from mcp_http import McpClient
from player_actions import server_state


def eventually(operation, predicate, timeout=30):
    deadline = time.monotonic() + timeout
    while True:
        result = operation()
        if predicate(result): return result
        assert time.monotonic() < deadline, result
        time.sleep(.1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--factorio', type=Path, default=Path.home()/'.steam/steam/steamapps/common/Factorio/bin/x64/factorio')
    parser.add_argument('--injector', type=Path, default=ROOT/'build/bin/linuxX64/releaseExecutable/factorio-mcp.kexe')
    parser.add_argument('--work', type=Path, default=ROOT/'build/verification/lifecycle')
    args = parser.parse_args()
    fixture = GameFixture(args.factorio, args.work)
    records = []
    scenario = fixture.folder/'server/scenarios/factorio-mcp'
    scenario.mkdir(parents=True, exist_ok=True)
    source = (ROOT/'tests/scenario.lua').read_text()
    (scenario/'control.lua').write_text(f"""{source}
script.on_event(defines.events.on_tick, nil)
""")
    settings = fixture.folder/'server-settings.json'
    settings.write_text(json.dumps({'name':'Lifecycle fixture','description':'Local acceptance test','max_players':2,
        'visibility':{'public':False,'lan':False,'steam':False},'require_user_verification':False,
        'allow_commands':'true','auto_pause':False,'autosave_interval':0}))
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.bind(('127.0.0.1',0))
        port = sock.getsockname()[1]
    try:
        server = fixture.launch('server', ['--start-server-load-scenario','factorio-mcp','--server-settings',str(settings),
            '--bind','127.0.0.1','--port',str(port)])
        fixture.wait_log('server','Hosting game at',server)
        client = fixture.launch('client',['--mp-connect',f'127.0.0.1:{port}'],graphical=True)
        with McpClient(args.injector,None,fixture.folder) as api:
            unrelated = subprocess.Popen(['sleep','60'])
            try:
                time.sleep(.1)
                before = Path(f'/proc/{unrelated.pid}/maps').read_text()
                result = api.call('status',{'pid':unrelated.pid})
                assert result['state']=='unrecognized' and not result['resident'], result
                assert before == Path(f'/proc/{unrelated.pid}/maps').read_text()
                records.append({'test':'Unrecognized executable is not modified','result':result})
            finally:
                unrelated.terminate()
                unrelated.wait()
            api.call('attach',{'pid':client.pid},error=True)
            startup = []
            def observe():
                state = api.call('status',{'pid':client.pid})
                startup.append(state)
                print(json.dumps(state),flush=True)
                return state
            state = eventually(observe,lambda s:s['state']=='in_game',timeout=120)
            assert state['resident'] and not state['ready'] and not state['actions_installed'], state
            api.call('player',error=True)
            for _ in range(3):
                repeated = api.call('status',{'pid':client.pid})
                assert repeated['instance']==state['instance'] and not repeated['ready'], repeated
            records.append({'test':'Startup observation and repeated status leave Lua unbound','observations':startup})
            api.kill()
        with McpClient(args.injector,None,fixture.folder) as api:
            resumed = api.call('status',{'pid':client.pid})
            assert resumed['instance']==state['instance'] and not resumed['ready'], resumed
            attached = api.attach_ready(client.pid)
            for _ in range(3):
                repeated = api.call('attach',{'pid':client.pid})
                assert repeated['ready'] and repeated['instance']==attached['instance'] and repeated['world_generation']==attached['world_generation'], repeated
            local = api.call('player')
            assert not local['admin'], local
            server_handlers = server_state(fixture,server,'{tick_handler=script.get_event_handler(defines.events.on_tick)~=nil}')
            assert not server_handlers['tick_handler'], server_handlers
            records.append({'test':'Observer survives MCP SIGKILL; idle-world attach is idempotent','state':attached})
            ore = 'game.connected_players[1].surface.find_entity("iron-ore",{2.5,.5})'
            fixture.console(server,'game.connected_players[1].character_mining_speed_modifier=-0.75')
            initial = server_state(fixture,server,f'{{amount={ore}.amount}}')['amount']
            api.send('tools/call',{'name':'mine','arguments':{'x':2.5,'y':.5}})
            eventually(lambda:server_state(fixture,server,'{mining=game.connected_players[1].mining_state.mining}'),lambda s:s['mining'],timeout=5)
            api.kill()
        with McpClient(args.injector,None,fixture.folder) as api:
            resumed = api.call('status',{'pid':client.pid})
            assert resumed['ready'] and resumed['instance']==attached['instance'], resumed
            api.attach_ready(client.pid)
            assert api.call('player')['mining']['mining'], 'Fixture action ended before reconnect could exercise reuse'
            assert api.call('recipes',{'name':'iron-gear-wheel'})['items']
            finished = eventually(lambda:server_state(fixture,server,f'{{amount={ore}.amount,mining=game.connected_players[1].mining_state.mining}}'),
                lambda s:s['amount']<initial and not s['mining'],timeout=10)
            records.append({'test':'Repeated attachment preserves admitted work; old completion is discarded after MCP SIGKILL','server':finished})
            eventually(lambda:api.call('player'),lambda state:not state['mining']['mining'],timeout=5)
            # Existing IDs are irrelevant to the new process; a fresh read must still complete.
            assert api.call('recipes',{'name':'iron-gear-wheel'})['items']
            marker = f'LIFECYCLE_ADMITTED_{time.monotonic_ns()}'
            installed = f'LIFECYCLE_GATED_{time.monotonic_ns()}'
            waiting_task = 'return {ready=function() return false end,callback=function() end}'
            # Admit one deliberately blocked fixture task, then replace its Lua VM.
            fixture.console(server,f'''local original=rawget(_G,'__factorio_mcp_submit_v1');if original then
                __factorio_mcp_submit_v1=function(id,source,timeout)
                    __factorio_mcp_submit_v1=original
                    original(id,{json.dumps(waiting_task)},timeout)
                    print({json.dumps(marker)})
                end
                print({json.dumps(installed)})
            end'''.replace('\n',' '))
            fixture.wait_log('client',installed,client)
            pending = api.send('tools/call',{'name':'player','arguments':{}})
            fixture.wait_log('client',marker,client)
            marker = f'LIFECYCLE_RELOAD_{time.monotonic_ns()}'
            started = time.monotonic()
            fixture.console(server,f'game.reload_script(); print({json.dumps(marker)})')
            fixture.wait_log('server',marker,server)
            interrupted = api.receive(pending,timeout=5)
            assert interrupted.get('isError') and 'world changed' in interrupted['content'][0]['text'], interrupted
            records.append({'test':'World invalidation notifies pending calls without waiting for their deadline','elapsed':time.monotonic()-started})
            changed = eventually(lambda:api.call('status'),lambda s:s['state']=='in_game' and s['world_generation']!=resumed['world_generation'])
            assert not changed['ready'] and changed['waiting_for']=='attach', changed
            api.call('player',error=True)
            time.sleep(.3)
            assert not api.call('status')['ready'], 'Status implicitly rebound the Lua VM'
            rebound = api.attach_ready(client.pid)
            assert rebound['instance']==resumed['instance'] and api.call('player'), rebound
            records.append({'test':'World replacement invalidates binding and requires explicit attach','before':resumed,'invalidated':changed,'after':rebound})
            api.call('detach')
            api.call('player',error=True)
            reconnected = api.call('status',{'pid':client.pid})
            assert reconnected['instance']==rebound['instance'] and reconnected['ready'], reconnected
            api.attach_ready(client.pid)
        fixture.verify_crc(server, client)
        fixture.stop(client)
        menu = fixture.launch('menu',[],graphical=True)
        fixture.wait_log('menu','Factorio initialised',menu,120)
        with McpClient(args.injector,None,fixture.folder) as api:
            state = eventually(lambda:api.call('status',{'pid':menu.pid}),lambda s:s['state']=='main_menu')
            assert not state['ready'] and not state['actions_installed'], state
            api.call('attach',{'pid':menu.pid},error=True)
            api.call('player',error=True)
            records.append({'test':'Menu permits observation and rejects world attachment','state':state})
        print(json.dumps({'ok':True,'records':len(records),'evidence':str(fixture.folder/'result.json')}),flush=True)
    finally:
        (fixture.folder/'result.json').write_text(json.dumps(records,indent=2))
        fixture.close()


if __name__=='__main__': main()
