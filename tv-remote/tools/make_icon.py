"""Draws the TV Remote app icon (src/tv_remote/pcremote_ui/remote.ico) in code: a graphite remote with an amber
D-pad ring on a dark rounded tile. No logos. Needs Pillow, which is not a dependency of the app; run it by hand when
the icon changes and commit the .ico:

    uv run --with pillow python tools/make_icon.py
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

S = 1024  # drawn large, then scaled down for each size in the .ico
OUT = Path(__file__).resolve().parents[1] / "src" / "tv_remote" / "pcremote_ui" / "remote.ico"


def vgradient(size, top, bottom):
    w, h = size
    g = Image.new("RGBA", size)
    px = g.load()
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(4))
        for x in range(w):
            px[x, y] = c
    return g


def masked(img, mask_draw):
    mask = Image.new("L", img.size, 0)
    mask_draw(ImageDraw.Draw(mask))
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def draw() -> Image.Image:
    canvas = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    # tile
    tile = vgradient((S, S), (34, 40, 56, 255), (12, 14, 20, 255))
    canvas.alpha_composite(masked(tile, lambda d: d.rounded_rectangle((24, 24, S - 24, S - 24), radius=220, fill=255)))
    # remote body, tilted slightly, with a soft shadow
    body = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    bw, bh = 380, 820
    x0, y0 = (S - bw) // 2, (S - bh) // 2 + 30
    shadow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((x0 + 10, y0 + 40, x0 + bw + 10, y0 + bh + 40), radius=190, fill=(0, 0, 0, 170))
    shadow = shadow.filter(ImageFilter.GaussianBlur(30))
    grad = vgradient((S, S), (74, 81, 98, 255), (36, 40, 50, 255))
    body.alpha_composite(shadow)
    body.alpha_composite(masked(grad, lambda d: d.rounded_rectangle((x0, y0, x0 + bw, y0 + bh), radius=190, fill=255)))
    d = ImageDraw.Draw(body)
    d.rounded_rectangle((x0 + 6, y0 + 6, x0 + bw - 6, y0 + bh - 6), radius=184, outline=(255, 255, 255, 40), width=6)
    # D-pad: amber ring and OK button
    cx, cy, r = S // 2, y0 + 250, 128
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(255, 184, 28, 255))
    d.ellipse((cx - r + 34, cy - r + 34, cx + r - 34, cy + r - 34), fill=(30, 34, 44, 255))
    d.ellipse((cx - 52, cy - 52, cx + 52, cy + 52), fill=(255, 207, 90, 255))
    # buttons below
    for i, by in enumerate((y0 + 470, y0 + 590)):
        for bx in (cx - 82, cx + 82):
            d.ellipse((bx - 38, by - 38, bx + 38, by + 38), fill=(150, 160, 180, 255) if i == 0 else (110, 118, 136, 255))
    d.rounded_rectangle((cx - 90, y0 + 680, cx + 90, y0 + 720), radius=20, fill=(110, 118, 136, 255))
    canvas.alpha_composite(body.rotate(-12, resample=Image.BICUBIC, center=(S / 2, S / 2)))
    clip = Image.new("L", (S, S), 0)  # keep the body's shadow inside the tile
    ImageDraw.Draw(clip).rounded_rectangle((24, 24, S - 24, S - 24), radius=220, fill=255)
    canvas.putalpha(clip)
    return canvas


def main() -> None:
    img = draw()
    sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    img.resize((256, 256), Image.LANCZOS).save(OUT, format="ICO", sizes=sizes)
    if len(sys.argv) > 1:  # optional preview PNG path
        img.resize((512, 512), Image.LANCZOS).save(sys.argv[1])
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
