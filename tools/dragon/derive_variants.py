"""Derive colour variants of the wyvern from Bruno's hand-authored BLACK.

Geometry is copied VERBATIM - same bones, same pivots, same rest pose, same
cubes, same UV rects. Only the geometry identifier changes. So the gorilla
plant, the bowed head, the membrane panels and their alpha carve are common to
every variant by construction, because they are literally the same file.

The texture is re-tinted, not repainted. Black's atlas is near-neutral
(saturation mean 0.14, only 0.43% of pixels above S=0.35) with a median
luminance of 39, so each pixel's LUMINANCE is mapped through a per-variant
colour ramp. That preserves every hand-painted mark - the shading, the torn
membrane fringes, the scale work - and changes only the hue and value it is
rendered in. The handful of genuinely saturated pixels (the iris, the mouth)
are carried through a separate accent rule so they do not get flattened into
the ramp.

Writes ONLY into tools/dragon/out. Nothing here can touch the mod resource
tree or Bruno's reference files.
"""

import colorsys
import json
import os
import shutil

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
SRC_GEO = os.path.join(OUT, "wyvern_black.geo - Copy.json")
SRC_TEX = os.path.join(OUT, "wyvern_black.png")

# Luminance window taken from black's own p1..p100. The gamma expands the
# crowded dark end, where the median pixel actually lives, instead of letting
# the sparse bright tail eat most of the ramp.
L_LO, L_HI, L_GAMMA = 18.0, 243.0, 0.55

# Ramps are (t, rgb) with t over the normalised luminance above.
RAMPS = {
    "red": [(0.00, (40, 12, 10)), (0.28, (100, 28, 24)), (0.52, (146, 46, 38)),
            (0.74, (196, 104, 74)), (1.00, (230, 168, 128))],
    "white": [(0.00, (120, 124, 140)), (0.28, (182, 186, 198)),
              (0.52, (214, 218, 226)), (0.74, (238, 240, 244)),
              (1.00, (253, 253, 255))],
}

# Where a saturated accent should land. Black's iris is yellow; the white
# wyvern's is blue, so its accents rotate rather than ride the ramp.
ACCENT_HUE = {"red": 0.13, "white": 0.60}   # HSV hue, 0..1
ACCENT_MIN_S = 0.35


def _lerp(a, b, f):
    return tuple(a[i] + (b[i] - a[i]) * f for i in range(3))


def ramp_lookup(ramp, t):
    for i in range(len(ramp) - 1):
        t0, c0 = ramp[i]
        t1, c1 = ramp[i + 1]
        if t <= t1:
            f = 0.0 if t1 == t0 else (t - t0) / (t1 - t0)
            return _lerp(c0, c1, max(0.0, f))
    return ramp[-1][1]


def retint(src: Image.Image, variant: str) -> Image.Image:
    ramp = RAMPS[variant]
    hue = ACCENT_HUE[variant]
    out = Image.new("RGBA", src.size)
    sp = src.load()
    op = out.load()
    w, h = src.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = sp[x, y]
            if a == 0:                       # carved-away texels stay carved
                op[x, y] = (0, 0, 0, 0)
                continue
            hsv = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            if hsv[1] >= ACCENT_MIN_S:
                # keep the mark's own value and punch, just re-hue it
                nr, ng, nb = colorsys.hsv_to_rgb(hue, hsv[1], hsv[2])
                op[x, y] = (int(nr * 255), int(ng * 255), int(nb * 255), a)
                continue
            lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            t = (lum - L_LO) / (L_HI - L_LO)
            t = min(1.0, max(0.0, t)) ** L_GAMMA
            c = ramp_lookup(ramp, t)
            op[x, y] = (int(c[0]), int(c[1]), int(c[2]), a)
    return out


def derive(variant: str):
    doc = json.load(open(SRC_GEO))
    doc["minecraft:geometry"][0]["description"]["identifier"] = \
        f"geometry.allunderheaven.wyvern_{variant}"
    geo_path = os.path.join(OUT, f"derived_wyvern_{variant}.geo.json")
    with open(geo_path, "w") as f:
        json.dump(doc, f, indent=1)
    tex = retint(Image.open(SRC_TEX).convert("RGBA"), variant)
    tex_path = os.path.join(OUT, f"derived_wyvern_{variant}.png")
    tex.save(tex_path)
    bones = doc["minecraft:geometry"][0]["bones"]
    cubes = sum(len(b.get("cubes", [])) for b in bones)
    print(f"{variant:6} geo={os.path.basename(geo_path)} "
          f"bones={len(bones)} cubes={cubes} (verbatim from black)")
    print(f"       tex={os.path.basename(tex_path)} {tex.size[0]}x{tex.size[1]}")


if __name__ == "__main__":
    for name in ("red", "white"):
        derive(name)
    # black is the master; copy it under the same naming so the three sit
    # together, but it is byte-identical to Bruno's reference.
    shutil.copyfile(SRC_TEX, os.path.join(OUT, "derived_wyvern_black.png"))
    doc = json.load(open(SRC_GEO))
    with open(os.path.join(OUT, "derived_wyvern_black.geo.json"), "w") as f:
        json.dump(doc, f, indent=1)
    print("black  copied verbatim as the master")
    print("\nwrote", OUT, "- geometry identical across all three")
