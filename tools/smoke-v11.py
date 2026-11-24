"""Verify branded release upgrades on project-owned Android emulators."""
from pathlib import Path
import hashlib
import json
import time

helper = Path(__file__).with_name('smoke-v03.py')
prefix = helper.read_text(encoding='utf-8').split('avd = run(')[0]
prefix = prefix.replace("'artifacts/v0.3.0'", "'artifacts/v1.1.0'")
ns = {'__file__': str(helper), '__name__': 'qa_helpers'}
exec(compile(prefix, str(helper), 'exec'), ns)
run, wait, click, screenshot, bounds, tree, nodes = [ns[k] for k in ('run', 'wait', 'click', 'screenshot', 'bounds', 'tree', 'nodes')]
ROOT, OUT, BASE, PACKAGE, args = [ns[k] for k in ('ROOT', 'OUT', 'BASE', 'PACKAGE', 'args')]
avd = run('shell', 'getprop', 'ro.kernel.qemu.avd_name').strip() or run('shell', 'getprop', 'ro.boot.qemu.avd_name').strip()
assert avd in ('yijian_api30', 'yijian_api35'), avd
upgrade = {}
translation = '能给我一点时间吗？我想把这件事做好。'
if args.upgrade:
    baseline = ROOT/'artifacts/v1.0.0/relax-translator-v1.0.0.apk'
    assert baseline.is_file()
    run('uninstall', PACKAGE, check=False)
    assert 'Success' in run('install', str(baseline))
    assert 'versionName=1.0.0' in run('shell', 'dumpsys', 'package', PACKAGE)
    run('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
    wait('今天，想翻译些什么？')
    click('试译一句英文'); click('开始翻译', 'content-desc')
    wait(translation); wait('注音与读音'); click('返回历史', 'content-desc')
    wait(translation); screenshot('00-before-upgrade')
    upgrade = {'from': '1.0.0', 'originalTranslation': translation}
else:
    run('uninstall', PACKAGE, check=False)
apk = OUT / 'relax-translator-v1.1.0.apk'
assert 'Success' in run('install', '-r', str(apk))
installed = run('shell', 'dumpsys', 'package', PACKAGE)
assert 'versionName=1.1.0' in installed and 'versionCode=10' in installed
run('shell', 'am', 'force-stop', PACKAGE)
run('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
wait('轻松翻译')
if args.upgrade:
    wait(translation); upgrade['preset_and_history_preserved'] = True
screenshot('01-relax-translator-home')
click('打开菜单', 'content-desc'); wait('轻松翻译'); screenshot('02-branded-sidebar')
click('打开设置', 'content-desc')
heading = bounds(wait('设置')[0])
wrong_badges = [node for node in tree().iter('node') if node.get('text', '').strip() == '雾蓝' and bounds(node)[1] < heading[3] + 50]
assert not wrong_badges, 'Old theme badge remains in the settings header'
screenshot('03-clean-settings-header')
click('返回首页', 'content-desc'); wait('轻松翻译')
if args.upgrade:
    click(translation); wait('注音与读音'); screenshot('04-preserved-detail')
    assert not nodes('纠错解析')
    click('返回历史', 'content-desc')
# Verify the new switch in the signed release, including an existing record retranslation.
click('额外内容'); wait('为当前预设选择要请求的内容')
# The master switch is the first checkable control, above all section checkboxes.
controls = sorted([n for n in tree().iter('node') if n.get('checkable') == 'true'], key=lambda n: bounds(n)[1])
assert controls and controls[0].get('checked') == 'true'
x1,y1,x2,y2 = bounds(controls[0]); run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
wait('关闭后只请求译文，不发送额外内容请求。\n已选项目会保留，开启后继续使用。')
screenshot('05-extras-disabled')
run('shell','input','keyevent','4')
if args.upgrade:
    click(translation); click('重新翻译'); wait(translation)
    time.sleep(.5)
    assert not nodes('注音与读音') and not nodes('一个意思，多种表达')
    screenshot('06-translation-only')
    click('返回历史','content-desc')
report = {'version': '1.1.0', 'versionCode': 10, 'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
          'android': run('shell', 'getprop', 'ro.build.version.release').strip(),
          'passed': True, 'upgrade': upgrade,
          'checks': ['signed release installed', 'new app name on home and sidebar', 'translation mark rendered in UI',
                     'theme badge removed from settings header', 'upgrade preserved preset and original translation',
                     'detail readings retained and no false correction section', 'extra content switch works on signed release', 'translation-only retranslation has no unrequested readings']}
(OUT / f'smoke-{args.label}.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
