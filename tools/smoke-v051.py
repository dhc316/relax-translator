"""Verify the signed 0.5.1 toolbar and preserved translation flows on owned emulators."""
from pathlib import Path
import hashlib
import json
import subprocess
import time
import cv2
import numpy as np

# Reuse only the UI helper definitions, before the older script's install/actions.
helper = Path(__file__).with_name('smoke-v03.py')
prefix = helper.read_text(encoding='utf-8').split('avd = run(')[0]
prefix = prefix.replace("'artifacts/v0.3.0'", "'artifacts/v0.5.1'")
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
    assert 'versionName=0.5.0' in run('shell', 'dumpsys', 'package', PACKAGE)
    run('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
    if nodes('返回历史', 'content-desc'):
        click('返回历史', 'content-desc')
    make_preset('Frost051'); japanese_record(); click('返回历史', 'content-desc')
    wait('Frost051'); wait('今日は何を食べますか。'); screenshot('00-before-upgrade')
    upgrade = {'from': '0.5.0', 'preset': 'Frost051', 'translation': '今日は何を食べますか。'}
else:
    run('uninstall', PACKAGE, check=False)

apk = OUT / '译间-v0.5.1.apk'
assert 'Success' in run('install', '-r', str(apk))
assert 'versionName=0.5.1' in run('shell', 'dumpsys', 'package', PACKAGE)
run('shell', 'am', 'force-stop', PACKAGE)
run('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
if args.upgrade:
    wait('Frost051'); wait('今日は何を食べますか。')
    upgrade['preset_and_history_preserved'] = True
    screenshot('01-upgrade-preserved'); click('今日は何を食べますか。')
else:
    wait('今天，想翻译些什么？'); make_preset('Frost051'); japanese_record()

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
click('返回历史', 'content-desc'); wait('Frost051'); screenshot('05-middle-overlap')

title = max(wait('Frost051'), key=lambda node: bounds(node)[1])
_, y1, _, y2 = bounds(title)
deck_y = min(height - 260, (y1 + y2) // 2 + 110)
# The left foreground card owns its exposed edge.
run('shell', 'input', 'tap', str(int(width * .025)), str(deck_y))
time.sleep(1)
assert not nodes('Frost051 · 独立上下文')
screenshot('06-left-card-receives-overlap-tap')
# Click the exposed right strip to return to the saved preset.
run('shell', 'input', 'tap', str(int(width * .985)), str(deck_y))
wait('Frost051 · 独立上下文'); wait('今日は何を食べますか。')
drag = subprocess.Popen(BASE + ['shell', 'input', 'swipe', str(int(width * .70)), str(deck_y), str(int(width * .27)), str(deck_y), '3000'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
time.sleep(.9); screenshot('07-deck-in-motion'); drag.communicate(timeout=8)
# Capture the actual scroll backdrop, in addition to native pixel and layout tests.
click('打开菜单', 'content-desc'); scroll_to('Frost051'); click('Frost051')
click('今日は何を食べますか。')
for _ in range(5):
    click('重新翻译'); wait('注音与读音')
click('返回历史', 'content-desc')
wait('历史记录'); screenshot('08-clear-history-heading')
video_dir = OUT / 'videos'; video_dir.mkdir(exist_ok=True)
remote_video = '/sdcard/yijian-v051-frost.mp4'
recorder = subprocess.Popen(BASE + ['shell', 'screenrecord', '--time-limit', '9', '--size', '720x1560', remote_video], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
time.sleep(.8)
run('shell', 'input', 'swipe', str(width // 2), str(int(height * .50)), str(width // 2), str(int(height * .13)), '2500')
screenshot('09-linear-frost-scrolled')
# Locate the strip from real toolbar bounds: system insets differ on API 35.
# It sits right of the brand, above the subtitle and left of the menu.
title_bounds = bounds(wait('译间')[0])
menu_bounds = bounds(wait('打开菜单', 'content-desc')[0])
image_path = OUT / 'screenshots' / args.label / '09-linear-frost-scrolled.png'
gray = cv2.imdecode(np.frombuffer(image_path.read_bytes(), dtype=np.uint8), cv2.IMREAD_GRAYSCALE)
image_height, image_width = gray.shape
probe_bounds = [max(title_bounds[2] + 16, int(image_width * .33)), title_bounds[1] + 6,
                min(menu_bounds[0] - 24, int(image_width * .80)), title_bounds[3] - 6]
left, top, right, bottom = probe_bounds
assert left < right and top < bottom
strip = gray[top:bottom, left:right]
sharp_edge_variance = float(cv2.Laplacian(strip, cv2.CV_64F).var())
assert sharp_edge_variance < 100, f'Sharp history leaked behind the toolbar: {sharp_edge_variance}'
run('shell', 'input', 'swipe', str(width // 2), str(int(height * .21)), str(width // 2), str(int(height * .49)), '2500')
recorder.communicate(timeout=15)
run('pull', remote_video, str(video_dir / (args.label + '-header-blur.mp4')))

report = {'version': '0.5.1', 'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'backdropSharpEdgeVariance': sharp_edge_variance, 'backdropProbeBounds': probe_bounds,
          'android': run('shell', 'getprop', 'ro.build.version.release').strip(), 'passed': True, 'upgrade': upgrade,
          'checks': ['signed APK installed', 'source and translation above readings', 'Japanese kana and romaji retained',
                     'no correction card for correct input', 'real detail keyboard', 'left foreground card remains selectable',
                     'right background card remains selectable', 'history survives preset switching', 'swipe frame captured', 'history heading clear outside toolbar', 'scroll backdrop recording captured']}
(OUT / f'smoke-{args.label}.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
