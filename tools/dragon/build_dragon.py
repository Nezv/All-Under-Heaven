"""Procedural wyvern model generator for All Under Heaven.

Defines semirealistic, House-of-the-Dragon-style wyverns (two hind legs,
wings as forelimbs) as a bone hierarchy + axis-aligned cuboids, in three
variants, and emits for each:

  out/wyvern_<variant>.bbmodel        - Blockbench project (editable master,
                                        flight animation embedded + playable)
  out/wyvern_<variant>.geo.json       - bedrock geometry for GeckoLib
  out/wyvern_<variant>.animation.json - GeckoLib flight animation
  out/<variant>_*.png                 - software-rendered preview stills
  out/<variant>_fly.gif / _fly_sheet.png - animated flight preview + stills

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

import json
import math
import os
import uuid
from dataclasses import dataclass, field

from PIL import Image, ImageDraw

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
TEX_W, TEX_H = 512, 512

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
    wing_scale: float = 1.0
    #          (yaw from straight-out, length) per finger spar
    fingers: tuple = ((14, 46), (38, 44), (66, 38))
    tail_scale: float = 1.0
    girth: float = 1.0        # body width multiplier (ethereal = slim)
    whiskers: bool = False    # trailing snout barbels
    #             (pitch, length, width, height) per neck segment
    neck: tuple = ((34, 13, 13, 12), (16, 12, 11, 11), (-6, 12, 10, 10), (-20, 12, 8.5, 9))
    #             (pitch, length, width, height) per tail segment (None = stock)
    tail: tuple | None = None


BLACK = Variant(
    # Grandiose: oversized four-fingered wings dominate the silhouette.
    name="black",
    hide=(42, 42, 48), hide_dark=(30, 30, 36),
    membrane=(84, 84, 92),          # storm-grey membranes
    horn=(118, 112, 126), ridge=(56, 54, 66),
    eye=(196, 198, 206), socket=(14, 12, 18),
    teeth=(198, 192, 184),
    wing_scale=1.69,                # 1.3 base, then +30% per request
    fingers=((10, 48), (30, 46), (50, 42), (70, 36)),
)

WHITE = Variant(
    # Ethereal: slim, elongated (5-segment neck, long tail), snout whiskers.
    name="white",
    hide=(226, 228, 232), hide_dark=(198, 202, 210),
    membrane=(216, 196, 202),       # pale rose membranes
    horn=(240, 236, 224), ridge=(206, 210, 218),
    eye=(248, 248, 255), socket=(58, 52, 60),
    teeth=(214, 206, 188),
    wing_scale=1.0,
    girth=0.9,
    whiskers=True,
    # 7-segment neck: same root (13x12) and tip (8.5x9) girth as the stock
    # neck, sizes interpolated between - only the length grows. Pitch sum
    # stays 14 so the head carries the same rest angle.
    neck=((26, 13, 13, 12), (20, 12, 12.25, 11.5), (12, 12, 11.5, 11),
          (2, 12, 10.75, 10.5), (-8, 12, 10, 10), (-16, 12, 9.25, 9.5),
          (-22, 11, 8.5, 9)),
    # 9-segment tail: same root (12x11) and tip (2.4x2.4) as stock, evenly
    # tapered across the extra nodes. Pitch sum stays -4 (droop then upcurve).
    tail=((14, 15, 12, 11), (10, 15, 10.8, 9.9), (6, 15, 9.6, 8.8),
          (2, 16, 8.4, 7.7), (-3, 16, 7.2, 6.6), (-7, 16, 6, 5.5),
          (-9, 16, 4.8, 4.4), (-9, 17, 3.6, 3.4), (-8, 18, 2.4, 2.4)),
)

RED = Variant(
    # The baseline wyvern: classic brick-red hide, amber eyes, stock build.
    name="red",
    hide=(146, 46, 38), hide_dark=(106, 30, 26),
    membrane=(168, 86, 60),         # warm sunset membranes
    horn=(216, 206, 186), ridge=(92, 26, 22),
    eye=(255, 178, 48), socket=(28, 10, 8),
    teeth=(226, 218, 202),
)

BONES: list[Bone] = []
CUBES: list[Cube] = []


def reset():
    BONES.clear()
    CUBES.clear()


def bone(name, parent, pivot, rot=(0, 0, 0), color=(140, 140, 140)):
    BONES.append(Bone(name, parent, pivot, rot, color, str(uuid.uuid4())))
    return name


def cube(bone_name, center, size, inflate=0.0, mirror=False, color=None):
    """Axis-aligned cube from center + size (pre-bone-rotation)."""
    cx, cy, cz = center
    sx, sy, sz = size
    CUBES.append(Cube(bone_name, (cx - sx / 2, cy - sy / 2, cz - sz / 2),
                      (cx + sx / 2, cy + sy / 2, cz + sz / 2), inflate, mirror, color))


# ------------------------------------------------------------ wyvern anatomy

def build_head(parent: str, tip: tuple[float, float, float], neck_pitch_sum: float, v: Variant):
    """Predatory head: layered skull, hooded brow over inset eyes, tapering
    down-angled snout with a hooked tip, parted fanged jaw, cheek flares and
    swept horns. Small sub-bones carry the angles so nothing is a plain box
    stack, and the jaw/head stay animatable."""
    head = bone("head", parent, tip, rot=(-neck_pitch_sum, 0, 0), color=v.hide)
    hx, hy, hz = tip

    # skull core + rear crest fin
    cube(head, (hx, hy + 1.6, hz - 4.5), (8.6, 7.6, 11))
    crest = bone("head_crest", head, (hx, hy + 5.2, hz - 1), rot=(-34, 0, 0), color=v.ridge)
    cube(crest, (hx, hy + 6.4, hz + 2.5), (1.2, 4.4, 8))

    # hooded brow: a ledge wider than the skull, dipping down DIRECTLY over
    # the eye line so the eyes sit in shadow — the predatory stare.
    brow = bone("brow", head, (hx, hy + 4.8, hz - 7.5), rot=(-12, 0, 0), color=v.hide_dark)
    cube(brow, (hx, hy + 5.1, hz - 8.6), (9.0, 2.0, 5.6))

    # eyes: dark socket rims with proud, bright eye cubes (variant color)
    for sx in (1, -1):
        cube(head, (4.0 * sx + hx, hy + 3.3, hz - 7.2), (1.1, 3.0, 3.6), color=v.socket)
        cube(head, (4.5 * sx + hx, hy + 3.3, hz - 7.2), (0.8, 2.2, 2.8), color=v.eye)

    # snout: slight droop, ridge on top, hooked tip
    snout = bone("snout", head, (hx, hy + 2.4, hz - 10), rot=(-5, 0, 0), color=v.hide)
    cube(snout, (hx, hy + 2.9, hz - 15.3), (5.4, 3.6, 11.5))
    cube(snout, (hx, hy + 4.8, hz - 14), (2.6, 1.2, 8))
    tip_b = bone("snout_tip", snout, (hx, hy + 2.4, hz - 20.5), rot=(-12, 0, 0), color=v.hide)
    cube(tip_b, (hx, hy + 2.4, hz - 22.6), (4.4, 3.2, 5.2))
    cube(tip_b, (hx, hy + 1.1, hz - 24.3), (3.2, 1.8, 2.4))          # hooked tip
    for sx in (1, -1):
        cube(tip_b, (1.4 * sx + hx, hy + 4.0, hz - 23.2), (1.0, 0.8, 1.8),
             color=v.hide_dark)                                       # nostrils
        # long front fangs off the tip, the wolf-teeth of the profile
        cube(tip_b, (1.5 * sx + hx, hy + 0.4, hz - 23.4), (0.7, 2.2, 0.8),
             color=v.teeth)

    # ethereal variants: thin whisker barbels trailing back off the snout,
    # drooping in two segments past the jaw line
    if v.whiskers:
        for side, sx in (("l", 1), ("r", -1)):
            w1 = bone(f"whisker_{side}_1", tip_b, (2.4 * sx + hx, hy + 1.8, hz - 21.5),
                      rot=(10, 34 * sx, 0), color=v.horn)
            cube(w1, (2.4 * sx + hx, hy + 1.8, hz - 17), (0.6, 0.6, 10))
            w2 = bone(f"whisker_{side}_2", w1, (2.4 * sx + hx, hy + 1.8, hz - 12),
                      rot=(18, 10 * sx, 0), color=v.horn)
            cube(w2, (2.4 * sx + hx, hy + 1.8, hz - 7.5), (0.45, 0.45, 10))

    # discrete upper fangs along the lip line (not a strip — actual teeth)
    for sx in (1, -1):
        for fz, fh in ((-12.6, 1.3), (-15.2, 1.5), (-17.6, 1.2)):
            cube(snout, (2.25 * sx + hx, hy + 0.6, hz + fz), (0.6, fh, 0.8),
                 color=v.teeth)

    # parted lower jaw: dark mouth shadow, upward teeth, chin
    jaw = bone("jaw", head, (hx, hy - 0.9, hz - 2), rot=(-13, 0, 0), color=v.hide_dark)
    cube(jaw, (hx, hy - 1.7, hz - 10.8), (4.6, 2.2, 16))
    cube(jaw, (hx, hy - 0.35, hz - 10.5), (3.8, 1.1, 13), color=(42, 16, 18))
    for sx in (1, -1):
        for fz in (-13.6, -16.4):
            cube(jaw, (1.85 * sx + hx, hy - 0.1, hz + fz), (0.55, 1.2, 0.7),
                 color=v.teeth)
    cube(jaw, (hx, hy - 2.3, hz - 19.3), (3.4, 1.8, 3))

    # cheek flares: thin plates yawed outward for an angular skull
    for side, sx in (("l", 1), ("r", -1)):
        flare = bone(f"cheek_{side}", head, (4.2 * sx + hx, hy + 2, hz + 0.5),
                     rot=(0, 26 * sx, 0), color=v.hide_dark)
        cube(flare, (4.6 * sx + hx, hy + 1.8, hz + 3.8), (0.8, 5, 7.5), mirror=sx < 0)

    # horns: two segments each, swept back and out
    for side, sx in (("l", 1), ("r", -1)):
        h1 = bone(f"horn_{side}_1", head, (3.0 * sx + hx, hy + 5.2, hz + 0.5),
                  rot=(-26, -14 * sx, 0), color=v.horn)
        cube(h1, (3.0 * sx + hx, hy + 5.2, hz + 5.5), (2.2, 2.2, 11))
        h2 = bone(f"horn_{side}_2", h1, (3.0 * sx + hx, hy + 5.2, hz + 11),
                  rot=(-16, 0, 0), color=v.horn)
        cube(h2, (3.0 * sx + hx, hy + 5.2, hz + 15.5), (1.4, 1.4, 10))


def build_wyvern(v: Variant):
    root = bone("root", None, (0, 0, 0))

    # --- body: chest (deep) + abdomen (narrower), keel below ---
    g = v.girth
    body = bone("body", root, (0, 26, 4), color=v.hide)
    cube(body, (0, 25, -12), (22 * g, 20, 26))       # chest
    cube(body, (0, 24, 10), (18 * g, 16, 22))        # abdomen
    cube(body, (0, 16.5, -12), (14 * g, 5, 22))      # keel / belly line

    # --- neck: chained segments arcing up (variant-specific S curve) ---
    parent = body
    tip = (0.0, 30.0, -24.0)  # neck root at front of chest
    for i, (pitch, length, w, h) in enumerate(v.neck, 1):
        name = bone(f"neck{i}", parent, tip, rot=(pitch, 0, 0), color=v.hide)
        cube(name, (tip[0], tip[1] + 0.5, tip[2] - length / 2), (w, h, length + 2))
        cube(name, (tip[0], tip[1] + h / 2 + 1.2, tip[2] - length / 2),
             (1.4, 3.2, length - 3), color=v.ridge)
        tip = (tip[0], tip[1], tip[2] - length)
        parent = name

    build_head(parent, tip, sum(s[0] for s in v.neck), v)

    # --- wings: humerus -> forearm -> hand, finger spars + membranes ---
    # Built for the LEFT (+X) side, mirrored programmatically for the right.
    ws = v.wing_scale

    def build_wing(side: str, sx: int):
        sh = (11 * sx, 31.0, -16.0)  # shoulder
        arm = bone(f"wing_{side}_arm", "body", sh,
                   rot=(0, 14 * sx, 20 * sx), color=v.hide)
        cube(arm, (sh[0] + 11 * ws * sx, sh[1], sh[2]), (22 * ws, 4.6, 4.6))
        elbow = (sh[0] + 21 * ws * sx, sh[1], sh[2])
        fore = bone(f"wing_{side}_fore", arm, elbow,
                    rot=(0, -18 * sx, -12 * sx), color=v.hide)
        cube(fore, (elbow[0] + 13 * ws * sx, elbow[1], elbow[2]), (27 * ws, 3.6, 3.6))
        wrist = (elbow[0] + 26 * ws * sx, elbow[1], elbow[2])
        hand = bone(f"wing_{side}_hand", fore, wrist, rot=(0, 0, 0), color=v.hide)
        cube(hand, (wrist[0] + 2.5 * sx, wrist[1], wrist[2]), (6, 3.2, 3.2))
        # small wing claw hooking forward off the wrist
        cube(hand, (wrist[0] + 2 * sx, wrist[1] + 1, wrist[2] - 3.4), (1.6, 1.6, 5),
             color=v.horn)
        # finger spars fan backward (+Z); membranes trail as thin plates
        base = (wrist[0] + 5 * sx, wrist[1], wrist[2])
        finger_count = len(v.fingers)
        for fi, (yaw, raw_len) in enumerate(v.fingers, 1):
            length = raw_len * ws
            fname = bone(f"wing_{side}_finger{fi}", hand, base,
                         rot=(0, -yaw * sx, 0), color=v.hide_dark)
            cube(fname, (base[0] + (length / 2) * sx, base[1], base[2]),
                 (length, 2.0, 2.0))
            mem_len = length - 4
            mem_depth = (22 if fi < finger_count else 17) * ws
            mname = bone(f"wing_{side}_mem{fi}", fname, base, rot=(0, 0, 0),
                         color=v.membrane)
            cube(mname, (base[0] + (mem_len / 2 + 2) * sx, base[1] - 0.4,
                         base[2] + mem_depth / 2 + 1.2), (mem_len, 0.6, mem_depth))
        # armpit membrane between forearm and body
        mroot = bone(f"wing_{side}_mem0", fore, elbow, color=v.membrane)
        cube(mroot, (elbow[0] + 12 * ws * sx, elbow[1] - 1.2, elbow[2] + 8.5),
             (24 * ws, 0.6, 14))

    build_wing("l", 1)
    build_wing("r", -1)

    # --- hind legs: digitigrade thigh -> shin -> foot -> toes ---
    def build_leg(side: str, sx: int):
        hip = (9.5 * sx, 24.0, 12.0)
        # Digitigrade: femur forward-down, tibia back-down, foot near-vertical.
        thigh = bone(f"leg_{side}_thigh", "body", hip, rot=(28, 0, 0), color=v.hide_dark)
        cube(thigh, (hip[0], hip[1] - 6.5, hip[2] + 1), (7.5, 15, 10))
        knee = (hip[0], hip[1] - 12, hip[2] + 6)
        shin = bone(f"leg_{side}_shin", thigh, knee, rot=(-46, 0, 0), color=v.hide_dark)
        cube(shin, (knee[0], knee[1] - 6, knee[2] - 1.5), (5, 13, 6))
        ankle = (knee[0], knee[1] - 11.5, knee[2] - 4)
        foot = bone(f"leg_{side}_foot", shin, ankle, rot=(18, 0, 0), color=v.hide_dark)
        cube(foot, (ankle[0], ankle[1] - 4, ankle[2] - 1), (4.6, 8.5, 5))
        toes = bone(f"leg_{side}_toes", foot, (ankle[0], ankle[1] - 8, ankle[2] - 2),
                    color=v.hide_dark)
        for ti, tox in enumerate((-1.8, 0.0, 1.8)):
            cube(toes, (ankle[0] + tox * sx, ankle[1] - 8 + 1.2, ankle[2] - 5.5),
                 (1.8, 2.4, 8))
            cube(toes, (ankle[0] + tox * sx, ankle[1] - 8 + 0.8, ankle[2] - 10),
                 (1.2, 1.4, 2.4), color=v.horn)  # claws

    build_leg("l", 1)
    build_leg("r", -1)

    # --- tail: chained segments, tapering, gentle droop then upcurve ---
    tail_specs = v.tail or (  # (pitch, length, width, height)
        (14, 15, 12, 11),
        (8, 15, 10, 9.5),
        (4, 16, 8, 8),
        (-4, 16, 6.5, 6.5),
        (-8, 16, 5, 5),
        (-10, 17, 3.6, 3.6),
        (-8, 18, 2.4, 2.4),
    )
    parent = "body"
    tip = (0.0, 26.0, 20.0)
    for i, (pitch, raw_len, w, h) in enumerate(tail_specs, 1):
        length = raw_len * v.tail_scale
        name = bone(f"tail{i}", parent, tip, rot=(pitch, 0, 0), color=v.hide)
        cube(name, (tip[0], tip[1], tip[2] + length / 2), (w, h, length + 2))
        if i <= len(tail_specs) - 2:  # spine ridges fade out toward the tip
            cube(name, (tip[0], tip[1] + h / 2 + 1.1, tip[2] + length / 2),
                 (1.2, 2.6, length - 4), color=v.ridge)
        tip = (tip[0], tip[1], tip[2] + length)
        parent = name
    fin = bone("tail_fin", parent, tip, color=v.membrane)
    cube(fin, (tip[0], tip[1] + 1, tip[2] + 4), (0.8, 7, 10))

    # --- back ridges along the spine ---
    for rz, ry in ((-20, 36.2), (-13, 36.6), (-6, 36.6), (1, 36.2), (8, 34.4), (15, 33.4)):
        cube("body", (0, ry, rz), (1.6, 3.4, 5.4), color=v.ridge)


def ground_model():
    """Shifts the whole model vertically so the toes rest exactly on y=0.

    The chain rotations make the feet's world height awkward to hand-compute,
    so measure it: transform the toe cubes through the rest pose and offset
    every pivot and cube by the difference.
    """
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
    if not math.isfinite(min_y):
        return
    shift = -min_y
    for b in BONES:
        b.pivot = (b.pivot[0], b.pivot[1] + shift, b.pivot[2])
    for c in CUBES:
        c.lo = (c.lo[0], c.lo[1] + shift, c.lo[2])
        c.hi = (c.hi[0], c.hi[1] + shift, c.hi[2])


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


def fly_channels(v: Variant):
    """Procedural straight-flight cycle, generalized by the variant's build:
    period and flap depth follow wing_scale (a bigger wing beats slower and
    shallower), the neck counter-sway and tail wave distribute over however
    many segments the variant actually has, whiskers trail only if present.

    Returns (period_seconds, {bone: {channel: [(t, (x, y, z)), ...]}}) in
    Blockbench space (left side authored, right side sign-mirrored on y/z).
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

    # body heaves with the lift: lowest at the top of the stroke, rising
    # through the powered downstroke; a subtle pitch rocks behind the heave
    pos("body", lambda t: (0, -1.6 * math.cos(W * t), 0))
    rot("body", lambda t: (1.8 * math.sin(W * t - 2.1), 0, 0))

    # neck: a small counter-wave travels head-ward across half a cycle; the
    # head counter-pitches against the body so the gaze stays near-level.
    # Per-segment amplitude divides by the chain length, so long necks bend
    # the same total arc as short ones.
    n = len(v.neck)
    for i in range(1, n + 1):
        a, ph = 6.0 / n, math.pi + (i / n) * math.pi
        rot(f"neck{i}", lambda t, a=a, ph=ph: (a * math.sin(W * t - ph), 0, 0))
    rot("head", lambda t: (2.0 * math.sin(W * t - 2.1 - math.pi), 0, 0))

    if v.whiskers:
        for side in ("l", "r"):
            rot(f"whisker_{side}_1", lambda t: (7 * math.sin(W * t - 2.4), 0, 0))
            rot(f"whisker_{side}_2", lambda t: (10 * math.sin(W * t - 3.1), 0, 0))

    # tail: a vertical wave rippling tip-ward (a bit over one wavelength
    # along the chain regardless of segment count), growing toward the tip
    m = len(v.tail) if v.tail else 7
    for i in range(1, m + 1):
        a = 1.1 + 3.4 * (i / m) ** 1.5
        ph = 0.7 + (i / m) * 2.3 * math.pi
        rot(f"tail{i}", lambda t, a=a, ph=ph: (a * math.sin(W * t - ph), 0, 0))
    rot("tail_fin", lambda t: (5.5 * math.sin(W * t - 1.2 - 2.3 * math.pi), 0, 0))

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

    # body: deep heave lagging the stroke, nose-up surge on the power-out
    pos("body", lambda t: (0, -2.6 * math.cos(theta(t) - 0.6), 0))
    rot("body", lambda t: (4.5 * math.sin(theta(t) - 2.6), 0, 0))

    # neck: the swim. Each segment blends an upward reach with a FLATTENING
    # of its own rest pitch, so stretching straightens the S-curve into a
    # skyward line and coiling deepens it - and the wave ripples root-to-head
    # (per-segment delay). Works for any segment count/curve by construction.
    n = len(v.neck)
    for i, seg in enumerate(v.neck, 1):
        r, au = seg[0], 22.0 / n
        rot(f"neck{i}",
            lambda t, r=r, au=au, i=i: (
                stretch(t - (i / n) * 0.14 * T) * (au - 0.5 * r), 0, 0))
    rot("head", lambda t: (9.0 * stretch(t - 0.16 * T), 0, 0))
    rot("jaw", lambda t: (-2.5 * (stretch(t - 0.16 * T) + 1) / 2, 0, 0))

    if v.whiskers:
        for side in ("l", "r"):
            rot(f"whisker_{side}_1", lambda t: (9 * math.sin(theta(t) - 2.6), 0, 0))
            rot(f"whisker_{side}_2", lambda t: (13 * math.sin(theta(t) - 3.2), 0, 0))

    # tail: deeper traveling wave than cruise, plus a uniform counter-curl -
    # tucked up under the coil, whipping down-back on the stretch
    m = len(v.tail) if v.tail else 7
    for i in range(1, m + 1):
        a = 1.8 + 4.2 * (i / m) ** 1.5
        ph = 0.9 + (i / m) * 2.0 * math.pi
        rot(f"tail{i}",
            lambda t, a=a, ph=ph: (
                a * math.sin(theta(t) - ph) + stretch(t) * 9.0 / m, 0, 0))
    rot("tail_fin", lambda t: (6.5 * math.sin(theta(t) - 1.3 - 2.0 * math.pi), 0, 0))

    _tuck_legs(rot)
    # denser bake: the tanh dwell/snap needs a few more keys than a sine
    return T, _bake(ch, T, keys=12)


# ------------------------------------------------------------------ UV packer

def pack_uvs():
    """Shelf-packs box UVs onto the sheet, biggest cubes first."""
    order = sorted(range(len(CUBES)), key=lambda i: -(
        _uv_w(CUBES[i]) * _uv_h(CUBES[i])))
    x = y = shelf_h = 0
    for i in order:
        w, h = _uv_w(CUBES[i]), _uv_h(CUBES[i])
        if x + w > TEX_W:
            x, y = 0, y + shelf_h
            shelf_h = 0
        if y + h > TEX_H:
            raise SystemExit(f"UV overflow: texture {TEX_W}x{TEX_H} too small")
        CUBES[i].uv = (x, y)
        x += w
        shelf_h = max(shelf_h, h)


def _dims(c: Cube):
    return (math.ceil(c.hi[0] - c.lo[0]), math.ceil(c.hi[1] - c.lo[1]),
            math.ceil(c.hi[2] - c.lo[2]))


def _uv_w(c: Cube):
    w, h, d = _dims(c)
    return 2 * (w + d)


def _uv_h(c: Cube):
    w, h, d = _dims(c)
    return h + d


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
    ((0, 1, 3, 2), (-1, 0, 0)), ((4, 6, 7, 5), (1, 0, 0)),
    ((0, 4, 5, 1), (0, -1, 0)), ((2, 3, 7, 6), (0, 1, 0)),
    ((0, 2, 6, 4), (0, 0, -1)), ((1, 5, 7, 3), (0, 0, 1)),
)


def render(view_yaw, view_pitch, path, size=(1000, 780), scale=3.0, center=None,
           pose=None, ground=True):
    """Orthographic painter's-algorithm render. `center` (world point) recenters
    and is used for close-ups; without it the model is framed for full body.
    `pose` applies animation deltas; the image is returned (saved if `path`)."""
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

    polys = []
    for c in CUBES:
        fn = transforms[c.bone]
        lo = (c.lo[0] - c.inflate, c.lo[1] - c.inflate, c.lo[2] - c.inflate)
        hi = (c.hi[0] + c.inflate, c.hi[1] + c.inflate, c.hi[2] + c.inflate)
        verts = [fn((x, y, z)) for x in (lo[0], hi[0]) for y in (lo[1], hi[1])
                 for z in (lo[2], hi[2])]
        base = c.color if c.color is not None else bone_colors[c.bone]
        for idx, _ in FACES:
            pts = [verts[i] for i in idx]
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
    if path:
        img.save(path)
    return img


def render_fly_previews(v: Variant, channels, length, tag, frames=16):
    """A looping GIF of an airborne cycle + a 4x2 contact sheet of stills."""
    cam = 2.0 / v.wing_scale ** 0.5  # zoom out for the big-winged builds
    imgs = [render(35, 16, None, size=(760, 600), scale=cam, ground=False,
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


# ------------------------------------------------------------------ exports

def export_bbmodel(path, model_name, animations=None):
    elements = []
    uuids = {}
    bone_index = {b.name: b for b in BONES}
    for i, c in enumerate(CUBES):
        eid = str(uuid.uuid4())
        uuids.setdefault(c.bone, []).append(eid)
        w, h, d = _dims(c)
        u, v = c.uv
        faces = {
            "north": {"uv": [u + d, v + d, u + d + w, v + d + h]},
            "east": {"uv": [u, v + d, u + d, v + d + h]},
            "south": {"uv": [u + 2 * d + w, v + d, u + 2 * (d + w), v + d + h]},
            "west": {"uv": [u + d + w, v + d, u + 2 * d + w, v + d + h]},
            "up": {"uv": [u + d + w, v + d, u + d, v]},
            "down": {"uv": [u + 2 * w + d, v, u + d + w, v + d]},
        }
        for f in faces.values():
            f["texture"] = 0
        elements.append({
            "name": f"{c.bone}_{i}",
            "box_uv": True,
            "rescale": False,
            "locked": False,
            "from": list(c.lo),
            "to": list(c.hi),
            "autouv": 0,
            "color": i % 8,
            "origin": list(bone_index[c.bone].pivot),
            "uv_offset": [u, v],
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
    doc = {
        "meta": {"format_version": "4.5", "model_format": "animated_entity_model",
                 "box_uv": True},
        "name": model_name,
        "model_identifier": model_name,
        "visible_box": [1, 1, 0],
        "variable_placeholders": "",
        "variable_placeholder_buttons": [],
        "resolution": {"width": TEX_W, "height": TEX_H},
        "elements": elements,
        "outliner": roots,
        "textures": [],
        "animations": animations or [],
    }
    with open(path, "w") as f:
        json.dump(doc, f, indent=1)


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
                kfs.append({
                    "channel": cname,
                    "data_points": [{"x": str(round(vec[0], 3)),
                                     "y": str(round(vec[1], 3)),
                                     "z": str(round(vec[2], 3))}],
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
    [(name, length, channels), ...]. Same X-mirror as the geometry export:
    rotation [x,-y,-z], position [-x,y,z] vs Blockbench space."""
    animations = {}
    for name, length, channels in anims:
        bones = {}
        for bname, chans in channels.items():
            entry = {}
            for cname, keys in chans.items():
                conv = ((lambda p: [p[0], -p[1], -p[2]]) if cname == "rotation"
                        else (lambda p: [-p[0], p[1], p[2]]))
                if len(keys) == 1:
                    entry[cname] = [round(x, 3) for x in conv(keys[0][1])]
                else:
                    entry[cname] = {
                        f"{t:g}": {"post": [round(x, 3) for x in conv(vec)],
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


def export_geo(path, identifier):
    """Bedrock geometry for GeckoLib. Bedrock mirrors X vs Blockbench space."""
    bones_json = []
    cubes_by_bone: dict[str, list[Cube]] = {}
    for c in CUBES:
        cubes_by_bone.setdefault(c.bone, []).append(c)
    for b in BONES:
        entry = {
            "name": b.name,
            "pivot": [-b.pivot[0], b.pivot[1], b.pivot[2]],
        }
        if b.parent:
            entry["parent"] = b.parent
        if any(abs(a) > 1e-6 for a in b.rot):
            entry["rotation"] = [b.rot[0], -b.rot[1], -b.rot[2]]
        cl = []
        for c in cubes_by_bone.get(b.name, []):
            w = c.hi[0] - c.lo[0]
            cl.append({
                "origin": [-c.hi[0], c.lo[1], c.lo[2]],
                "size": [w, c.hi[1] - c.lo[1], c.hi[2] - c.lo[2]],
                "uv": list(c.uv),
                **({"inflate": c.inflate} if c.inflate else {}),
                **({"mirror": True} if c.mirror else {}),
            })
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
                "visible_bounds_width": 24,
                "visible_bounds_height": 12,
                "visible_bounds_offset": [0, 4, 0],
            },
            "bones": bones_json,
        }],
    }
    with open(path, "w") as f:
        json.dump(doc, f, indent=1)


# ---------------------------------------------------------------------- main

def build_variant(v: Variant):
    reset()
    build_wyvern(v)
    ground_model()
    pack_uvs()
    fly_len, fly = fly_channels(v)
    vert_len, vert = fly_vertical_channels(v)
    anims = [("animation.wyvern.fly", fly_len, fly),
             ("animation.wyvern.fly_vertical", vert_len, vert)]
    export_bbmodel(os.path.join(OUT_DIR, f"wyvern_{v.name}.bbmodel"),
                   f"wyvern_{v.name}",
                   animations=[bb_animation(*a) for a in anims])
    export_geo(os.path.join(OUT_DIR, f"wyvern_{v.name}.geo.json"),
               f"geometry.allunderheaven.wyvern_{v.name}")
    export_animation_json(os.path.join(OUT_DIR, f"wyvern_{v.name}.animation.json"),
                          anims)
    render(35, 18, os.path.join(OUT_DIR, f"{v.name}_three_quarter.png"))
    render(90, 5, os.path.join(OUT_DIR, f"{v.name}_side.png"))
    render(0, 8, os.path.join(OUT_DIR, f"{v.name}_front.png"))
    head = bone_world_pivot("head")
    render(52, 10, os.path.join(OUT_DIR, f"{v.name}_head.png"),
           size=(900, 700), scale=8.5, center=head)
    render_fly_previews(v, fly, fly_len, "fly")
    render_fly_previews(v, vert, vert_len, "flyvert")
    print(f"{v.name}: bones={len(BONES)} cubes={len(CUBES)} "
          f"fly={fly_len:.2f}s vert={vert_len:.2f}s head_at="
          f"({head[0]:.0f},{head[1]:.0f},{head[2]:.0f})")


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    for variant in (RED, BLACK, WHITE):
        build_variant(variant)
    print("wrote", OUT_DIR)
