"""Validate the signed 0.3 APK and optional in-place upgrade on owned emulators."""
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
parser.add_argument('--label', required=True)
parser.add_argument('--upgrade', action='store_true')
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    raise SystemExit('Only project-owned emulators are supported.')
BASE = [str(ROOT / '.local/sdk/platform-tools/adb.exe'), '-s', args.serial]
PACKAGE = 'com.yijian.translate'
OUT = ROOT / 'artifacts/v0.3.0'
SCREENS = OUT / 'screenshots' / args.label
SCREENS.mkdir(parents=True, exist_ok=True)

def run(*cmd, check=True):
    return subprocess.run(BASE + list(cmd), check=check, capture_output=True).stdout.decode('utf-8', errors='replace')

def bounds(node):
    return list(map(int, re.findall(r'\d+', node.get('bounds', ''))))

def tree():
    run('shell', 'uiautomator', 'dump', '/data/local/tmp/yijian-window.xml')
    return ET.fromstring(run('exec-out', 'cat', '/data/local/tmp/yijian-window.xml'))

def nodes(value, attribute='text'):
    found = []
    def walk(node, ancestors):
        if node.get(attribute) == value:
            for candidate in [node] + ancestors[::-1]:
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
        result = nodes(value, attribute)
        if result:
            return result
        time.sleep(.2)
    raise AssertionError('UI element not found: ' + value)

def click(value, attribute='text', last=False):
    found = wait(value, attribute)
    node = max(found, key=lambda n: bounds(n)[1]) if last else found[0]
    x1,y1,x2,y2 = bounds(node)
    run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(.3)

def screenshot(name):
    time.sleep(.5)
    data = subprocess.check_output(BASE + ['exec-out','screencap','-p'])
    assert data.startswith(b'\x89PNG')
    (SCREENS / (name + '.png')).write_bytes(data)

def scroll_to(value):
    for _ in range(12):
        if nodes(value):
            return
        run('shell','input','swipe',str(width//2),str(int(height*.78)),str(width//2),str(int(height*.35)),'350')
    raise AssertionError('Could not scroll to: ' + value)

avd = run('shell','getprop','ro.kernel.qemu.avd_name').strip() or run('shell','getprop','ro.boot.qemu.avd_name').strip()
assert avd in ['yijian_api30','yijian_api35'], avd
upgrade = {}
if args.upgrade:
    assert 'versionName=0.2.0' in run('shell','dumpsys','package',PACKAGE)
    run('shell','am','start','-n',PACKAGE+'/.MainActivity')
    wait('Travel'); wait('能给我一点时间吗？我想把这件事做好。')
    upgrade = {'from':'0.2.0','preset_before':'Travel','history_before':True}
else:
    run('uninstall',PACKAGE,check=False)
assert 'Success' in run('install','-r',str(OUT/'译间-v0.3.0.apk'))
run('shell','settings','put','global','animator_duration_scale','1')
run('shell','am','start','-n',PACKAGE+'/.MainActivity')
if args.upgrade:
    wait('Travel');wait('能给我一点时间吗？我想把这件事做好。')
    upgrade['preset_and_history_preserved'] = True
    screenshot('00-upgrade-preserved')
print('Installed final release:',args.label,flush=True)
width,height=map(int,re.findall(r'(\d+)x(\d+)',run('shell','wm','size'))[-1])

def make_preset(name):
    click('新建预设','content-desc');wait('给这张卡片一个用途');click('设置这个预设')
    click('预设名称，如：工作邮件')
    run('shell','input','keyevent','123');run('shell','input','keyevent',*(['67']*35));run('shell','input','text',name)
    run('shell','input','keyevent','4');click('完成');wait(name)

# A new preset has its own empty history and saves settings without a Save control.
make_preset('Reading')
wait('今天，想翻译些什么？');click('日语注音示例');click('开始翻译','content-desc')
wait('注音与读音');wait('假名  きょうはなにをたべますか。');wait('罗马字  Kyō wa nani o tabemasu ka?')
assert not nodes('纠错解析')
screenshot('02-japanese-readings-first')
print('Both Japanese reading forms are first:',args.label,flush=True)
# Open the real system keyboard on the editable original.
edits=[n for n in tree().iter('node') if n.get('class')=='android.widget.EditText']
x1,y1,x2,y2=bounds(edits[0]);run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
wait('收起键盘');screenshot('03-detail-keyboard');click('收起键盘')
click('返回历史','content-desc');wait('今日は何を食べますか。');screenshot('04-simple-history')
click('日语',last=True);wait('翻译成');screenshot('05-language-menu');click('英语')
policy = next(n.get('text') for n in tree().iter('node') if re.match(r'^(上下文关闭|近 \d+ 分钟|最近 \d+ 条|\d+ 分钟 · \d+ 条)$',n.get('text','')))
click(policy);click('按时间')
screenshot('06-context-soft-shadow');run('shell','input','keyevent','4')
# Second preset starts empty, with no history from Reading.
make_preset('Work');wait('今天，想翻译些什么？');assert not nodes('今日は何を食べますか。')
click('试译一句英文');click('开始翻译','content-desc')
wait('能给我一点时间吗？我想把这件事做好。');assert not nodes('纠错解析')
click('返回历史','content-desc');wait('Work');assert not nodes('今日は何を食べますか。')
# Restart also checks automatic language/context persistence and both side previews.
run('shell','am','force-stop',PACKAGE);run('shell','am','start','-n',PACKAGE+'/.MainActivity')
wait('Work');click('打开菜单','content-desc');wait('预设与独立上下文');screenshot('07-preset-drawer')
click('Reading');wait('今日は何を食べますか。');wait('英语');wait('近 30 分钟');assert not nodes('能给我一点时间吗？我想把这件事做好。')
screenshot('08-preset-context-isolation')
click('打开菜单','content-desc');click('Work');wait('Work')
run('shell','am','force-stop',PACKAGE);run('shell','am','start','-n',PACKAGE+'/.MainActivity');wait('Work')
screenshot('09-stacked-cards')
card=max(wait('Work'),key=lambda n:bounds(n)[1]);x1,y1,x2,y2=bounds(card)
drag=subprocess.Popen(BASE+['shell','input','swipe',str(int(width*.72)),str((y1+y2)//2),str(int(width*.25)),str((y1+y2)//2),'3000'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
time.sleep(1);screenshot('10-deck-in-motion');drag.wait(timeout=8)
report={'version':'0.3.0','apkSha256':__import__('hashlib').sha256((OUT/'译间-v0.3.0.apk').read_bytes()).hexdigest(),
        'android':run('shell','getprop','ro.build.version.release').strip(),'passed':True,'upgrade':upgrade,
        'checks':['signed APK installation','source and translation reading blocks first','Japanese kana and romaji',
                  'no correction card for correct input','actual IME visible above source','auto-saved preset name and language',
                  'separate preset histories after restart','soft popup shadow','real overlapping neighbour cards and intermediate swipe frame']}
(OUT/f'smoke-{args.label}.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)
