"""Export the repository banner with local fonts. Optional: Pillow and PyMuPDF."""
from pathlib import Path
import io
import pymupdf
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT/'docs/assets'
SCALE = 2
font_dir = Path('C:/Windows/Fonts')
regular = font_dir/'msyh.ttc'
bold = font_dir/'msyhbd.ttc'
english = font_dir/'segoeuib.ttf'
assert regular.exists() and bold.exists() and english.exists(), 'Set font paths for your platform before regenerating the optional banner.'

document = pymupdf.open(stream=(ASSETS/'relax-translator.svg').read_bytes(), filetype='svg')
mark = document[0].get_pixmap(matrix=pymupdf.Matrix(2,2),alpha=True)
logo = Image.open(io.BytesIO(mark.tobytes('png'))).convert('RGBA')
logo.save(ASSETS/'relax-translator.png')
document.close()

canvas = Image.new('RGB',(1200*SCALE,360*SCALE),'white')
draw = ImageDraw.Draw(canvas)
box = lambda rect: tuple(round(v*SCALE) for v in rect)
font = lambda file,size: ImageFont.truetype(str(file),round(size*SCALE))
draw.rounded_rectangle(box((1,1,1199,359)),radius=28*SCALE,fill='#EEF2FF',outline='#DEE6FA',width=SCALE)
draw.ellipse(box((890,30,1190,330)),fill='#E1E8FE')
draw.text((68*SCALE,42*SCALE),'RELAX TRANSLATOR',font=font(english,19),fill='#526BC0',anchor='lt')
draw.text((64*SCALE,92*SCALE),'轻松翻译',font=font(regular,54),fill='#2D3448',anchor='lt')
draw.text((68*SCALE,182*SCALE),'先看译文，再把词句弄明白。',font=font(regular,27),fill='#626F90',anchor='lt')
for left,right,label in [(68,200,'文字 / 图片'),(214,346,'译文优先'),(360,492,'双向注音'),(506,658,'独立上下文')]:
    draw.rounded_rectangle(box((left,250,right,292)),radius=21*SCALE,fill='white',outline='#DCE4F8',width=SCALE)
    draw.text((((left+right)/2)*SCALE,271*SCALE),label,font=font(regular,17),fill='#526BC0',anchor='mm')
icon = logo.resize((230*SCALE,230*SCALE),Image.Resampling.LANCZOS)
canvas.paste(icon,(866*SCALE,65*SCALE),icon)
canvas.save(ASSETS/'readme-hero.png',optimize=True)
print('Rendered portable README hero and icon PNGs from the vector mark.')
