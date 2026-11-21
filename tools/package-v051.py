"""Archive the exact tested toolbar fix; preserve older version artifacts."""
from collections import Counter
from datetime import datetime
from hashlib import sha256
from pathlib import Path
import json
import shutil
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'artifacts/v0.5.1'
EVIDENCE = OUT / 'verification'
EVIDENCE.mkdir(parents=True, exist_ok=True)

def digest(path):
    return sha256(path.read_bytes()).hexdigest()

def summary(files):
    assert files
    counts = Counter()
    for path in files:
        test = ET.parse(path).getroot()
        for key in ('tests', 'failures', 'errors', 'skipped'):
            counts[key] += int(test.get(key, '0'))
        shutil.copy2(path, EVIDENCE / path.name)
    assert counts['failures'] == counts['errors'] == counts['skipped'] == 0
    return dict(counts)

unit = summary(sorted((ROOT / 'android/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')))
ui = summary(sorted((ROOT / 'android/app/build/outputs/androidTest-results/connected/debug').glob('TEST-*.xml')))
assert unit['tests'] == 58 and ui['tests'] == 22
lint = ROOT / 'android/app/build/reports/lint-results-debug.xml'
lint_counts = Counter(issue.get('severity') for issue in ET.parse(lint).getroot().findall('issue'))
assert lint_counts['Error'] == lint_counts['Fatal'] == 0
shutil.copy2(lint, EVIDENCE / lint.name)
for name in ('v051-checks.log', 'v051-release-build.log'):
    path = ROOT / '.local' / name
    assert 'BUILD SUCCESSFUL' in path.read_text(encoding='utf-8', errors='replace')
    shutil.copy2(path, EVIDENCE / name)

apk = OUT / '译间-v0.5.1.apk'
assert digest(apk) == digest(ROOT / 'android/app/build/outputs/apk/release/app-release.apk')
signature = (EVIDENCE / 'apk-signature.txt').read_text(encoding='utf-8')
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
assert '2071eef5007ad70a305d75e2b11ac60b56ea277667c81833cc57a72f7f9487c1' in signature
smokes = {label: json.loads((OUT / f'smoke-{label}.json').read_text(encoding='utf-8')) for label in ('android11', 'android15')}
assert all(item['passed'] and item['apkSha256'] == digest(apk) for item in smokes.values())
assert all(item['upgrade']['from'] == '0.5.0' and item['upgrade']['preset_and_history_preserved'] for item in smokes.values())
assert all(item['backdropSharpEdgeVariance'] < 100 for item in smokes.values())

excluded = {'.local', '.gradle', '.kotlin', 'build', '__pycache__', '.git'}
files = [ROOT / name for name in ('README.md', 'project.json', '.gitignore', 'build.ps1')]
for directory in ('android', 'tools', 'docs', 'references'):
    files.extend(path for path in (ROOT / directory).rglob('*') if path.is_file()
                 and not excluded.intersection(path.relative_to(ROOT).parts)
                 and path.name not in ('local.properties', 'signing.properties')
                 and path.suffix.lower() not in ('.jks', '.keystore', '.pyc'))
archive = OUT / '译间-v0.5.1-源码.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as output:
    for path in sorted(set(files)):
        output.write(path, path.relative_to(ROOT).as_posix())
with zipfile.ZipFile(archive) as check:
    assert check.testzip() is None
    assert not any('.local/' in name or name.endswith(('.jks', '.keystore', 'signing.properties', 'local.properties')) for name in check.namelist())
report = {
    'version': '0.5.1', 'versionCode': 7, 'generatedAt': datetime.now().astimezone().isoformat(),
    'applicationId': 'com.yijian.translate', 'minimumAndroidApi': 26, 'targetAndroidApi': 35,
    'apk': {'file': apk.name, 'bytes': apk.stat().st_size, 'sha256': digest(apk), 'signatureV2Verified': True, 'sameSigningKeyAsVersion050': True},
    'source': {'file': archive.name, 'bytes': archive.stat().st_size, 'sha256': digest(archive), 'files': len(set(files)), 'signingKeysAndLocalCachesExcluded': True},
    'unitTests': unit, 'androidFlowTests': ui,
    'lint': {'errors': 0, 'warnings': lint_counts['Warning']}, 'releaseSmokeChecks': smokes,
    'newRegressionChecks': ['subtitle line layout at normal and actual 1.6x font scale',
                            'history heading below measured blur boundary',
                            'rendered backdrop top contrast reduction and sharp bottom edge',
                            'rendered average luminance rejects an opaque tint in place of blur',
                            'opaque frame and live blur after repeated detail and preset navigation',
                            'signed-release screenshot rejects sharp glyphs behind the toolbar'],
    'remoteProviderCalls': False
}
(OUT / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'apkBytes': apk.stat().st_size, 'sourceFiles': len(set(files)), 'unitTests': unit, 'flowTests': ui, 'apkSha256': digest(apk)}, ensure_ascii=False, indent=2))
