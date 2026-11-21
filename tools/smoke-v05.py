"""Check the signed layout fix on project-owned emulators, optionally upgrading 0.4.0."""
from pathlib import Path
import hashlib
import json
import subprocess
import time

# Reuse only the UI helper definitions, before the older script's install/actions.
helper = Path(__file__).with_name('smoke-v03.py')
prefix = helper.read_text(encoding='utf-8').split('avd = run(')[0]
prefix = prefix.replace("'artifacts/v0.3.0'", "'artifacts/v0.5.0'")
ns = {'__file__': str(helper), '__name__': 'qa_helpers'}
exec(compile(prefix, str(helper), 'exec'), ns)
run, wait, click, screenshot, bounds, tree, nodes = [ns[key] for key in ('run', 'wait', 'click', 'screenshot', 'bounds', 'tree', 'nodes')]
ROOT, OUT, BASE, PACKAGE, args = [ns[key] for key in ('ROOT', 'OUT', 'BASE', 'PACKAGE', 'args')]
avd = run('shell', 'getprop', 'ro.kernel.qemu.avd_name').strip() or run('shell', 'getprop', 'ro.boot.qemu.avd_name').strip()
assert avd in ('yijian_api30', 'yijian_api35'), avd
width, height = map(int, ns['re'].findall(r'(\d+)x(\d+)', run('shell', 'wm', 'size'))[-1])

def make_preset(name):
    click('打开菜单', 'content-desc'); click('＋  新建预设'); wait('给这张卡片一个用途'); click('设置这个预设')
    click('预设名称，如：工作邮件')
    run('shell', 'input', 'keyevent', '123')
    run('shell', 'input', 'keyevent', *(['67'] * 35))
    run('shell', 'input', 'text', name)
    run('shell', 'input', 'keyevent', '4'); click('完成'); wait(name)

def japanese_record():
    click('日语注音示例'); click('开始翻译', 'content-desc')
    wait('今日は何を食べますか。')

def scroll_to(value):
    for _ in range(10):
        if nodes(value):
            return
        run('shell', 'input', 'swipe', str(width // 2), str(int(height * .78)), str(width // 2), str(int(height * .35)), '350')
    raise AssertionError('Could not scroll to ' + value)

upgrade = {}
if args.upgrade:
    assert 'versionName=0.4.0' in run('shell', 'dumpsys', 'package', PACKAGE)
    run('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
    if nodes('返回历史', 'content-desc'):
        click('返回历史', 'content-desc')
    make_preset('Layer05'); japanese_record(); click('返回历史', 'content-desc')
    wait('Layer05'); wait('今日は何を食べますか。'); screenshot('00-before-upgrade')
    upgrade = {'from': '0.4.0', 'preset': 'Layer05', 'translation': '今日は何を食べますか。'}
else:
    run('uninstall', PACKAGE, check=False)

apk = OUT / '译间-v0.5.0.apk'
assert 'Success' in run('install', '-r', str(apk))
assert 'versionName=0.5.0' in run('shell', 'dumpsys', 'package', PACKAGE)
run('shell', 'am', 'force-stop', PACKAGE)
run('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
if args.upgrade:
    wait('Layer05'); wait('今日は何を食べますか。')
    upgrade['preset_and_history_preserved'] = True
    screenshot('01-upgrade-preserved'); click('今日は何を食べますか。')
else:
    wait('今天，想翻译些什么？'); make_preset('Layer05'); japanese_record()

wait('注音与读音')
edits = [node for node in tree().iter('node') if node.get('class') == 'android.widget.EditText']
assert len(edits) == 1
source_box = bounds(edits[0])
translation_box = bounds(wait('今日は何を食べますか。')[0])
reading_box = bounds(wait('注音与读音')[0])
assert source_box[3] <= translation_box[1] < translation_box[3] <= reading_box[1]
screenshot('02-input-and-result-first')
scroll_to('假名  きょうはなにをたべますか。')
wait('罗马字  Kyō wa nani o tabemasu ka?')
assert not nodes('纠错解析')
screenshot('03-readings-before-learning')
click('返回历史', 'content-desc'); click('今日は何を食べますか。')
edits = [node for node in tree().iter('node') if node.get('class') == 'android.widget.EditText']
x1, y1, x2, y2 = bounds(edits[0])
run('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
wait('收起键盘'); screenshot('04-detail-keyboard'); click('收起键盘')
click('返回历史', 'content-desc'); wait('Layer05'); screenshot('05-middle-overlap')

title = max(wait('Layer05'), key=lambda node: bounds(node)[1])
_, y1, _, y2 = bounds(title)
deck_y = min(height - 260, (y1 + y2) // 2 + 110)
# The left foreground card owns its exposed edge.
run('shell', 'input', 'tap', str(int(width * .025)), str(deck_y))
time.sleep(1)
assert not nodes('Layer05 · 独立上下文')
screenshot('06-left-card-receives-overlap-tap')
# Click the exposed right strip to return to the saved preset.
run('shell', 'input', 'tap', str(int(width * .985)), str(deck_y))
wait('Layer05 · 独立上下文'); wait('今日は何を食べますか。')
drag = subprocess.Popen(BASE + ['shell', 'input', 'swipe', str(int(width * .70)), str(deck_y), str(int(width * .27)), str(deck_y), '3000'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
time.sleep(.9); screenshot('07-deck-in-motion'); drag.communicate(timeout=8)
report = {'version': '0.5.0', 'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
          'android': run('shell', 'getprop', 'ro.build.version.release').strip(), 'passed': True, 'upgrade': upgrade,
          'checks': ['signed APK installed', 'source and translation above readings', 'Japanese kana and romaji retained',
                     'no correction card for correct input', 'real detail keyboard', 'left foreground card remains selectable',
                     'right background card remains selectable', 'history survives preset switching', 'swipe frame captured']}
(OUT / f'smoke-{args.label}.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
