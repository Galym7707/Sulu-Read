"""Generate the PWA icon PNGs. Run after any palette change: python make_icons.py

Drawn rather than rasterised from icon.svg because this box has no SVG rasteriser. The mark is
three lines of text on paper with the middle one highlighted amber — the focus-reading mode,
which is what the app is for. Colours are the app's tokens, kept in sync by hand.
"""
from PIL import Image, ImageDraw

ACCENT = (23, 78, 166)      # --accent  #174EA6
PAPER = (247, 242, 220)     # --reading #F7F2DC
AMBER = (255, 213, 79)      # --focus-bg #FFD54F
INK = (66, 69, 74)          # --ink-muted #42454A

SIZES = [
    ("icon-192.png", 192, 0.14),
    ("icon-512.png", 512, 0.14),
    ("icon-512-maskable.png", 512, 0.26),   # maskable: keep the mark inside the safe circle
    ("apple-touch-icon.png", 180, 0.0),     # iOS masks corners itself; a full bleed square
    ("favicon-32.png", 32, 0.10),
]


def draw(size, pad_frac):
    # RGB, not RGBA: iOS renders a transparent apple-touch-icon on black.
    img = Image.new("RGB", (size, size), ACCENT)
    d = ImageDraw.Draw(img)
    pad = size * (0.20 + pad_frac * 0.5)
    box = [pad, pad, size - pad, size - pad]
    r = max(2, int(size * 0.06))
    d.rounded_rectangle(box, radius=r, fill=PAPER)

    inner_w = box[2] - box[0]
    line_h = max(2, int(inner_w * 0.13))
    gap = line_h * 0.85
    x0 = box[0] + inner_w * 0.14
    top = box[1] + (box[3] - box[1] - (3 * line_h + 2 * gap)) / 2
    for i in range(3):
        y = top + i * (line_h + gap)
        w = inner_w * (0.72 if i == 1 else 0.56)
        if i == 1:
            # The focus word: amber block, full-bleed to the right so it reads as a highlight.
            d.rounded_rectangle([x0 - line_h * 0.35, y - line_h * 0.3,
                                 x0 + w + line_h * 0.35, y + line_h * 1.3],
                                radius=max(1, line_h // 3), fill=AMBER)
        d.rounded_rectangle([x0, y, x0 + w, y + line_h],
                            radius=max(1, line_h // 3), fill=INK)
    return img


for name, size, pad in SIZES:
    draw(size, pad).save(name)
    print("wrote", name, size)
