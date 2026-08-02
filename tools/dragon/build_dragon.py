"""Procedural wyvern model generator for All Under Heaven.

Defines semirealistic, House-of-the-Dragon-style wyverns (two hind legs,
wings as forelimbs) as a bone hierarchy + axis-aligned cuboids, in three
variants, and emits for each:

  out/wyvern_<variant>.bbmodel        - Blockbench project (editable master,
                                        texture + animations embedded)
  out/wyvern_<variant>.png            - painted 1024x1024 texture sheet
  out/wyvern_<variant>.geo.json       - bedrock geometry for GeckoLib
  out/wyvern_<variant>.animation.json - GeckoLib flight animations
  out/<variant>_*.png                 - software-rendered preview stills
  out/<variant>_fly*.gif / *_sheet.png - animated cycle previews

Variants:
  red   - baseline: brick hide, amber eyes, stock proportions
  black - grandiose: 1.69x four-fingered wings, grey membranes/eyes
  white - ethereal: slim, 7-segment neck, 9-segment tail, whiskers

Design rules that keep the models animation-ready:
  * All rest-pose orientation lives on BONES (groups), not cubes, so GeckoLib
    keyframes rotate the same pivots Blockbench shows.
  * Child pivots are authored UNROTATED (Blockbench semantics: a parent's
    rest rotation carries its children) - chains advance in straight steps
    and curves come purely from nested bone rotations.
  * Bones prefer a single rotation axis in rest pose; multi-axis rotations
    (wings) keep angles modest so euler-order differences stay invisible.
  * The wyvern faces NORTH (-Z), vanilla entity convention: head -Z, tail +Z.
  * 16 units = 1 block. Ground is y=0 (toes auto-grounded after build).

Run:  python build_dragon.py
"""

from __future__ import annotations

import base64
import json
import math
import os
import random
import uuid
from dataclasses import dataclass, field

from PIL import Image, ImageDraw, ImageStat

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
# The mod's resource tree: run with --install (or INSTALL=1) to copy the
# runtime artifacts (geo, animations, textures, sprites) into the mod so the
# game ships exactly what this generator produced. GeckoLib 5 conventions:
# geckolib/models/<path>.geo.json + geckolib/animations/<path>.animations.json
# (note the PLURAL .animations.json - the 5.x loader strips that suffix).
RES_DIR = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..",
    "src", "main", "resources", "assets", "allunderheaven"))
TEX_W, TEX_H = 2048, 2048
SCALE = 1.35  # adult sizing: all authored geometry scales up at emit time,
              # which also buys texture density (texels are per unit)
WING_BASE = 1.5   # base wing template multiplier (GoT wings dominate the
                  # silhouette and must reach the ground when planted);
                  # variant wing_scale stacks on top

# Form spec, from the wyvern form review: proportions are quoted in SKULL
# LENGTHS (S). The review's own S was eyeballed off perspective renders and
# is not self-consistent between regions, so we anchor S on the one dimension
# it is most emphatic about - chest depth, "the chest IS the animal", 1.1 S
# now going to 1.35 S - which puts S at ~20 authoring units and turns every
# other prescription into a buildable multiplier instead of a rebuild.
# Ratios WITHIN a region (neck taper 0.80 -> 0.32, skull depth 0.72 -> 0.34,
# digits 4.3/3.6/3.1/2.8) are taken from the review verbatim.
S = 20.0

# Skull scale about the head root. Referenced by _solve_head_low and
# export_rig too - it used to be a bare 1.18 duplicated across all three,
# so the head could not be resized without silently desyncing the rig.
HEAD_SCALE = 1.06

# ---------------------------------------------------------------- model data


@dataclass
class Bone:
    name: str
    parent: str | None
    pivot: tuple[float, float, float]
    rot: tuple[float, float, float] = (0.0, 0.0, 0.0)  # degrees, applied Z*Y*X
    color: tuple[int, int, int] = (140, 140, 140)  # preview only
    uuid: str = ""  # stable id shared by outliner + animation animators


@dataclass
class Cube:
    bone: str
    lo: tuple[float, float, float]
    hi: tuple[float, float, float]
    inflate: float = 0.0
    mirror: bool = False
    color: tuple[int, int, int] | None = None  # preview override (else bone color)
    uv: tuple[int, int] = (0, 0)  # filled by the packer
    rot: tuple[float, float, float] = (0.0, 0.0, 0.0)  # per-cube rotation (deg)
    origin: tuple[float, float, float] = (0.0, 0.0, 0.0)  # its pivot (scaled)
    carve: bool = False  # membrane plane whose silhouette the texture alpha cuts
    # per-face UV rects (x1,y1,x2,y2), read straight from the authored model
    uvmap: dict = field(default_factory=dict)


@dataclass
class Variant:
    name: str
    hide: tuple[int, int, int]
    hide_dark: tuple[int, int, int]
    membrane: tuple[int, int, int]
    horn: tuple[int, int, int]
    ridge: tuple[int, int, int]
    eye: tuple[int, int, int]
    socket: tuple[int, int, int]
    teeth: tuple[int, int, int]
    belly: tuple[int, int, int] = (200, 170, 140)  # scutes / throat plates
    iris: tuple[int, int, int] = (255, 210, 40)    # vibrant ring round the slit
    wing_scale: float = 1.0
    #          (yaw from straight-out, length) per finger spar, at the review's
    #          descending 4.3 / 3.6 / 3.1 / 2.8 ratio so the front spar owns
    #          the tip and the trailing edge rakes down toward the hip
    fingers: tuple = ((14, 46), (38, 38.5), (66, 33))
    tail_scale: float = 1.0
    girth: float = 1.0        # body width multiplier (ethereal = slim)
    whiskers: bool = False    # trailing snout barbels
    #             (pitch, length, width, height) per neck segment. Shorter than
    #             it was and tapered HARD (0.80 S at the base -> 0.32 S at the
    #             atlas); the curvature is loaded into the last joint at the
    #             head rather than spread as an even circular arc. Pitch sum is
    #             load-bearing - build_head and _solve_head_low both key off it.
    neck: tuple = ((30, 12, 16, 15), (22, 12, 13, 12.5), (2, 11, 10, 10),
                   (-30, 10, 6.4, 7))
    #             (pitch, length, width, height) per tail segment (None = stock)
    tail: tuple | None = None
    #             breath gradient (core, mid, outer) - each dragon burns its
    #             own color; drives previews now, particle tint in-game later
    fire: tuple = ((255, 236, 150), (255, 148, 42), (208, 64, 18))


BLACK = Variant(
    # Grandiose: oversized four-fingered wings dominate the silhouette.
    name="black",
    hide=(42, 42, 48), hide_dark=(30, 30, 36),
    membrane=(128, 118, 118),       # lifted well clear of the 42-value hide
    horn=(118, 112, 126), ridge=(56, 54, 66),
    eye=(106, 100, 56), socket=(14, 12, 18),   # greenish-brown reptile eye
    iris=(250, 220, 30),                        # vibrant yellow
    teeth=(198, 192, 184),
    belly=(104, 100, 112),
    wing_scale=1.69,                # 1.3 base, then +30% per request
    fingers=((10, 48), (30, 40), (50, 34.5), (70, 31)),
    fire=((255, 138, 66), (198, 46, 28), (96, 30, 26)),  # smoky crimson ember
)

WHITE = Variant(
    # Ethereal: slim, elongated (5-segment neck, long tail), snout whiskers.
    name="white",
    hide=(226, 228, 232), hide_dark=(198, 202, 210),
    # A near-white hide cannot go 1.5 stops lighter without clipping, so the
    # white's membrane separates by HUE and saturation instead - warm rose
    # against a cold blue-white body.
    membrane=(240, 214, 204),
    horn=(240, 236, 224), ridge=(206, 210, 218),
    eye=(160, 196, 228), socket=(58, 52, 60),  # light-blue reptile eye
    iris=(64, 140, 255),                        # vibrant blue
    teeth=(214, 206, 188),
    belly=(247, 244, 237),
    wing_scale=1.0,
    girth=0.9,
    whiskers=True,
    # 7-segment neck: same root (16x15) and tip (6.4x7) girth as the stock
    # neck, sizes interpolated between - only the length grows. Pitch sum
    # stays 14 so the head carries the same rest angle, and as with the stock
    # neck the curvature is loaded into the last joint.
    neck=((24, 12, 16, 15), (20, 12, 14.4, 13.7), (14, 11, 12.8, 12.3),
          (6, 11, 11.2, 11.0), (0, 11, 9.6, 9.7), (-14, 10, 8.0, 8.3),
          (-36, 9, 6.4, 7.0)),
    # 9-segment tail: same root (13x12) and tip (2.6x2.6) as stock, holding
    # its thickness through the first third before tapering across the extra
    # nodes. Pitch sum stays -4 (droop then upcurve).
    tail=((14, 14, 13, 12), (10, 14, 12.6, 11.7), (6, 14, 12.0, 11.2),
          (2, 14, 10.8, 10.1), (-3, 13, 9.2, 8.6), (-7, 13, 7.4, 6.9),
          (-9, 12, 5.4, 5.1), (-9, 12, 3.8, 3.6), (-8, 12, 2.6, 2.6)),
    fire=((228, 246, 255), (130, 190, 255), (56, 110, 225)),  # ice-blue flame
)

RED = Variant(
    # The baseline wyvern: classic brick-red hide, amber eyes, stock build.
    name="red",
    hide=(146, 46, 38), hide_dark=(106, 30, 26),
    membrane=(214, 132, 96),        # ~1.5 stops above the hide, and warmer
    horn=(216, 206, 186), ridge=(92, 26, 22),
    eye=(172, 92, 24), socket=(28, 10, 8),     # dark-orange reptile eye
    iris=(255, 214, 40),                        # vibrant yellow
    teeth=(226, 218, 202),
    belly=(216, 166, 124),
)

BONES: list[Bone] = []
CUBES: list[Cube] = []


def reset():
    BONES.clear()
    CUBES.clear()
    FACE_COLORS.clear()


# --------------------------------------------------------------- geo loader
#
# THE MODEL IS NO LONGER GENERATED. `wyvern_<variant>.geo.json` and its .png
# are hand-authored source of truth, edited in Blockbench and read from the
# mod resource tree; this script only DERIVES from them (animations, the pose
# -solver rig sidecar, and the preview renders). Nothing here writes geometry
# or texture, so hand edits survive every run.

# Flat per-face colours for the preview renderer, sampled from the authored
# texture by sample_face_colors(). Keyed (cube index, face name).
FACE_COLORS: dict[tuple[int, str], tuple[int, int, int]] = {}


def _norm_rect(r):
    x1, y1, x2, y2 = r
    return (min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))


def _face_rects(c: Cube):
    """Per-face UV rects, now READ from the authored model rather than packed.
    Kept as an accessor so the bbmodel export and the colour sampler did not
    have to change when the packer went away."""
    return c.uvmap


def load_geo(path: str):
    """Populates BONES/CUBES from an authored .geo.json - the exact inverse of
    the old export_geo, including its mirror law: pivots and origins negate X,
    rotations are (-x, -y, +z), and east/west UV rects trade places.

    Values are stored already-scaled. SCALE was baked in when the geometry was
    authored, so nothing is multiplied on the way in."""
    doc = json.load(open(path))
    geo = doc["minecraft:geometry"][0]
    swap = {"east": "west", "west": "east"}
    for b in geo["bones"]:
        p = b["pivot"]
        r = b.get("rotation", [0, 0, 0])
        BONES.append(Bone(b["name"], b.get("parent"),
                          (-p[0], p[1], p[2]), (-r[0], -r[1], r[2]),
                          (140, 140, 140), str(uuid.uuid4())))
        for c in b.get("cubes", []):
            o, s = c["origin"], c["size"]
            hi_x = -o[0]
            uvmap = {}
            for fname, fd in c.get("uv", {}).items():
                (ux, uy), (uw, uh) = fd["uv"], fd["uv_size"]
                uvmap[swap.get(fname, fname)] = (ux, uy, ux + uw, uy + uh)
            cr = c.get("rotation", [0, 0, 0])
            cp = c.get("pivot", [0, 0, 0])
            CUBES.append(Cube(
                b["name"], (hi_x - s[0], o[1], o[2]),
                (hi_x, o[1] + s[1], o[2] + s[2]),
                c.get("inflate", 0.0), c.get("mirror", False), None,
                uv=(0, 0), rot=(-cr[0], -cr[1], cr[2]),
                origin=(-cp[0], cp[1], cp[2]), uvmap=uvmap))


def sample_face_colors(png_path: str):
    """Flat per-face colours for the preview renderer, sampled from the
    AUTHORED texture. Alpha-weighted: a plain convert('RGB') counts carved-away
    pixels as black and drags every membrane far darker than it renders."""
    img = Image.open(png_path).convert("RGBA")
    FACE_COLORS.clear()
    for i, c in enumerate(CUBES):
        for fname, rect in _face_rects(c).items():
            r = _norm_rect(rect)
            if r[2] - r[0] < 1 or r[3] - r[1] < 1:
                continue
            crop = img.crop(r)
            if crop.getchannel("A").getextrema()[1] == 0:
                continue
            mean = ImageStat.Stat(crop.convert("RGB"),
                                  mask=crop.getchannel("A")).mean
            FACE_COLORS[(i, fname)] = tuple(int(x) for x in mean)


def sync_variant_to_geo(v: Variant):
    """Re-derives the per-segment REST ANGLES the animations key off from the
    bones actually present in the loaded model.

    This is the join that keeps hand edits safe. The animation code reads
    v.neck/v.tail/v.fingers for segment COUNTS and for each segment's rest
    pitch or yaw; those tables used to also drive geometry, so they agreed by
    construction. Now that geometry is authored by hand they would silently go
    stale - add a neck segment in Blockbench and the neck would animate as
    though it were still the old length. So the tables are rebuilt FROM the
    geometry on every load, and the hand-authored model always wins."""
    by = {b.name: b for b in BONES}

    def chain(prefix):
        out, i = [], 1
        while f"{prefix}{i}" in by:
            out.append(by[f"{prefix}{i}"])
            i += 1
        return out

    neck = chain("neck")
    tail = chain("tail")
    if not neck:
        raise SystemExit(f"{v.name}: no neck1 bone in the authored model")
    # only the rest angle is consumed downstream; lengths/girths died with the
    # generator, so they are zero-filled rather than faked
    v.neck = tuple((b.rot[0], 0, 0, 0) for b in neck)
    v.tail = tuple((b.rot[0], 0, 0, 0) for b in tail) or None
    fingers, fi = [], 1
    while f"wing_l_finger{fi}" in by:
        fingers.append((-by[f"wing_l_finger{fi}"].rot[1], 0))
        fi += 1
    if not fingers:
        raise SystemExit(f"{v.name}: no wing_l_finger1 bone in the authored model")
    v.fingers = tuple(fingers)


def check_grounded(v: Variant, tol=0.75):
    """The authored model is expected to stand on y=0. Warns rather than
    shifting anything - moving hand-authored geometry behind Bruno's back is
    exactly what this refactor exists to stop."""
    transforms = bone_world_transform()
    min_y = math.inf
    for c in CUBES:
        if "toes" not in c.bone:
            continue
        fn = transforms[c.bone]
        for x in (c.lo[0], c.hi[0]):
            for y in (c.lo[1], c.hi[1]):
                for z in (c.lo[2], c.hi[2]):
                    min_y = min(min_y, fn((x, y, z))[1])
    if math.isfinite(min_y) and abs(min_y) > tol:
        print(f"  ! {v.name}: toes rest at y={min_y:.2f}, not 0 - the walk and "
              f"idle stances solve against the ground plane and will float or "
              f"sink by that much.")



# ---------------------------------------------------------- flight animation

FLY_LEN = 2.0   # seconds per wingbeat at wing_scale 1.0 (the ~2 s GoT beat)
FLY_KEYS = 8    # looping channels bake to this many uniform catmullrom keys


def _skew_cos(theta, k=0.30):
    """Cosine with a skewed time axis: the fall (downstroke) runs faster than
    the rise (recovery) - the wing powers down and floats back up."""
    return math.cos(theta + k * math.sin(theta))


def _two_point(theta, k=0.32, flat=1.5):
    """Skewed cosine squashed toward its extremes (tanh): the motion DWELLS
    at the two end poses and snaps through the middle - a two-point beat."""
    return math.tanh(flat * _skew_cos(theta, k)) / math.tanh(flat)


def _tuck_legs(rot):
    """Flight leg tuck: femur swept well back, shin near-horizontal, foot
    pointing aft, toes drooping relaxed (world pitches approx -52/-74/-86).
    Shared by every airborne animation so in-game blending never fights."""
    for side in ("l", "r"):
        rot(f"leg_{side}_thigh", (-80, 0, 0))
        rot(f"leg_{side}_shin", (24, 0, 0))
        rot(f"leg_{side}_foot", (-30, 0, 0))
        rot(f"leg_{side}_toes", (-40, 0, 0))


def _bake(ch, T, keys=FLY_KEYS):
    """Looping channels -> keys+1 uniform catmullrom keys with an exact
    first==last seam (loops cleanly everywhere); statics -> one held key."""
    baked: dict[str, dict[str, list]] = {}
    for bname, chans in ch.items():
        for cname, f in chans.items():
            if callable(f):
                kf = [(round(k * T / keys, 4), f(k * T / keys))
                      for k in range(keys + 1)]
                kf[-1] = (kf[-1][0], kf[0][1])
            else:
                kf = [(0.0, f)]
            baked.setdefault(bname, {})[cname] = kf
    return baked


def _fly_core(v: Variant):
    """The straight-flight cycle, UNBAKED (bone -> channel -> callable), so
    derived airborne clips (fly_fire) can override individual channels before
    baking. Generalized by the variant's build: period and flap depth follow
    wing_scale (a bigger wing beats slower and shallower), the neck
    counter-sway and tail wave distribute over however many segments the
    variant actually has, whiskers trail only if present.

    Rotation keys are DELTAS on top of the rest pose, per bbmodel/GeckoLib
    semantics (keyframe 0 = bind pose)."""
    T = FLY_LEN * v.wing_scale ** 0.25
    W = 2 * math.pi / T
    amp = 30.0 / v.wing_scale ** 0.35
    ch: dict[str, dict[str, object]] = {}

    def rot(b, f):
        ch.setdefault(b, {})["rotation"] = f

    def pos(b, f):
        ch.setdefault(b, {})["position"] = f

    # wings: master flap on the humerus (Z = flap axis for an X-spar wing);
    # forearm and hand run the SAME skewed wave late and shallower, so the
    # wing visibly flexes under its own inertia. The hand also folds back a
    # touch (Y) during the recovery stroke.
    for side, sx in (("l", 1), ("r", -1)):
        rot(f"wing_{side}_arm",
            lambda t, s=sx: (0, 0, s * (-4 + amp * _skew_cos(W * t))))
        rot(f"wing_{side}_fore",
            lambda t, s=sx: (0, 0, s * 0.55 * amp * _skew_cos(W * t - 0.55)))
        rot(f"wing_{side}_hand",
            lambda t, s=sx: (0, -s * 2.5 * (1 - math.cos(W * t - 1.6)),
                             s * 0.32 * amp * _skew_cos(W * t - 1.05)))
        for fi in range(1, len(v.fingers) + 1):
            rot(f"wing_{side}_finger{fi}b",
                lambda t, s=sx: (0, -s * 2.5 * (1 - math.cos(W * t - 1.9)), 0))
            rot(f"wing_{side}_finger{fi}c",
                lambda t, s=sx: (0, -s * 1.8 * (1 - math.cos(W * t - 2.1)), 0))

    # body heaves with the lift: lowest at the top of the stroke, rising
    # through the powered downstroke; a subtle pitch rocks behind the heave
    pos("body", lambda t: (0, -1.6 * SCALE * math.cos(W * t), 0))
    rot("body", lambda t: (1.8 * math.sin(W * t - 2.1), 0, 0))

    # neck: the rest pose is the SITTING posture (upright S) - in flight each
    # segment cancels 80% of its own rest pitch, laying the neck out as a
    # near-straight forward lance, with the head offset back to level. A
    # small counter-wave travels head-ward on top; per-segment amplitude
    # divides by chain length, so long necks bend the same total arc.
    n = len(v.neck)
    ps = sum(s[0] for s in v.neck)
    for i, seg in enumerate(v.neck, 1):
        r, a, ph = seg[0], 6.0 / n, math.pi + (i / n) * math.pi
        rot(f"neck{i}",
            lambda t, r=r, a=a, ph=ph: (
                -0.8 * r + a * math.sin(W * t - ph), 0, 0))
    rot("head", lambda t: (0.8 * ps + 2.0 * math.sin(W * t - 2.1 - math.pi), 0, 0))

    if v.whiskers:
        for side in ("l", "r"):
            rot(f"whisker_{side}_1", lambda t: (7 * math.sin(W * t - 2.4), 0, 0))
            rot(f"whisker_{side}_2", lambda t: (10 * math.sin(W * t - 3.1), 0, 0))

    # tail: near-still in level cruise - just a faint ripple fading in
    # toward the tip so it doesn't read as a rigid pole
    m = len(v.tail) if v.tail else 7
    for i in range(1, m + 1):
        a = 0.35 + 1.05 * (i / m) ** 1.5
        ph = 0.7 + (i / m) * 2.3 * math.pi
        rot(f"tail{i}", lambda t, a=a, ph=ph: (a * math.sin(W * t - ph), 0, 0))
    rot("tail_fin", lambda t: (1.8 * math.sin(W * t - 1.2 - 2.3 * math.pi), 0, 0))

    _tuck_legs(rot)
    return T, ch


def fly_channels(v: Variant):
    """Level cruise: the baked _fly_core."""
    T, ch = _fly_core(v)
    return T, _bake(ch, T)


def fly_fire_channels(v: Variant):
    """The aerial hose: the cruise flap continues, but the neck arcs down,
    the head strikes toward the ground ahead and the jaw hinges wide - the
    strafing-run breath. Derived from _fly_core so the wings/body/tail stay
    in perfect sync with the plain fly clip (the controller can cut between
    them mid-beat without a hitch)."""
    T, ch = _fly_core(v)
    W = 2 * math.pi / T

    def rot(b, f):
        ch[b] = dict(ch.get(b, {}));  ch[b]["rotation"] = f

    n = len(v.neck)
    ps = sum(s[0] for s in v.neck)
    for i, seg in enumerate(v.neck, 1):
        r, f = seg[0], (i - 1) / max(1, n - 1)
        # cruise flatten stays, plus a downward arc growing toward the head
        rot(f"neck{i}",
            lambda t, r=r, f=f: (-0.8 * r - (16.0 / n) * (0.4 + 0.6 * f), 0, 0))
    rot("head", lambda t: (0.8 * ps - 24 + 1.5 * math.sin(2 * W * t), 0, 0))
    rot("jaw", lambda t: (-23 - 3.0 * (0.5 + 0.5 * math.sin(2 * W * t - 0.8)),
                          0, 0))
    return T, _bake(ch, T)


def glide_channels(v: Variant):
    """The soar: wings locked out flat with a light dihedral, slow breathing
    sway the only motion - the descent/approach posture between flap cycles.
    Neck carries the cruise flatten, legs stay tucked, tail streams."""
    T = 3.6
    W = 2 * math.pi / T
    ch: dict[str, dict[str, object]] = {}

    def rot(b, f):
        ch.setdefault(b, {})["rotation"] = f

    def pos(b, f):
        ch.setdefault(b, {})["position"] = f

    for side, sx in (("l", 1), ("r", -1)):
        rot(f"wing_{side}_arm",
            lambda t, s=sx: (0, 0, s * (2.5 + 1.6 * math.sin(W * t))))
        rot(f"wing_{side}_fore",
            lambda t, s=sx: (0, 0, s * (-1.5 + 0.9 * math.sin(W * t - 0.7))))
        rot(f"wing_{side}_hand",
            lambda t, s=sx: (0, 0, s * (-1.0 + 0.6 * math.sin(W * t - 1.2))))
        for fi in range(1, len(v.fingers) + 1):
            rot(f"wing_{side}_finger{fi}b", (0, -2.5 * sx, 0))
            rot(f"wing_{side}_finger{fi}c", (0, -1.8 * sx, 0))

    pos("body", lambda t: (0, -0.6 * SCALE * math.sin(W * t - 0.4), 0))
    rot("body", (1.5, 0, 0))  # nose gently down into the glide path

    n = len(v.neck)
    ps = sum(s[0] for s in v.neck)
    for i, seg in enumerate(v.neck, 1):
        rot(f"neck{i}", (-0.8 * seg[0], 0, 0))
    rot("head", lambda t: (0.8 * ps + 1.2 * math.sin(W * t - 1.5), 0, 0))

    if v.whiskers:
        for side in ("l", "r"):
            rot(f"whisker_{side}_1", lambda t: (6 * math.sin(W * t - 1.8), 0, 0))
            rot(f"whisker_{side}_2", lambda t: (9 * math.sin(W * t - 2.4), 0, 0))

    m = len(v.tail) if v.tail else 7
    for i in range(1, m + 1):
        a = 0.3 + 1.0 * (i / m) ** 1.5
        rot(f"tail{i}",
            lambda t, a=a, i=i: (a * math.sin(W * t - 0.8 - (i / m) * 1.8), 0, 0))
    rot("tail_fin", lambda t: (1.6 * math.sin(W * t - 2.8), 0, 0))

    _tuck_legs(rot)
    return T, _bake(ch, T)


def fly_vertical_channels(v: Variant):
    """Climbing/descending beat: a two-point wing cycle (poses held at the
    high gather and the low power-out, snapping through the middle) driving
    a swimming body. The neck breathes in ANTI-phase with the wings - wings
    push up while the neck coils down between the shoulders, then the wings
    slam down as the neck stretches skyward - with the wave rippling
    root-to-head, the body heaving and pitching nose-up on the power stroke,
    and the tail counter-curling. Same size generalization as fly_channels;
    the cycle starts just after the power-out: wings pushing up first."""
    T = 2.4 * v.wing_scale ** 0.25
    W = 2 * math.pi / T
    amp = 42.0 / v.wing_scale ** 0.35
    ch: dict[str, dict[str, object]] = {}

    def rot(b, f):
        ch.setdefault(b, {})["rotation"] = f

    def pos(b, f):
        ch.setdefault(b, {})["position"] = f

    def theta(t):
        return W * t + 3.6  # t=0: wings near the bottom, starting to rise

    def stretch(t):  # -1 = neck coiled (wings high) .. +1 = stretched (wings low)
        return -_two_point(theta(t) - 0.55)

    # wings: the two-point master on the humerus, distal lag as in cruise
    # but deeper - the whole wing gathers high, then punches down
    for side, sx in (("l", 1), ("r", -1)):
        rot(f"wing_{side}_arm",
            lambda t, s=sx: (0, 0, s * (-3 + amp * _two_point(theta(t)))))
        rot(f"wing_{side}_fore",
            lambda t, s=sx: (0, 0, s * 0.55 * amp * _two_point(theta(t) - 0.5)))
        rot(f"wing_{side}_hand",
            lambda t, s=sx: (0, -s * 3.0 * (1 - math.cos(theta(t) - 1.4)),
                             s * 0.32 * amp * _two_point(theta(t) - 0.95)))
        for fi in range(1, len(v.fingers) + 1):
            rot(f"wing_{side}_finger{fi}b",
                lambda t, s=sx: (0, -s * 3.5 * (1 - math.cos(theta(t) - 1.7)), 0))
            rot(f"wing_{side}_finger{fi}c",
                lambda t, s=sx: (0, -s * 2.4 * (1 - math.cos(theta(t) - 1.9)), 0))

    # body: deep heave lagging the stroke, nose-up surge on the power-out
    pos("body", lambda t: (0, -2.6 * SCALE * math.cos(theta(t) - 0.6), 0))
    rot("body", lambda t: (4.5 * math.sin(theta(t) - 2.6), 0, 0))

    # neck: the swim, layered on a flight base that cancels 55% of the
    # sitting rest pitch (airborne = laid out, not goose-upright). Each
    # segment then blends an upward reach with a FLATTENING of its own rest
    # pitch, so stretching straightens the S-curve into a skyward line
    # (coil returns toward the tucked sit) - and the wave ripples
    # root-to-head. Works for any segment count/curve by construction.
    n = len(v.neck)
    ps = sum(s[0] for s in v.neck)
    for i, seg in enumerate(v.neck, 1):
        r, au = seg[0], 22.0 / n
        rot(f"neck{i}",
            lambda t, r=r, au=au, i=i: (
                -0.55 * r + stretch(t - (i / n) * 0.14 * T) * (au - 0.5 * r),
                0, 0))
    rot("head", lambda t: (0.55 * ps + 9.0 * stretch(t - 0.16 * T), 0, 0))
    rot("jaw", lambda t: (-2.5 * (stretch(t - 0.16 * T) + 1) / 2, 0, 0))

    if v.whiskers:
        for side in ("l", "r"):
            rot(f"whisker_{side}_1", lambda t: (9 * math.sin(theta(t) - 2.6), 0, 0))
            rot(f"whisker_{side}_2", lambda t: (13 * math.sin(theta(t) - 3.2), 0, 0))

    # tail: pure inertia - a rope trailing the body's heave. The body dips
    # as the wings push up, and each joint follows the INVERTED dip with a
    # delay that grows down the chain and an amplitude that grows toward
    # the tip, so the up-lash ripples through the intersections one after
    # another instead of the whole tail snapping at once.
    m = len(v.tail) if v.tail else 7
    for i in range(1, m + 1):
        a = (18.0 / m) * (0.45 + 1.15 * (i / m))
        tau = (0.10 + 0.25 * (i / m)) * T
        rot(f"tail{i}",
            lambda t, a=a, tau=tau: (-a * math.cos(theta(t - tau) - 0.6), 0, 0))
    rot("tail_fin", lambda t: (-5.5 * math.cos(theta(t - 0.41 * T) - 0.6), 0, 0))

    _tuck_legs(rot)
    # denser bake: the tanh dwell/snap needs a few more keys than a sine
    return T, _bake(ch, T, keys=12)


def _smoothstep(a, b, t):
    if t <= a:
        return 0.0
    if t >= b:
        return 1.0
    u = (t - a) / (b - a)
    return u * u * (3 - 2 * u)


# Folded-wing finger fan, total local yaw per finger (front -> back), from
# Bruno's annotated reference: the HAND plants on the ground like a bat's and
# every finger radiates UP-BACK from that ground point - the front spar
# standing steepest, each one behind reclining further - so the folded wing
# reads as the zigzag skyline of standing knuckle peaks, membrane draped
# between. (-90 = horizontal-back in the planted hand's frame; toward -180
# rises to vertical.)
FAN_FRONT = -166.0
FAN_BACK = -118.0


def _solve_hind(pose_base, thigh, shin0, foot0, target_y=0.4):  # -> (thigh, shin, foot)
    """Re-solves a crouch's shin/foot deltas so the toe pads rest ON the
    ground (lowest toe corner at target_y) instead of folding through it.
    With the baked stance planted, the runtime foot IK idles at zero on
    flat ground and only acts on real terrain. Scans the shin delta with a
    partial foot counter-rotation that keeps the foot near-vertical."""
    toe_cubes = [c for c in CUBES if c.bone in ("leg_l_toes", "leg_l_foot")]

    def sole_y(d0, d1, d2):
        pose = dict(pose_base)
        for side in ("l", "r"):
            pose[f"leg_{side}_thigh"] = {"rotation": (thigh + d0, 0, 0)}
            pose[f"leg_{side}_shin"] = {"rotation": (shin0 + d1, 0, 0)}
            pose[f"leg_{side}_foot"] = {"rotation": (foot0 + d2, 0, 0)}
        tf = bone_world_transform(pose)
        return min(tf[c.bone]((x, y, z))[1] for c in toe_cubes
                   for x in (c.lo[0], c.hi[0]) for y in (c.lo[1], c.hi[1])
                   for z in (c.lo[2], c.hi[2]))

    # 3-DOF grid (thigh raises the knee, shin/foot re-plant): land the sole
    # at target_y with the smallest total joint change. Coarse pass, then a
    # fine pass around the winner.
    def scan(t_rng, s_rng, f_rng, step):
        best, best_key = None, None
        t = t_rng[0]
        while t <= t_rng[1]:
            s_ = s_rng[0]
            while s_ <= s_rng[1]:
                f_ = f_rng[0]
                while f_ <= f_rng[1]:
                    err = abs(sole_y(t, s_, f_) - target_y)
                    key = (round(err, 1), abs(t) + abs(s_) + abs(f_))
                    if best_key is None or key < best_key:
                        best_key, best = key, (t, s_, f_)
                    f_ += step
                s_ += step
            t += step
        return best

    c = scan((0.0, 36.0), (-36.0, 12.0), (-24.0, 24.0), 4.0)
    c = scan((c[0] - 3, c[0] + 3), (c[1] - 3, c[1] + 3), (c[2] - 3, c[2] + 3),
             1.0)
    return thigh + c[0], shin0 + c[1], foot0 + c[2]


def _solve_plant(pose_base, v, fore_yaw=22.0, pad=8.0):
    """The bat plant (Bruno's annotated reference): the HAND itself rests on
    the ground - shoulder to a modest elbow apex, forearm descending all the
    way down - and the fingers then stand up-back from that ground point as
    the tall knuckle-peak skyline. Ground contact = the carpal knuckle, so
    the solve targets the HAND, not any finger.

    Two nested solves over the left wing (mirror with *sx per side):
      outer - arm Z-roll: bisected to the LARGEST apex angle from which the
              forearm still lands the hand at pad height (short red wings
              settle near-horizontal humerus; the black's huge wings keep a
              grand apex naturally);
      inner - forearm Z: chosen on the shallow side of its lowest-reach
              swing so the edge leans out/forward, not under the chest.
    Returns (arm_z, fore_z) rotation deltas."""
    by = {b.name: b for b in BONES}
    hand = by["wing_l_hand"]

    def contact_y(arm_z, fore_z):
        pose = dict(pose_base)
        pose["wing_l_arm"] = {"rotation": (0.0, -10.0, arm_z)}
        pose["wing_l_fore"] = {"rotation": (0.0, fore_yaw, fore_z)}
        return bone_world_transform(pose)[hand.parent](hand.pivot)[1]

    target_y = pad

    def lowest(arm_z):
        # ternary search: contact height over fore_z is smooth with one dip
        lo, hi = -170.0, -40.0
        for _ in range(40):
            m1 = lo + (hi - lo) / 3
            m2 = hi - (hi - lo) / 3
            if contact_y(arm_z, m1) < contact_y(arm_z, m2):
                hi = m2
            else:
                lo = m1
        mid = (lo + hi) / 2
        return contact_y(arm_z, mid), mid

    # largest apex that still reaches the target (cap just shy of vertical)
    lo_a, hi_a = -6.0, 62.0
    if lowest(hi_a)[0] <= target_y:
        arm_z = hi_a
    elif lowest(lo_a)[0] > target_y:
        arm_z = lo_a  # wing too short even flat - plant as deep as it goes
    else:
        for _ in range(26):
            mid = (lo_a + hi_a) / 2
            if lowest(mid)[0] <= target_y:
                lo_a = mid
            else:
                hi_a = mid
        arm_z = lo_a

    # forearm: bisect on the shallow branch (between lowest reach and -40)
    _, deepest = lowest(arm_z)
    lo_f, hi_f = deepest, -40.0
    if contact_y(arm_z, lo_f) > target_y:
        return arm_z, lo_f
    for _ in range(28):
        mid = (lo_f + hi_f) / 2
        if contact_y(arm_z, mid) > target_y:
            hi_f = mid
        else:
            lo_f = mid
    return arm_z, (lo_f + hi_f) / 2


def _solve_head_low(v: Variant, pose_base, target_y=18.0):
    """The crawling ground neck: every segment cancels its OWN rest pitch
    (the sitting S levels into a straight lance, exactly like the flight
    flatten), then the whole straight lance tilts down from the BASE joint
    alone by a solved angle, with the head re-leveled on top. The result is
    Bruno's green line - a straight neck streaming forward just above the
    ground - instead of the arching dive a uniform per-segment drop gives
    (rises off the shoulder, then plunges muzzle-first into the dirt).

    The base tilt is bisected until the HEAD CENTER sits at target_y scaled
    units (18 = 1.13 blocks: the jaw and chin barbels hang ~5.5 units below
    the center, so the chin clears flat ground with margin and the head
    never starts inside a block). Returns (neck_x list, head_x)."""
    head = {b.name: b for b in BONES}["head"]
    rests = [s[0] for s in v.neck]
    ps = sum(rests)
    # mid-skull center, rest space: forward-down of the head pivot
    # (authored head-local (0, 1.5, -5.5) x hs 1.18, scaled at emit)
    hc = (head.pivot[0], head.pivot[1] + 1.5 * HEAD_SCALE * SCALE,
          head.pivot[2] - 5.5 * HEAD_SCALE * SCALE)

    def build(k):
        xs = [-r for r in rests]  # straighten the S
        xs[0] -= k                # tilt the whole lance from the base
        # head re-levels: rest compensation + most of the tilt back (the
        # slight remainder keeps a predatory nose-down cast)
        return xs, ps + 0.85 * k

    def head_y(k):
        xs, hx = build(k)
        pose = dict(pose_base)
        for i, x in enumerate(xs, 1):
            pose[f"neck{i}"] = {"rotation": (x, 0.0, 0.0)}
        pose["head"] = {"rotation": (hx, 0.0, 0.0)}
        return bone_world_transform(pose)["head"](hc)[1]

    lo, hi = 0.0, 55.0
    if head_y(hi) > target_y:
        return build(hi)  # neck too short to reach lower - full tilt
    if head_y(lo) < target_y:
        return build(lo)
    for _ in range(28):
        mid = (lo + hi) / 2
        if head_y(mid) > target_y:
            lo = mid
        else:
            hi = mid
    return build((lo + hi) / 2)


def _ground_stance(v: Variant):
    """The planted beach stance shared by every ground animation: body
    crouched between the wing-forelimbs, hind legs folded, forearms dropped
    to the per-variant solved knuckle plant, fingers fanned back as folded
    ribs, and the neck lowered until the head center rides one block off
    the ground (muzzle level). Returns (static_channels, plant_z, drop);
    animations layer motion by overwriting entries with time functions
    (neck overrides should re-add the drop unless they aim themselves)."""
    base = {"body": {"position": (0, -9.0 * SCALE, 0)}}
    thigh_g, shin_g, foot_g = _solve_hind(base, 22, -20, -2)
    for side in ("l", "r"):
        base[f"leg_{side}_thigh"] = {"rotation": (thigh_g, 0, 0)}
        base[f"leg_{side}_shin"] = {"rotation": (shin_g, 0, 0)}
        base[f"leg_{side}_foot"] = {"rotation": (foot_g, 0, 0)}
    arm_z, fore_z = _solve_plant(base, v)
    st: dict[str, dict[str, object]] = {
        "body": {"position": (0, -9.0 * SCALE, 0)}}
    n_f = len(v.fingers)
    for side, sx in (("l", 1), ("r", -1)):
        st[f"wing_{side}_arm"] = {"rotation": (0, -10 * sx, arm_z * sx)}
        st[f"wing_{side}_fore"] = {"rotation": (0, 22 * sx, fore_z * sx)}
        # bat plant: with the hand ON the ground, every finger radiates
        # UP-BACK from the contact - the front spar steepest, each one
        # behind reclining further (FAN_FRONT -> FAN_BACK), tip segments
        # leaning on a touch more - the standing knuckle-peak skyline of
        # the reference, membrane strips draped between the spars.
        for fi, (yaw_rest, _fl) in enumerate(v.fingers, 1):
            t_f = (fi - 1) / max(1, n_f - 1)
            target = FAN_FRONT + (FAN_BACK - FAN_FRONT) * t_f
            b_yaw = -(16.0 - 6.0 * t_f)
            st[f"wing_{side}_finger{fi}"] = {
                "rotation": (0, (target + yaw_rest) * sx, 0)}
            st[f"wing_{side}_finger{fi}b"] = {
                "rotation": (0, b_yaw * sx, 0)}
            st[f"wing_{side}_finger{fi}c"] = {
                "rotation": (0, b_yaw * 0.7 * sx, 0)}
        st[f"leg_{side}_thigh"] = {"rotation": (thigh_g, 0, 0)}
        st[f"leg_{side}_shin"] = {"rotation": (shin_g, 0, 0)}
        st[f"leg_{side}_foot"] = {"rotation": (foot_g, 0, 0)}
    neck_x, head_x = _solve_head_low(v, st)
    for i, x in enumerate(neck_x, 1):
        st[f"neck{i}"] = {"rotation": (x, 0.0, 0.0)}
    st["head"] = {"rotation": (head_x, 0.0, 0.0)}
    return st, (arm_z, fore_z), neck_x, head_x


def idle_channels(v: Variant):
    """Land idle on the rest pose (sitting S-neck, standing legs): wings
    FOLDED into the beach stance - humerus up-back, forearm dropped to the
    planted knuckle, fingers arcing back - with slow breathing, a 120-degree
    scout gaze (ease left, hold, sweep right, hold, return) distributed
    along the neck, and a rapid shake-off roll rippling down the neck
    before settling. 7s loop, baked dense for the shake."""
    T = 7.0
    ch: dict[str, dict[str, object]] = {}

    def rot(b, f):
        ch.setdefault(b, {})["rotation"] = f

    def pos(b, f):
        ch.setdefault(b, {})["position"] = f

    def breath(t, phase=0.0):  # two slow breaths per loop
        return math.sin(2 * math.pi * 2 * t / T + phase)

    # --- planted wings: the beach stance uses the wings as FRONT LEGS
    # (shared _ground_stance: solved knuckle plant + finger fan + crouch +
    # head-low neck); slow breathing rides the planted humerus on top
    st, (arm_z, _fore_z), neck_x, head_x = _ground_stance(v)
    for b, chans in st.items():
        for cname, vec in chans.items():
            ch.setdefault(b, {})[cname] = vec
    for side, sx in (("l", 1), ("r", -1)):
        rot(f"wing_{side}_arm",
            lambda t, s=sx: (0, -10 * s, s * (arm_z + 2.2 * breath(t))))

    # --- scout gaze: +1 = dragon-left (-X), -1 = right, 0 = ahead ---
    def gaze(t):
        u = t / T
        return (_smoothstep(0.03, 0.11, u) - 2.0 * _smoothstep(0.24, 0.40, u)
                + _smoothstep(0.54, 0.62, u))

    # --- shake-off: enveloped 2.2 Hz roll rippling root-to-head ---
    def shake(t, phase):
        u = t / T
        if not 0.63 <= u <= 0.82:
            return 0.0
        env = math.sin(math.pi * (u - 0.63) / 0.19) ** 2
        return env * math.sin(2 * math.pi * 2.2 * t - phase)

    # gaze turns near the HEAD end (tip-heavy weights - the base barely
    # moves, the last segments and head carry the 120 degrees); the shake
    # is a pure Z TWIST about the neck's own axis (the dog shake), rolling
    # root-to-head with growing amplitude - no yaw component at all. The
    # solved head-low drop stays underneath both layers: the dragon scans
    # and shakes with its head skimming a block off the ground.
    n = len(v.neck)
    wsum = sum((i / n) ** 2.5 for i in range(1, n + 1))
    for i in range(1, n + 1):
        wgt = (i / n) ** 2.5 / wsum * 25.0
        rot(f"neck{i}",
            lambda t, w=wgt, i=i: (neck_x[i - 1], w * gaze(t),
                                   (2.0 + 6.0 * i / n) * shake(t, i * 0.7)))
    rot("head", lambda t: (head_x, 35.0 * gaze(t),
                           15.0 * shake(t, (n + 1) * 0.7)))
    rot("jaw", lambda t: (-1.6 * (0.5 - 0.5 * math.cos(2 * math.pi * 2 * t / T)),
                          0, 0))

    if v.whiskers:
        for side in ("l", "r"):
            rot(f"whisker_{side}_1", lambda t: (3.5 * breath(t, 0.6), 0, 0))
            rot(f"whisker_{side}_2", lambda t: (5.0 * breath(t, 1.2), 0, 0))

    # --- crouched, breathing body + gentle lateral tail sway ---
    pos("body", lambda t: (0, SCALE * (-9.0 + 0.8 * breath(t, -0.5)), 0))
    rot("body", lambda t: (1.0 * breath(t, -1.1), 0, 0))
    m = len(v.tail) if v.tail else 7
    for i in range(1, m + 1):
        rot(f"tail{i}",
            lambda t, i=i: (0, 2.6 * math.sin(2 * math.pi * t / T - i * 0.55), 0))
    rot("tail_fin",
        lambda t: (0, 3.4 * math.sin(2 * math.pi * t / T - (m + 1) * 0.55), 0))

    return T, _bake(ch, T, keys=64)


def walk_channels(v: Variant):
    """Ground walk on the planted-wing stance: the folded wings ARE the
    front legs (the GoT wyvern gait). Lateral-sequence walk - left hind,
    left wing, right hind, right wing - each limb raking back through its
    stance and swinging forward with a clearance lift, while the body rolls
    onto the planted shoulder, heaves twice a cycle, and wags a slow yaw;
    the neck counter-sways with a step bob fading toward the steady head,
    and the tail whips the opposite way down its chain. Heavy and slow -
    period scales with the wing (= body) size like the flight beats."""
    T = 2.9 * v.wing_scale ** 0.2
    ch: dict[str, dict[str, object]] = {}

    def rot(b, f):
        ch.setdefault(b, {})["rotation"] = f

    def pos(b, f):
        ch.setdefault(b, {})["position"] = f

    st, _pz, neck_x, head_x = _ground_stance(v)
    for b, chans in st.items():
        for cname, vec in chans.items():
            ch.setdefault(b, {})[cname] = vec

    # walking rides higher than the sitting crouch: body up, legs longer,
    # forearm plant re-solved for the raised shoulder
    base = {"body": {"position": (0, -7.8 * SCALE, 0)}}
    thigh_w, shin_w, foot_w = _solve_hind(base, 19, -17, -2)
    for side in ("l", "r"):
        base[f"leg_{side}_thigh"] = {"rotation": (thigh_w, 0, 0)}
        base[f"leg_{side}_shin"] = {"rotation": (shin_w, 0, 0)}
        base[f"leg_{side}_foot"] = {"rotation": (foot_w, 0, 0)}
    arm_zw, fore_zw = _solve_plant(base, v)

    SW = 0.30  # fraction of the cycle each limb spends in the air

    def gait(t, p):
        """Fore-aft swing at phase offset p: +1 leading .. -1 trailing.
        Swing = eased forward recovery, stance = linear rake back."""
        u = (t / T - p) % 1.0
        if u < SW:
            return -math.cos(math.pi * u / SW)
        return 1 - 2 * (u - SW) / (1 - SW)

    def lift(t, p):
        """Vertical clearance bump during the swing phase only."""
        u = (t / T - p) % 1.0
        return math.sin(math.pi * u / SW) if u < SW else 0.0

    # lateral-sequence footfall: LH 0.00, LF 0.25, RH 0.50, RF 0.75
    PH = {"leg_l": 0.00, "wing_l": 0.25, "leg_r": 0.50, "wing_r": 0.75}

    # hind legs: the thigh pendulums about X (+X swings the down-pointing
    # femur forward), the knee folds through the swing for clearance and
    # the foot rolls toe-down into the lift, flat through the stance
    for side in ("l", "r"):
        p = PH[f"leg_{side}"]
        rot(f"leg_{side}_thigh",
            lambda t, p=p: (thigh_w + 15.0 * gait(t, p), 0, 0))
        rot(f"leg_{side}_shin",
            lambda t, p=p: (shin_w - 17.0 * lift(t, p), 0, 0))
        rot(f"leg_{side}_foot",
            lambda t, p=p: (foot_w - 9.0 * lift(t, p) + 4.0 * gait(t, p), 0, 0))

    # wing forelimbs: the humerus pendulums about X (elbow back = wrist
    # back), the forearm eases its solved plant angle open during the swing
    # so the knuckle unweights and clears, and the shoulder hikes a touch
    for side, sx in (("l", 1), ("r", -1)):
        p = PH[f"wing_{side}"]
        rot(f"wing_{side}_arm",
            lambda t, s=sx, p=p: (-9.0 * gait(t, p), -10 * s,
                                  s * (arm_zw + 3.0 * lift(t, p))))
        rot(f"wing_{side}_fore",
            lambda t, s=sx, p=p: (0, 22 * s,
                                  s * (fore_zw + 16.0 * lift(t, p))))

    # body: two-bump heave on the diagonal supports, roll onto the planted
    # forelimb, slow shoulder yaw - all small, this is tonnes of dragon
    pos("body", lambda t: (
        0, SCALE * (-7.8 + 0.9 * math.sin(4 * math.pi * t / T + 0.7)), 0))
    rot("body", lambda t: (1.2 * math.sin(4 * math.pi * t / T - 0.5),
                           2.0 * math.sin(2 * math.pi * t / T - 0.94),
                           2.4 * math.sin(2 * math.pi * t / T - 0.94)))

    # neck: counter-sway against the shoulder yaw (fading toward the head)
    # + a step-timed bob, all riding the solved head-low drop - the stalk:
    # head skimming a block off the ground, steady on the horizon
    n = len(v.neck)
    for i in range(1, n + 1):
        f = i / n
        rot(f"neck{i}",
            lambda t, f=f, i=i: (
                neck_x[i - 1] + 1.0 * (1 - f) * math.sin(4 * math.pi * t / T - 1.3),
                -(1.6 / n) * math.sin(2 * math.pi * t / T - 0.94), 0))
    rot("head", lambda t: (head_x
                           + 1.2 * math.sin(4 * math.pi * t / T - 1.9),
                           -0.8 * math.sin(2 * math.pi * t / T - 0.94), 0))

    if v.whiskers:
        for side in ("l", "r"):
            rot(f"whisker_{side}_1",
                lambda t: (4 * math.sin(4 * math.pi * t / T - 2.3), 0, 0))
            rot(f"whisker_{side}_2",
                lambda t: (6 * math.sin(4 * math.pi * t / T - 2.9), 0, 0))

    # tail: lateral counter-whip - one wave per cycle traveling down the
    # chain, opposite the shoulder yaw, amplitude growing toward the tip
    m = len(v.tail) if v.tail else 7
    for i in range(1, m + 1):
        f = i / m
        a = (8.0 / m) * (0.45 + 1.3 * f)
        rot(f"tail{i}",
            lambda t, a=a, f=f: (
                0, a * math.sin(2 * math.pi * t / T - 0.94 + math.pi - f * 2.2),
                0))
    rot("tail_fin",
        lambda t: (0, 3.0 * math.sin(2 * math.pi * t / T - 0.94 + math.pi - 2.6),
                   0))

    return T, _bake(ch, T, keys=24)


def fire_channels(v: Variant):
    """Fire-breath attack loop on the planted stance: the dragon rears its
    chest up off the wrists (plant re-solved so the knuckles stay on the
    ground), coils the neck base back, and HOLDS a sustained blast - jaw
    hinged wide, head raking slowly side to side to hose the cone across
    the target line - elbows flared for balance, tail lashing, a low
    tremor riding the neck. The loop IS the sustained breath: every frame
    is a valid blasting pose, so the game can blend in and out anywhere."""
    T = 3.2
    ch: dict[str, dict[str, object]] = {}

    def rot(b, f):
        ch.setdefault(b, {})["rotation"] = f

    def pos(b, f):
        ch.setdefault(b, {})["position"] = f

    # stance minus the head-low layer: fire OVERRIDES every neck segment
    # and the head below (the strike aims itself)
    st, _pz, _nx, _hx = _ground_stance(v)
    for b, chans in st.items():
        for cname, vec in chans.items():
            ch.setdefault(b, {})[cname] = vec

    def sweep(t):  # slow head rake: left -> right -> left, once per loop
        return math.sin(2 * math.pi * t / T)

    def trem(t, phase=0.0):  # the strain of the blast: 7 shivers per loop
        return math.sin(2 * math.pi * 7 * t / T + phase)

    # reared stance: chest lifts off the forelimbs as far as the forearm
    # can still reach the ground, hind legs extend a little, forearm plant
    # re-solved for the raised chest + mildly flared elbow
    base = {"body": {"position": (0, -7.4 * SCALE, 0), "rotation": (2.5, 0, 0)}}
    thigh_f, shin_f, foot_f = _solve_hind(base, 16, -15, -1)
    for side in ("l", "r"):
        base[f"leg_{side}_thigh"] = {"rotation": (thigh_f, 0, 0)}
        base[f"leg_{side}_shin"] = {"rotation": (shin_f, 0, 0)}
        base[f"leg_{side}_foot"] = {"rotation": (foot_f, 0, 0)}
    arm_zf, fz = _solve_plant(base, v)
    pos("body", lambda t: (
        0, SCALE * (-7.4 + 0.35 * math.sin(4 * math.pi * t / T)), 0))
    rot("body", lambda t: (2.5 + 0.4 * trem(t), 0, 0))
    for side, sx in (("l", 1), ("r", -1)):
        rot(f"wing_{side}_arm",
            lambda t, s=sx: (0, -10 * s, s * (arm_zf + 1.5 * math.sin(
                4 * math.pi * t / T + 0.8))))
        rot(f"wing_{side}_fore", (0, 22 * sx, fz * sx))
        rot(f"leg_{side}_thigh", (thigh_f, 0, 0))
        rot(f"leg_{side}_shin", (shin_f, 0, 0))
        rot(f"leg_{side}_foot", (foot_f, 0, 0))

    # neck: the base coils up and back (front-loaded arc), the head end
    # levels onto the target; the last segments carry a share of the rake
    # so the sweep bends through the neck instead of snapping at the skull
    n = len(v.neck)
    ps = sum(s[0] for s in v.neck)
    for i in range(1, n + 1):
        f = (i - 1) / max(1, n - 1)
        # +X arcs the neck up, strongest at the base; total arc is fixed
        # across the chain (44 deg / n) so long necks rear the same height
        arc = (44.0 / n) * (1 - 0.55 * f)
        rake = 4.0 * max(0.0, f - 0.5) / 0.5   # rear half joins the sweep
        rot(f"neck{i}",
            lambda t, arc=arc, rake=rake, i=i: (
                arc + 0.7 * trem(t, i * 0.9), rake * sweep(t), 0))
    # head: strikes down off the raised neck (the blast hoses the ground
    # ahead) and carries the main rake; jaw hinges WIDE with a surge pulse
    rot("head", lambda t: (-0.55 * ps - 20 + 1.0 * trem(t, 1.7),
                           10.0 * sweep(t), 0))
    rot("jaw", lambda t: (-24 - 3.0 * (0.5 + 0.5 * math.sin(
        4 * math.pi * t / T - 0.9)), 0, 0))

    if v.whiskers:
        for side in ("l", "r"):
            rot(f"whisker_{side}_1", lambda t: (8 * trem(t, 2.2), 0, 0))
            rot(f"whisker_{side}_2", lambda t: (11 * trem(t, 2.8), 0, 0))

    # tail: an agitated lash - bigger than the idle sway, traveling wave
    # with a small vertical ripple riding the effort
    m = len(v.tail) if v.tail else 7
    for i in range(1, m + 1):
        f = i / m
        a = (11.0 / m) * (0.4 + 1.4 * f)
        rot(f"tail{i}",
            lambda t, a=a, f=f: (
                1.2 * f * math.sin(4 * math.pi * t / T - f * 2.0),
                a * math.sin(2 * math.pi * t / T - f * 2.8), 0))
    rot("tail_fin",
        lambda t: (0, 4.5 * math.sin(2 * math.pi * t / T - 3.2), 0))

    return T, _bake(ch, T, keys=48)




# ---------------------------------------------------------------- transforms

def _rot_x(v, a):
    c, s = math.cos(a), math.sin(a)
    return (v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c)


def _rot_y(v, a):
    c, s = math.cos(a), math.sin(a)
    return (v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c)


def _rot_z(v, a):
    c, s = math.cos(a), math.sin(a)
    return (v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2])


def bone_world_transform(pose=None):
    """name -> function(point) applying the full parent chain. `pose` maps
    bone -> {"rotation": deg_deltas, "position": offset} layered on the rest
    pose the way animations are (eulers summed per-axis, offset after)."""
    by_name = {b.name: b for b in BONES}
    cache: dict[str, callable] = {}
    pose = pose or {}

    def transform_of(name: str):
        if name in cache:
            return cache[name]
        b = by_name[name]
        parent_fn = transform_of(b.parent) if b.parent else (lambda p: p)
        d = pose.get(name, {})
        dr = d.get("rotation", (0.0, 0.0, 0.0))
        dp = d.get("position", (0.0, 0.0, 0.0))
        rx, ry, rz = (math.radians(a + da) for a, da in zip(b.rot, dr))
        px, py, pz = b.pivot

        def fn(p, _parent=parent_fn, _rx=rx, _ry=ry, _rz=rz,
               _pv=(px, py, pz), _dp=dp):
            v = (p[0] - _pv[0], p[1] - _pv[1], p[2] - _pv[2])
            v = _rot_z(v, _rz)
            v = _rot_y(v, _ry)
            v = _rot_x(v, _rx)
            v = (v[0] + _pv[0] + _dp[0], v[1] + _pv[1] + _dp[1],
                 v[2] + _pv[2] + _dp[2])
            return _parent(v)

        cache[name] = fn
        return fn

    return {b.name: transform_of(b.name) for b in BONES}


def bone_world_pivot(name: str):
    by_name = {b.name: b for b in BONES}
    transforms = bone_world_transform()
    b = by_name[name]
    return transforms[b.parent](b.pivot) if b.parent else b.pivot


# --------------------------------------------------------- animation sampling

def _catmull(p0, p1, p2, p3, f):
    return 0.5 * (2 * p1 + (p2 - p0) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3)
                  * f * f + (3 * (p1 - p2) + p3 - p0) * f ** 3)


def sample_channel(keys, t, length):
    """Evaluates a baked channel at time t: uniform catmullrom over the keys,
    wrapping across the loop seam (keys[-1] duplicates keys[0])."""
    if len(keys) == 1:
        return keys[0][1]
    n = len(keys) - 1
    u = (t % length) / length * n
    i0 = int(u)
    f = u - i0
    i = i0 % n

    def val(j):
        return keys[j % n][1]

    return tuple(_catmull(val(i - 1)[k], val(i)[k], val(i + 1)[k],
                          val(i + 2)[k], f) for k in range(3))


def pose_at(channels, t, length):
    return {b: {c: sample_channel(k, t, length) for c, k in chans.items()}
            for b, chans in channels.items()}


# ------------------------------------------------------------------ renderer

FACES = (  # vertex index quads over the (x,y,z) in {lo,hi} corner ordering
    ((0, 1, 3, 2), "west"), ((4, 6, 7, 5), "east"),
    ((0, 4, 5, 1), "down"), ((2, 3, 7, 6), "up"),
    ((0, 2, 6, 4), "north"), ((1, 5, 7, 3), "south"),
)


def render(view_yaw, view_pitch, path, size=(1000, 780), scale=3.0, center=None,
           pose=None, ground=True, overlay=None):
    """Orthographic painter's-algorithm render. `center` (world point) recenters
    and is used for close-ups; without it the model is framed for full body.
    `pose` applies animation deltas; the image is returned (saved if `path`).
    `overlay(img, to_screen)` draws on top after the cubes (fire breath)."""
    transforms = bone_world_transform(pose)
    bone_colors = {b.name: b.color for b in BONES}
    yaw, pitch = math.radians(view_yaw), math.radians(view_pitch)
    light = (0.4, 0.8, -0.45)
    ll = math.sqrt(sum(c * c for c in light))
    light = tuple(c / ll for c in light)

    if center is not None:
        c_view = _rot_x(_rot_y(center, yaw), pitch)
        anchor = (size[0] / 2 - c_view[0] * scale, size[1] * 0.5 + c_view[1] * scale)
    else:
        anchor = (size[0] / 2, size[1] * 0.62)

    # world-space boxes for hidden-face culling (painter's algorithm has no
    # z-buffer, so faces buried inside another cube must be skipped, not
    # drawn-then-overdrawn - detail cubes interpenetrate on purpose)
    def cube_fn(c):
        """Bone chain transform with the cube's own rotation folded in."""
        fn = transforms[c.bone]
        if not any(c.rot):
            return fn
        ox, oy, oz = c.origin
        rx, ry, rz = (math.radians(a) for a in c.rot)

        def cfn(p, _fn=fn, _o=(ox, oy, oz), _rx=rx, _ry=ry, _rz=rz):
            v = (p[0] - _o[0], p[1] - _o[1], p[2] - _o[2])
            v = _rot_z(v, _rz)
            v = _rot_y(v, _ry)
            v = _rot_x(v, _rx)
            return _fn((v[0] + _o[0], v[1] + _o[1], v[2] + _o[2]))

        return cfn

    EPS = 0.05
    boxes = []
    for bi, c in enumerate(CUBES):
        vol = ((c.hi[0] - c.lo[0]) * (c.hi[1] - c.lo[1]) * (c.hi[2] - c.lo[2]))
        if vol < 25:  # tiny spikes/teeth can't meaningfully bury a face
            continue
        fn = cube_fn(c)
        o = fn((0.0, 0.0, 0.0))
        ax = [tuple(a - b for a, b in zip(fn(u), o))
              for u in ((1, 0, 0), (0, 1, 0), (0, 0, 1))]
        lo = (c.lo[0] - c.inflate, c.lo[1] - c.inflate, c.lo[2] - c.inflate)
        hi = (c.hi[0] + c.inflate, c.hi[1] + c.inflate, c.hi[2] + c.inflate)
        ctr = fn(tuple((a + b) / 2 for a, b in zip(c.lo, c.hi)))
        hd2 = sum(((hi[k] - lo[k]) / 2) ** 2 for k in range(3)) + 1.0
        boxes.append((bi, o, ax, lo, hi, ctr, hd2))

    def buried(fc, self_i):
        for j, o, ax, lo, hi, ctr, hd2 in boxes:
            if j == self_i:
                continue
            if sum((fc[k] - ctr[k]) ** 2 for k in range(3)) > hd2:
                continue
            rel = (fc[0] - o[0], fc[1] - o[1], fc[2] - o[2])
            inside = True
            for k in range(3):
                p = rel[0] * ax[k][0] + rel[1] * ax[k][1] + rel[2] * ax[k][2]
                if not (lo[k] + EPS < p < hi[k] - EPS):
                    inside = False
                    break
            if inside:
                return True
        return False

    def project(p):
        vv = _rot_x(_rot_y(p, yaw), pitch)
        return (anchor[0] + vv[0] * scale, anchor[1] - vv[1] * scale), vv[2]

    polys = []
    for ci, c in enumerate(CUBES):
        fn = cube_fn(c)
        lo = (c.lo[0] - c.inflate, c.lo[1] - c.inflate, c.lo[2] - c.inflate)
        hi = (c.hi[0] + c.inflate, c.hi[1] + c.inflate, c.hi[2] + c.inflate)
        verts = [fn((x, y, z)) for x in (lo[0], hi[0]) for y in (lo[1], hi[1])
                 for z in (lo[2], hi[2])]
        flat = c.color if c.color is not None else bone_colors[c.bone]
        if c.carve:
            # Membrane plane: the texture's alpha owns the silhouette, so draw
            # the CARVED outline rather than the full rectangle. Filling the
            # rectangle is what made every wing preview read as a flat kite -
            # the scallop only ever existed in the texture.
            outline = [fn(p) for p in _carve_outline(c)]
            base = FACE_COLORS.get((ci, "up"), flat)
            ux = tuple(outline[len(outline) // 3][k] - outline[0][k] for k in range(3))
            vx = tuple(outline[-1][k] - outline[0][k] for k in range(3))
            n = (ux[1] * vx[2] - ux[2] * vx[1], ux[2] * vx[0] - ux[0] * vx[2],
                 ux[0] * vx[1] - ux[1] * vx[0])
            nl = math.sqrt(sum(k * k for k in n)) or 1.0
            n = tuple(k / nl for k in n)
            shade = 0.52 + 0.48 * abs(sum(n[k] * light[k] for k in range(3)))
            col = tuple(min(255, int(ch * shade)) for ch in base)
            proj, dsum = [], 0.0
            for p in outline:
                sp, dz = project(p)
                proj.append(sp)
                dsum += dz
            polys.append((dsum / len(outline), proj, col))
            continue
        for idx, face_name in FACES:
            base = FACE_COLORS.get((ci, face_name), flat)
            pts = [verts[i] for i in idx]
            fc = tuple(sum(p[k] for p in pts) / 4 for k in range(3))
            if buried(fc, ci):
                continue
            ux = tuple(pts[1][k] - pts[0][k] for k in range(3))
            vx = tuple(pts[3][k] - pts[0][k] for k in range(3))
            n = (ux[1] * vx[2] - ux[2] * vx[1], ux[2] * vx[0] - ux[0] * vx[2],
                 ux[0] * vx[1] - ux[1] * vx[0])
            nl = math.sqrt(sum(k * k for k in n)) or 1.0
            n = tuple(k / nl for k in n)
            shade = 0.52 + 0.48 * abs(sum(n[k] * light[k] for k in range(3)))
            col = tuple(min(255, int(ch * shade)) for ch in base)
            proj = []
            depth = 0.0
            for p in pts:
                vv = _rot_x(_rot_y(p, yaw), pitch)
                proj.append((anchor[0] + vv[0] * scale, anchor[1] - vv[1] * scale))
                depth += vv[2]
            polys.append((depth / 4, proj, col))

    polys.sort(key=lambda t: -t[0])  # painter's: farthest first
    img = Image.new("RGB", size, (24, 26, 30))
    drw = ImageDraw.Draw(img)
    if center is None and ground:
        gy = size[1] * 0.62
        drw.line([(0, gy), (size[0], gy)], fill=(45, 48, 52), width=1)
    for _, proj, col in polys:
        drw.polygon(proj, fill=col, outline=tuple(int(ch * 0.75) for ch in col))
    if overlay:
        def to_screen(p):
            vv = _rot_x(_rot_y(p, yaw), pitch)
            return (anchor[0] + vv[0] * scale, anchor[1] - vv[1] * scale)
        img = overlay(img, to_screen)
    if path:
        img.save(path)
    return img


def render_fly_previews(v: Variant, channels, length, tag, frames=16,
                        ground=False):
    """A looping GIF of an animation cycle + a 4x2 contact sheet of stills."""
    cam = (2.0 / SCALE) / (v.wing_scale * WING_BASE) ** 0.5
    imgs = [render(35, 16, None, size=(760, 600), scale=cam, ground=ground,
                   pose=pose_at(channels, k * length / frames, length))
            for k in range(frames)]
    imgs[0].save(os.path.join(OUT_DIR, f"{v.name}_{tag}.gif"), save_all=True,
                 append_images=imgs[1:], duration=int(length * 1000 / frames),
                 loop=0)
    sheet = Image.new("RGB", (4 * 380, 2 * 300))
    for j in range(8):
        sheet.paste(imgs[j * 2].resize((380, 300)),
                    (380 * (j % 4), 300 * (j // 4)))
    sheet.save(os.path.join(OUT_DIR, f"{v.name}_{tag}_sheet.png"))


def _fire_overlay(v: Variant, pose, seed):
    """Breath cone painter for one frame: additive flame dots streaming from
    the posed mouth, core -> mid -> outer color over distance (the variant's
    own fire), spread widening and drooping with reach, deterministic
    flicker per frame. Returned as a render() overlay callback."""
    fn = bone_world_transform(pose)["head"]
    piv = {b.name: b for b in BONES}["head"].pivot
    dm = (0.0, -0.8, -27.9)   # rest-space mouth gap, relative to head pivot
    da = (0.0, -7.0, -46.0)   # aim point: down the muzzle, tilted below it
    mouth = fn(tuple(p + d for p, d in zip(piv, dm)))
    aim = fn(tuple(p + d for p, d in zip(piv, da)))
    dirv = tuple(a - m for a, m in zip(aim, mouth))
    dl = math.sqrt(sum(c * c for c in dirv)) or 1.0
    dirv = tuple(c / dl for c in dirv)
    up = (0.0, 1.0, 0.0)
    side = (dirv[1] * up[2] - dirv[2] * up[1],
            dirv[2] * up[0] - dirv[0] * up[2],
            dirv[0] * up[1] - dirv[1] * up[0])
    sl = math.sqrt(sum(c * c for c in side)) or 1.0
    side = tuple(c / sl for c in side)
    up2 = (side[1] * dirv[2] - side[2] * dirv[1],
           side[2] * dirv[0] - side[0] * dirv[2],
           side[0] * dirv[1] - side[1] * dirv[0])
    rng = random.Random(f"{v.name}_fire_{seed}")
    core, mid, outer = v.fire
    REACH = 110.0

    def lerp(c1, c2, f):
        return tuple(int(a + (b - a) * f) for a, b in zip(c1, c2))

    def overlay(img, to_screen):
        lay = Image.new("RGBA", img.size, (0, 0, 0, 0))
        drw = ImageDraw.Draw(lay)
        dots = []
        for _ in range(190):
            q = rng.random() ** 0.85
            d = q * REACH
            spread = 1.0 + 9.5 * q ** 1.4
            ra = rng.gauss(0, 0.55) * spread
            rb = rng.gauss(0, 0.55) * spread
            droop = (0.0, -14.0 * q * q, 0.0)  # gravity arc at the far end
            wp = tuple(m + dirv[k] * d + side[k] * ra + up2[k] * rb + droop[k]
                       for k, m in enumerate(mouth))
            sz = (1.5 + 5.5 * q) * (0.75 + 0.5 * rng.random())
            f = min(1.0, q + rng.gauss(0, 0.08))
            col = (lerp(core, mid, f / 0.3) if f < 0.3
                   else lerp(mid, outer, (f - 0.3) / 0.7))
            al = 30 + int(205 * (1 - q) ** 0.75) + rng.randint(0, 18)
            dots.append((q, wp, sz, col, min(255, al)))
        dots.sort(key=lambda e: -e[0])  # far first, hot core overdraws
        for q, wp, sz, col, al in dots:
            x, y = to_screen(wp)
            x2, y2 = to_screen(tuple(a + b * sz for a, b in zip(wp, side)))
            r = max(1.5, math.hypot(x2 - x, y2 - y))
            drw.ellipse([x - r, y - r, x + r, y + r], fill=col + (al,))
        return Image.alpha_composite(img.convert("RGBA"), lay).convert("RGB")

    return overlay


def render_fire_previews(v: Variant, channels, length, frames=20):
    """The fire loop with the painted breath: GIF + contact sheet + a side
    still mid-rake showing the cone pattern in the variant's flame color."""
    cam = (2.0 / SCALE) / (v.wing_scale * WING_BASE) ** 0.5
    imgs = []
    for k in range(frames):
        t = k * length / frames
        pose = pose_at(channels, t, length)
        imgs.append(render(35, 16, None, size=(760, 600), scale=cam,
                           ground=True, pose=pose,
                           overlay=_fire_overlay(v, pose, k)))
    imgs[0].save(os.path.join(OUT_DIR, f"{v.name}_fire.gif"), save_all=True,
                 append_images=imgs[1:], duration=int(length * 1000 / frames),
                 loop=0)
    sheet = Image.new("RGB", (4 * 380, 2 * 300))
    for j in range(8):
        sheet.paste(imgs[j * 2 + (j // 4)].resize((380, 300)),
                    (380 * (j % 4), 300 * (j // 4)))
    sheet.save(os.path.join(OUT_DIR, f"{v.name}_fire_sheet.png"))
    t = 0.25 * length
    pose = pose_at(channels, t, length)
    render(90, 8, os.path.join(OUT_DIR, f"{v.name}_fire_side.png"),
           size=(1000, 660), scale=cam, ground=True, pose=pose,
           overlay=_fire_overlay(v, pose, 99))


# ------------------------------------------------------------------ exports

def export_bbmodel(path, model_name, animations=None, texture_path=None):
    elements = []
    uuids = {}
    bone_index = {b.name: b for b in BONES}
    for i, c in enumerate(CUBES):
        eid = str(uuid.uuid4())
        uuids.setdefault(c.bone, []).append(eid)
        faces = {fname: {"uv": list(rect), "texture": 0}
                 for fname, rect in _face_rects(c).items()}
        elements.append({
            "name": f"{c.bone}_{i}",
            "box_uv": False,
            "rescale": False,
            "locked": False,
            "from": list(c.lo),
            "to": list(c.hi),
            "autouv": 0,
            "color": i % 8,
            "origin": list(c.origin),
            **({"rotation": list(c.rot)} if any(c.rot) else {}),
            "inflate": c.inflate,
            "mirror_uv": c.mirror,
            "faces": faces,
            "type": "cube",
            "uuid": eid,
        })

    def outliner_node(b: Bone):
        node = {
            "name": b.name,
            "origin": list(b.pivot),
            "rotation": list(b.rot),
            "bedrock_binding": "",
            "export": True,
            "mirror_uv": False,
            "isOpen": False,
            "locked": False,
            "visibility": True,
            "autouv": 0,
            "uuid": b.uuid,
            "children": list(uuids.get(b.name, [])),
        }
        for child in BONES:
            if child.parent == b.name:
                node["children"].append(outliner_node(child))
        return node

    roots = [outliner_node(b) for b in BONES if b.parent is None]
    textures = []
    if texture_path:
        with open(texture_path, "rb") as tf:
            b64 = base64.b64encode(tf.read()).decode("ascii")
        tex_name = os.path.basename(texture_path)
        textures.append({
            "path": texture_path, "name": tex_name, "folder": "", "namespace": "",
            "id": "0", "group": "", "width": TEX_W, "height": TEX_H,
            "uv_width": TEX_W, "uv_height": TEX_H, "particle": False,
            "use_as_default": False, "layers_enabled": False,
            "sync_to_project": "", "render_mode": "default",
            "render_sides": "auto", "frame_time": 1, "frame_order_type": "loop",
            "frame_order": "", "frame_interpolate": False, "visible": True,
            "internal": True, "saved": True, "uuid": str(uuid.uuid4()),
            "relative_path": tex_name,
            "source": f"data:image/png;base64,{b64}",
        })
    doc = {
        "meta": {"format_version": "4.5", "model_format": "animated_entity_model",
                 "box_uv": False},
        "name": model_name,
        "model_identifier": model_name,
        "visible_box": [1, 1, 0],
        "variable_placeholders": "",
        "variable_placeholder_buttons": [],
        "resolution": {"width": TEX_W, "height": TEX_H},
        "elements": elements,
        "outliner": roots,
        "textures": textures,
        "animations": animations or [],
    }
    with open(path, "w") as f:
        json.dump(doc, f, indent=1)


def _anim_convert(cname, vec):
    """Generator-space animation deltas -> Blockbench-timeline (= bedrock
    json) values. Blockbench's BoneAnimator applies keyframes as rotation
    (-x, -y, +z) and position (-x, +y, +z) on top of the rest pose, and
    GeckoLib replicates that, so X/Y rotation and X position flip sign."""
    x, y, z = vec
    return (-x, -y, z) if cname == "rotation" else (-x, y, z)


def bb_animation(name, length, channels):
    """Blockbench-embedded animation: animators keyed by the bone uuids used
    in the outliner, one keyframe entry per baked key. Opens ready to play."""
    uuid_of = {b.name: b.uuid for b in BONES}
    animators = {}
    for bname, chans in channels.items():
        kfs = []
        for cname, keys in chans.items():
            interp = "catmullrom" if len(keys) > 1 else "linear"
            for t, vec in keys:
                cx, cy, cz = _anim_convert(cname, vec)
                kfs.append({
                    "channel": cname,
                    "data_points": [{"x": str(round(cx, 3)),
                                     "y": str(round(cy, 3)),
                                     "z": str(round(cz, 3))}],
                    "uuid": str(uuid.uuid4()),
                    "time": t,
                    "color": -1,
                    "interpolation": interp,
                })
        animators[uuid_of[bname]] = {"name": bname, "type": "bone",
                                     "keyframes": kfs}
    return {
        "uuid": str(uuid.uuid4()),
        "name": name,
        "loop": "loop",
        "override": False,
        "length": round(length, 4),
        "snapping": 24,
        "selected": False,
        "anim_time_update": "",
        "blend_weight": "",
        "start_delay": "",
        "loop_delay": "",
        "animators": animators,
    }


def export_animation_json(path, anims):
    """GeckoLib (bedrock) animation file holding any number of animations
    [(name, length, channels), ...]. Bedrock json takes the exact numbers a
    Blockbench timeline shows, so it shares _anim_convert with bb_animation
    (Blockbench's own bedrock exporter writes timeline values verbatim)."""
    animations = {}
    for name, length, channels in anims:
        bones = {}
        for bname, chans in channels.items():
            entry = {}
            for cname, keys in chans.items():
                if len(keys) == 1:
                    entry[cname] = [round(x, 3)
                                    for x in _anim_convert(cname, keys[0][1])]
                else:
                    entry[cname] = {
                        f"{t:g}": {"post": [round(x, 3) for x in
                                            _anim_convert(cname, vec)],
                                   "lerp_mode": "catmullrom"}
                        for t, vec in keys
                    }
            bones[bname] = entry
        animations[name] = {
            "loop": True,
            "animation_length": round(length, 4),
            "bones": bones,
        }
    doc = {"format_version": "1.8.0", "animations": animations}
    with open(path, "w") as f:
        json.dump(doc, f, indent=1)



# ----------------------------------------------------------- asset install

def _paint_flame_sprite(path):
    """16x16 grayscale teardrop flame for the dragon_flame particle. Ships
    near-WHITE: the particle tints it with the variant's fire color at
    runtime (rCol/gCol/bCol), so any pigment here would mud the tint.
    Alpha is binary (cutout) to match the flame particle render layer."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    blobs = ((8.0, 11.0, 5.0, 1.0), (8.0, 7.5, 3.6, 0.95),
             (8.0, 4.2, 2.2, 0.85), (8.0, 2.0, 1.1, 0.72))
    rng = random.Random("dragon_flame")
    for y in range(16):
        for x in range(16):
            v = 0.0
            for cx, cy, r, w in blobs:
                d2 = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) / (r * r)
                v = max(v, w * math.exp(-1.6 * d2))
            v += (rng.random() - 0.5) * 0.06
            if v > 0.24:
                g = min(255, int(120 + 150 * min(1.0, v)))
                img.putpixel((x, y), (g, g, g, 255))
    img.save(path)


def _paint_egg_sprite(path):
    """16x16 dragon spawn egg item icon: brick-red egg, dark speckles."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    base, dark, spot = (146, 46, 38), (84, 28, 24), (34, 32, 40)
    rows = {2: (6, 9), 3: (5, 10), 4: (4, 11), 5: (4, 11), 6: (3, 12),
            7: (3, 12), 8: (3, 12), 9: (3, 12), 10: (3, 12), 11: (4, 11),
            12: (4, 11), 13: (5, 10)}
    rng = random.Random("dragon_egg")
    for y, (x0, x1) in rows.items():
        for x in range(x0, x1 + 1):
            c = base
            if x in (x0, x1) or y in (2, 13):
                c = dark
            elif rng.random() < 0.2:
                c = spot
            elif x <= x0 + 2 and y <= 6:
                c = tuple(min(255, int(k * 1.25)) for k in base)  # highlight
            img.putpixel((x, y), c + (255,))
    img.save(path)


def export_rig(v: Variant, path: str):
    """Physical-rig sidecar consumed by the runtime pose solver in the mod
    (client/dragon/pose/DragonRig). Every point is GENERATOR space - y up,
    the dragon faces -Z, +X is the model's left, units are geo units (16
    per block) - evaluated by FK at the ground stance, i.e. the exact pose
    idle holds on screen, so the solver's angle deltas are relative to what
    the player actually sees. The Java side converts rotation deltas into
    GeckoLib's keyframe convention with (-x, -y, +z) in radians."""
    st, _plant_z, _neck_x, _head_x = _ground_stance(v)
    tf = bone_world_transform(st)
    by = {b.name: b for b in BONES}

    def joint(name):  # stance-space location of a bone's pivot
        return tf[name](by[name].pivot)

    def axis(name, unit):  # bone-local unit vector in stance space (CCD axis)
        p = by[name].pivot
        o = tf[name](p)
        e = tf[name]((p[0] + unit[0], p[1] + unit[1], p[2] + unit[2]))
        d = (e[0] - o[0], e[1] - o[1], e[2] - o[2])
        n = math.sqrt(sum(c * c for c in d)) or 1.0
        return (d[0] / n, d[1] / n, d[2] / n)

    def axis_x(name):  # hinge for pure-pitch chains (legs, neck, head)
        return axis(name, (1.0, 0.0, 0.0))

    def axis_z(name):  # frontal hinge for the wing limbs (their long axis
        return axis(name, (0.0, 0.0, 1.0))  # is X, so X-rolls move nothing)

    def seg_len(child, parent):  # rigid length: rest pivots never stretch
        return math.dist(by[child].pivot, by[parent].pivot)

    def corners(c):
        for x in (c.lo[0], c.hi[0]):
            for y in (c.lo[1], c.hi[1]):
                for z in (c.lo[2], c.hi[2]):
                    yield tf[c.bone]((x, y, z))

    def lowest_point(bone_names):
        pts = [p for c in CUBES if c.bone in bone_names for p in corners(c)]
        return min(pts, key=lambda p: p[1])

    def leg(name, kind, bones, effector_bones, hinge):
        # CCD chain: joints[] are the rotatable pivots, axes[] each joint's
        # hinge direction in stance space, slots[] the euler channel that
        # hinge corresponds to ("x" pitch chains, "z" the wings' frontal
        # roll - their long axis IS X, so X-rolls would move nothing), and
        # effector is the ground contact point.
        ax = axis_x if hinge == "x" else axis_z
        return {"name": name, "kind": kind, "bones": bones,
                "joints": [joint(b) for b in bones],
                "axes": [ax(b) for b in bones],
                "slots": [hinge] * len(bones),
                "effector": lowest_point(effector_bones)}

    legs = []
    for side in ("l", "r"):
        legs.append(leg(
            f"hind_{side}", "hind",
            [f"leg_{side}_thigh", f"leg_{side}_shin", f"leg_{side}_foot"],
            {f"leg_{side}_foot", f"leg_{side}_toes"}, "x"))
        legs.append(leg(
            f"fore_{side}", "fore",
            [f"wing_{side}_arm", f"wing_{side}_fore", f"wing_{side}_hand"],
            {f"wing_{side}_hand", f"wing_{side}_finger1"}, "z"))

    hb = by["head"]
    n = len(v.neck)
    neck = {
        "bones": [f"neck{i}" for i in range(1, n + 1)],
        "joints": [joint(f"neck{i}") for i in range(1, n + 1)],
        "axes": [axis_x(f"neck{i}") for i in range(1, n + 1)],
        "headPivot": joint("head"), "headAxis": axis_x("head"),
        "headCenter": tf["head"]((hb.pivot[0],
                                  hb.pivot[1] + 1.5 * HEAD_SCALE * SCALE,
                                  hb.pivot[2] - 5.5 * HEAD_SCALE * SCALE)),
        "headLow": lowest_point({"head", "jaw", "snout", "snout_tip", "brow"}),
        "muzzle": min((p for c in CUBES if c.bone == "snout_tip"
                       for p in corners(c)), key=lambda p: p[2]),
        "headCounter": 0.85}

    segs = sorted((b.name for b in BONES
                   if b.name.startswith("tail") and b.name != "tail_fin"),
                  key=lambda s: int(s[4:]))
    tail = {"bones": segs,
            "joints": [joint(nm) for nm in segs] + [joint("tail_fin")],
            "drops": [joint(nm)[1] - lowest_point({nm})[1] for nm in segs]}

    rig = {"variant": v.name, "unitsPerBlock": 16.0,
           "body": {"pivot": joint("body")},
           "legs": legs, "neck": neck, "tail": tail}
    with open(path, "w") as f:
        json.dump(rig, f, indent=1)


def geo_src(v: Variant):
    """The authored model. SOURCE, not output - never written by this script."""
    return os.path.join(RES_DIR, "geckolib", "models", "entity",
                        f"wyvern_{v.name}.geo.json")


def tex_src(v: Variant):
    """The authored texture. SOURCE, not output."""
    return os.path.join(RES_DIR, "textures", "entity", f"wyvern_{v.name}.png")


def install_assets(v: Variant):
    """Copies the DERIVED artifacts into the mod resource tree (GeckoLib 5
    paths; note the plural .animations.json suffix).

    Only two things are installed now. The geometry and the texture are
    hand-authored and already live in the resource tree - copying over them is
    precisely the overwrite this refactor removes."""
    import shutil
    anim_dst = os.path.join(RES_DIR, "geckolib", "animations", "entity")
    rig_dst = os.path.join(RES_DIR, "rigs")
    for d in (anim_dst, rig_dst):
        os.makedirs(d, exist_ok=True)
    shutil.copyfile(os.path.join(OUT_DIR, f"wyvern_{v.name}.animation.json"),
                    os.path.join(anim_dst, f"wyvern_{v.name}.animations.json"))
    shutil.copyfile(os.path.join(OUT_DIR, f"wyvern_{v.name}.rig.json"),
                    os.path.join(rig_dst, f"wyvern_{v.name}.json"))


def install_shared_assets():
    part_dir = os.path.join(RES_DIR, "textures", "particle")
    item_dir = os.path.join(RES_DIR, "textures", "item")
    os.makedirs(part_dir, exist_ok=True)
    os.makedirs(item_dir, exist_ok=True)
    _paint_flame_sprite(os.path.join(part_dir, "dragon_flame.png"))
    _paint_egg_sprite(os.path.join(item_dir, "dragon_spawn_egg.png"))


# ---------------------------------------------------------------------- main

def check_channels(v: Variant, anims):
    """Every bone an animation drives must exist in the authored model.

    Without this a rename in Blockbench degrades silently: GeckoLib ignores
    channels for bones it cannot find, so the wing simply stops moving with no
    error anywhere. Fail here instead, where the name is still in hand."""
    known = {b.name for b in BONES}
    missing = {}
    for name, _len, channels in anims:
        for bname in channels:
            if bname not in known:
                missing.setdefault(bname, []).append(name)
    if missing:
        report = "\n".join(f"    {b}  (driven by {', '.join(a)})"
                           for b, a in sorted(missing.items()))
        raise SystemExit(
            f"{v.name}: {len(missing)} animated bone(s) missing from "
            f"{os.path.basename(geo_src(v))}:\n{report}\n"
            f"  Either restore the name in the model or update the animation.")


def build_variant(v: Variant):
    reset()
    load_geo(geo_src(v))
    sync_variant_to_geo(v)
    sample_face_colors(tex_src(v))
    check_grounded(v)
    tex_path = tex_src(v)
    fly_len, fly = fly_channels(v)
    vert_len, vert = fly_vertical_channels(v)
    idle_len, idle = idle_channels(v)
    walk_len, walk = walk_channels(v)
    fire_len, fire = fire_channels(v)
    glide_len, glide = glide_channels(v)
    flyfire_len, flyfire = fly_fire_channels(v)
    anims = [("animation.wyvern.fly", fly_len, fly),
             ("animation.wyvern.fly_vertical", vert_len, vert),
             ("animation.wyvern.idle", idle_len, idle),
             ("animation.wyvern.walk", walk_len, walk),
             ("animation.wyvern.fire", fire_len, fire),
             ("animation.wyvern.glide", glide_len, glide),
             ("animation.wyvern.fly_fire", flyfire_len, flyfire)]
    check_channels(v, anims)
    # A PREVIEW bbmodel, for scrubbing the generated animations in Blockbench.
    # It is written to out/ and is NOT the model source - edit the .geo.json in
    # the resource tree, which nothing here overwrites.
    export_bbmodel(os.path.join(OUT_DIR, f"wyvern_{v.name}_preview.bbmodel"),
                   f"wyvern_{v.name}",
                   animations=[bb_animation(*a) for a in anims],
                   texture_path=tex_path)
    export_animation_json(os.path.join(OUT_DIR, f"wyvern_{v.name}.animation.json"),
                          anims)
    render(35, 18, os.path.join(OUT_DIR, f"{v.name}_three_quarter.png"),
           scale=3.0 / SCALE)
    render(90, 5, os.path.join(OUT_DIR, f"{v.name}_side.png"), scale=3.0 / SCALE)
    render(0, 8, os.path.join(OUT_DIR, f"{v.name}_front.png"), scale=3.0 / SCALE)
    head = bone_world_pivot("head")
    render(52, 10, os.path.join(OUT_DIR, f"{v.name}_head.png"),
           size=(900, 700), scale=8.5 / SCALE, center=head)
    render_fly_previews(v, fly, fly_len, "fly")
    render_fly_previews(v, vert, vert_len, "flyvert")
    render_fly_previews(v, idle, idle_len, "idle", frames=28, ground=True)
    render_fly_previews(v, walk, walk_len, "walk", frames=24, ground=True)
    render_fire_previews(v, fire, fire_len)
    render_fly_previews(v, glide, glide_len, "glide")
    render_fly_previews(v, flyfire, flyfire_len, "flyfire")
    export_rig(v, os.path.join(OUT_DIR, f"wyvern_{v.name}.rig.json"))
    install_assets(v)
    print(f"{v.name}: bones={len(BONES)} cubes={len(CUBES)} "
          f"fly={fly_len:.2f}s vert={vert_len:.2f}s walk={walk_len:.2f}s "
          f"head_at=({head[0]:.0f},{head[1]:.0f},{head[2]:.0f})")


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    for variant in (RED, BLACK, WHITE):
        build_variant(variant)
    install_shared_assets()
    print("wrote", OUT_DIR, "and installed assets into", RES_DIR)
