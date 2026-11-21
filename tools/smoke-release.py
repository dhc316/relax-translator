"""Exercise the signed APK on a project-owned emulator and capture native screens."""
from pathlib import Path
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--label', default='android11')
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    raise SystemExit('This script only resets project-owned emulator app data.')
ADB = ROOT / '.local/sdk/platform-tools/adb.exe'
BASE = [str(ADB), '-s', args.serial]
PACKAGE = 'com.yijian.translate'
SCREENS = ROOT / 'artifacts/screenshots'
SCREENS.mkdir(parents=True, exist_ok=True)

def run(*command, check=True):
    return subprocess.run(BASE + list(command), check=check, capture_output=True).stdout.decode('utf-8', errors='replace')

def tree():
    run('shell', 'uiautomator', 'dump', '/data/local/tmp/yijian-window.xml')
    return ET.fromstring(run('exec-out', 'cat', '/data/local/tmp/yijian-window.xml'))

def nodes(value, attribute='text'):
    found = []
    def walk(node, ancestors):
        if node.get(attribute) == value:
            candidates = [node] + list(reversed(ancestors))
            for candidate in candidates:
                box = bounds(candidate)
                if len(box) == 4 and box[2] > box[0] and box[3] > box[1]:
                    found.append(candidate)
                    break
        for child in node:
            walk(child, ancestors + [node])
    walk(tree(), [])
    return found

def wait(value, attribute='text', timeout=20):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        found = nodes(value, attribute)
        if found:
            return found
        time.sleep(.25)
    raise AssertionError('UI element not found: ' + value)

def bounds(node):
    return list(map(int, re.findall(r'\d+', node.get('bounds', ''))))

def click(value, attribute='text', last=False):
    found = wait(value, attribute)
    node = max(found, key=lambda n: bounds(n)[1]) if last else found[0]
    x1, y1, x2, y2 = bounds(node)
    run('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(.25)

def screenshot(name):
    time.sleep(.5)
    target = SCREENS / f'release-{args.label}-{name}.png'
    data = subprocess.check_output(BASE + ['exec-out', 'screencap', '-p'])
    assert data.startswith(b'\x89PNG'), 'Screenshot failed'
    target.write_bytes(data)

run('uninstall', PACKAGE, check=False)
install = run('install', str(ROOT / 'artifacts/译间-v0.1.0.apk'))
assert 'Success' in install, install
run('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
wait('今天，想翻译些什么？')
screenshot('home')
click('试译一句英文')
click('开始翻译', 'content-desc')
wait('能给我一点时间吗？我想把这件事做好。')
screenshot('translation')
click('简体中文', last=True)
wait('翻译成')
screenshot('languages')
click('英语')
click('上下文关闭')
click('按时间')
screenshot('context')
run('shell', 'input', 'keyevent', '4')
wait('近 30 分钟')
click('本地演示')
wait('选择模型')
screenshot('models')
run('shell', 'input', 'keyevent', '4')
click('打开菜单', 'content-desc')
click('打开设置', 'content-desc')
wait('翻译模型')
screenshot('settings')
click('返回首页', 'content-desc')
card = wait('日常翻译')[0]
x1, y1, x2, y2 = bounds(card)
size = run('shell', 'wm', 'size')
width = int(re.findall(r'(\d+)x\d+', size)[-1])
run('shell', 'input', 'swipe', str(int(width*.87)), str((y1+y2)//2), str(int(width*.13)), str((y1+y2)//2), '350')
wait('给这张卡片一个用途')
screenshot('new-preset')
click('设置这个预设')
click('预设名称，如：工作邮件')
run('shell', 'input', 'text', 'Work')
run('shell', 'input', 'keyevent', '4')
click('保存预设')
wait('Work')
run('shell', 'am', 'force-stop', PACKAGE)
run('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
wait('Work')
wait('能给我一点时间吗？我想把这件事做好。')
report = {'serial': args.serial, 'android': run('shell','getprop','ro.build.version.release').strip(),
          'sdk': run('shell','getprop','ro.build.version.sdk').strip(),
          'installed_apk': '译间-v0.1.0.apk', 'passed': True,
          'checks': ['signed APK install and launch', 'demo translation', 'language popup and selection',
                     'context popup and mode change', 'model popup', 'settings navigation',
                     'swipe to new preset', 'inline preset save', 'history and preset survive restart']}
(ROOT / f'artifacts/release-smoke-{args.label}.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
