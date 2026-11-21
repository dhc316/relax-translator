"""Capture real, credential-free product screenshots on a project-owned emulator."""
from pathlib import Path
import hashlib
import json
import sys
import time

serial = sys.argv[1]
helper = Path(__file__).with_name('smoke-v03.py')
sys.argv = [str(helper), '--serial', serial, '--label', 'product']
prefix = helper.read_text(encoding='utf-8').split('avd = run(')[0].replace("'artifacts/v0.3.0'", "'artifacts/v0.6.0'")
ns = {'__file__': str(helper), '__name__': 'qa_helpers'}
exec(compile(prefix, str(helper), 'exec'), ns)
run, wait, click, bounds, tree, nodes = [ns[k] for k in ('run', 'wait', 'click', 'bounds', 'tree', 'nodes')]
ROOT, OUT, PACKAGE = [ns[k] for k in ('ROOT', 'OUT', 'PACKAGE')]
avd = run('shell', 'getprop', 'ro.kernel.qemu.avd_name').strip() or run('shell', 'getprop', 'ro.boot.qemu.avd_name').strip()
assert avd in ('yijian_api30', 'yijian_api35'), avd
assert 'versionName=0.6.0' in run('shell', 'dumpsys', 'package', PACKAGE)
width, height = map(int, ns['re'].findall(r'(\d+)x(\d+)', run('shell', 'wm', 'size'))[-1])
screens = ROOT / 'docs/assets/screenshots'
screens.mkdir(parents=True, exist_ok=True)
ns['SCREENS'] = screens
screenshot = ns['screenshot']

def align_heading(text):
    for _ in range(10):
        found = nodes(text)
        if found:
            top = bounds(found[0])[1]
            target = int(height * .17)
            delta = top - target
            if delta > 50:
                start = int(height * .76)
                end = max(int(height * .17), start - delta)
                run('shell', 'input', 'swipe', str(width // 2), str(start), str(width // 2), str(end), '450')
            return
        run('shell', 'input', 'swipe', str(width // 2), str(int(height * .76)), str(width // 2), str(int(height * .35)), '400')
    raise AssertionError('Cannot find ' + text)

def make_preset(name):
    click('打开菜单', 'content-desc'); click('＋  新建预设'); wait('给这张卡片一个用途'); click('设置这个预设')
    click('预设名称，如：工作邮件')
    run('shell', 'input', 'keyevent', '123')
    run('shell', 'input', 'keyevent', *(['67'] * 35))
    run('shell', 'input', 'text', name)
    run('shell', 'input', 'keyevent', '4'); click('完成'); wait(name)

# Only clear this disposable emulator fixture, after the signed upgrade check.
run('shell', 'am', 'force-stop', PACKAGE)
assert 'Success' in run('shell', 'pm', 'clear', PACKAGE)
run('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
wait('轻松翻译'); wait('今天，想翻译些什么？')
assert not nodes('译间')
screenshot('home')
print('Captured home', flush=True)

click('试试中文辨析'); click('开始翻译', 'content-desc')
wait('This job is a good fit for you.'); wait('注音与读音')
screenshot('translation')
align_heading('一个意思，多种表达'); wait('a good fit')
screenshot('vocabulary')
click('返回历史', 'content-desc')
print('Captured translation and vocabulary', flush=True)

make_preset('Japanese')
click('日语注音示例'); click('开始翻译', 'content-desc')
wait('今日は何を食べますか。'); wait('注音与读音')
align_heading('注音与读音')
wait('假名  きょうはなにをたべますか。')
wait('罗马字  Kyō wa nani o tabemasu ka?')
screenshot('japanese')
click('返回历史', 'content-desc')
print('Captured Japanese readings', flush=True)

click('上下文关闭'); wait('附带上下文')
switches = [n for n in tree().iter('node') if n.get('checkable') == 'true']
assert len(switches) == 1
x1, y1, x2, y2 = bounds(switches[0])
run('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
wait('最近多少条'); screenshot('context')
run('shell', 'input', 'tap', str(width//2), str(int(height*.08)))
if nodes('附带上下文'):
    run('shell', 'input', 'keyevent', '4')
    run('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
click('打开菜单', 'content-desc'); click('打开设置', 'content-desc'); wait('设置')
screenshot('settings')
click('添加翻译模型'); wait('API 密钥')
screenshot('models')
click('关闭模型设置', 'content-desc')
print('Captured context, settings and model setup', flush=True)

images = ['home', 'translation', 'vocabulary', 'japanese', 'context', 'settings', 'models']
report = {'version': '0.6.0', 'android': run('shell', 'getprop', 'ro.build.version.release').strip(),
          'passed': True, 'actualScreenshots': True, 'providerRequests': False,
          'apkSha256': hashlib.sha256((OUT/'relax-translator-v0.6.0.apk').read_bytes()).hexdigest(),
          'screenshots': {name: {'file': f'docs/assets/screenshots/{name}.png',
                                'sha256': hashlib.sha256((screens/f'{name}.png').read_bytes()).hexdigest()} for name in images}}
(OUT/'product-screenshots.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
