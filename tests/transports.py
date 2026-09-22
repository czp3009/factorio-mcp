#!/usr/bin/env python3
"""Exercise simultaneous SDK transports without launching or restarting Factorio."""
import argparse
import json
from pathlib import Path
import re
import selectors
import subprocess
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from mcp_http import McpClient

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--injector',type=Path,default=ROOT/'build/bin/linuxX64/releaseExecutable/factorio-mcp.kexe')
    parser.add_argument('--pid',type=int,help='Optionally reuse this running Factorio client')
    parser.add_argument('--work',type=Path,default=ROOT/'build/verification/transports')
    args = parser.parse_args()
    args.work.mkdir(parents=True,exist_ok=True)
    records = []
    for options in [ ['--no-stdio','--no-http'], ['--http-port','65536'], ['--no-http','--http-port','0'] ]:
        failed = subprocess.run([str(args.injector)]+options,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=10)
        assert failed.returncode==1 and not failed.stdout, failed
    records.append('Invalid transport options rejected')
    log_path = args.work/'both-stderr.log'
    with log_path.open('w') as log:
        child = subprocess.Popen([str(args.injector),'--http-port','0'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=log)
        reader = selectors.DefaultSelector()
        reader.register(child.stdout,selectors.EVENT_READ)
        try:
            deadline = time.monotonic()+15
            while True:
                match = re.search(r'MCP HTTP listening at (http://[^\s]+)',log_path.read_text())
                if match: break
                assert child.poll() is None and time.monotonic()<deadline,log_path.read_text()
                time.sleep(.02)
            url = match[1]
            port = url.split(':')[2].split('/')[0]
            occupied = subprocess.run([str(args.injector),'--no-stdio','--http-port',port],
                stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15)
            assert occupied.returncode!=0 and not occupied.stdout,occupied
            records.append('An occupied HTTP port fails startup')

            def stdio(message):
                child.stdin.write((json.dumps(message)+'\n').encode());child.stdin.flush()
                if 'id' not in message: return
                assert reader.select(15),'No stdio response'
                result = json.loads(child.stdout.readline())
                assert result['id']==message['id'] and 'error' not in result,result
                return result['result']

            with McpClient(url=url) as http:
                initialized = stdio({'jsonrpc':'2.0','id':1,'method':'initialize','params':{
                    'protocolVersion':'2025-11-25','capabilities':{},'clientInfo':{'name':'stdio-smoke','version':'1'}}})
                assert initialized['serverInfo']['name']=='factorio-mcp'
                stdio({'jsonrpc':'2.0','method':'notifications/initialized','params':{}})
                catalog = stdio({'jsonrpc':'2.0','id':2,'method':'tools/list','params':{}})
                assert {t['name'] for t in catalog['tools']}==http.advertised
                records.append('Simultaneous stdio and HTTP publish the same tools; stdout is protocol-only')
                if args.pid:
                    first = http.call('status',{'pid':args.pid})
                    attached = http.attach_ready(args.pid)
                    for _ in range(2): assert http.call('attach',{'pid':args.pid})['instance']==attached['instance']
                    result = stdio({'jsonrpc':'2.0','id':3,'method':'tools/call','params':{'name':'player','arguments':{}}})
                    assert not result.get('isError'),result
                    assert json.loads(result['content'][0]['text'])['index']==http.call('player')['index']
                    records.append({'shared_attachment':attached,'player':http.call('player')})
                with McpClient(url=url) as other:
                    assert other.call('status')==http.call('status')
                assert http.rpc('ping',{})=={}
                records.append('Deleting a second HTTP session leaves the first session and game state intact')
                for headers,code in [({'Mcp-Session-Id':'unknown'},404),({'Origin':'https://unrelated.invalid'},403)]:
                    request_headers={'Content-Type':'application/json','Accept':'application/json, text/event-stream'}
                    request_headers.update(headers)
                    try:
                        urlopen(Request(url,data=json.dumps({'jsonrpc':'2.0','id':9,'method':'ping'}).encode(),headers=request_headers),timeout=5)
                        raise AssertionError('Invalid request accepted')
                    except HTTPError as error: assert error.code==code,error
                child.stdin.close()
                assert http.rpc('ping',{})=={} and child.poll() is None
                records.append('HTTP remains available after stdio EOF')
            child.terminate();assert child.wait(timeout=15)==0
            assert child.stdout.read()==b'', 'Non-protocol stdout after stdio EOF'
            records.append('SIGTERM closes the server cleanly')
        finally:
            if child.poll() is None: child.kill();child.wait()
            if not child.stdin.closed: child.stdin.close()
            child.stdout.close();reader.close()
    # Stdio-only operation still follows the agent-owned EOF lifecycle.
    stdio_child = subprocess.Popen([str(args.injector),'--no-http'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    try:
        stdio_child.stdin.close()
        assert stdio_child.wait(timeout=10)==0
        assert stdio_child.stdout.read()==b''
    finally:
        if stdio_child.poll() is None: stdio_child.kill();stdio_child.wait()
        stdio_child.stdout.close();stdio_child.stderr.close()
    records.append('Stdio-only mode exits cleanly at EOF')
    # A fresh native MCP reuses the same game-resident observer and world binding.
    with McpClient(args.injector,args.pid,args.work,http_port=int(port)) as restarted:
        if args.pid:
            state = restarted.call('status')
            assert state['instance']==attached['instance'] and state['world_generation']==attached['world_generation'],state
            assert restarted.call('player')['index']>0
            records.append({'restarted_on_same_game':state})
    (args.work/'result.json').write_text(json.dumps(records,indent=2))
    print(json.dumps(records,indent=2))


if __name__=='__main__': main()
