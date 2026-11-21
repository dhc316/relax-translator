"""Verify real parallel HTTP and early display using the final signed APK and a local fixture."""
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
import sys
import threading

serial, label = sys.argv[1:3]
helper = Path(__file__).with_name('smoke-v04.py')
sys.argv = [str(helper), '--serial', serial, '--label', label]
ns = {'__file__': str(helper), '__name__': 'qa_helpers'}
exec(compile(helper.read_text(encoding='utf-8').split('upgrade = {}')[0], str(helper), 'exec'), ns)
run, wait, click, screenshot, make_preset, scroll_to, nodes = [ns[key] for key in ('run', 'wait', 'click', 'screenshot', 'make_preset', 'scroll_to', 'nodes')]
OUT = ns['OUT']
assert 'versionName=0.4.0' in run('shell', 'dumpsys', 'package', ns['PACKAGE'])
both = threading.Event(); release_full = threading.Event(); lock = threading.Lock(); requests = []

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        fast = '本次仅返回翻译结果' in request['messages'][0]['content']
        with lock:
            requests.append(('A' if fast else 'B', request))
            if len(requests) == 2:
                both.set()
        both.wait(5)
        if not fast:
            release_full.wait(50)
        content = '快速译文已经显示。' if fast else json.dumps({'translation': '完整译文与解析已经就绪。',
            'source_reading': {'language': 'en', 'phonetic': '/tɛst/'},
            'target_reading': {'language': 'zh-CN', 'phonetic': 'wán zhěng yì wén'},
            'learning': {'usage_notes': '这是本机兼容接口返回的完整用法说明。', 'has_errors': False, 'corrections': []}}, ensure_ascii=False)
        payload = json.dumps({'choices': [{'message': {'content': content}, 'finish_reason': 'stop'}]}, ensure_ascii=False).encode('utf-8')
        try:
            self.send_response(200); self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(payload))); self.end_headers(); self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass

server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
server.daemon_threads = True
threading.Thread(target=server.serve_forever, daemon=True).start()
port = server.server_address[1]
try:
    run('reverse', f'tcp:{port}', f'tcp:{port}')
    make_preset('Parallel04')
    click('打开菜单', 'content-desc'); click('打开设置', 'content-desc'); click('添加翻译模型')
    for field, value in [('显示名称，如：我的翻译模型', 'ParallelQA'),
                         ('接口地址，如：https://服务地址/v1', f'http://127.0.0.1:{port}/v1'),
                         ('模型 ID（供应商提供）', 'qa-parallel')]:
        scroll_to(field); click(field); run('shell', 'input', 'text', value); run('shell', 'input', 'keyevent', '4')
    click('保存模型'); click('返回首页', 'content-desc')
    click('试译一句英文'); click('开始翻译', 'content-desc')
    wait('快速译文已经显示。')
    assert both.is_set() and not release_full.is_set()
    wait('译文已就绪，正在补充注音与词汇解析…')
    wait('复制译文', 'content-desc'); screenshot('10-fast-before-full-http')
    assert len(requests) == 2 and {kind for kind, _ in requests} == {'A', 'B'}
    assert requests[0][1]['messages'][1:] == requests[1][1]['messages'][1:]
    release_full.set(); wait('完整译文与解析已经就绪。'); wait('注音与读音')
    assert not nodes('译文已就绪，正在补充注音与词汇解析…')
    screenshot('11-full-result-http')
    click('返回历史', 'content-desc'); wait('1 条'); wait('完整译文与解析已经就绪。')
    assert not nodes('快速译文已经显示。')
    report = {'version': '0.4.0', 'passed': True, 'apkSha256': hashlib.sha256((OUT / '译间-v0.4.0.apk').read_bytes()).hexdigest(),
              'requests': len(requests), 'sameInputAndContext': True, 'fastVisibleWhileFullBlocked': True,
              'fullReplacesSameHistoryRecord': True, 'service': 'local loopback fixture; no remote account'}
    (OUT / f'parallel-{label}.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
finally:
    release_full.set(); run('reverse', '--remove', f'tcp:{port}', check=False)
    server.shutdown(); server.server_close()
