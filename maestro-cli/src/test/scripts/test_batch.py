#!/usr/bin/env python3
"""Headless test-batch regression. Usage: test_batch.py /absolute/maestro /tmp/output.

Set SE_BROWSER_PATH/SE_CACHE_PATH to an isolated headless Chrome setup if needed.
The supplied executable must be built from the source under test.
"""
import http.server
import json
import os
import signal
import shlex
from pathlib import Path
import subprocess
import sys
import threading

executable = Path(sys.argv[1]).resolve()
output = Path(sys.argv[2]).resolve()
output.mkdir(parents=True, exist_ok=True)

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        content = b'''<!doctype html><meta charset="utf-8">
          <div id="state"></div><input id="entry"><button>Blur</button>
          <script>
          document.getElementById('state').textContent =
            document.cookie || localStorage.leak || sessionStorage.leak ? 'LEAKED' : 'CLEAN';
          document.getElementById('entry').oninput = () => {
            document.cookie='leak=yes'; localStorage.leak='yes'; sessionStorage.leak='yes';
          };
          </script>'''
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, *_):
        pass

server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
flow = output / 'isolation.yaml'
flow.write_text('''url: http://127.0.0.1:%d/
---
- launchApp
- assertVisible: CLEAN
- tapOn:
    id: entry
- inputText: "${typeof VALUE === 'undefined' ? 'default' : VALUE}"
- assertVisible: "${typeof VALUE === 'undefined' ? 'default' : VALUE}"
''' % server.server_port)
failure_flow = output / 'failure.yaml'
failure_flow.write_text(flow.read_text() + '- assertTrue: ${false}\n')
common = ['--platform=web', '--headless', '--test-output-dir', str(output / 'reports')]
requests = [
    dict(id='override', args=common + ['-e', 'VALUE=first', str(flow)]),
    dict(id='failure', args=common + ['-e', 'VALUE=second', str(failure_flow)]),
    dict(id='defaults', args=common + [str(flow)]),
]
env = os.environ.copy()
(output / 'java-home').mkdir(exist_ok=True)
env['JAVA_OPTS'] = env.get('JAVA_OPTS', '') + ' ' + shlex.quote('-Duser.home=' + str(output / 'java-home'))
env['XDG_STATE_HOME'] = str(output / 'state')
env.update(MAESTRO_CLI_NO_ANALYTICS='1', MAESTRO_DISABLE_UPDATE_CHECK='true',
           MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED='true')
try:
    capabilities = subprocess.run([str(executable), 'test-batch', '--capabilities'],
        env=env, capture_output=True, text=True, timeout=30, check=True)
    assert json.loads(capabilities.stdout) == dict(protocolVersion=1, platform='web',
        headless=True, isolation='fresh-browser-per-request')
    with (output / 'stderr.log').open('w') as log:
        process = subprocess.Popen([str(executable), 'test-batch'], env=env,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log,
            text=True, start_new_session=True)
        try:
            stdout, _ = process.communicate(
                ''.join(json.dumps(request) + '\n' for request in requests), timeout=120)
        except subprocess.TimeoutExpired:
            # Give the CLI shutdown hook a chance to close its browser. The process
            # group belongs exclusively to this test if forced cleanup is needed.
            process.terminate()
            try:
                process.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate()
            raise
    (output / 'stdout.jsonl').write_text(stdout)
    replies = [json.loads(line) for line in stdout.splitlines()]
    assert process.returncode == 0, process.returncode
    assert replies == [dict(id='override', exitCode=0), dict(id='failure', exitCode=1),
                       dict(id='defaults', exitCode=0)], replies
    # Current release branches write a stable commands.json into each flow report.
    artifacts = list((output / 'reports').rglob('commands.json'))
    assert len(artifacts) == 3, artifacts
    assert len({path.parent for path in artifacts}) == 3, artifacts
    values = []
    for artifact in sorted(artifacts):
        for item in json.loads(artifact.read_text()):
            command = item.get('metadata', {}).get('evaluatedCommand', {}).get('inputTextCommand')
            if command:
                values.append(command['text'])
    assert values == ['first', 'second', 'default'], values
    print('PASS: capabilities, fresh cookies/storage, env reset, failure recovery, unique artifacts')
finally:
    server.shutdown()
    server.server_close()
    thread.join()
