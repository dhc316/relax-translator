"""Generate matching Android and SVG translation marks from the same vector paths."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'android/app/src/main/res'
ASSETS = ROOT / 'docs/assets'
GLYPHS = [
    'M16.5,9.2 V11.8 M8.5,14 H24.5',
    'M12,16.5 C13.5,21.7 17.4,25.3 23,28 M21,14 C19.3,20.7 15,25.4 8.5,28.5',
    'M29,37 L34.5,23 L40,37 M31.2,32 H37.8',
]
ARROWS = [
    'M33,8 H43 M40,5 L43,8 L40,11',
    'M15,41 H5 M8,38 L5,41 L8,44',
]
FRONT = 'M12,3 H21 Q30,3 30,12 V26 Q30,35 21,35 H12 Q3,35 3,26 V12 Q3,3 12,3 Z'
BACK = 'M29,16 H36 Q45,16 45,25 V36 Q45,45 36,45 H29 Q20,45 20,36 V25 Q20,16 29,16 Z'

def save(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content.rstrip() + '\n', encoding='utf-8')

android_paths = '\n'.join(f'    <path android:fillColor="@android:color/transparent" android:strokeColor="#FFFFFF" android:strokeWidth="1.8" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{path}" />' for path in GLYPHS)
android_arrows = '\n'.join(f'    <path android:fillColor="@android:color/transparent" android:strokeColor="{color}" android:strokeWidth="1.5" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{path}" />' for path,color in zip(ARROWS,['#8E9DD1','#AAAED7']))
save(RES / 'drawable/ic_brand.xml', f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"
    android:width="48dp" android:height="48dp" android:viewportWidth="48" android:viewportHeight="48">
    <path android:fillColor="#AAADD8" android:pathData="{BACK}" />
    <path android:fillColor="#788ACB" android:pathData="{FRONT}" />
{android_paths}
{android_arrows}
</vector>''')
save(RES / 'drawable/ic_launcher.xml', '''<inset xmlns:android="http://schemas.android.com/apk/res/android" android:drawable="@drawable/ic_brand" android:inset="0dp" />''')
save(RES / 'drawable/ic_launcher_foreground.xml', '''<inset xmlns:android="http://schemas.android.com/apk/res/android" android:drawable="@drawable/ic_brand" android:inset="18dp" />''')
save(RES / 'drawable/ic_launcher_monochrome.xml', f'''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <group android:translateX="18" android:translateY="18" android:scaleX="1.5" android:scaleY="1.5">
{android_paths}
{android_arrows.replace('#8E9DD1','#FFFFFF').replace('#AAAED7','#FFFFFF')}
    </group>
</vector>''')
save(RES / 'values/colors.xml', '<resources><color name="launcher_background">#E9EEFC</color></resources>')
save(RES / 'mipmap-anydpi/ic_launcher.xml', '<inset xmlns:android="http://schemas.android.com/apk/res/android" android:drawable="@drawable/ic_launcher" android:inset="0dp" />')
adaptive = '''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
{monochrome}</adaptive-icon>'''
save(RES / 'mipmap-anydpi-v26/ic_launcher.xml', adaptive.format(monochrome=''))
save(RES / 'mipmap-anydpi-v33/ic_launcher.xml', adaptive.format(monochrome='    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'))

svg_paths = ''.join(f'<path d="{path}" />' for path in GLYPHS)
svg_arrows = ''.join(f'<path d="{path}" stroke="{color}" />' for path,color in zip(ARROWS,['#8E9DD1','#AAAED7']))
mark = f'<path d="{BACK}" fill="#AAADD8"/><path d="{FRONT}" fill="#788ACB"/><g fill="none" stroke="white" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">{svg_paths}</g><g fill="none" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">{svg_arrows}</g>'
gradient = '<linearGradient id="blue" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#7086D5"/><stop offset="1" stop-color="#435DB7"/></linearGradient>'
save(ASSETS / 'relax-translator.svg', f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" width="256" height="256" role="img" aria-label="轻松翻译：文与A双向翻译标志"><defs>{gradient}</defs>{mark}</svg>')
save(ASSETS / 'readme-hero.svg', f'''<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="360" viewBox="0 0 1200 360" role="img" aria-label="轻松翻译 Relax Translator：先看译文，再把词句弄明白">
<defs>{gradient}<linearGradient id="paper" x2="1" y2="1"><stop stop-color="#F6F8FF"/><stop offset="1" stop-color="#E5ECFF"/></linearGradient></defs>
<rect x="1" y="1" width="1198" height="358" rx="28" fill="#EEF2FF" stroke="#DEE6FA"/>
<circle cx="1040" cy="180" r="150" fill="#DAE4FF" opacity=".6"/>
<g font-family="Microsoft YaHei, Noto Sans SC, system-ui, sans-serif">
<text x="68" y="67" fill="#526BC0" font-size="19" font-weight="650" letter-spacing="3">RELAX TRANSLATOR</text>
<text x="64" y="147" fill="#2D3448" font-size="54" font-weight="400">轻松翻译</text>
<text x="68" y="204" fill="#626F90" font-size="27">先看译文，再把词句弄明白。</text>
<g fill="white" stroke="#DCE4F8"><rect x="68" y="250" width="132" height="42" rx="21"/><rect x="214" y="250" width="132" height="42" rx="21"/><rect x="360" y="250" width="132" height="42" rx="21"/><rect x="506" y="250" width="152" height="42" rx="21"/></g>
<g fill="#526BC0" font-size="17" text-anchor="middle"><text x="134" y="277">文字 / 图片</text><text x="280" y="277">译文优先</text><text x="426" y="277">双向注音</text><text x="582" y="277">独立上下文</text></g>
</g>
<g transform="translate(866 65) scale(4.8)">{mark}</g>
</svg>''')
print('Generated Android launcher, shared in-app mark, and README SVG assets.')
