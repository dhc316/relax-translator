from pathlib import Path
import concurrent.futures
import hashlib
import json
import shutil
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1] / '.local'
DOWNLOADS = ROOT / 'downloads'
DOWNLOADS.mkdir(exist_ok=True)

def download(url, name, expected=None):
    target = DOWNLOADS / name
    if not target.exists():
        print('Downloading ' + name, flush=True)
        with urllib.request.urlopen(url, timeout=120) as response, target.with_suffix('.partial').open('wb') as output:
            shutil.copyfileobj(response, output, 1024 * 1024)
        target.with_suffix('.partial').replace(target)
    if expected and hashlib.sha256(target.read_bytes()).hexdigest() != expected:
        raise ValueError('Checksum mismatch: ' + name)
    print('Verified ' + name, flush=True)
    return target

def gradle():
    base = 'https://downloads.gradle.org/distributions/gradle-8.11.1-bin.zip'
    expected = urllib.request.urlopen(base + '.sha256', timeout=60).read().decode().strip()
    archive = download(base, 'gradle-8.11.1-bin.zip', expected)
    destination = ROOT / 'gradle'
    if not (destination / 'gradle-8.11.1/bin/gradle.bat').exists():
        with zipfile.ZipFile(archive) as zip_file:
            zip_file.extractall(destination)

def android_tools():
    content = urllib.request.urlopen('https://dl.google.com/android/repository/repository2-3.xml', timeout=60).read()
    (DOWNLOADS / 'repository.xml').write_bytes(content)
    tree = ET.fromstring(content)
    packages = [p for p in tree.findall('remotePackage') if p.get('path', '').startswith('cmdline-tools;') and p.findtext('channelRef') is None]
    # Prefer a known JDK 17 compatible command line tools version.
    package = next((p for p in tree.findall('remotePackage') if p.get('path') == 'cmdline-tools;19.0'), None)
    if package is None:
        package = next(p for p in tree.findall('remotePackage') if p.get('path') == 'cmdline-tools;latest')
    archive_info = next(a for a in package.findall('archives/archive') if a.findtext('host-os') == 'windows')
    relative_url = archive_info.findtext('complete/url')
    archive = download('https://dl.google.com/android/repository/' + relative_url, relative_url.split('/')[-1])
    expected_sha1 = archive_info.findtext('complete/checksum')
    if expected_sha1 and hashlib.sha1(archive.read_bytes()).hexdigest() != expected_sha1:
        raise ValueError('Android tools checksum mismatch')
    destination = ROOT / 'sdk/cmdline-tools/latest'
    if not (destination / 'bin/sdkmanager.bat').exists():
        temporary = ROOT / 'sdk-tools-extract'
        with zipfile.ZipFile(archive) as zip_file:
            zip_file.extractall(temporary)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(temporary / 'cmdline-tools'), str(destination))
    print('Android tools ready', flush=True)

with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
    futures = [executor.submit(gradle), executor.submit(android_tools)]
    for future in futures:
        future.result()
print('Bootstrap complete', flush=True)
