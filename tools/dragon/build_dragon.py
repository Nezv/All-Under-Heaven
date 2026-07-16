"""Procedural wyvern model generator for All Under Heaven.

Defines a semirealistic, House-of-the-Dragon-style wyvern (two hind legs,
wings as forelimbs) as a bone hierarchy + axis-aligned cuboids, then emits:

  out/wyvern.bbmodel   - Blockbench project (the editable master)
  out/wyvern.geo.json  - bedrock geometry for GeckoLib (used in a later step)
  out/preview_*.png    - software-rendered previews for fast iteration

Design rules that keep the model animation-ready:
  * All rest-pose orientation lives on BONES (groups), not cubes, so GeckoLib
    keyframes rotate the same pivots Blockbench shows.
  * Bones prefer a single rotation axis in rest pose; multi-axis rotations
    (wings) keep angles modest so euler-order differences stay invisible.
  * The wyvern faces NORTH (-Z), vanilla entity convention: head -Z, tail +Z.
  * 16 units = 1 block. Ground is y=0.

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


@dataclass
class Cube:
    bone: str
    lo: tuple[float, float, float]
    hi: tuple[float, float, float]
    inflate: float = 0.0
    mirror: bool = False
    uv: tuple[int, int] = (0, 0)  # filled by the packer


BONES: list[Bone] = []
CUBES: list[Cube] = []


def bone(name, parent, pivot, rot=(0, 0, 0), color=(140, 140, 140)):
    BONES.append(Bone(name, parent, pivot, rot, color))
    return name


def cube(bone_name, center, size, inflate=0.0, mirror=False):
    """Axis-aligned cube from center + size (pre-bone-rotation)."""
    cx, cy, cz = center
    sx, sy, sz = size
    CUBES.append(Cube(bone_name, (cx - sx / 2, cy - sy / 2, cz - sz / 2),
                      (cx + sx / 2, cy + sy / 2, cz + sz / 2), inflate, mirror))


# ------------------------------------------------------------ wyvern anatomy
# Palette (preview only): keeps parts readable in renders.
HIDE = (96, 108, 96)        # body scales: grey-green
HIDE_D = (78, 88, 78)       # darker hide (legs, tail underside)
MEMBRANE = (140, 84, 84)    # wing membrane: dull red
HORN = (196, 188, 168)      # bone/horn
RIDGE = (70, 78, 70)        # spine ridges


def build_wyvern():
    root = bone("root", None, (0, 0, 0))

    # --- body: chest (deep) + abdomen (narrower), back line sloping down ---
    body = bone("body", root, (0, 26, 4), color=HIDE)
    cube(body, (0, 25, -12), (22, 20, 26))           # chest
    cube(body, (0, 24, 10), (18, 16, 22))            # abdomen
    cube(body, (0, 16.5, -12), (14, 5, 22))          # keel / belly line

    # --- neck: 4 chained segments arcing up, tapering 13 -> 8 ---
    # Child pivots are authored UNROTATED (Blockbench semantics: a parent's
    # rest rotation carries its children), so the chain advances in straight
    # -Z steps and the S-curve comes purely from the nested bone rotations.
    neck_specs = [  # (pitch deg [+ lifts the head-ward end], length, w, h)
        (34, 13, 13, 12),
        (16, 12, 11, 11),
        (-6, 12, 10, 10),
        (-20, 12, 8.5, 9),
    ]
    parent = body
    tip = (0.0, 30.0, -24.0)  # neck root at front of chest
    for i, (pitch, length, w, h) in enumerate(neck_specs, 1):
        name = bone(f"neck{i}", parent, tip, rot=(pitch, 0, 0), color=HIDE)
        # segment cube sits from the pivot toward -Z (head-ward)
        cube(name, (tip[0], tip[1] + 0.5, tip[2] - length / 2), (w, h, length + 2))
        # ridge fin on top of each neck segment
        cube(name, (tip[0], tip[1] + h / 2 + 1.2, tip[2] - length / 2), (1.4, 3.2, length - 3))
        tip = (tip[0], tip[1], tip[2] - length)
        parent = name

    # --- head: skull + brow + tapering snout + jaw + horns + cheek frills ---
    # Own pitch cancels the accumulated neck arc so the head sits level.
    head = bone("head", parent, tip, rot=(-24, 0, 0), color=HIDE)
    hx, hy, hz = tip
    cube(head, (hx, hy + 1.5, hz - 5), (9, 8, 12))            # skull
    cube(head, (hx, hy + 4.6, hz - 8), (7.6, 2.6, 7))         # brow ledge
    cube(head, (hx, hy + 2.2, hz - 15), (6, 4.4, 12))         # snout upper
    cube(head, (hx, hy - 0.6, hz - 14.4), (5.2, 2.2, 10.6))   # snout lower lip line
    jaw = bone("jaw", head, (hx, hy - 1.6, hz - 2), rot=(-14, 0, 0), color=HIDE_D)
    cube(jaw, (hx, hy - 2.6, hz - 12), (4.8, 2.4, 15))        # lower jaw (slightly open)
    # horns: two segments each, swept back and out (shallow HotD sweep)
    for side, sx in (("l", 1), ("r", -1)):
        h1 = bone(f"horn_{side}_1", head, (hx + 3.2 * sx, hy + 5, hz + 0.5),
                  rot=(-26, -14 * sx, 0), color=HORN)
        cube(h1, (hx + 3.2 * sx, hy + 5, hz + 5.5), (2.2, 2.2, 11))
        h2 = bone(f"horn_{side}_2", h1, (hx + 3.2 * sx, hy + 5, hz + 11),
                  rot=(-16, 0, 0), color=HORN)
        cube(h2, (hx + 3.2 * sx, hy + 5, hz + 15.5), (1.4, 1.4, 10))
        # cheek frill
        cube(head, (hx + 4.8 * sx, hy + 1.5, hz + 1.5), (0.8, 5, 6), mirror=sx < 0)

    # --- wings: humerus -> forearm -> hand with 3 finger spars + membranes ---
    # Built for the LEFT (+X) side, mirrored programmatically for the right.
    def build_wing(side: str, sx: int):
        sh = (11 * sx, 31.0, -16.0)  # shoulder
        arm = bone(f"wing_{side}_arm", "body", sh,
                   rot=(0, 14 * sx, 20 * sx), color=HIDE)
        cube(arm, (sh[0] + 11 * sx, sh[1], sh[2]), (22, 4.6, 4.6))
        elbow = (sh[0] + 21 * sx, sh[1], sh[2])
        fore = bone(f"wing_{side}_fore", arm, elbow,
                    rot=(0, -18 * sx, -12 * sx), color=HIDE)
        cube(fore, (elbow[0] + 13 * sx, elbow[1], elbow[2]), (27, 3.6, 3.6))
        wrist = (elbow[0] + 26 * sx, elbow[1], elbow[2])
        hand = bone(f"wing_{side}_hand", fore, wrist, rot=(0, 0, 0), color=HIDE)
        cube(hand, (wrist[0] + 2.5 * sx, wrist[1], wrist[2]), (6, 3.2, 3.2))
        # small wing claw hooking forward off the wrist
        cube(hand, (wrist[0] + 2 * sx, wrist[1] + 1, wrist[2] - 3.4), (1.6, 1.6, 5))
        # finger spars fan backward (+Z), thin; membranes hang as thin plates
        fingers = [  # (yaw from straight-out, length)
            (14, 46),
            (38, 44),
            (66, 38),
        ]
        base = (wrist[0] + 5 * sx, wrist[1], wrist[2])
        for fi, (yaw, length) in enumerate(fingers, 1):
            fname = bone(f"wing_{side}_finger{fi}", hand, base,
                         rot=(0, -yaw * sx, 0), color=HIDE_D)
            cube(fname, (base[0] + (length / 2) * sx, base[1], base[2]),
                 (length, 2.0, 2.0))
            # membrane plate trailing this spar (thin in Y, sits just behind)
            mem_len = length - 4
            mem_depth = 22 if fi < 3 else 17
            mname = bone(f"wing_{side}_mem{fi}", fname, base, rot=(0, 0, 0),
                         color=MEMBRANE)
            cube(mname, (base[0] + (mem_len / 2 + 2) * sx, base[1] - 0.4,
                         base[2] + mem_depth / 2 + 1.2), (mem_len, 0.6, mem_depth))
        # armpit membrane between forearm and body
        mroot = bone(f"wing_{side}_mem0", fore, elbow, color=MEMBRANE)
        cube(mroot, (elbow[0] + 12 * sx, elbow[1] - 1.2, elbow[2] + 8.5), (24, 0.6, 14))

    build_wing("l", 1)
    build_wing("r", -1)

    # --- hind legs: digitigrade thigh -> shin -> foot -> toes ---
    def build_leg(side: str, sx: int):
        hip = (9.5 * sx, 24.0, 12.0)
        # Digitigrade: femur forward-down, tibia back-down, foot near-vertical.
        thigh = bone(f"leg_{side}_thigh", "body", hip, rot=(28, 0, 0), color=HIDE_D)
        cube(thigh, (hip[0], hip[1] - 6.5, hip[2] + 1), (7.5, 15, 10))
        knee = (hip[0], hip[1] - 12, hip[2] + 6)
        shin = bone(f"leg_{side}_shin", thigh, knee, rot=(-46, 0, 0), color=HIDE_D)
        cube(shin, (knee[0], knee[1] - 6, knee[2] - 1.5), (5, 13, 6))
        ankle = (knee[0], knee[1] - 11.5, knee[2] - 4)
        foot = bone(f"leg_{side}_foot", shin, ankle, rot=(18, 0, 0), color=HIDE_D)
        cube(foot, (ankle[0], ankle[1] - 4, ankle[2] - 1), (4.6, 8.5, 5))
        toes = bone(f"leg_{side}_toes", foot, (ankle[0], ankle[1] - 8, ankle[2] - 2),
                    color=HIDE_D)
        for ti, tox in enumerate((-1.8, 0.0, 1.8)):
            cube(toes, (ankle[0] + tox * sx, ankle[1] - 8 + 1.2, ankle[2] - 5.5),
                 (1.8, 2.4, 8))

    build_leg("l", 1)
    build_leg("r", -1)

    # --- tail: 7 chained segments, tapering, gentle droop then upcurve ---
    tail_specs = [  # (pitch, length, width, height)
        (14, 15, 12, 11),
        (8, 15, 10, 9.5),
        (4, 16, 8, 8),
        (-4, 16, 6.5, 6.5),
        (-8, 16, 5, 5),
        (-10, 17, 3.6, 3.6),
        (-8, 18, 2.4, 2.4),
    ]
    parent = "body"
    tip = (0.0, 26.0, 20.0)
    for i, (pitch, length, w, h) in enumerate(tail_specs, 1):
        name = bone(f"tail{i}", parent, tip, rot=(pitch, 0, 0), color=HIDE)
        cube(name, (tip[0], tip[1], tip[2] + length / 2), (w, h, length + 2))
        if i <= 5:  # spine ridges fade out toward the tip
            cube(name, (tip[0], tip[1] + h / 2 + 1.1, tip[2] + length / 2),
                 (1.2, 2.6, length - 4))
        tip = (tip[0], tip[1], tip[2] + length)
        parent = name
    # tail tip fin (vertical blade)
    fin = bone("tail_fin", parent, tip, color=MEMBRANE)
    cube(fin, (tip[0], tip[1] + 1, tip[2] + 4), (0.8, 7, 10))

    # --- back ridges along the spine ---
    for rz, ry in ((-20, 36.2), (-13, 36.6), (-6, 36.6), (1, 36.2), (8, 34.4), (15, 33.4)):
        cube("body", (0, ry, rz), (1.6, 3.4, 5.4))


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


# ------------------------------------------------------------------ UV packer

def pack_uvs():
    """Shelf-packs box UVs onto the 256x256 sheet, biggest cubes first."""
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


def bone_world_transform():
    """name -> function(point) applying the full parent chain (rest pose)."""
    by_name = {b.name: b for b in BONES}
    cache: dict[str, callable] = {}

    def transform_of(name: str):
        if name in cache:
            return cache[name]
        b = by_name[name]
        parent_fn = transform_of(b.parent) if b.parent else (lambda p: p)
        rx, ry, rz = (math.radians(a) for a in b.rot)
        px, py, pz = b.pivot

        def fn(p, _parent=parent_fn, _rx=rx, _ry=ry, _rz=rz, _pv=(px, py, pz)):
            v = (p[0] - _pv[0], p[1] - _pv[1], p[2] - _pv[2])
            v = _rot_z(v, _rz)
            v = _rot_y(v, _ry)
            v = _rot_x(v, _rx)
            v = (v[0] + _pv[0], v[1] + _pv[1], v[2] + _pv[2])
            return _parent(v)

        cache[name] = fn
        return fn

    return {b.name: transform_of(b.name) for b in BONES}


# ------------------------------------------------------------------ renderer

FACES = (  # vertex indices per face + outward normal (axis-aligned, pre-rotation)
    ((0, 1, 3, 2), (-1, 0, 0)), ((4, 6, 7, 5), (1, 0, 0)),
    ((0, 4, 5, 1), (0, -1, 0)), ((2, 3, 7, 6), (0, 1, 0)),
    ((0, 2, 6, 4), (0, 0, -1)), ((1, 5, 7, 3), (0, 0, 1)),
)


def render(view_yaw, view_pitch, path, size=(1000, 780), scale=3.0):
    transforms = bone_world_transform()
    colors = {b.name: b.color for b in BONES}
    yaw, pitch = math.radians(view_yaw), math.radians(view_pitch)
    light = (0.4, 0.8, -0.45)
    ll = math.sqrt(sum(c * c for c in light))
    light = tuple(c / ll for c in light)

    polys = []
    for c in CUBES:
        fn = transforms[c.bone]
        lo = (c.lo[0] - c.inflate, c.lo[1] - c.inflate, c.lo[2] - c.inflate)
        hi = (c.hi[0] + c.inflate, c.hi[1] + c.inflate, c.hi[2] + c.inflate)
        verts = [fn((x, y, z)) for x in (lo[0], hi[0]) for y in (lo[1], hi[1])
                 for z in (lo[2], hi[2])]
        base = colors[c.bone]
        for idx, normal in FACES:
            pts = [verts[i] for i in idx]
            # world normal ~ rotated normal: approximate from the polygon
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
                v = _rot_y(p, yaw)
                v = _rot_x(v, pitch)
                proj.append((size[0] / 2 + v[0] * scale,
                             size[1] * 0.62 - v[1] * scale))
                depth += v[2]
            polys.append((depth / 4, proj, col))

    polys.sort(key=lambda t: -t[0])  # painter's: farthest first
    img = Image.new("RGB", size, (24, 26, 30))
    drw = ImageDraw.Draw(img)
    # ground line
    gy = size[1] * 0.62
    drw.line([(0, gy), (size[0], gy)], fill=(45, 48, 52), width=1)
    for _, proj, col in polys:
        drw.polygon(proj, fill=col, outline=tuple(int(c * 0.75) for c in col))
    img.save(path)


# ------------------------------------------------------------------ exports

def export_bbmodel(path):
    elements = []
    uuids = {}
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
            "origin": list(BONES[[b.name for b in BONES].index(c.bone)].pivot),
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
            "uuid": str(uuid.uuid4()),
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
        "name": "wyvern",
        "model_identifier": "wyvern",
        "visible_box": [1, 1, 0],
        "variable_placeholders": "",
        "variable_placeholder_buttons": [],
        "resolution": {"width": TEX_W, "height": TEX_H},
        "elements": elements,
        "outliner": roots,
        "textures": [],
        "animations": [],
    }
    with open(path, "w") as f:
        json.dump(doc, f, indent=1)


def export_geo(path):
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
                "identifier": "geometry.allunderheaven.wyvern",
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

if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    build_wyvern()
    ground_model()
    pack_uvs()
    export_bbmodel(os.path.join(OUT_DIR, "wyvern.bbmodel"))
    export_geo(os.path.join(OUT_DIR, "wyvern.geo.json"))
    render(35, 18, os.path.join(OUT_DIR, "preview_three_quarter.png"))
    render(90, 5, os.path.join(OUT_DIR, "preview_side.png"))
    render(0, 8, os.path.join(OUT_DIR, "preview_front.png"))
    render(30, 55, os.path.join(OUT_DIR, "preview_top.png"))
    print(f"bones={len(BONES)} cubes={len(CUBES)}")
    print("wrote", OUT_DIR)
