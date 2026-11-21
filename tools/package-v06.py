"""Package the verified, renamed app and source selected for GitHub publication."""
from collections import Counter
from datetime import datetime
from hashlib import sha256
from pathlib import Path
from zoneinfo import ZoneInfo
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'artifacts/v0.6.0'
EVIDENCE = OUT / 'verification'
EVIDENCE.mkdir(parents=True, exist_ok=True)

def digest(path):
    return sha256(path.read_bytes()).hexdigest()

def results(paths):
    assert paths
    totals = Counter()
    for path in paths:
        suite = ET.parse(path).getroot()
        for key in ('tests', 'failures', 'errors', 'skipped'):
            totals[key] += int(suite.get(key, '0'))
        shutil.copy2(path, EVIDENCE / path.name)
    assert totals['failures'] == totals['errors'] == totals['skipped'] == 0
    return dict(totals)

unit = results(sorted((ROOT/'android/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')))
ui = results(sorted((ROOT/'android/app/build/outputs/androidTest-results/connected/debug').glob('TEST-*.xml')))
assert unit['tests'] == 58 and ui['tests'] == 22
for name in ('v06-debug-checks.log', 'v06-ui-checks.log', 'v06-release-build.log'):
    path = ROOT/'.local'/name
    assert 'BUILD SUCCESSFUL' in path.read_text(encoding='utf-8', errors='replace')
    shutil.copy2(path, EVIDENCE/path.name)
lint = ROOT/'android/app/build/reports/lint-results-debug.xml'
counts = Counter(item.get('severity') for item in ET.parse(lint).getroot().findall('issue'))
assert counts['Error'] == counts['Fatal'] == 0
shutil.copy2(lint, EVIDENCE/lint.name)

apk = OUT/'relax-translator-v0.6.0.apk'
assert digest(apk) == digest(ROOT/'android/app/build/outputs/apk/release/app-release.apk')
signature = (EVIDENCE/'apk-signature.txt').read_text(encoding='utf-8')
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
assert '2071eef5007ad70a305d75e2b11ac60b56ea277667c81833cc57a72f7f9487c1' in signature
assert "application-label:'轻松翻译'" in (EVIDENCE/'apk-badging.txt').read_text(encoding='utf-8')
smokes = {label: json.loads((OUT/f'smoke-{label}.json').read_text(encoding='utf-8')) for label in ('android11','android15')}
assert all(s['passed'] and s['apkSha256'] == digest(apk) and s['upgrade']['preset_and_history_preserved'] for s in smokes.values())
images = json.loads((OUT/'product-screenshots.json').read_text(encoding='utf-8'))
assert images['passed'] and images['actualScreenshots'] and images['apkSha256'] == digest(apk)
for image in images['screenshots'].values():
    assert digest(ROOT/image['file']) == image['sha256']

tracked = subprocess.check_output(['git','ls-files','-z'], cwd=ROOT).decode('utf-8').split('\0')
files = [ROOT/p for p in tracked if p and not p.startswith('artifacts/')]
for path in files:
    relative = path.relative_to(ROOT)
    assert not {'.local','.git','build','.gradle','references'}.intersection(relative.parts)
    assert path.name not in ('signing.properties','local.properties','参考图清单.json')
    assert path.suffix not in ('.jks','.keystore','.pfx','.p12')
archive = OUT/'relax-translator-v0.6.0-source.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as output:
    for path in sorted(files): output.write(path,path.relative_to(ROOT).as_posix())
with zipfile.ZipFile(archive) as check: assert check.testzip() is None
(OUT/'SHA256SUMS').write_text('\n'.join(f'{digest(p)}  {p.name}' for p in (apk,archive))+'\n', encoding='utf-8')
report = {'name':'轻松翻译','englishName':'Relax Translator','version':'0.6.0','versionCode':8,
          'generatedAt':datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),
          'applicationId':'com.yijian.translate','applicationIdPreservedForUpgrade':True,
          'minimumAndroidApi':26,'targetAndroidApi':35,
          'apk':{'file':apk.name,'sha256':digest(apk),'bytes':apk.stat().st_size,'sameSigningKey':True},
          'source':{'file':archive.name,'sha256':digest(archive),'files':len(files),'personalReferencesAndCredentialsExcluded':True},
          'unitTests':unit,'androidFlowTests':ui,'lint':{'errors':0,'warnings':counts['Warning']},
          'releaseSmokeChecks':smokes,'productScreenshots':images,'remoteModelProviderCalls':False}
(OUT/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'sourceFiles':len(files),'unitTests':unit,'androidFlowTests':ui,'apkSha256':digest(apk)},indent=2))
