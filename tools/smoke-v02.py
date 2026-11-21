"""Validate the signed 0.2 APK and optional in-place upgrade on owned emulators."""
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
OUT = ROOT / 'artifacts/v0.2.0'
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
    before = run('shell','dumpsys','package',PACKAGE)
    assert 'versionName=0.1.0' in before, 'Expected installed 0.1.0 for upgrade verification'
    run('shell','am','start','-n',PACKAGE+'/.MainActivity')
    wait('Work')
    wait('能给我一点时间吗？我想把这件事做好。')
    upgrade = {'from':'0.1.0','preset_before':'Work','history_before':True}
else:
    run('uninstall',PACKAGE,check=False)
assert 'Success' in run('install','-r',str(OUT/'译间-v0.2.0.apk'))
run('shell','am','start','-n',PACKAGE+'/.MainActivity')
if args.upgrade:
    wait('Work'); wait('历史记录'); wait('能给我一点时间吗？我想把这件事做好。')
    upgrade['preset_and_history_preserved'] = True
    screenshot('00-upgrade-preserved')
    click('新建翻译会话','content-desc')
wait('今天，想翻译些什么？')
screenshot('01-home')
width,height=map(int,re.findall(r'(\d+)x(\d+)',run('shell','wm','size'))[-1])
click('试译一句英文')
click('开始翻译','content-desc')
wait('翻译详情'); wait('能给我一点时间吗？我想把这件事做好。')
screenshot('02-translation-detail')
# The editable original stays visible when the actual system keyboard opens.
edits=[n for n in tree().iter('node') if n.get('class')=='android.widget.EditText']
assert edits
x1,y1,x2,y2=bounds(edits[0]);run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
wait('收起键盘')
screenshot('03-detail-keyboard')
run('shell','input','keyevent','4')
scroll_to('纠错解析');screenshot('04-pronunciation-and-corrections')
click('返回历史','content-desc'); wait('历史记录')
assert not nodes('原文读音')
screenshot('05-simple-history')
click('能给我一点时间吗？我想把这件事做好。')
wait('翻译详情');click('详情设置','content-desc');wait('翻译模型')
run('shell','input','keyevent','4');wait('翻译详情')
click('返回历史','content-desc')
click('新建翻译会话','content-desc');click('试试中文辨析');click('开始翻译','content-desc')
wait('This job is a good fit for you.')
screenshot('06-chinese-to-english-detail')
scroll_to('suitable');screenshot('07-word-choice-suitable')
scroll_to('appropriate');screenshot('08-word-choice-appropriate')
click('返回历史','content-desc')
click('英语',last=True);wait('翻译成');screenshot('09-language-menu');click('简体中文')
policy_candidates=['上下文关闭','近 30 分钟','最近 5 条']
policy=next((text for text in policy_candidates if nodes(text)),None)
assert policy;click(policy);click('按时间');screenshot('10-context-menu');run('shell','input','keyevent','4')
click('本地演示');wait('选择模型');screenshot('11-model-menu');run('shell','input','keyevent','4')
label='Work' if nodes('Work') else '日常翻译'
card=wait(label)[0];x1,y1,x2,y2=bounds(card)
run('shell','input','swipe',str(int(width*.88)),str((y1+y2)//2),str(int(width*.12)),str((y1+y2)//2),'650')
wait('给这张卡片一个用途');screenshot('12-preset-card')
click('设置这个预设');click('预设名称，如：工作邮件');run('shell','input','text','Travel')
run('shell','input','keyevent','4');click('保存预设');wait('Travel')
run('shell','am','force-stop',PACKAGE);run('shell','am','start','-n',PACKAGE+'/.MainActivity')
wait('Travel');wait('This job is a good fit for you.')
report={'version':'0.2.0','apkSha256':__import__('hashlib').sha256((OUT/'译间-v0.2.0.apk').read_bytes()).hexdigest(),'serial':args.serial,'android':run('shell','getprop','ro.build.version.release').strip(),
        'passed':True,'upgrade':upgrade,
        'checks':['signed package installation','compact history and detail navigation','editable source with real IME',
                  'foreign pronunciation and correction sections','Chinese contextual word choices',
                  'detail settings back stack','three upward menus','swipe preset and inline save','restart persistence']}
(OUT/f'smoke-{args.label}.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
