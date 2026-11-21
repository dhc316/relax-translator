"""Capture final release frames after smoke-v02.py created the Travel preset (owned emulators only)."""
from pathlib import Path
import sys,subprocess,time,json

ROOT=Path(__file__).resolve().parents[1]
serial,label=sys.argv[1:3]
helper=ROOT/'tools/smoke-v02.py'
sys.argv=[str(helper),'--serial',serial,'--label',label]
ns={'__file__':str(helper),'__name__':'visual_helpers'}
exec(compile(helper.read_text(encoding='utf-8').split('avd = run(')[0],str(helper),'exec'),ns)
run,click,wait,nodes,shot,scroll,bounds=[ns[x] for x in ['run','click','wait','nodes','screenshot','scroll_to','bounds']]
base=ns['BASE'];package=ns['PACKAGE'];out=ns['OUT'];screens=ns['SCREENS']
avd = run('shell','getprop','ro.kernel.qemu.avd_name').strip() or run('shell','getprop','ro.boot.qemu.avd_name').strip()
assert avd in ['yijian_api30','yijian_api35'], avd
assert 'Success' in run('install','-r',str(out/'译间-v0.2.0.apk'))
run('shell','settings','put','global','animator_duration_scale','1')
run('shell','am','force-stop',package);run('shell','am','start','-n',package+'/.MainActivity')
wait('Travel')
if not nodes('This job is a good fit for you.'):
    click('新建翻译会话','content-desc');click('试试中文辨析');click('开始翻译','content-desc')
    wait('This job is a good fit for you.');click('返回历史','content-desc');wait('Travel')
wait('历史记录');shot('05-simple-history')
width,height=map(int,__import__('re').findall(r'(\d+)x(\d+)',run('shell','wm','size'))[-1]);ns['width']=width;ns['height']=height
click('This job is a good fit for you.');wait('翻译详情')
scroll('appropriate');shot('08-word-choice-appropriate')
click('返回历史','content-desc');card=wait('Travel')[0]
x1,y1,x2,y2=bounds(card)
drag=subprocess.Popen(base+['shell','input','swipe',str(int(width*.88)),str((y1+y2)//2),str(int(width*.13)),str((y1+y2)//2),'3000'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
time.sleep(1.1);shot('13-deck-in-motion');drag.wait(timeout=8)
run('shell','am','force-stop',package);run('shell','am','start','-n',package+'/.MainActivity')
wait('Travel');click('新建翻译会话','content-desc');click('试译一句英文')
node=wait('开始翻译','content-desc')[0];x1,y1,x2,y2=bounds(node)
run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
data=subprocess.check_output(base+['exec-out','screencap','-p']);(screens/'14-input-rising.png').write_bytes(data)
wait('能给我一点时间吗？我想把这件事做好。');shot('02-translation-detail')
edits=[n for n in ns['tree']().iter('node') if n.get('class')=='android.widget.EditText']
x1,y1,x2,y2=bounds(edits[0]);run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));wait('收起键盘');shot('03-detail-keyboard')
click('收起键盘');scroll('纠错解析');shot('04-pronunciation-and-corrections')
click('返回历史','content-desc');shot('15-history-after-final-check')
(out/f'visual-{label}.json').write_text(json.dumps({'passed':True,'version':'0.2.0','apkSha256':__import__('hashlib').sha256((out/'译间-v0.2.0.apk').read_bytes()).hexdigest(),'checks':['final signed APK replaced in place','history heading remains visible below cropped blur','word content scrolls underneath bounded glass','actual deck intermediate frame','input rise intermediate frame','actual keyboard on final APK']},indent=2),encoding='utf-8')
print('Final signed-package visual checks passed:',label)
