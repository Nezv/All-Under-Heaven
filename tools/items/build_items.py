"""Procedural item/block/armour textures for the Star-forged & Dragon-lord
tiers. Deterministic pixel art:

  Star-forged  = dark grey steel with cold blue tempering flecks (static).
  Dragon-lord  = lighter grey steel with crimson + ember flecks that FLICKER
                 (animated .png strip + .mcmeta), for the fire-forged look.

Run:  python build_items.py            (writes into the mod + a preview sheet)
"""
import math
import os
import random

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "..", "src", "main",
                                    "resources", "assets", "allunderheaven"))
ITEM_DIR = os.path.join(RES, "textures", "item")
BLOCK_DIR = os.path.join(RES, "textures", "block")
EQUIP_H = os.path.join(RES, "textures", "entity", "equipment", "humanoid")
EQUIP_L = os.path.join(RES, "textures", "entity", "equipment", "humanoid_leggings")
for d in (ITEM_DIR, BLOCK_DIR, EQUIP_H, EQUIP_L):
    os.makedirs(d, exist_ok=True)

TRANSPARENT = (0, 0, 0, 0)


class Palette:
    def __init__(self, base, light, dark, outline, accent, spark, animated):
        self.base = base
        self.light = light
        self.dark = dark
        self.outline = outline
        self.accent = accent      # the tier's colour bits (blue / crimson)
        self.spark = spark        # brightest accent (star glint / ember)
        self.animated = animated


STAR = Palette(base=(92, 99, 108), light=(146, 154, 165), dark=(52, 57, 66),
               outline=(24, 26, 32), accent=(58, 120, 214), spark=(150, 200, 255),
               animated=False)
LORD = Palette(base=(156, 158, 162), light=(206, 208, 212), dark=(98, 100, 106),
               outline=(30, 24, 26), accent=(196, 44, 40), spark=(255, 150, 54),
               animated=True)


def _shade(c, f):
    return (max(0, min(255, int(c[0] * f))), max(0, min(255, int(c[1] * f))),
            max(0, min(255, int(c[2] * f))), 255)


# ---- silhouettes: each returns a 16x16 mask dict pos->role -----------------
# roles: 'b' base, 'l' light, 'd' dark, 'h' haft, 'a' accent-slot (may spark)

def _haft(mask, x0, y0, x1, y1, w=2):
    steps = max(abs(x1 - x0), abs(y1 - y0))
    for s in range(steps + 1):
        u = s / max(1, steps)
        px = round(x0 + (x1 - x0) * u)
        py = round(y0 + (y1 - y0) * u)
        for dx in range(w):
            for dy in range(w):
                mask[(px + dx, py + dy)] = 'h'


def _blade(mask, x0, y0, x1, y1, w=3):
    steps = max(abs(x1 - x0), abs(y1 - y0))
    for s in range(steps + 1):
        u = s / max(1, steps)
        px = round(x0 + (x1 - x0) * u)
        py = round(y0 + (y1 - y0) * u)
        for dx in range(w):
            for dy in range(w):
                role = 'l' if dx == 0 else ('a' if (dx == w - 1 and s % 3 == 0) else 'b')
                mask[(px + dx, py + dy)] = role


def sword():
    m = {}
    _haft(m, 3, 12, 6, 9, 2)                 # grip
    for x in range(4, 9):                    # guard
        m[(x, 9)] = 'a' if x in (4, 8) else 'd'
    _blade(m, 6, 8, 12, 2, 3)                # blade up-right
    m[(13, 1)] = 'l'; m[(12, 1)] = 'a'
    return m


def pickaxe():
    m = {}
    _haft(m, 6, 13, 9, 4, 2)
    for x in range(2, 14):                    # arched head
        y = 3 + int(2.2 * (1 - math.sin(math.pi * (x - 2) / 11)))
        m[(x, y)] = 'l' if x in (2, 13) else 'b'
        m[(x, y + 1)] = 'a' if x in (4, 11) else 'd'
    return m


def axe():
    m = {}
    _haft(m, 7, 13, 9, 3, 2)
    for x in range(3, 9):                     # blocky bit on the left
        for y in range(2, 9):
            if (x - 8) ** 2 * 0.5 + (y - 5) ** 2 * 0.4 < 8:
                m[(x, y)] = 'b'
    m[(3, 5)] = 'l'; m[(4, 3)] = 'a'; m[(4, 7)] = 'a'
    return m


def shovel():
    m = {}
    _haft(m, 7, 12, 9, 5, 2)
    for x in range(6, 11):                     # spade head
        for y in range(3, 8):
            if not (y == 7 and x in (6, 10)):
                m[(x, y)] = 'b'
    for x in range(6, 11):
        m[(x, 3)] = 'l'
    m[(8, 5)] = 'a'
    return m


def hoe():
    m = {}
    _haft(m, 6, 13, 10, 3, 2)
    for x in range(3, 11):                      # top bar
        m[(x, 3)] = 'l' if x == 3 else 'b'
        m[(x, 4)] = 'd'
    for y in range(4, 7):                        # short down blade
        m[(3, y)] = 'b'
    m[(3, 6)] = 'a'; m[(9, 3)] = 'a'
    return m


def helmet():
    m = {}
    for x in range(3, 13):
        for y in range(2, 10):
            edge = x in (3, 12) or y == 2
            if y >= 8 and 5 <= x <= 10:          # visor gap
                continue
            m[(x, y)] = 'l' if (y == 2 or x == 3) else ('d' if edge else 'b')
    m[(5, 5)] = 'a'; m[(10, 5)] = 'a'
    return m


def chestplate():
    m = {}
    for x in range(3, 13):
        for y in range(3, 13):
            if (x in (3, 12) and y < 5):
                continue
            edge = x in (3, 12) or y in (3, 12)
            m[(x, y)] = 'l' if y == 3 else ('d' if edge else 'b')
    for y in range(5, 11):                        # sternum accent
        m[(8, y)] = 'a' if y % 2 else 'd'
    m[(4, 4)] = 'b'; m[(11, 4)] = 'b'
    return m


def leggings():
    m = {}
    for x in range(3, 13):
        for y in range(2, 6):                     # belt
            m[(x, y)] = 'l' if y == 2 else 'b'
    for x in list(range(3, 7)) + list(range(9, 13)):   # two legs
        for y in range(6, 14):
            edge = x in (3, 6, 9, 12)
            m[(x, y)] = 'd' if edge else 'b'
    m[(5, 3)] = 'a'; m[(10, 3)] = 'a'
    return m


def boots():
    m = {}
    for x in list(range(3, 7)) + list(range(9, 13)):
        for y in range(5, 10):
            m[(x, y)] = 'b'
        for x2 in range(x, x + 4):
            m[(x2, 11)] = 'd'
    for x in range(3, 13):
        m[(x, 12)] = 'd'
    m[(4, 6)] = 'l'; m[(10, 6)] = 'l'; m[(4, 8)] = 'a'; m[(10, 8)] = 'a'
    return m


def ingot():
    m = {}
    for x in range(2, 14):
        for y in range(5, 11):
            # trapezoid ingot
            inset = 1 if (y in (5, 10)) else 0
            if 2 + inset <= x <= 13 - inset:
                top = y in (5,)
                m[(x, y)] = 'l' if top else ('d' if y == 10 else 'b')
    for (ax, ay) in ((5, 7), (8, 8), (11, 7)):
        m[(ax, ay)] = 'a'
    return m


def dust():
    m = {}
    rng = random.Random(7)
    for _ in range(46):
        x, y = rng.randint(3, 12), rng.randint(4, 12)
        m[(x, y)] = rng.choice(['b', 'b', 'd', 'a'])
    for _ in range(6):
        m[(rng.randint(4, 11), rng.randint(5, 11))] = 'a'
    return m


SHAPES = {
    "sword": sword, "pickaxe": pickaxe, "axe": axe, "shovel": shovel,
    "hoe": hoe, "helmet": helmet, "chestplate": chestplate,
    "leggings": leggings, "boots": boots,
}


# ---- rendering -------------------------------------------------------------

def _outline(img, pal):
    px = img.load()
    w, h = img.size
    out = img.copy()
    op = out.load()
    for y in range(h):
        for x in range(w):
            if px[x, y][3] != 0:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and px[nx, ny][3] != 0:
                    op[x, y] = pal.outline + (255,)[:1] and (*pal.outline, 255)
                    break
    return out


def render_frame(mask, pal, frame, frames):
    img = Image.new("RGBA", (16, 16), TRANSPARENT)
    p = img.load()
    rng = random.Random(1)
    for (x, y), role in mask.items():
        if not (0 <= x < 16 and 0 <= y < 16):
            continue
        if role == 'b':
            p[x, y] = _shade(pal.base, 1.0)
        elif role == 'l':
            p[x, y] = _shade(pal.light, 1.0)
        elif role == 'd':
            p[x, y] = _shade(pal.dark, 1.0)
        elif role == 'h':
            p[x, y] = _shade(pal.dark, 0.8)
        elif role == 'a':
            if pal.animated:
                # ember flicker: cycle accent -> spark per fleck+frame
                phase = (x * 3 + y * 5 + frame * 2) % frames
                t = phase / max(1, frames - 1)
                glow = 0.5 + 0.5 * math.sin(2 * math.pi * t)
                col = tuple(int(pal.accent[i] + (pal.spark[i] - pal.accent[i]) * glow)
                            for i in range(3))
                p[x, y] = (*col, 255)
            else:
                p[x, y] = (*(pal.accent if (x + y) % 3 else pal.spark), 255)
    return _outline(img, pal)


def save_item(name, mask, pal):
    frames = 6 if pal.animated else 1
    imgs = [render_frame(mask, pal, f, frames) for f in range(frames)]
    if frames == 1:
        imgs[0].save(os.path.join(ITEM_DIR, f"{name}.png"))
    else:
        strip = Image.new("RGBA", (16, 16 * frames), TRANSPARENT)
        for f, im in enumerate(imgs):
            strip.paste(im, (0, 16 * f))
        strip.save(os.path.join(ITEM_DIR, f"{name}.png"))
        with open(os.path.join(ITEM_DIR, f"{name}.png.mcmeta"), "w") as fh:
            fh.write('{\n "animation": {\n  "frametime": 3\n }\n}\n')
    return imgs[0]


def build_ore():
    img = Image.new("RGBA", (16, 16), (255, 0, 255, 255))
    d = ImageDraw.Draw(img)
    rng = random.Random(3)
    for y in range(16):                          # deepslate-ish base
        for x in range(16):
            v = rng.uniform(0.82, 1.06)
            d.point((x, y), fill=_shade((46, 48, 54), v))
    for _ in range(10):                           # star flecks (blue glow)
        x, y = rng.randint(1, 14), rng.randint(1, 14)
        d.point((x, y), fill=(150, 200, 255, 255))
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if rng.random() < 0.5:
                d.point((x + dx, y + dy), fill=(70, 120, 200, 255))
    for _ in range(5):                            # raw gold glints
        x, y = rng.randint(1, 14), rng.randint(1, 14)
        d.point((x, y), fill=(240, 205, 90, 255))
    img.save(os.path.join(BLOCK_DIR, "stardust_ore.png"))


def build_blood():
    img = Image.new("RGBA", (16, 16), TRANSPARENT)
    d = ImageDraw.Draw(img)
    d.ellipse([5, 6, 11, 13], fill=(120, 12, 16, 255))     # droplet body
    d.polygon([(8, 2), (6, 7), (10, 7)], fill=(120, 12, 16, 255))  # tip
    d.ellipse([6, 8, 9, 11], fill=(180, 30, 30, 255))       # sheen
    d.point((7, 9), fill=(230, 90, 90, 255))
    img.save(os.path.join(ITEM_DIR, "dragon_blood.png"))


def build_armor_layer(tier, pal):
    """Filled 64x32 layer: the game samples each equipped piece's region, so a
    fully tinted plate reads as tier-coloured armour on the body."""
    for w, h, folder in ((64, 32, EQUIP_H), (64, 32, EQUIP_L)):
        img = Image.new("RGBA", (w, h), TRANSPARENT)
        d = ImageDraw.Draw(img)
        rng = random.Random(hash(tier) & 0xFFFF)
        for y in range(h):
            for x in range(w):
                v = rng.uniform(0.86, 1.08)
                d.point((x, y), fill=_shade(pal.base, v))
        for _ in range(70):                        # rivets / accent studs
            x, y = rng.randint(0, w - 1), rng.randint(0, h - 1)
            d.point((x, y), fill=(*(pal.accent if rng.random() < 0.6 else pal.light), 255))
        img.save(os.path.join(folder, f"{tier}.png"))


def build_preview(previews):
    cols = 10
    rows = (len(previews) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * 20, rows * 20), (30, 32, 38, 255))
    for i, (name, im) in enumerate(previews):
        sheet.paste(im.resize((16, 16)), (20 * (i % cols) + 2, 20 * (i // cols) + 2))
    sheet.resize((cols * 20 * 4, rows * 20 * 4), Image.NEAREST).save(
        os.path.join(HERE, "preview.png"))


if __name__ == "__main__":
    previews = []
    for tier, pal in (("star_forged", STAR), ("dragonlord", LORD)):
        previews.append((f"{tier}_steel", save_item(f"{tier}_steel", ingot(), pal)))
        for part, fn in SHAPES.items():
            previews.append((f"{tier}_{part}", save_item(f"{tier}_{part}", fn(), pal)))
        build_armor_layer(tier, pal)
    previews.append(("star_dust", save_item("star_dust", dust(), STAR)))
    build_ore(); build_blood()
    build_preview(previews)
    print("wrote item/block/armour textures into", RES)
