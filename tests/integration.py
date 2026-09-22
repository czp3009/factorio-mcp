#!/usr/bin/env python3
"""Validate resident MCP against one local server and at most one graphical client.

The test launcher grants ptrace permission before exec. Injection still happens
only after game initialization; the product has no launcher or window controls.
"""
import argparse
import ctypes
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import textwrap
import time

from mcp_http import McpClient
from semantic_tools import verify_domain_tools, verify_semantic_tools, verify_counted_transfer
from player_actions import verify_player_actions, server_state
from space_actions import verify_space_tools, verify_planet_tools
from entity_settings import verify_space_settings

ROOT = Path(__file__).resolve().parents[1]


class GameFixture:
    def __init__(self, factorio, folder):
        self.factorio = factorio.resolve()
        self.folder = folder.resolve()
        self.folder.mkdir(parents=True, exist_ok=True)
        self.processes = []
        self.logs = []
        self.graphical = None
        self.parent = os.getpid()
        self.libc = ctypes.CDLL(None, use_errno=True)

    def authorize(self):
        if self.libc.prctl(0x59616d61, self.parent, 0, 0, 0) != 0:  # Linux PR_SET_PTRACER.
            os._exit(120)

    def launch(self, name, options, graphical=False):
        if graphical:
            assert self.graphical is None or self.graphical.poll() is not None, 'A graphical test client is already running'
        folder = self.folder/name
        folder.mkdir(exist_ok=True)
        if not graphical:
            (folder/'mods').mkdir(exist_ok=True)
            mods = [{'name': name, 'enabled': True} for name in ('base', 'space-age', 'quality', 'elevated-rails')]
            (folder/'mods/mod-list.json').write_text(json.dumps({'mods': mods}))
            (folder/'config.ini').write_text(f'''[path]
read-data={self.factorio.parents[2]/'data'}
write-data={folder}
[general]
locale=en
[other]
enable-new-mods=false
enable-blueprint-storage-cloud-sync=false
autosave-interval=0
[graphics]
max-threads=2
graphics-quality=normal
video-memory-usage=low
''')
        env = dict(os.environ, SteamAppId='427520', SteamGameId='427520')
        env.pop('LD_PRELOAD', None)
        log = open(folder/'launch.log', 'w')
        self.logs.append(log)
        command = [str(self.factorio), *options] if graphical else [str(self.factorio), '--config', str(folder/'config.ini'),
            '--mod-directory', str(folder/'mods'), *options]
        child = subprocess.Popen(command, env=env, stdin=subprocess.PIPE,
            stdout=log, stderr=subprocess.STDOUT, preexec_fn=self.authorize)
        self.processes.append(child)
        if graphical:
            self.graphical = child
        return child

    def log(self, name):
        return (self.folder/name/'launch.log').read_text(errors='replace')

    def wait_log(self, name, text, child, timeout=90):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            log = self.log(name)
            if text in log:
                return
            assert child.poll() is None, log[-5000:]
            time.sleep(.1)
        raise AssertionError(f'Timeout waiting for {text}: {log[-5000:]}')

    @staticmethod
    def console(child, source):
        child.stdin.write(f'/c {source}\n'.encode())
        child.stdin.flush()

    def verify_crc(self, server, client):
        marker = f'FULL_CRC_{time.monotonic_ns()}'
        self.console(server, f'game.force_crc(); print({json.dumps(marker)})')
        self.wait_log('server', marker, server)
        self.wait_log('client', marker, client)
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            assert client.poll() is None, self.log('client')[-5000:]
            for name in ('server', 'client'):
                assert 'desync' not in self.log(name).lower(), self.log(name)[-5000:]
            time.sleep(.1)

    @staticmethod
    def stop(child):
        if child.poll() is None:
            child.send_signal(signal.SIGCONT)
            child.terminate()
            try:
                child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)

    def close(self):
        for child in reversed(self.processes):
            self.stop(child)
            child.stdin.close()
            Path(f'/tmp/factorio-mcp-{os.getuid()}-{child.pid}.lock').unlink(missing_ok=True)
        for log in self.logs:
            log.close()


def verify_scheduler(injector, client, folder, records):
    status = subprocess.run([str(injector), 'status', str(client.pid)], capture_output=True, text=True, timeout=30)
    assert status.returncode == 0, status.stderr
    diagnostic = json.loads(status.stdout)
    assert diagnostic['state'] == 'in_game' and diagnostic['pid'] == client.pid and diagnostic['build_id'], diagnostic
    kotlin = (ROOT/'src/commonMain/kotlin/com/hiczp/factorio/mcp/ResidentScheduler.kt').read_text()
    factory = re.search(r'internal val residentSchedulerSource = """\n(.*?)\n"""\.trimIndent', kotlin, re.S)
    assert factory, 'Cannot extract the scheduler Lua fixture'
    source = (ROOT/'tests/scheduler.lua').read_text().replace('__SCHEDULER_FACTORY__', textwrap.dedent(factory.group(1)))
    script = folder/'scheduler.lua'
    script.write_text(source)
    result = subprocess.run([str(injector), 'run', str(client.pid), '--lua', str(script), '--ticks', '3'],
        capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stderr
    value = json.loads(result.stdout)['result']['value']
    assert value['checks'] == 18, value
    records.append({'test': 'Lua scheduler FIFO, readiness, completion queues, capacity, and lost IPC', 'result': value})


def verify_resident(fixture, injector, server, client, records):
    first_instance = None
    reads = [('player', {}), ('recipes', {}), ('technologies', {}), ('inventory', {}),
             *[('player', {'section': section}) for section in ('cursor', 'crafting', 'research', 'quickbar', 'equipment')],
             ('inspect_area', {}), *[('prototypes', {'kind': kind}) for kind in ('items', 'entities', 'fluids')]]
    for attempt in range(4):
        with McpClient(injector, client.pid, fixture.folder) as api:
            state = api.call('status')
            assert state['state'] == 'in_game' and state['ready'], state
            if first_instance is None:
                first_instance = state['instance']
            assert state['instance'] == first_instance, state
            requests = [api.send('tools/call', {'name': name, 'arguments': arguments}) for name, arguments in reads]
            for request in requests:
                response = api.receive(request)
                assert not response.get('isError'), response
                assert json.loads(response['content'][0]['text']), response
            assert 'TracerPid:\t0\n' in Path(f'/proc/{client.pid}/status').read_text()
            records.append({'test': 'Concurrent reads and resident reuse', 'attempt': attempt, 'state': state})
            if attempt == 0:
                local = api.call('player', {})
                roster = api.call('players')['players']
                assert not local['admin'], local
                summary = api.call('players', {'id':local['index']})['players'][0]
                assert summary['connected'] and summary['name'] == local['name'], summary
                assert api.call('players', {'name':local['name']})['players'][0]['id'] == local['index']
                assert set(summary) == {'id','name','connected','life_state','surface','position'}, summary
                assert len(api.call('players', {'offset':0,'limit':1})['players']) == 1
                api.call('inventory', {'view':'slots','player':local['index']}, error=True)
                api.call('player', {'section':'players'}, error=True)
                api.call('inventory', {'view':'slots','name':'character_main','x':12,'y':12}, error=True)
                api.call('players', {'id':local['index'],'name':local['name']}, error=True)
                records.append({'test':'Non-admin local client identity; public queries cannot retarget tools','local_id':local['index'],'roster':roster})
                assert api.call('player', {'section':'cursor'})['ghost']['name'] == 'stone-furnace'
                verify_domain_tools(api, fixture, server, records)
                verify_semantic_tools(api, fixture, server, records)
                verify_counted_transfer(api, fixture, server, records)
                verify_player_actions(api, fixture, server, client, records)
                screenshot = api.call('screenshot')
                assert screenshot.startswith(b'\x89PNG\r\n\x1a\n'), 'Screenshot is not a PNG'
                (fixture.folder/'screenshot.png').write_bytes(screenshot)
                waiting = api.send('tools/call', {'name':'wait_for','arguments':{'kind':'inventory_count','name':'coal','count':999999,'timeout_ms':10000}})
                time.sleep(.3)
                marker = f'RESIDENT_RELOADED_{time.monotonic_ns()}'
                fixture.console(server, f'game.reload_script(); print({json.dumps(marker)})')
                fixture.wait_log('server', marker, server, 10)
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline:
                    after = api.call('status')
                    if not after['ready'] and after['world_generation'] != state['world_generation']:
                        break
                    time.sleep(.1)
                assert not after['ready'] and after['world_generation'] != state['world_generation'], (state, after)
                cancelled_wait = api.receive(waiting)
                assert cancelled_wait.get('isError'), cancelled_wait
                api.call('players', error=True)
                api.attach_ready(client.pid)
                assert api.call('players')['players']
                assert api.call('recipes', {'name':'iron-gear-wheel'})['items'][0]['name'] == 'iron-gear-wheel'
                assert api.call('available_recipes', {'name':'iron-gear-wheel'})['items'][0]['enabled']
                records.append({'test': 'Rebind after Lua VM replacement', 'before': state, 'after': after})
                selected = api.call('research', {'name': 'automation'})
                assert selected['technology'] == 'automation' and selected['queue_position'] == 1, selected
                unchanged = api.call('research', {'name': 'automation'})
                assert unchanged['unchanged'], unchanged
                marker = f'RESIDENT_RESEARCH_{time.monotonic_ns()}:'
                fixture.console(server, f'print({json.dumps(marker)}..game.forces.player.current_research.name)')
                fixture.wait_log('server', f'{marker}automation', server, 5)
                records.append({'test': 'Research confirmed independently on server, repeated selection is idempotent', 'result': selected})
            if attempt == 1:
                marker = f'RESIDENT_PAUSED_{time.monotonic_ns()}'
                fixture.console(server, f'game.tick_paused=true; print({json.dumps(marker)})')
                fixture.wait_log('server', marker, server, 5)
                timed_out = api.call('player', {}, error=True)
                assert 'timed out' in timed_out['content'][0]['text'], timed_out
                api.send('tools/call', {'name': 'research', 'arguments': {'name': 'logistics'}})
                time.sleep(.5)
                api.kill()
                fixture.console(server, 'game.tick_paused=false')
                marker = f'RESIDENT_AUTONOMOUS_{time.monotonic_ns()}:'
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline:
                    fixture.console(server, f'for _,t in pairs(game.forces.player.research_queue or {{}}) do print({json.dumps(marker)}..(type(t)=="string" and t or t.name)) end')
                    if f'{marker}logistics' in fixture.log('server'):
                        break
                    time.sleep(.2)
                assert f'{marker}logistics' in fixture.log('server')
                records.append({'test': 'Queued research finishes after MCP SIGKILL, independently server verified'})
            elif attempt == 2:
                player = 'game.connected_players[1]'
                ore = f'{player}.surface.find_entity("iron-ore",{{2.5,.5}})'
                # Earlier construction may block a straight walk; this fixture tests mining lifetime, not navigation.
                marker = f'RESIDENT_MINING_SETUP_{time.monotonic_ns()}'
                fixture.console(server, f'local p={player};local position=assert(p.surface.find_non_colliding_position(p.character.name,{ore}.position,2,.1));assert(p.teleport(position));print({json.dumps(marker)})')
                fixture.wait_log('server',marker,server)
                fixture.wait_log('client',marker,client)
                assert server_state(fixture, server, f'{{reachable={player}.can_reach_entity({ore})}}')['reachable']
                initial = server_state(fixture, server, f'{{amount={ore}.amount}}')['amount']
                api.send('tools/call', {'name':'mine', 'arguments':{'x':2.5, 'y':.5}})
                deadline = time.monotonic()+5
                while time.monotonic() < deadline:
                    observed = server_state(fixture, server, f'{{mining={player}.mining_state.mining}}')
                    if observed['mining']: break
                assert observed['mining'], observed
                api.kill()
                deadline = time.monotonic()+10
                while time.monotonic() < deadline:
                    observed = server_state(fixture, server, f'{{amount={ore}.amount,mining={player}.mining_state.mining}}')
                    if observed['amount'] < initial and not observed['mining']: break
                    time.sleep(.1)
                assert observed['amount'] < initial and not observed['mining'], observed
                records.append({'test':'Finite mining finishes and releases after MCP SIGKILL', 'server':observed})
            else:
                if attempt == 3:
                    assert api.call('player', {'section':'research'})['queue'] == ['automation', 'logistics']
                    assert not api.call('player', {})['mining']['mining']
                assert api.call('detach')['detached']
            assert client.poll() is None
    fixture.verify_crc(server, client)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--factorio', type=Path, default=Path.home()/'.steam/steam/steamapps/common/Factorio/bin/x64/factorio')
    parser.add_argument('--injector', type=Path, default=ROOT/'build/bin/linuxX64/releaseExecutable/factorio-mcp.kexe')
    parser.add_argument('--work', type=Path, default=ROOT/'build/verification/resident')
    args = parser.parse_args()
    fixture = GameFixture(args.factorio, args.work)
    records = []
    try:
        scenario = fixture.folder/'server/scenarios/factorio-mcp'
        scenario.mkdir(parents=True, exist_ok=True)
        (scenario/'control.lua').write_text((ROOT/'tests/scenario.lua').read_text())
        settings = fixture.folder/'server-settings.json'
        settings.write_text(json.dumps({'name': 'Resident local test', 'description': 'Disposable local integration fixture', 'max_players': 2,
            'visibility': {'public': False, 'lan': False, 'steam': False},
            'require_user_verification': False, 'allow_commands': 'true', 'auto_pause': False, 'autosave_interval': 0}))
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = reservation.getsockname()[1]
        server = fixture.launch('server', ['--start-server-load-scenario', 'factorio-mcp', '--server-settings', str(settings), '--bind', '127.0.0.1', '--port', str(port)])
        fixture.wait_log('server', 'Hosting game at', server)
        client = fixture.launch('client', ['--mp-connect', f'127.0.0.1:{port}'], graphical=True)
        fixture.wait_log('client', 'InGame', client)
        verify_scheduler(args.injector, client, fixture.folder, records)
        verify_resident(fixture, args.injector, server, client, records)
        with McpClient(args.injector, client.pid, fixture.folder) as api:
            verify_space_settings(api, fixture, server, client, records)
            verify_space_tools(api, fixture, server, client, records)
            verify_planet_tools(api, fixture, server, client, records)
        fixture.stop(client)
        menu = fixture.launch('menu', [], graphical=True)
        fixture.wait_log('menu', 'Factorio initialised', menu)
        with McpClient(args.injector, menu.pid, fixture.folder) as api:
            state = api.call('status')
            assert state['state'] == 'main_menu' and not state['ready'], state
            api.call('attach', {'pid':menu.pid}, error=True)
            api.call('players', error=True)
            api.call('research', {'name': 'automation'}, error=True)
            assert api.call('detach')['detached']
            records.append({'test': 'Main-menu detection and world-operation gating', 'state': state})
        assert McpClient.called == McpClient.advertised, McpClient.advertised - McpClient.called
        records.append({'test':'Every advertised MCP tool exercised', 'count':len(McpClient.called),'tools':sorted(McpClient.called)})
        print(json.dumps({'ok': True, 'records': len(records), 'evidence': str(fixture.folder/'result.json')}))
    finally:
        (fixture.folder/'result.json').write_text(json.dumps(records, indent=2))
        fixture.close()


if __name__ == '__main__':
    main()
