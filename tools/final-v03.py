"""Check the immutable final signed APK after the complete flow run and final regression tests."""
from pathlib import Path
import sys,json,hashlib
ROOT=Path(__file__).resolve().parents[1]
serial,label=sys.argv[1:3]
helper=ROOT/'tools/smoke-v03.py'
sys.argv=[str(helper),'--serial',serial,'--label',label]
ns={'__file__':str(helper),'__name__':'helpers'}
exec(compile(helper.read_text(encoding='utf-8').split('avd = run(')[0],str(helper),'exec'),ns)
run,wait,click,shot,bounds,tree=[ns[k] for k in ('run','wait','click','screenshot','bounds','tree')]
package=ns['PACKAGE'];out=ns['OUT']
avd=run('shell','getprop','ro.kernel.qemu.avd_name').strip() or run('shell','getprop','ro.boot.qemu.avd_name').strip()
assert avd in ['yijian_api30','yijian_api35']
if label=='android11':run('uninstall',package,check=False)
assert 'Success' in run('install','-r',str(out/'译间-v0.3.0.apk'))
assert 'versionName=0.3.0' in run('shell','dumpsys','package',package)
run('shell','am','force-stop',package);run('shell','am','start','-n',package+'/.MainActivity')
if label=='android11':
    wait('今天，想翻译些什么？');click('日语注音示例');click('开始翻译','content-desc')
else:
    click('打开菜单','content-desc');click('Reading');wait('今日は何を食べますか。');click('今日は何を食べますか。')
wait('注音与读音');wait('假名  きょうはなにをたべますか。');wait('罗马字  Kyō wa nani o tabemasu ka?')
wait('朗读原文','content-desc');wait('朗读译文','content-desc');assert not ns['nodes']('纠错解析')
shot('02-japanese-readings-first')
edits=[n for n in tree().iter('node') if n.get('class')=='android.widget.EditText']
x1,y1,x2,y2=bounds(edits[0]);run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));wait('收起键盘');shot('03-detail-keyboard');click('收起键盘')
click('返回历史','content-desc');wait('今日は何を食べますか。');shot('04-simple-history')
report={'passed':True,'version':'0.3.0','apkSha256':hashlib.sha256((out/'译间-v0.3.0.apk').read_bytes()).hexdigest(),
        'checks':['final signed APK installed','both reading blocks first','Japanese kana and romaji','two speech controls',
                  'no clean correction card','actual keyboard with visible source','history available after update']}
(out/f'final-{label}.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
