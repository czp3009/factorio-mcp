"""Standard Streamable HTTP MCP helpers for acceptance tests and persistent local servers."""
import base64
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import re
import subprocess
import time
from urllib.request import Request, urlopen


class McpClient:
    advertised = set()
    called = set()

    def __init__(self, injector=None, pid=None, folder=None, *, url=None, http_port=0):
        self.child = None
        self.log = None
        self.session = None
        self.version = '2025-11-25'
        self.sequence = 0
        self.pending = {}
        self.executor = ThreadPoolExecutor(max_workers=8)
        self.expected_exit_code = 0
        try:
            if url is None:
                log_path = Path(folder)/'mcp-stderr.log'
                self.log = log_path.open('w')
                self.child = subprocess.Popen([str(injector),'--no-stdio','--http-port',str(http_port)],
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=self.log)
                deadline = time.monotonic()+15
                while True:
                    text = log_path.read_text()
                    match = re.search(r'MCP HTTP listening at (http://[^\s]+)', text)
                    if match:
                        url = match[1]
                        break
                    assert self.child.poll() is None, text
                    assert time.monotonic()<deadline, text
                    time.sleep(.02)
            self.url = url
            result = self.rpc('initialize', {'protocolVersion':self.version,'capabilities':{},
                'clientInfo':{'name':'integration','version':'1'}})
            assert result['serverInfo']['name']=='factorio-mcp', result
            self.version = result['protocolVersion']
            self.notify('notifications/initialized', {})
            names = {t['name'] for t in self.rpc('tools/list', {})['tools']}
            assert names == {'logistic_section','land_player','create_platform','launch_rocket','platform_schedule','remote_view','inspect_space','transfer_items','recipes','available_recipes','crafting_check','inspect_area','inspect_networks','preview_build','wait_for','players','attach','detach','status','player','inventory','inspect_entity','prototypes','technologies','inspect_blueprint','research','screenshot','move','clear_cursor','quickbar','build','rotate','pipette','drop_item','vehicle','switch_weapon','craft','mine','transfer','open_entity','take_item','inventory_slot','equipment','drive','quickbar_page','use_item','equip','pave','place_blueprint','import_blueprint','configure_entity','cancel_crafting','set_recipe','pickup','attack','repair','copy_entity_settings'}, names
            self.advertised.update(names)
            if self.child:
                assert self.call('status')['state']=='detached'
                self.call('players', error=True)
            if pid is not None:
                deadline = time.monotonic()+30
                while True:
                    state = self.call('status', {'pid':pid})
                    if state['state'] in ('in_game','main_menu'): break
                    assert time.monotonic()<deadline, state
                    time.sleep(.1)
                if state['state']=='in_game': self.attach_ready(pid)
        except BaseException:
            self.close()
            raise

    def _request(self, message=None, method='POST'):
        headers = {'Content-Type':'application/json','Accept':'application/json, text/event-stream',
            'MCP-Protocol-Version':self.version}
        if self.session: headers['Mcp-Session-Id'] = self.session
        data = json.dumps(message).encode() if message is not None else None
        with urlopen(Request(self.url,data=data,headers=headers,method=method),timeout=70) as response:
            if response.headers.get('Mcp-Session-Id'): self.session=response.headers['Mcp-Session-Id']
            body = response.read()
            return json.loads(body) if body else None

    def notify(self, method, params):
        return self._request({'jsonrpc':'2.0','method':method,'params':params})

    def attach_ready(self, pid):
        state = self.call('attach', {'pid':pid})
        deadline = time.monotonic()+10
        while not state['ready']:
            assert time.monotonic()<deadline, state
            time.sleep(.05)
            state = self.call('status')
        return state

    def send(self, method, params):
        if method=='tools/call': self.called.add(params['name'])
        self.sequence += 1
        request_id = self.sequence
        self.pending[request_id] = self.executor.submit(self._request,
            {'jsonrpc':'2.0','id':request_id,'method':method,'params':params})
        return request_id

    def receive(self, request_id, timeout=70):
        message = self.pending.pop(request_id).result(timeout=timeout)
        assert message.get('jsonrpc')=='2.0' and message.get('id')==request_id, message
        assert 'error' not in message, message
        return message['result']

    def rpc(self, method, params):
        return self.receive(self.send(method, params))

    def call(self, name, arguments=None, error=False):
        result = self.rpc('tools/call', {'name':name,'arguments':arguments or {}})
        assert bool(result.get('isError',False))==error, result
        if error: return result
        if result['content'][0]['type']=='image': return base64.b64decode(result['content'][0]['data'])
        return json.loads(result['content'][0]['text'])

    def close(self):
        try:
            if self.session and (not self.child or self.child.poll() is None):
                self._request(method='DELETE')
        finally:
            if self.child and self.child.poll() is None:
                self.child.terminate()
                try: self.child.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    self.child.kill(); self.child.wait()
                    raise AssertionError('MCP did not shut down cleanly after SIGTERM')
            for future in self.pending.values(): future.cancel()
            self.executor.shutdown(wait=True)
            if self.log: self.log.close()
        if self.child: assert self.child.returncode==self.expected_exit_code, self.child.returncode

    def kill(self):
        self.child.kill()
        self.child.wait(timeout=10)
        assert self.child.returncode==-9, self.child.returncode
        self.expected_exit_code = -9

    def __enter__(self): return self
    def __exit__(self, *unused): self.close()
