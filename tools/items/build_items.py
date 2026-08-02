"""Procedural item/block/armour textures for the Star-forged & Dragon-lord
tiers. Deterministic pixel art with a medieval / House-of-the-Dragon bent:

  Star-forged  = ornate BRONZE & gold, a Knight-of-the-Seven-Kingdoms look —
                 warm brass plate, pale-gold edges, gilded embossing (STATIC).
  Dragon-lord  = near-black Targaryen plate with GOLD trim and fervent crimson
                 blood that FLOWS through the metal (8-frame animated .png strip
                 + .mcmeta), embers on the crest — the blood-quenched apex.
  (star_dust keeps its cold cosmic blue via the COSMIC palette.)

The blade/tool/plate silhouettes are drawn as real shapes (broad fullered
blades, crossguards, pommels, plate cuirasses) so they read correctly when the
handheld/flat item model extrudes them to 3D.

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
FRAMES = 8            # animation length for the blood-forged tier
FRAMETIME = 3        # ticks per frame


# ---- material ramps --------------------------------------------------------
# Per the Minecraft style guide, shade each material as a HUE-SHIFTED ramp, not
# a single flat colour: hue + saturation + value all shift between steps, so
# shadows read cool/deep and highlights warm/bright. Index 0 = deep shadow ..
# 4 = highlight. Each item then MIXES several ramps by region (plate + fitting
# + grip + accent), and the renderer picks a step from each pixel's form.

DARKPLATE = [(20, 20, 28), (36, 38, 50), (58, 62, 78), (98, 104, 124), (152, 162, 184)]
BRONZE    = [(56, 32, 18), (100, 60, 30), (150, 100, 52), (198, 148, 88), (236, 198, 138)]
GILT      = [(94, 56, 20), (150, 100, 34), (206, 152, 54), (236, 194, 98), (255, 236, 160)]
BLOOD     = [(64, 10, 20), (120, 20, 28), (182, 36, 34), (224, 92, 46), (255, 168, 78)]
WOOD      = [(36, 24, 14), (60, 42, 24), (92, 66, 40), (126, 94, 58), (162, 128, 86)]
LEATHER   = [(26, 12, 14), (44, 22, 24), (68, 38, 36), (100, 60, 50), (136, 90, 70)]
COSMIC    = [(26, 32, 50), (44, 56, 86), (78, 100, 142), (124, 156, 206), (184, 214, 252)]


class Tier:
    """A kit's material mix: a base plate ramp, a metal-fitting ramp (guards,
    pommels, trim, rivets), a grip ramp (wood/leather) and an accent ramp (the
    tier pop). `animated` drives the flowing blood on the dragon-lord kit."""
    def __init__(self, base, fitting, grip, accent, animated=False):
        self.base = base
        self.fitting = fitting
        self.grip = grip
        self.accent = accent
        self.animated = animated


# Star-forged: bronze plate + gilded fittings + wooden hafts, gold embossing.
STAR = Tier(base=BRONZE, fitting=GILT, grip=WOOD, accent=GILT, animated=False)
# Dragon-lord: near-black plate + gold trim + dark leather + flowing crimson.
LORD = Tier(base=DARKPLATE, fitting=GILT, grip=LEATHER, accent=BLOOD, animated=True)
# star_dust: cold cosmic grit.
DUST = Tier(base=COSMIC, fitting=COSMIC, grip=COSMIC, accent=COSMIC, animated=False)


def _shade(c, f):
    return (max(0, min(255, int(c[0] * f))), max(0, min(255, int(c[1] * f))),
            max(0, min(255, int(c[2] * f))), 255)


def _lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


# ---- silhouettes -----------------------------------------------------------
# Each returns a 16x16 mask dict pos -> role. Roles:
#   's' steel  'l' light  'd' dark  'e' edge
#   'g' grip/haft  'k' grip-shadow  'm' metal  'n' metal-dark
#   'a' accent (tier colour, may flow)  'p' spark (bright glint)

def _put(m, x, y, role):
    if 0 <= x < 16 and 0 <= y < 16:
        m[(x, y)] = role


def _haft(m, x0, y0, x1, y1, w=2, role='g', shadow='k'):
    """A straight haft/grip of given width, shaded on its trailing side."""
    steps = max(abs(x1 - x0), abs(y1 - y0))
    for s in range(steps + 1):
        u = s / max(1, steps)
        px = round(x0 + (x1 - x0) * u)
        py = round(y0 + (y1 - y0) * u)
        for i in range(w):
            _put(m, px + i, py, shadow if i == w - 1 and w > 1 else role)


def sword():
    """A longsword drawn along the SW->NE diagonal (pommel in the lower-left so
    the handheld model seats it in the fist): a 2-wide fullered blade with a
    bright upper edge, upswept quillons, a wrapped grip and a disc pommel."""
    m = {}
    x0, y0, x1, y1 = 6, 9, 13, 2
    steps = max(abs(x1 - x0), abs(y1 - y0))
    for s in range(steps + 1):
        u = s / steps
        cx = round(x0 + (x1 - x0) * u)
        cy = round(y0 + (y1 - y0) * u)
        _put(m, cx, cy, 'e')                        # bright cutting edge
        _put(m, cx + 1, cy + 1, 's')                # blade body / fuller side
        if s in (2, 4):
            _put(m, cx + 1, cy + 1, 'a')            # temper / blood in the fuller
    _put(m, 13, 2, 'e'); _put(m, 12, 2, 'l')        # crisp point
    for (gx, gy) in ((3, 9), (4, 10), (5, 10), (6, 10), (7, 9)):  # upswept quillons
        _put(m, gx, gy, 'm')
    _put(m, 3, 9, 'n'); _put(m, 7, 9, 'n')
    for (gx, gy) in ((5, 11), (4, 12), (4, 13)):    # wrapped grip
        _put(m, gx, gy, 'g')
    _put(m, 4, 12, 'k')
    for (px, py) in ((3, 14), (4, 14), (3, 13)):    # disc pommel
        _put(m, px, py, 'm')
    _put(m, 4, 14, 'p')
    return m


def pickaxe():
    m = {}
    _haft(m, 8, 5, 4, 14, 2, 'g', 'k')              # haft to bottom-left
    for x in range(2, 14):                          # arched twin-horn head
        t = (x - 2) / 11.0
        y = 5 - int(round(3.0 * math.sin(math.pi * t)))
        _put(m, x, y, 'e' if x in (2, 13) else ('l' if x in (7, 8) else 's'))
        _put(m, x, y + 1, 'a' if x in (5, 10) else ('d' if x in (3, 12) else 's'))
    _put(m, 2, 5, 'e'); _put(m, 13, 5, 'e')
    return m


def axe():
    m = {}
    _haft(m, 9, 3, 5, 14, 2, 'g', 'k')              # haft to bottom-left
    for y in range(3, 10):                           # broad bit, arced cutting edge
        left = 4 + (1 if y in (3, 9) else 0)
        for x in range(left, 9):
            role = 'e' if x == left else ('a' if x == 7 and y in (5, 7)
                                          else ('l' if y == 6 else 's'))
            _put(m, x, y, role)
    for y in range(4, 9):                            # socket seam at the haft
        _put(m, 8, y, 'n')
    return m


def shovel():
    m = {}
    _haft(m, 9, 6, 5, 14, 2, 'g', 'k')              # haft to bottom-left
    for y in range(2, 8):                            # spade blade, top
        halfw = 3 - max(0, y - 5)
        for x in range(8 - halfw, 9 + halfw):
            edge = x == 8 - halfw or x == 8 + halfw
            _put(m, x, y, 'l' if y == 2 else ('e' if edge else 's'))
    _put(m, 8, 5, 'a'); _put(m, 8, 4, 'a')
    return m


def hoe():
    m = {}
    _haft(m, 9, 4, 5, 14, 2, 'g', 'k')              # haft to bottom-left
    for x in range(3, 10):                            # top head bar
        _put(m, x, 2, 'l')
        _put(m, x, 3, 's' if x else 's')
    for y in range(2, 6):                             # short down-blade (left)
        _put(m, 3, y, 'e' if y == 2 else 's')
        _put(m, 4, y, 's')
    _put(m, 3, 4, 'a'); _put(m, 8, 3, 'a')
    return m


def helmet():
    m = {}
    for x in range(4, 12):                            # rounded great-helm dome
        for y in range(2, 12):
            if (x in (4, 11) and y in (2, 11)):       # clip corners
                continue
            edge = x in (4, 11) or y == 11
            _put(m, x, y, 'l' if y == 2 else ('d' if edge else 's'))
    for x in range(5, 11):                            # visor slit
        _put(m, x, 6, 'n')
    for y in range(4, 10):                            # central reinforce ridge
        _put(m, 7, y, 'l'); _put(m, 8, y, 'd')
    for x in range(4, 12):                            # brow trim accent
        if x % 2 == 0:
            _put(m, x, 3, 'a')
    return m


def chestplate():
    m = {}
    for x in (3, 4, 11, 12):                          # pauldrons
        _put(m, x, 4, 'l'); _put(m, x, 5, 'd')
    for x in range(4, 12):                            # cuirass body
        for y in range(4, 14):
            edge = x in (4, 11) or y == 13
            _put(m, x, y, 'l' if y == 4 else ('d' if edge else 's'))
    for y in range(5, 13):                            # sternum ridge
        _put(m, 7, y, 'e'); _put(m, 8, y, 'd')
    for x in range(5, 11):                            # collar + belt trim
        if x % 2:
            _put(m, x, 4, 'a')
    for x in range(4, 12):
        _put(m, x, 12, 'm' if x % 2 else 'n')
    return m


def leggings():
    m = {}
    for x in range(3, 13):                            # belt
        _put(m, x, 2, 'l'); _put(m, x, 3, 's'); _put(m, x, 4, 'm')
    for x in list(range(3, 7)) + list(range(9, 13)):  # two cuisses
        for y in range(5, 14):
            edge = x in (3, 6, 9, 12)
            _put(m, x, y, 'd' if edge else 's')
        _put(m, x, 5, 'l')
    _put(m, 4, 8, 'a'); _put(m, 11, 8, 'a')           # knee studs
    _put(m, 5, 4, 'a'); _put(m, 10, 4, 'a')
    return m


def boots():
    m = {}
    for x in list(range(3, 7)) + list(range(9, 13)):  # two sabatons
        for y in range(6, 12):
            _put(m, x, y, 's')
        _put(m, x, 6, 'l')
    for x in range(3, 7):                              # soles
        _put(m, x, 12, 'n')
    for x in range(9, 13):
        _put(m, x, 12, 'n')
    for x in (3, 4, 5, 6):                             # pointed toe cap
        _put(m, x, 7, 'e' if x == 3 else 's')
    _put(m, 4, 9, 'a'); _put(m, 11, 9, 'a')           # ankle trim
    return m


def ingot():
    m = {}
    for x in range(2, 14):                             # forged trapezoid bar
        for y in range(5, 11):
            inset = 1 if y in (5, 10) else 0
            if 2 + inset <= x <= 13 - inset:
                _put(m, x, y, 'l' if y == 5 else ('d' if y == 10 else 's'))
    for (ax, ay) in ((5, 7), (8, 8), (11, 7)):         # veins / temper glints
        _put(m, ax, ay, 'a')
    _put(m, 4, 6, 'p')
    return m


def dust():
    m = {}
    rng = random.Random(7)
    for _ in range(44):
        x, y = rng.randint(3, 12), rng.randint(4, 12)
        m[(x, y)] = rng.choice(['s', 's', 'd', 'a'])
    for _ in range(7):
        m[(rng.randint(4, 11), rng.randint(5, 11))] = 'p'
    return m


SHAPES = {
    "sword": sword, "pickaxe": pickaxe, "axe": axe, "shovel": shovel,
    "hoe": hoe, "helmet": helmet, "chestplate": chestplate,
    "leggings": leggings, "boots": boots,
}


# ---- rendering -------------------------------------------------------------

def _outline(img, colour):
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
                    op[x, y] = (*colour, 255)
                    break
    return out


def _flow(x, y, frame):
    """A travelling wave up the blade axis, 0..1, drives the blood glow."""
    return 0.5 + 0.5 * math.sin(2.0 * math.pi * ((x - y) * 0.22 - frame / FRAMES))


# role -> (ramp name on the Tier, base index into that 5-step ramp)
_ROLE = {
    's': ("base", 2), 'l': ("base", 3), 'd': ("base", 1), 'e': ("base", 4),
    'g': ("grip", 2), 'k': ("grip", 1),
    'm': ("fitting", 2), 'n': ("fitting", 1),
    'a': ("accent", 2), 'p': ("accent", 4),
}


def render_frame(mask, tier, frame):
    """Shade the silhouette with hue-shifted ramps, a top-left form bevel and a
    light transition dither — the anti-flat pipeline the style guide calls for."""
    opaque = set(mask.keys())
    ramp_of = {"base": tier.base, "fitting": tier.fitting,
               "grip": tier.grip, "accent": tier.accent}
    # pass 1: choose a ramp + step per pixel (role step, nudged by the form)
    idx, ramps = {}, {}
    for (x, y), role in mask.items():
        if role not in _ROLE:
            continue
        rname, i = _ROLE[role]
        if (x - 1, y - 1) not in opaque:          # top-left silhouette edge = lit
            i += 1
        if (x + 1, y + 1) not in opaque:          # bottom-right edge = shaded
            i -= 1
        if role in ("a", "p") and tier.animated:  # crimson->ember blood flow
            i = 1 + int(round(3 * (_flow(x, y, frame) ** 1.4)))
        ramps[(x, y)] = ramp_of[rname]
        idx[(x, y)] = max(0, min(4, i))
    # pass 2: emit, dithering the mid step toward an adjacent highlight
    img = Image.new("RGBA", (16, 16), TRANSPARENT)
    p = img.load()
    for (x, y) in idx:
        ramp = ramps[(x, y)]
        i = idx[(x, y)]
        if i == 2 and (x + y) % 2 == 0:
            for nb in ((x - 1, y), (x, y - 1), (x + 1, y), (x, y + 1)):
                if ramps.get(nb) is ramp and idx.get(nb, 0) >= 3:
                    i = 3
                    break
        col = ramp[i]
        if tier.animated and ramp is tier.base:   # ember heat pulsing in the black plate
            col = _lerp(col, BLOOD[4], 0.16 * _flow(x, y, frame))
        p[x, y] = (*col, 255)
    return _outline(img, _lerp(tier.base[0], (0, 0, 0), 0.35))


def save_item(name, mask, tier):
    frames = FRAMES if tier.animated else 1
    imgs = [render_frame(mask, tier, f) for f in range(frames)]
    if frames == 1:
        imgs[0].save(os.path.join(ITEM_DIR, f"{name}.png"))
        _rm(os.path.join(ITEM_DIR, f"{name}.png.mcmeta"))
    else:
        strip = Image.new("RGBA", (16, 16 * frames), TRANSPARENT)
        for f, im in enumerate(imgs):
            strip.paste(im, (0, 16 * f))
        strip.save(os.path.join(ITEM_DIR, f"{name}.png"))
        _write_mcmeta(os.path.join(ITEM_DIR, f"{name}.png.mcmeta"))
    return imgs[0]


def _write_mcmeta(path, frametime=FRAMETIME):
    with open(path, "w") as fh:
        fh.write('{\n "animation": {\n  "frametime": %d\n }\n}\n' % frametime)


def _rm(path):
    if os.path.exists(path):
        os.remove(path)


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
    """An 8-frame vial-drop of fervent dragon blood: the body roils with a
    travelling crimson->ember heat wave and a bobbing specular, so it reads as
    living, boiling blood rather than a flat droplet."""
    frames = FRAMES
    # droplet silhouette: rounded body + tapered top
    inside = set()
    for x in range(16):
        for y in range(16):
            in_body = (x - 8) ** 2 / 10.0 + (y - 10) ** 2 / 12.0 <= 1.0
            in_tip = 2 <= y <= 9 and abs(x - 8) <= (y - 2) * 0.55
            if in_body or in_tip:
                inside.add((x, y))
    rim = _lerp(BLOOD[0], (0, 0, 0), 0.25)
    deep, crim, hot, ember = BLOOD[1], BLOOD[2], BLOOD[3], BLOOD[4]
    strip = Image.new("RGBA", (16, 16 * frames), TRANSPARENT)
    first = None
    for f in range(frames):
        im = Image.new("RGBA", (16, 16), TRANSPARENT)
        p = im.load()
        t = f / frames
        for (x, y) in inside:
            edge = not all((x + dx, y + dy) in inside
                           for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if edge:
                p[x, y] = (*rim, 255)
                continue
            wave = 0.5 + 0.5 * math.sin(2.0 * math.pi * (y * 0.28 + x * 0.08 - t))
            wave = wave ** 1.5
            if wave < 0.30:
                col = deep
            elif wave < 0.62:
                col = crim
            elif wave < 0.85:
                col = hot
            else:
                col = ember
            p[x, y] = (*col, 255)
        # bobbing specular highlight near the upper-left of the body
        sy = 8 + int(round(1.5 * math.sin(2.0 * math.pi * t)))
        for (hx, hy) in ((7, sy), (7, sy + 1), (8, sy)):
            if (hx, hy) in inside:
                p[hx, hy] = (255, 205, 150, 255)
        im = _outline(im, _lerp(BLOOD[0], (0, 0, 0), 0.3))
        strip.paste(im, (0, 16 * f))
        if first is None:
            first = im
    strip.save(os.path.join(ITEM_DIR, "dragon_blood.png"))
    _write_mcmeta(os.path.join(ITEM_DIR, "dragon_blood.png.mcmeta"))
    return first


# NOTE: the worn armour look is now custom GeckoLib 3D geometry (see
# tools/armor/build_armor.py); the old flat 64x32 equipment layers are no
# longer generated here.


GUI_DIR = os.path.join(RES, "textures", "gui", "container")
os.makedirs(GUI_DIR, exist_ok=True)

BLACKSTONE = (46, 40, 48)
CRIMSON = (168, 42, 36)
EMBER = (255, 132, 52)
GOLD = (240, 205, 90)


def _noise(d, w, h, base, rng, lo=0.80, hi=1.10, x0=0, y0=0):
    for y in range(h):
        for x in range(w):
            d.point((x0 + x, y0 + y), fill=_shade(base, rng.uniform(lo, hi)))


def _maw(d, frame, frames, lit):
    """The dragon-mouth furnace opening: an arched maw with fang teeth. When
    lit it roars with animated crimson->ember->gold fire."""
    rng = random.Random(100 + frame)
    for x in range(4, 12):                              # arched cavity
        top = 5 + (0 if 6 <= x <= 9 else 1)
        for y in range(top, 14):
            if lit:
                t = (y - top) / 8.0                     # bottom = hotter
                flick = rng.random()
                if flick < 0.12 + 0.5 * t:
                    col = EMBER if flick < 0.3 else (255, 210, 90)
                elif flick < 0.4 + 0.4 * t:
                    col = CRIMSON
                else:
                    col = (70, 14, 16)
                d.point((x, y), fill=(*col, 255))
            else:
                d.point((x, y), fill=(20, 12, 14, 255))
                if rng.random() < 0.10:
                    d.point((x, y), fill=(90, 26, 22, 255))
    for fx in (5, 7, 9, 11):                            # fang teeth on the lip
        d.point((fx, 5 if 6 <= fx <= 9 else 6), fill=(210, 205, 198, 255))
        d.point((fx, 13), fill=(210, 205, 198, 255))
    for (rx, ry) in ((3, 4), (12, 4), (3, 13), (12, 13)):   # crimson bolts
        d.point((rx, ry), fill=(*CRIMSON, 255))


def build_forge_block():
    rng = random.Random(11)
    # side: blackstone with a crimson seam and hot bolts
    img = Image.new("RGBA", (16, 16), TRANSPARENT)
    d = ImageDraw.Draw(img)
    _noise(d, 16, 16, BLACKSTONE, rng)
    for x in range(16):
        d.point((x, 7), fill=_shade(BLACKSTONE, 0.6))
        d.point((x, 8), fill=(*CRIMSON, 255) if x % 4 == 0 else _shade(BLACKSTONE, 1.25))
    for (rx, ry) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        d.point((rx, ry), fill=(*EMBER, 255))
    img.save(os.path.join(BLOCK_DIR, "dragonlord_forge_side.png"))
    # top: cracked blackstone with a glowing crimson rune ring
    img = Image.new("RGBA", (16, 16), TRANSPARENT)
    d = ImageDraw.Draw(img)
    _noise(d, 16, 16, BLACKSTONE, rng)
    d.ellipse([3, 3, 12, 12], outline=(*CRIMSON, 255))
    d.ellipse([5, 5, 10, 10], outline=(*EMBER, 255))
    for a in range(0, 360, 45):
        px = 7.5 + 4.5 * math.cos(math.radians(a))
        py = 7.5 + 4.5 * math.sin(math.radians(a))
        d.point((int(px), int(py)), fill=(*GOLD, 255))
    img.save(os.path.join(BLOCK_DIR, "dragonlord_forge_top.png"))
    # front unlit
    img = Image.new("RGBA", (16, 16), TRANSPARENT)
    d = ImageDraw.Draw(img)
    _noise(d, 16, 16, BLACKSTONE, rng)
    _maw(d, 0, 1, lit=False)
    img.save(os.path.join(BLOCK_DIR, "dragonlord_forge_front.png"))
    # front lit: animated fire strip
    frames = 8
    strip = Image.new("RGBA", (16, 16 * frames), TRANSPARENT)
    for f in range(frames):
        fr = Image.new("RGBA", (16, 16), TRANSPARENT)
        fd = ImageDraw.Draw(fr)
        _noise(fd, 16, 16, BLACKSTONE, random.Random(11))
        _maw(fd, f, frames, lit=True)
        strip.paste(fr, (0, 16 * f))
    strip.save(os.path.join(BLOCK_DIR, "dragonlord_forge_front_on.png"))
    with open(os.path.join(BLOCK_DIR, "dragonlord_forge_front_on.png.mcmeta"), "w") as fh:
        fh.write('{\n "animation": {\n  "frametime": 4\n }\n}\n')


def build_gui():
    """176x166 forge panel in a 256x256 sheet, with full flame (176,0) and
    full arrow (176,14) sprites for the screen to blit partially."""
    img = Image.new("RGBA", (256, 256), TRANSPARENT)
    d = ImageDraw.Draw(img)
    panel = (58, 52, 60)
    rng = random.Random(21)
    _noise(d, 176, 166, panel, rng, 0.94, 1.06)
    d.rectangle([0, 0, 175, 165], outline=_shade(CRIMSON, 0.8))
    d.rectangle([1, 1, 174, 164], outline=_shade(panel, 1.2))

    def slot(sx, sy, accent=None):
        d.rectangle([sx, sy, sx + 17, sy + 17], fill=_shade(panel, 0.7))
        d.rectangle([sx, sy, sx + 17, sy + 17], outline=_shade(panel, 0.5))
        d.rectangle([sx + 1, sy + 1, sx + 16, sy + 16], fill=_shade(panel, 0.55))
        if accent:
            d.rectangle([sx, sy, sx + 17, sy + 17], outline=(*accent, 255))

    slot(55, 16, CRIMSON)        # input (star-forged steel)
    slot(55, 52, (150, 20, 24))  # fuel (dragon blood)
    slot(115, 34, EMBER)         # output (dragon-lord steel)
    # arrow groove
    for x in range(79, 103):
        d.point((x, 41), fill=_shade(panel, 0.5))
        d.point((x, 42), fill=_shade(panel, 0.5))
    # flame recess under the fuel slot
    d.rectangle([56, 36, 69, 49], fill=(24, 14, 14, 255))
    # player inventory + hotbar slots
    for row in range(3):
        for col in range(9):
            slot(7 + col * 18, 83 + row * 18)
    for col in range(9):
        slot(7 + col * 18, 141)
    # --- sprites the screen blits partially ---
    # full flame at (176,0) 14x14
    fr = random.Random(7)
    for x in range(14):
        for y in range(14):
            t = 1 - y / 13.0
            if fr.random() < 0.25 + 0.6 * t:
                col = (255, 210, 90) if t > 0.6 else (EMBER if t > 0.3 else CRIMSON)
                d.point((176 + x, y), fill=(*col, 255))
    # full arrow at (176,14) 24x17 (crimson->ember)
    for x in range(24):
        for y in range(17):
            body = (5 <= y <= 11 and x <= 16) or (abs(y - 8) <= (17 - x) and x > 16 and x <= 23)
            if body:
                t = x / 23.0
                col = tuple(int(CRIMSON[i] + (EMBER[i] - CRIMSON[i]) * t) for i in range(3))
                d.point((176 + x, 14 + y), fill=(*col, 255))
    img.save(os.path.join(GUI_DIR, "dragonlord_forge.png"))


def build_keeper():
    """Dragon Keeper profession overlay (64x64, villager UV). Dark ashen robe
    with crimson trim and a gold dragon sigil — leaves the head/face to the
    biome base. Broad-stroke UV: torso + legs + arms robe regions."""
    vdir = os.path.join(RES, "textures", "entity", "villager", "profession")
    os.makedirs(vdir, exist_ok=True)
    img = Image.new("RGBA", (64, 64), TRANSPARENT)
    d = ImageDraw.Draw(img)
    rng = random.Random(41)
    robe = (52, 44, 54)
    trim = (150, 36, 32)
    gold = (224, 186, 78)

    def cloth(x0, y0, x1, y1):
        for y in range(y0, y1):
            for x in range(x0, x1):
                d.point((x, y), fill=_shade(robe, rng.uniform(0.82, 1.12)))

    # body robe (front/back/sides band on the villager body UV) + legs
    cloth(16, 20, 40, 38)          # torso wrap
    cloth(0, 20, 16, 32)           # overlay: right leg
    cloth(0, 36, 16, 48)           # overlay: left leg
    cloth(40, 20, 56, 35)          # arms
    # crimson sash down the chest + gold sigil
    for y in range(20, 34):
        d.point((26, y), fill=(*trim, 255))
        d.point((27, y), fill=(*trim, 255))
    d.point((26, 24), fill=(*gold, 255)); d.point((27, 24), fill=(*gold, 255))
    d.point((25, 25), fill=(*gold, 255)); d.point((28, 25), fill=(*gold, 255))
    d.point((26, 26), fill=(*gold, 255)); d.point((27, 26), fill=(*gold, 255))
    # hood band on the head base (partial, not the face)
    for x in range(16, 40):
        d.point((x, 20), fill=(*trim, 255))
    img.save(os.path.join(vdir, "dragon_keeper.png"))
    with open(os.path.join(vdir, "dragon_keeper.png.mcmeta"), "w") as fh:
        fh.write('{\n "villager": {\n  "hat": "none"\n }\n}\n')


def build_preview(previews):
    cols = 10
    rows = (len(previews) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * 20, rows * 20), (30, 32, 38, 255))
    for i, (name, im) in enumerate(previews):
        sheet.paste(im.resize((16, 16)), (20 * (i % cols) + 2, 20 * (i // cols) + 2))
    sheet.resize((cols * 20 * 4, rows * 20 * 4), Image.NEAREST).save(
        os.path.join(HERE, "preview.png"))


if __name__ == "__main__":
    # Dragon-lord armour ICONS are authored in the Claude Design project and
    # imported directly, so don't regenerate them here (that would re-animate
    # and clobber the designed sprites). Weapons/tools/steel still come from here.
    imported = {("dragonlord", p) for p in ("helmet", "chestplate", "leggings", "boots")}
    previews = []
    for name, tier in (("star_forged", STAR), ("dragonlord", LORD)):
        previews.append((f"{name}_steel", save_item(f"{name}_steel", ingot(), tier)))
        for part, fn in SHAPES.items():
            if (name, part) in imported:
                continue
            previews.append((f"{name}_{part}", save_item(f"{name}_{part}", fn(), tier)))
    previews.append(("star_dust", save_item("star_dust", dust(), DUST)))
    previews.append(("dragon_blood", build_blood()))
    build_ore(); build_forge_block(); build_gui(); build_keeper()
    build_preview(previews)
    print("wrote item/block/armour textures into", RES)
