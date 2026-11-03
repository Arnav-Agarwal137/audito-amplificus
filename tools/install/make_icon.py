"""Draws the laptop's desktop-shortcut icon from the phone's launcher icon, so both show the same Aurora cloud.

Source of truth: app/src/main/res/drawable/ic_launcher_fg.xml (three radial lights on a 108-unit canvas) and
values/colors.xml icon_bg. The numbers below are read from those files, not retyped (global 7a): if the phone icon
changes, re-run this and install.ps1.

    python tools/install/make_icon.py      ->  tools/install/audito.ico  (16..256 px)
"""
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
FG = ROOT / "app/src/main/res/drawable/ic_launcher_fg.xml"
COLORS = ROOT / "app/src/main/res/values/colors.xml"
OUT = Path(__file__).resolve().parent / "audito.ico"


def argb(s):
    s = s.lstrip("#")
    return int(s[2:4], 16), int(s[4:6], 16), int(s[6:8], 16), int(s[0:2], 16)


def lights():
    xml = FG.read_text(encoding="utf-8")
    out = []
    for g in re.finditer(r'centerX="([\d.]+)" android:centerY="([\d.]+)" android:gradientRadius="([\d.]+)">(.*?)</gradient>', xml, re.S):
        stops = [(float(o), argb(c)) for o, c in re.findall(r'offset="([\d.]+)" android:color="(#\w{8})"', g.group(4))]
        out.append((float(g.group(1)), float(g.group(2)), float(g.group(3)), stops))
    if len(out) != 3:
        sys.exit(f"expected 3 lights in {FG}, found {len(out)}")
    return out


def render(px=256):
    bg = argb(re.search(r'name="icon_bg">(#\w{8})<', COLORS.read_text(encoding="utf-8")).group(1))
    lo, hi = 20.0, 88.0                      # the part of the 108 canvas the lights live in (safe zone + glow)
    u = lo + (np.arange(px) + 0.5) / px * (hi - lo)
    X, Y = np.meshgrid(u, u)
    rgb = np.zeros((px, px, 3)); rgb[:] = np.array(bg[:3]) / 255.0
    for cx, cy, r, stops in lights():       # drawn in file order, each over the last (Android vector order)
        t = np.clip(np.hypot(X - cx, Y - cy) / r, 0, 1)
        offs = [o for o, _ in stops]
        a = np.interp(t, offs, [c[3] / 255.0 for _, c in stops])
        col = np.array(stops[0][1][:3]) / 255.0
        rgb = rgb * (1 - a[..., None]) + col * a[..., None]
    img = Image.fromarray((rgb * 255).round().astype(np.uint8), "RGB").convert("RGBA")
    mask = Image.new("L", (px, px), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, px - 1, px - 1], radius=px // 5, fill=255)
    img.putalpha(mask)
    return img


if __name__ == "__main__":
    big = render(256)
    big.save(OUT, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    big.save(OUT.with_suffix(".png"))
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")
