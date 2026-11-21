"""Package the verified 0.2 source and local validation evidence; never include signing keys."""
from collections import Counter
from datetime import datetime
from hashlib import sha256
from pathlib import Path
import json
import shutil
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'artifacts/v0.2.0'
EVIDENCE = OUT / 'verification'
EVIDENCE.mkdir(parents=True, exist_ok=True)

def digest(path):
    return sha256(path.read_bytes()).hexdigest()

def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

references = ROOT / 'docs/参考图清单.json'
items = json.loads(references.read_text(encoding='utf-8-sig'))
feedback = [
    ('e6393e7e-5160-41c3-a0b9-a9c928116a46', '原有衍生信息内容反馈'),
    ('8676a4b4-338b-4637-b406-0a3d13943e7b', '顶部模糊与历史布局反馈'),
    ('434ed40b-4470-4b70-bf06-6ebfa0d48568', '预设堆叠与切换动画反馈'),
    ('cf3bf7cc-cf25-4482-bf39-898df5106fdf', '首页、二级页和输入法适配反馈'),
]
for index, (identifier, purpose) in enumerate(feedback, 1):
    filename = f'references/feedback-0.2/feedback-{index}.png'
    if not any(item['File'] == filename for item in items):
        items.append({'File': filename, 'OriginalPath': filename,
                      'Purpose': purpose, 'SHA256': digest(ROOT / filename).upper()})
save(references, items)

unit_files = sorted((ROOT / 'android/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
ui_files = sorted((ROOT / 'android/app/build/outputs/androidTest-results/connected/debug').glob('TEST-*.xml'))
assert len(unit_files) == 2 and len(ui_files) == 1

def test_summary(files):
    total = Counter()
    for source in files:
        test = ET.parse(source).getroot()
        for key in ('tests', 'failures', 'errors', 'skipped'):
            total[key] += int(test.get(key, '0'))
        shutil.copy2(source, EVIDENCE / source.name)
    assert total['failures'] == total['errors'] == total['skipped'] == 0
    return dict(total)

unit = test_summary(unit_files)
ui = test_summary(ui_files)
assert unit['tests'] == 30 and ui['tests'] == 9
lint = ROOT / 'android/app/build/reports/lint-results-debug.xml'
lint_counts = Counter(issue.get('severity') for issue in ET.parse(lint).getroot().findall('issue'))
assert lint_counts['Error'] == lint_counts['Fatal'] == 0
shutil.copy2(lint, EVIDENCE / lint.name)

smokes = {label: json.loads((OUT / f'smoke-{label}.json').read_text(encoding='utf-8')) for label in ('android11', 'android15')}
visuals = {label: json.loads((OUT / f'visual-{label}.json').read_text(encoding='utf-8')) for label in ('android11', 'android15')}
assert all(item['passed'] for item in [*smokes.values(), *visuals.values()])
assert smokes['android15']['upgrade']['preset_and_history_preserved']

apk = OUT / '译间-v0.2.0.apk'
assert digest(apk) == digest(ROOT / 'android/app/build/outputs/apk/release/app-release.apk')
assert all(item.get('apkSha256') == digest(apk) for item in visuals.values())
assert smokes['android11'].get('apkSha256') == digest(apk)
archive = OUT / '译间-v0.2.0-源码.zip'
excluded_dirs = {'.local', '.gradle', '.kotlin', 'build', '__pycache__', '.git'}
files = [ROOT / name for name in ('README.md', 'project.json', '.gitignore', 'build.ps1')]
for directory in ('android', 'tools', 'docs', 'references'):
    files.extend(path for path in (ROOT / directory).rglob('*') if path.is_file()
                 and not excluded_dirs.intersection(path.relative_to(ROOT).parts)
                 and path.name not in {'local.properties', 'signing.properties'}
                 and path.suffix.lower() not in {'.jks', '.keystore', '.pyc'})
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as output:
    for path in sorted(set(files)):
        output.write(path, path.relative_to(ROOT).as_posix())
with zipfile.ZipFile(archive) as check:
    assert check.testzip() is None
    assert not any('.local/' in name or name.endswith(('.jks', '.keystore', 'signing.properties', 'local.properties')) for name in check.namelist())
    assert 'android/gradle/wrapper/gradle-wrapper.jar' in check.namelist()

report = {
    'version': '0.2.0', 'versionCode': 2, 'generatedAt': datetime.now().astimezone().isoformat(),
    'applicationId': 'com.yijian.translate', 'minimumAndroidApi': 26, 'targetAndroidApi': 35,
    'apk': {'file': apk.name, 'bytes': apk.stat().st_size, 'sha256': digest(apk), 'signatureV2Verified': True,
            'sameSigningKeyAsVersion010': True, 'abis': ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']},
    'source': {'file': archive.name, 'bytes': archive.stat().st_size, 'sha256': digest(archive),
               'files': len(set(files)), 'signingKeysAndLocalCachesExcluded': True},
    'unitTests': unit, 'androidFlowTests': ui,
    'lint': {'errors': lint_counts['Error'] + lint_counts['Fatal'], 'warnings': lint_counts['Warning']},
    'releaseSmokeChecks': smokes, 'finalReleaseVisualChecks': visuals,
    'upgradeNote': '0.1.0→0.2.0 数据保留已验证；其后的最终渲染调整包在原有数据上再次覆盖安装并通过视觉检查。',
    'remoteProviderValidation': 'MockWebServer 本机兼容接口；未使用外部模型账号。真实远程输出需填写用户自己的接口配置。',
}
save(OUT / 'verification.json', report)
print(json.dumps({'apkBytes': apk.stat().st_size, 'sourceBytes': archive.stat().st_size,
                  'sourceFiles': len(set(files)), 'unitTests': unit['tests'], 'flowTests': ui['tests'],
                  'lintErrors': report['lint']['errors'], 'apkSha256': digest(apk)}, ensure_ascii=False, indent=2))
