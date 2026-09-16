#!/usr/bin/env python3
import os
from PIL import Image

src = "/workspace/courier/assets_raw/app_icon.jpg"
img = Image.open(src).convert("RGBA")
# square-crop from center if not square
w, h = img.size
s = min(w, h)
img = img.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s))

res = {
    "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
}
base = "/workspace/courier/app/src/main/res"
for dpi, px in res.items():
    d = os.path.join(base, f"mipmap-{dpi}")
    os.makedirs(d, exist_ok=True)
    for name in ("ic_launcher", "ic_launcher_round"):
        img.resize((px, px), Image.LANCZOS).save(os.path.join(d, f"{name}.png"))
print("done", s)