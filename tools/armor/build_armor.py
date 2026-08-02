"""Procedural GeckoLib armour models for the Star-forged & Dragon-lord kits.

Vanilla armour is a flat skin on the humanoid model; to get real medieval
volume — horns, plumes, pauldrons, a helm comb — we ship custom GeckoLib
armour: one geo model + texture per SET, over the standard armour bones
(armorHead / armorBody / armorLeft+RightArm / …Leg / …Boot) so GeckoLib maps
each segment onto the wearer. Base shells sit at the vanilla armour pivots
(so alignment is correct by construction); each tier then adds its own
character cubes.

  Star-forged = ornate bronze/gold (Knight of the Seven Kingdoms): a crested
                helm comb, rounded pauldrons, a gorget ring.
  Dragon-lord = near-black Targaryen plate: swept-back dragon horns, gold-
                trimmed pauldrons, a crimson-veined cuirass.

Emits per tier:
  geckolib/models/armor/<tier>.geo.json        - bedrock geometry (box UV)
  geckolib/animations/armor/<tier>.animation.json - idle loop (static)
  textures/entity/armor/<tier>.png             - 64x64 armour sheet
and a scaled *_sheet.png preview beside this script.

Run:  python build_armor.py
"""
import json
import math
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "..", "src", "main",
                                    "resources", "assets", "allunderheaven"))
GEO_DIR = os.path.join(RES, "geckolib", "models", "armor")
ANIM_DIR = os.path.join(RES, "geckolib", "animations", "armor")
TEX_DIR = os.path.join(RES, "textures", "entity", "armor")
for d in (GEO_DIR, ANIM_DIR, TEX_DIR):
    os.makedirs(d, exist_ok=True)

TEX_W, TEX_H = 64, 64


# ---- palettes (match the 2D item kit) -------------------------------------

# Hue-shifted ramps (0=deep shadow .. 4=highlight), the same material
# vocabulary as the 2D item kit so worn armour and inventory icons read as one
# metal. Each cube's `paint` role selects a ramp; faces are shaded by form.
DARKPLATE = [(20, 20, 28), (36, 38, 50), (58, 62, 78), (98, 104, 124), (152, 162, 184)]
BRONZE_R  = [(56, 32, 18), (100, 60, 30), (150, 100, 52), (198, 148, 88), (236, 198, 138)]
GILT      = [(94, 56, 20), (150, 100, 34), (206, 152, 54), (236, 194, 98), (255, 236, 160)]
BLOOD     = [(64, 10, 20), (120, 20, 28), (182, 36, 34), (224, 92, 46), (255, 168, 78)]
HORN      = [(8, 8, 12), (16, 16, 22), (26, 26, 34), (44, 46, 58), (72, 76, 92)]


class Look:
    """A tier's material mix as hue-shifted ramps."""
    def __init__(self, base, trim, accent, horn):
        self.base = base      # plate body
        self.trim = trim      # gilded bands / rivets
        self.accent = accent  # tier pop (crimson blood / deep gold)
        self.horn = horn      # horns / comb


BRONZE = Look(base=BRONZE_R, trim=GILT, accent=GILT, horn=BRONZE_R)
TARGARYEN = Look(base=DARKPLATE, trim=GILT, accent=BLOOD, horn=HORN)


def _ramp_for(look, paint):
    return {"plate": look.base, "trim": look.trim, "accent": look.accent,
            "horn": look.horn, "plate_trim": look.base}[paint]


# ---- geometry model --------------------------------------------------------
# Authoring space = vanilla armour space (bedrock, y up, feet y=0, neck y=24),
# so a cube's origin is its min corner. GeckoLib copies the wearer's matching
# body-part transform onto each named bone, so these shells land on the body.

class Cube:
    def __init__(self, bone, origin, size, uv, inflate=0.0, mirror=False,
                 rot=None, pivot=None, paint="plate"):
        self.bone = bone
        self.origin = origin          # min corner (x,y,z)
        self.size = size              # (w,h,d)
        self.uv = uv                  # (u,v) box-uv anchor
        self.inflate = inflate
        self.mirror = mirror
        self.rot = rot                # (rx,ry,rz) deg about pivot
        self.pivot = pivot
        self.paint = paint            # which painter to use


BONES = [
    ("armorHead", (0, 24, 0)),
    ("armorBody", (0, 24, 0)),
    ("armorRightArm", (-5, 22, 0)),
    ("armorLeftArm", (5, 22, 0)),
    ("armorRightLeg", (-1.9, 12, 0)),
    ("armorLeftLeg", (1.9, 12, 0)),
    ("armorRightBoot", (-1.9, 12, 0)),
    ("armorLeftBoot", (1.9, 12, 0)),
]


def base_shell():
    """The eight vanilla-aligned armour shells (helmet, cuirass, sleeves,
    cuisses, sabatons). UVs use the classic 64x32 armour layout."""
    return [
        Cube("armorHead", (-4, 24, -4), (8, 8, 8), (0, 0), inflate=1.0),
        Cube("armorBody", (-4, 12, -2), (8, 12, 4), (16, 16), inflate=1.0),
        Cube("armorRightArm", (-8, 12, -2), (4, 12, 4), (40, 16), inflate=1.0),
        Cube("armorLeftArm", (4, 12, -2), (4, 12, 4), (40, 16), inflate=1.0, mirror=True),
        Cube("armorRightLeg", (-3.9, 0, -2), (4, 12, 4), (0, 16), inflate=0.5),
        Cube("armorLeftLeg", (-0.1, 0, -2), (4, 12, 4), (0, 16), inflate=0.5, mirror=True),
        Cube("armorRightBoot", (-3.9, 0, -2), (4, 5, 4), (0, 16), inflate=1.0),
        Cube("armorLeftBoot", (-0.1, 0, -2), (4, 5, 4), (0, 16), inflate=1.0, mirror=True),
    ]


def _sternum():
    # a raised central ridge down the cuirass (accent: crimson / gold)
    return Cube("armorBody", (-1, 13.5, -3), (2, 8, 1), None, paint="accent")


def _gorget():
    # gilded collar ring at the neck
    return Cube("armorBody", (-4.3, 22.5, -2.7), (8.6, 1.5, 5.4), None, paint="trim")


def _spaulders(paint):
    # small FLUSH shoulder caps (not the old flaring pauldrons): sit on the
    # top of each sleeve, barely proud of it.
    return [
        Cube("armorRightArm", (-8.5, 22.5, -2.5), (5, 2, 5), None, paint=paint),
        Cube("armorLeftArm", (3.5, 22.5, -2.5), (5, 2, 5), None, mirror=True, paint=paint),
    ]


def _horns():
    """A bold pair of dragon horns off the upper-rear temples. Each is three
    tapering segments that step UP and BACK (+z) by position — so the sweep
    reads even before any rotation — with a little extra back-tilt on top."""
    segs = [((-4.7, 30.0, 1.0), (2.5, 3.0, 2.5), 16),
            ((-4.5, 32.6, 2.6), (2.0, 3.0, 2.0), 32),
            ((-4.2, 35.0, 4.4), (1.5, 3.2, 1.5), 50)]
    root = (-3.3, 30.0, 2.0)
    cubes = []
    for org, size, ang in segs:
        cubes.append(Cube("armorHead", org, size, None,
                          rot=(ang, 0, 0), pivot=root, paint="horn"))
        cubes.append(Cube("armorHead", (-org[0] - size[0], org[1], org[2]), size,
                          None, rot=(ang, 0, 0),
                          pivot=(-root[0], root[1], root[2]), mirror=True, paint="horn"))
    return cubes


def dragonlord_extras():
    """Sternum ridge + gorget, bold back-swept horns, flush gold caps.
    (The helm's face is a painted visor slit + gold brow, not extra geometry.)"""
    return [_sternum(), _gorget()] + _horns() + _spaulders("trim")


def starforged_extras():
    """Sternum + gorget, a knightly helm comb, flush bronze caps."""
    comb = Cube("armorHead", (-0.5, 32, -3), (1, 3.5, 6), None, paint="accent")
    return [_sternum(), _gorget(), comb] + _spaulders("plate_trim")


# ---- box-UV footprint + packing -------------------------------------------

def footprint(size):
    w, h, d = size
    return (2 * d + 2 * w, d + h)


def pack_extra_uvs(cubes):
    """Assign box-UV anchors to any cube whose uv is None, packing them into
    the free lower band of the sheet (y>=32)."""
    x, y, row_h = 0, 33, 0
    for c in cubes:
        if c.uv is not None:
            continue
        fw, fh = footprint(c.size)
        if x + fw > TEX_W:
            x, y, row_h = 0, y + row_h + 1, 0
        c.uv = (x, y)
        x += fw + 1
        row_h = max(row_h, fh)


# ---- geo.json emission -----------------------------------------------------

def export_geo(path, identifier, cubes):
    by_bone = {}
    for c in cubes:
        by_bone.setdefault(c.bone, []).append(c)
    bones_json = []
    for name, pivot in BONES:
        entry = {"name": name, "pivot": list(pivot)}
        cl = []
        for c in by_bone.get(name, []):
            cube = {
                "origin": list(c.origin),
                "size": list(c.size),
                "uv": list(c.uv),
            }
            if c.inflate:
                cube["inflate"] = c.inflate
            if c.mirror:
                cube["mirror"] = True
            if c.rot:
                cube["pivot"] = list(c.pivot)
                # GeckoLib/bedrock negate the X and Y euler vs authoring space
                # (the same law the dragon exporter uses); Z passes straight.
                cube["rotation"] = [-c.rot[0], -c.rot[1], c.rot[2]]
            cl.append(cube)
        if cl:
            entry["cubes"] = cl
        bones_json.append(entry)
    doc = {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": identifier,
                "texture_width": TEX_W,
                "texture_height": TEX_H,
                "visible_bounds_width": 3,
                "visible_bounds_height": 4,
                "visible_bounds_offset": [0, 1.5, 0],
            },
            "bones": bones_json,
        }],
    }
    with open(path, "w") as f:
        json.dump(doc, f, indent=1)


def export_anim(path):
    # Static plate: an empty looping idle holds the model in its rest pose.
    # GeckoLib resolves getAnimationResource -> geckolib/animations/<p>.animations.json
    doc = {"format_version": "1.8.0",
           "animations": {"idle": {"loop": True, "animation_length": 1.0}}}
    with open(path, "w") as f:
        json.dump(doc, f, indent=1)


# ---- texture painting ------------------------------------------------------

def _sh(c, f):
    return (max(0, min(255, int(c[0] * f))), max(0, min(255, int(c[1] * f))),
            max(0, min(255, int(c[2] * f))), 255)


def paint(look, cubes):
    img = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
    px = img.load()
    for c in cubes:
        ramp = _ramp_for(look, c.paint)
        u, v = int(c.uv[0]), int(c.uv[1])
        w, h, dd = (int(round(s)) for s in c.size)
        fw, fh = (int(round(s)) for s in footprint(c.size))
        # box-uv footprint: top strip = cap faces (lit), the H-tall band below
        # is the four sides; give it a left-lit / right-shaded bevel.
        for yy in range(fh):
            for xx in range(fw):
                if yy < dd:
                    idx = 3                       # top/bottom caps catch light
                elif yy >= dd + h:
                    idx = 1                       # underside band
                elif xx == 0:
                    idx = 3                       # left bevel highlight
                elif xx >= fw - 1:
                    idx = 1                       # right bevel shade
                else:
                    idx = 2
                px[u + xx, v + yy] = (*ramp[idx], 255)
        _detail(px, look, c, u + dd, v + dd, w, h)
    return img


def _detail(px, look, c, fx, fy, w, h):
    """Front-face detailing: bevel, gilded bands, rivets, visor, accent stripe."""
    base = _ramp_for(look, c.paint)

    def put(x, y, col):
        if 0 <= fx + x < TEX_W and 0 <= fy + y < TEX_H:
            px[fx + x, fy + y] = (*col, 255)

    if c.paint in ("trim", "horn", "accent"):
        for x in range(w):                            # lit top edge
            put(x, 0, base[4])
        return
    for y in range(h):                                # plate bevel
        put(0, y, base[3])
        put(w - 1, y, base[1])
    for x in range(w):
        put(x, 0, base[3])
        put(x, h - 1, base[1])
    if c.bone == "armorBody" and h >= 8:
        for x in range(w):                            # gilded collar + hem bands
            put(x, 0, look.trim[3])
            put(x, h - 1, look.trim[2])
        for y in range(2, h - 2):                     # central accent stripe (dithered)
            put(w // 2, y, look.accent[2 + (y % 2)])
        put(1, 2, look.trim[1])                       # rivets
        put(w - 2, 2, look.trim[1])
    if c.bone == "armorHead" and h >= 6:
        for x in range(w):                            # visor slit (dark)
            put(x, h - 3, base[0])
        for x in range(0, w, 2):                      # brow trim
            put(x, 1, look.trim[3])


# ---- build -----------------------------------------------------------------

def _rotv(p, pivot, rot):
    """Rotate point p about pivot by bedrock euler (deg), order X then Y then Z."""
    x, y, z = p[0] - pivot[0], p[1] - pivot[1], p[2] - pivot[2]
    for axis, ang in zip("xyz", rot):
        a = math.radians(ang)
        c, s = math.cos(a), math.sin(a)
        if axis == "x":
            y, z = y * c - z * s, y * s + z * c
        elif axis == "y":
            x, z = x * c + z * s, -x * s + z * c
        else:
            x, y = x * c - y * s, x * s + y * c
    return (x + pivot[0], y + pivot[1], z + pivot[2])


def _faces(c):
    (ox, oy, oz), (w, h, dd) = c.origin, c.size
    g = c.inflate
    x0, y0, z0 = ox - g, oy - g, oz - g
    x1, y1, z1 = ox + w + g, oy + h + g, oz + dd + g
    v = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
         (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    if c.rot:
        v = [_rotv(p, c.pivot, c.rot) for p in v]
    quads = [((0, 1, 2, 3), (0, 0, -1)), ((4, 5, 6, 7), (0, 0, 1)),
             ((0, 1, 5, 4), (0, -1, 0)), ((3, 2, 6, 7), (0, 1, 0)),
             ((0, 3, 7, 4), (-1, 0, 0)), ((1, 2, 6, 5), (1, 0, 0))]
    return [([v[i] for i in idx], n) for idx, n in quads]


def _view(p, yaw, pitch):
    a, b = math.radians(yaw), math.radians(pitch)
    x, y, z = p
    x, z = x * math.cos(a) + z * math.sin(a), -x * math.sin(a) + z * math.cos(a)
    y, z = y * math.cos(b) - z * math.sin(b), y * math.sin(b) + z * math.cos(b)
    return (x, y, z)


# Steve body parts (bedrock coords), drawn as a skin reference under the armour
# so the preview shows true FIT and bulk, not floating shells.
BODY_REF = [((-4, 24, -4), (8, 8, 8)), ((-4, 12, -2), (8, 12, 4)),
            ((-8, 12, -2), (4, 12, 4)), ((4, 12, -2), (4, 12, 4)),
            ((-4, 0, -2), (4, 12, 4)), ((0, 0, -2), (4, 12, 4))]


def render_preview(tier, look, extras, yaw, pitch, scale=13):
    W, H = 260, 480
    img = Image.new("RGBA", (W, H), (26, 28, 34, 255))
    d = ImageDraw.Draw(img)
    cx, cy = W // 2, 44
    light = (-0.4, 0.7, -0.55)
    ln = math.sqrt(sum(k * k for k in light))
    light = tuple(k / ln for k in light)
    prims = []

    def emit(cubes, colour_fn):
        for c in cubes:
            col = colour_fn(c)
            for verts, n in _faces(c):
                vv = [_view(p, yaw, pitch) for p in verts]
                nv = _view(n, yaw, pitch)
                depth = sum(p[2] for p in vv) / 4.0
                shade = 0.55 + 0.45 * max(0.0, -(nv[0] * light[0]
                                                 + nv[1] * light[1] + nv[2] * light[2]))
                pts = [(cx + p[0] * scale, cy + (34 - p[1]) * scale) for p in vv]
                prims.append((depth, pts, _sh(col, shade)))

    emit([Cube("_body", o, s, None) for o, s in BODY_REF], lambda c: (156, 132, 118))
    emit(base_shell() + extras(), lambda c: _ramp_for(look, c.paint)[2])

    for _, pts, col in sorted(prims, key=lambda t: t[0]):
        d.polygon(pts, fill=col, outline=(0, 0, 0, 80))
    return img


def build(tier, look, extras):
    cubes = base_shell() + extras()
    pack_extra_uvs(cubes)
    export_geo(os.path.join(GEO_DIR, f"{tier}.geo.json"),
               f"geometry.allunderheaven.{tier}_armor", cubes)
    export_anim(os.path.join(ANIM_DIR, f"{tier}.animations.json"))
    img = paint(look, cubes)
    img.save(os.path.join(TEX_DIR, f"{tier}.png"))
    return img


# Only Star-forged is generated here. Dragon-lord's geo model + worn texture +
# item icons are authored in the Claude Design project ("Dragon-lord Armour")
# and imported directly, so this generator must NOT overwrite them.
TIERS = (("star_forged", BRONZE, starforged_extras),)

if __name__ == "__main__":
    sheets = [(tier, build(tier, look, extras)) for tier, look, extras in TIERS]
    scale = 8
    prev = Image.new("RGBA", (TEX_W * scale * len(sheets) + scale, TEX_H * scale),
                     (30, 32, 38, 255))
    for i, (tier, im) in enumerate(sheets):
        prev.paste(im.resize((TEX_W * scale, TEX_H * scale), Image.NEAREST),
                   (i * (TEX_W * scale + scale), 0))
    prev.save(os.path.join(HERE, "armor_sheet.png"))

    # 3D geometry preview (front / 3-4 / side) for the procedural tiers
    views = ((0, 0), (32, 12), (90, 0))
    tile_w, tile_h = 260, 480
    grid = Image.new("RGBA", (tile_w * 3, tile_h * len(TIERS)), (26, 28, 34, 255))
    for r, (tier, look, extras) in enumerate(TIERS):
        for cidx, (yaw, pitch) in enumerate(views):
            grid.paste(render_preview(tier, look, extras, yaw, pitch),
                       (cidx * tile_w, r * tile_h))
    grid.save(os.path.join(HERE, "armor_model.png"))
    print("wrote Star-forged armour (Dragon-lord is imported from Claude Design)")
