"""Exercise the final signed app's gallery, floating composer, direct deletion and motion."""
from pathlib import Path
from PIL import Image, ImageDraw
import cv2
import hashlib
import json
import re
import subprocess
import sys
import time

serial, label = sys.argv[1:3]
helper = Path(__file__).with_name('smoke-v05.py')
sys.argv = [str(helper), '--serial', serial, '--label', label]
ns = {'__file__': str(helper), '__name__': 'qa_helpers'}
exec(compile(helper.read_text(encoding='utf-8').split('upgrade = {}')[0], str(helper), 'exec'), ns)
run, wait, click, screenshot, make_preset, nodes, tree, bounds, scroll_to = [ns[key] for key in ('run', 'wait', 'click', 'screenshot', 'make_preset', 'nodes', 'tree', 'bounds', 'scroll_to')]
ROOT, OUT, BASE, width, height = [ns[key] for key in ('ROOT', 'OUT', 'BASE', 'width', 'height')]
assert 'versionName=0.5.0' in run('shell', 'dumpsys', 'package', ns['PACKAGE'])

fixture = ROOT / '.local/gallery-motion-05.png'
picture = Image.new('RGB', (900, 700), '#e5ecff')
draw = ImageDraw.Draw(picture)
draw.rounded_rectangle((65, 70, 835, 630), radius=52, fill='white', outline='#526bc0', width=5)
draw.text((155, 245), 'Hello, Yijian', fill='#263455', stroke_width=1)
draw.text((155, 310), 'Gallery selection test', fill='#526bc0')
picture.save(fixture)
remote_photo = '/sdcard/Pictures/yijian-gallery-motion-05.png'
run('shell', 'mkdir', '-p', '/sdcard/Pictures')
run('push', str(fixture), remote_photo)
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file://' + remote_photo)
# ADB-pushed fixtures can remain pending on API 30; finish publishing this owned test image.
rows = run('shell', 'content', 'query', '--uri', 'content://media/external/images/media', '--projection', '_id:_display_name')
fixture_ids = re.findall(r'_id=(\d+), _display_name=yijian-gallery-motion-05\.png', rows)
assert fixture_ids, 'Owned gallery fixture was not indexed'
for image_id in fixture_ids:
    run('shell', 'content', 'update', '--uri', 'content://media/external/images/media/' + image_id, '--bind', 'is_pending:i:0', '--bind', 'width:i:900', '--bind', 'height:i:700')
time.sleep(1)
make_preset('Motion05')
assert not nodes('新建预设', 'content-desc')

click('附带图片')
resumed = run('shell', 'dumpsys', 'activity', 'activities')
picker_activity = next((line.strip() for line in resumed.splitlines() if 'mResumedActivity' in line or 'topResumedActivity' in line), '')
assert 'documentsui' not in picker_activity.lower(), picker_activity
assert any(word in picker_activity.lower() for word in ('photo', 'gallery', 'picker', 'media')), picker_activity
screenshot('12-gallery-picker')
run('shell', 'input', 'keyevent', '4'); wait('Motion05'); assert not nodes('图片已添加')

click('附带图片')
selected = False
for _ in range(12):
    snapshot = tree()
    if any(node.get('text') == '图片已添加' for node in snapshot.iter('node')):
        break
    allow = [node for node in snapshot.iter('node') if node.get('text') in ('Allow', 'Allow all', '允许', '允许全部')]
    if allow:
        box = bounds(allow[-1]); run('shell', 'input', 'tap', str((box[0] + box[2]) // 2), str((box[1] + box[3]) // 2)); continue
    done = [node for node in snapshot.iter('node') if re.match(r'^(Add|Done|Select|添加|完成|选择)(\s*\(?\d+\)?)?$', node.get('text', ''))]
    if selected and done:
        box = bounds(done[-1]); run('shell', 'input', 'tap', str((box[0] + box[2]) // 2), str((box[1] + box[3]) // 2)); continue
    thumbs = []
    for node in snapshot.iter('node'):
        box = bounds(node)
        if len(box) != 4 or box[2] - box[0] < width * .18 or box[3] - box[1] < width * .18:
            continue
        if node.get('class') == 'android.widget.ImageView' or 'thumbnail' in node.get('resource-id', '').lower():
            if height * .12 < (box[1] + box[3]) / 2 < height * .88:
                thumbs.append(node)
    if thumbs:
        box = bounds(thumbs[0]); run('shell', 'input', 'tap', str((box[0] + box[2]) // 2), str((box[1] + box[3]) // 2)); selected = True
    else:
        time.sleep(.5)
wait('图片已添加'); screenshot('13-gallery-photo-on-card')

videos = OUT / 'videos'; videos.mkdir(exist_ok=True)
remote_video = '/sdcard/yijian-motion-05.mp4'
recording = subprocess.Popen(BASE + ['shell', 'screenrecord', '--size', '720x1560', '--bit-rate', '2500000', '--time-limit', '12', remote_video], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
time.sleep(.7)
title = max(wait('Motion05'), key=lambda node: bounds(node)[1])
box = bounds(title); deck_y = (box[1] + box[3]) // 2
run('shell', 'input', 'swipe', str(int(width * .28)), str(deck_y), str(int(width * .82)), str(deck_y), '1000')
wait('Layer05 · 独立上下文')
run('shell', 'input', 'swipe', str(int(width * .72)), str(deck_y), str(int(width * .18)), str(deck_y), '1000')
wait('Motion05 · 独立上下文'); wait('图片已添加')
recording.communicate(timeout=18)
video = videos / f'{label}-preset-switch.mp4'
run('pull', remote_video, str(video))
capture = cv2.VideoCapture(str(video))
frame_count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT)); fps = capture.get(cv2.CAP_PROP_FPS)
assert frame_count > 30 and fps > 0
frames = OUT / 'screenshots' / label
for index, second in enumerate((1.1, 1.6, 4.7, 5.2), 1):
    capture.set(cv2.CAP_PROP_POS_MSEC, second * 1000)
    ok, frame = capture.read()
    if ok:
        cv2.imencode('.png', frame)[1].tofile(str(frames / f'15-motion-frame-{index}.png'))
capture.release()

click('编辑当前预设', 'content-desc'); wait('删除预设'); screenshot('14-delete-in-editor')
click('删除预设'); click('确认删除')
wait('打开菜单', 'content-desc')
assert not nodes('Motion05 · 独立上下文')
click('打开菜单', 'content-desc'); scroll_to('Layer05'); click('Layer05'); wait('今日は何を食べますか。')
report = {'version': '0.5.0', 'passed': True, 'apkSha256': hashlib.sha256((OUT / '译间-v0.5.0.apk').read_bytes()).hexdigest(),
          'galleryActivity': picker_activity, 'galleryCancelPreservesDraft': True, 'galleryPhotoImported': True,
          'headerAddButtonRemoved': True, 'directEditorDeletion': True, 'otherHistoryPreserved': True,
          'motionVideo': str(video.relative_to(OUT)), 'recordedFrames': frame_count, 'encodedFps': fps}
(OUT / f'polish-{label}.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
