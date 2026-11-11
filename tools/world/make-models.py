"""The world's models, built from scratch and exported as glTF binaries.

Run headless (see README.md):

    blender --background --factory-startup --python tools/world/make-models.py -- [--preview PNG]

Every piece is modelled in world units (metres), y up, its front facing +z, then exported to
resources/web/world/models/<piece>.glb with transforms applied. Colour lives in per-material base
colours; ambient occlusion is ray-cast here and stored in the COLOR_0 vertex colours (light grey,
darker in creases), which the engine multiplies in. Shapes are chunky and slightly wobbly on
purpose: the engine draws them in three cel bands with a chalk outline. All randomness is seeded,
so every run writes the same files.

With --preview, also renders a contact sheet of every piece (cel bands + chalk outline, EEVEE).
"""

import math
import os
import random
import sys
from math import cos, pi, sin, sqrt

import bmesh
import bpy
from mathutils import Vector
from mathutils.bvhtree import BVHTree

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(ROOT, "resources", "web", "world", "models")

TAU = 2 * pi


# ---------------------------------------------------------------- colours and materials

def lin(hexrgb):
    """An sRGB hex colour as linear RGBA (what Blender's nodes and glTF's factors hold)."""
    def ch(v):
        v /= 255
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    return (ch(hexrgb >> 16 & 255), ch(hexrgb >> 8 & 255), ch(hexrgb & 255), 1.0)


def material(name, hexrgb, glow=False, double=False):
    """A plain material: base colour, rough, optionally glowing in its own colour."""
    mt = bpy.data.materials.new(name)
    bsdf = mt.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Base Color"].default_value = lin(hexrgb)
    bsdf.inputs["Roughness"].default_value = 0.9
    bsdf.inputs["Metallic"].default_value = 0.0
    if glow:
        bsdf.inputs["Emission Color"].default_value = lin(hexrgb)
        bsdf.inputs["Emission Strength"].default_value = 1.0
    mt.use_backface_culling = not double
    mt["hex"] = hexrgb
    mt["glow"] = glow
    return mt


# ---------------------------------------------------------------- a tiny mesh builder (world coords)

class M:
    """Vertices (world coords: y up, +z front), faces as index lists, a material name per face."""

    def __init__(self):
        self.v, self.f, self.mat = [], [], []

    def vert(self, p):
        self.v.append(Vector(p))
        return len(self.v) - 1

    def face(self, ids, mat):
        self.f.append(list(ids))
        self.mat.append(mat)

    def add(self, *others):
        for o in others:
            n = len(self.v)
            self.v += [p.copy() for p in o.v]
            self.f += [[i + n for i in f] for f in o.f]
            self.mat += list(o.mat)
        return self

    def map(self, fn):
        self.v = [Vector(fn(p)) for p in self.v]
        return self

    def move(self, x, y, z):
        d = Vector((x, y, z))
        return self.map(lambda p: p + d)

    def scale(self, x, y, z):
        return self.map(lambda p: (p.x * x, p.y * y, p.z * z))

    def rot_y(self, a):
        c, s = cos(a), sin(a)
        return self.map(lambda p: (p.x * c + p.z * s, p.y, -p.x * s + p.z * c))

    def rot_x(self, a):
        c, s = cos(a), sin(a)
        return self.map(lambda p: (p.x, p.y * c - p.z * s, p.y * s + p.z * c))

    def rot_z(self, a):
        c, s = cos(a), sin(a)
        return self.map(lambda p: (p.x * c - p.y * s, p.x * s + p.y * c, p.z))

    def wobble(self, amp, freq, seed, lean=0.0):
        """A smooth hand-made wobble (a few seeded sine waves, so touching parts move together) and a
        lean."""
        rng = random.Random(seed)
        waves = [[(Vector([rng.uniform(-1, 1) for _ in range(3)]).normalized() * freq * rng.uniform(0.7, 1.4),
                   rng.uniform(0, TAU)) for _ in range(3)] for _ in range(3)]

        def field(p, axis):
            return sum(sin(k.dot(p) + ph) for k, ph in waves[axis]) / 3

        def f(p):
            dy = field(p, 1) * amp * 0.5 if p.y > 0.001 else 0.0  # the floor stays at y = 0
            return (p.x + field(p, 0) * amp + lean * p.y, p.y + dy, p.z + field(p, 2) * amp)
        return self.map(f)


def ring(r, y, seg, rot=0.0, cx=0.0, cz=0.0, rfn=None, yfn=None):
    """seg points round the y axis at height y (angle increasing from +x toward +z)."""
    pts = []
    for i in range(seg):
        a = rot + TAU * i / seg
        rr = rfn(i, a) if rfn else r
        yy = yfn(i, a) if yfn else y
        pts.append((cx + rr * cos(a), yy, cz + rr * sin(a)))
    return pts


def loft(rows, mats, top=None, bottom=None, closed=False):
    """Bands of quads between rows of points (same count, closed loops), rows[k]->rows[k+1] in
    mats[k]. top/bottom: None (open), ('ngon', mat) or ('fan', mat, apex)."""
    m = M()
    ids = [[m.vert(p) for p in row] for row in rows]
    n = len(rows[0])
    for k in range(len(rows) - (0 if closed else 1)):
        a, b = ids[k], ids[(k + 1) % len(rows)]
        for i in range(n):
            j = (i + 1) % n
            m.face([a[i], a[j], b[j], b[i]], mats[k])

    def cap(row, spec, is_top):
        if spec is None:
            return
        if spec[0] == "ngon":
            m.face(row[::-1] if is_top else row, spec[1])
        else:
            apex = m.vert(spec[2])
            for i in range(n):
                j = (i + 1) % n
                m.face([row[j], row[i], apex] if is_top else [row[i], row[j], apex], spec[1])
    cap(ids[0], top, True)
    cap(ids[-1], bottom, False)
    return m


def box(cx, cy, cz, sx, sy, sz, mat, taper=1.0):
    """A box centred at (cx, cy, cz); taper narrows its top."""
    hx, hy, hz = sx / 2, sy / 2, sz / 2
    corners = [(-1, -1), (1, -1), (1, 1), (-1, 1)]
    top = [(cx + x * hx * taper, cy + hy, cz + z * hz * taper) for x, z in corners]
    bot = [(cx + x * hx, cy - hy, cz + z * hz) for x, z in corners]
    return loft([top, bot], [mat], top=("ngon", mat), bottom=("ngon", mat))


def prism(profile, z0, z1, mat):
    """A 2D profile (x, y), extruded along z from z0 to z1."""
    return loft([[(x, y, z1) for x, y in profile], [(x, y, z0) for x, y in profile]], [mat],
                top=("ngon", mat), bottom=("ngon", mat))


def arch(w, h, steps=4):
    """A door's outline: straight sides, a round top (w wide, h tall to the top of the arch)."""
    r = w / 2
    pts = [(-r, 0.0), (r, 0.0)]
    for i in range(steps + 1):
        a = pi * i / steps
        pts.append((r * cos(a), h - r + r * sin(a)))
    return pts


def blob(rx, ry, rz, seg, rings, mat, rng=None, jit=0.0, e=1.0):
    """A (super)ellipsoid of seg x rings; jit pushes vertices in and out a little."""
    rows = []
    for k in range(1, rings):
        th = pi / 2 - pi * k / rings
        row = []
        for i in range(seg):
            a = TAU * i / seg + (0.5 * TAU / seg if k % 2 else 0)
            c, s = cos(th), sin(th)
            ca, sa = cos(a), sin(a)
            sg = lambda v: math.copysign(abs(v) ** e, v)
            k_ = 1 + (rng.uniform(-jit, jit) if rng else 0)
            row.append((rx * sg(c) * sg(ca) * k_, ry * sg(s) * k_, rz * sg(c) * sg(sa) * k_))
        rows.append(row)
    top = (0, ry * (1 + (rng.uniform(-jit, jit) if rng else 0)), 0)
    bot = (0, -ry * (1 + (rng.uniform(-jit, jit) if rng else 0)), 0)
    return loft(rows, [mat] * len(rows), top=("fan", mat, top), bottom=("fan", mat, bot))


def ico(r, mat, rng, jit=0.0, sub=2):
    """An icosphere with its vertices pushed in and out (a chunky stone or bush)."""
    bm = bmesh.new()
    bmesh.ops.create_icosphere(bm, subdivisions=sub, radius=r)
    bm.verts.index_update()
    m = M()
    for v in bm.verts:
        m.vert(v.co * (1 + rng.uniform(-jit, jit)))
    for f in bm.faces:
        m.face([v.index for v in f.verts], mat)
    bm.free()
    return m


# ---------------------------------------------------------------- into Blender

def w2b(p):
    """World (y up, +z front) to Blender (z up, -y front): the glTF exporter's +Y up undoes it."""
    return Vector((p[0], -p[2], p[1]))


def make_object(name, m, mats, coll, sharp=None, recalc=True):
    """A Blender mesh object from M; sharp: edges sharper than this many degrees stay hard (None =
    all smooth)."""
    names = []
    for x in m.mat:
        if x not in names:
            names.append(x)
    me = bpy.data.meshes.new(name)
    me.from_pydata([w2b(p) for p in m.v], [], m.f)
    for i, poly in enumerate(me.polygons):
        poly.material_index = names.index(m.mat[i])
    for n in names:
        me.materials.append(mats[n])
    bm = bmesh.new()
    bm.from_mesh(me)
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-5)
    if recalc:
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bmesh.ops.triangulate(bm, faces=bm.faces, quad_method="BEAUTY", ngon_method="BEAUTY")
    bm.to_mesh(me)
    bm.free()
    me.shade_smooth()
    if sharp is not None:
        me.set_sharp_from_angle(angle=math.radians(sharp))
    ob = bpy.data.objects.new(name, me)
    coll.objects.link(ob)
    return ob


def hemisphere(n):
    """n cosine-weighted directions round +z (a Fibonacci spiral: even and fixed)."""
    g = pi * (3 - sqrt(5))
    out = []
    for i in range(n):
        u = (i + 0.5) / n
        r = sqrt(u)
        out.append(Vector((r * cos(i * g), r * sin(i * g), sqrt(1 - u))))
    return out


def bake_ao(objs, reach, ground=False, dark=0.4, rays=96):
    """Ambient occlusion per vertex (per corner where normals split), ray-cast against every part of
    the piece (and the ground under it), into a 'Color' corner attribute: dark..1 linear grey."""
    verts, polys = [], []
    for o in objs:
        off = len(verts)
        verts += [o.matrix_world @ v.co for v in o.data.vertices]
        polys += [[off + i for i in p.vertices] for p in o.data.polygons]
    if ground:
        g = len(verts)
        verts += [Vector((-60, -60, 0)), Vector((60, -60, 0)), Vector((60, 60, 0)), Vector((-60, 60, 0))]
        polys.append([g, g + 1, g + 2, g + 3])
    bvh = BVHTree.FromPolygons(verts, polys)
    dirs = hemisphere(rays)
    for o in objs:
        me = o.data
        attr = me.color_attributes.new("Color", "BYTE_COLOR", "CORNER")
        normals = me.corner_normals
        cache = {}
        for li, loop in enumerate(me.loops):
            n = Vector(normals[li].vector).normalized()
            vi = loop.vertex_index
            key = (vi, round(n.x, 3), round(n.y, 3), round(n.z, 3))
            if key not in cache:
                p = o.matrix_world @ me.vertices[vi].co + n * 0.0025
                t = n.orthogonal().normalized()
                b = n.cross(t)
                occ = 0.0
                for d in dirs:
                    w = t * d.x + b * d.y + n * d.z
                    hit = bvh.ray_cast(p, w, reach)
                    if hit[0] is not None:
                        occ += 1 - (hit[3] / reach) ** 0.7
                ao = max(0.0, 1 - 1.35 * occ / len(dirs))
                cache[key] = dark + (1 - dark) * ao
            c = cache[key]
            attr.data[li].color = (c, c, c, 1.0)
        me.color_attributes.active_color = attr
        me.color_attributes.render_color_index = me.color_attributes.find("Color")


# ---------------------------------------------------------------- the pieces

GRASS, SOIL, ROCK = 0x7CBF5C, 0x9B7552, 0x8A8178
TRUNK, LEAVES = 0x7A5636, 0x4F9A4A


def island(variant):
    """A floating island of radius 1: grass on top at y = 0 with drips over its rim, a soil band, a
    jagged rock hanging below to about y = -1.6."""
    rng = random.Random(100 + variant)
    seg = 26
    ph, ph2 = rng.uniform(0, TAU), rng.uniform(0, TAU)
    lobes = [5, 7, 6][variant]
    rim = [1.0 + 0.035 * sin(3 * TAU * i / seg + ph) + rng.uniform(-0.018, 0.018) for i in range(seg)]
    drip = [0.09 + 0.15 * max(0.0, sin(lobes * TAU * i / seg + ph2)) ** 1.6 + rng.uniform(0, 0.035)
            for i in range(seg)]
    angles = [TAU * i / seg for i in range(seg)]

    def row(rs, ys):
        return [(rs[i] * cos(a), ys[i], rs[i] * sin(a)) for i, a in enumerate(angles)]

    top = M().add(loft(
        [row([0.5] * seg, [0.0] * seg),
         row([0.82 * r for r in rim], [rng.uniform(-0.002, 0.006) for _ in angles]),
         row(rim, [0.0] * seg),
         row([1.045 * r for r in rim], [-0.055] * seg),
         row([1.03 * r for r in rim], [-d for d in drip]),
         row([0.95 * r for r in rim], [-0.085] * seg),
         row([0.93 * r * rng.uniform(0.97, 1.03) for r in rim], [-0.24 + rng.uniform(-0.03, 0.03) for _ in angles]),
         row([0.87 * r for r in rim], [-0.40] * seg)],
        ["grass", "grass", "grass", "grass", "grass", "soil", "soil"],
        top=("fan", "grass", (0, 0.0, 0)), bottom=("ngon", "soil")))

    # The rock: few sides, every ring turned and jittered, so it reads as cut stone.
    rs = [9, 10, 8][variant]
    table = [
        [(-0.34, 0.80), (-0.50, 0.92), (-0.80, 0.72), (-1.10, 0.46), (-1.36, 0.22)],
        [(-0.34, 0.80), (-0.50, 0.90), (-0.74, 0.74), (-1.00, 0.50), (-1.20, 0.28)],
        [(-0.34, 0.80), (-0.50, 0.95), (-0.85, 0.78), (-1.15, 0.45), (-1.38, 0.2)],
    ][variant]
    rows = []
    for k, (y, r) in enumerate(table):
        rot = rng.uniform(0, TAU / rs) if k else 0
        rows.append(ring(r, y, rs, rot=rot,
                         rfn=lambda i, a, r=r, k=k: r * (rng.uniform(0.88, 1.1) if k else 1),
                         yfn=lambda i, a, y=y, k=k: y + (rng.uniform(-0.04, 0.04) if k > 1 else 0)))
    apex = [(0.06, -1.62, -0.04), (0.18, -1.42, 0.08), (-0.05, -1.58, 0.1)][variant]
    rock = loft(rows, ["rock"] * len(rows), top=("ngon", "rock"), bottom=("fan", "rock", apex))

    # Stalactites poking out of the rock's sides.
    spikes = [
        [(0.6, -0.72, 0.48, 0.16, 0.42)],
        [(2.6, -0.80, 0.40, 0.22, 0.80), (4.6, -0.70, 0.52, 0.15, 0.40)],
        [(1.4, -0.95, 0.52, 0.18, 0.50), (3.9, -0.90, 0.55, 0.15, 0.38), (5.6, -0.98, 0.50, 0.13, 0.30)],
    ][variant]
    for a, y, d, r, ln in spikes:
        cx, cz = d * cos(a), d * sin(a)
        tip = (cx * 1.12, y - ln, cz * 1.12)
        s = loft([ring(r, y + 0.06, 5, rot=a, cx=cx, cz=cz),
                  ring(r * 0.62, y - ln * 0.45, 5, rot=a + 0.6, cx=cx * 1.08, cz=cz * 1.08)],
                 ["rock", "rock"], top=("ngon", "rock"), bottom=("fan", "rock", tip))
        rock.add(s)
    # Boulders stuck to the rock's sides: big chunky facets that read through the outline.
    chunks = [
        [(2.0, -0.66, 0.7, 0.2), (4.3, -0.95, 0.47, 0.17)],
        [(0.9, -0.62, 0.74, 0.19), (3.6, -0.7, 0.68, 0.15)],
        [(1.0, -0.72, 0.7, 0.2), (3.3, -0.6, 0.8, 0.18), (5.0, -1.02, 0.42, 0.16)],
    ][variant]
    for a, y, d, r in chunks:
        rock.add(ico(r, "rock", rng, 0.15, sub=1).scale(1.0, 1.25, 1.0).rot_y(a).move(d * cos(a), y, d * sin(a)))
    top.add(rock)
    return top


def building(variant):
    """A little cartoon house on a 1 x 1 footprint at the origin, door on +z."""
    rng = random.Random(200 + variant)
    m = M()
    if variant == 0:  # cottage: gable to the front, chimney, round attic window
        walls = prism([(-0.45, 0), (0.45, 0), (0.45, 0.66), (0, 1.03), (-0.45, 0.66)], -0.42, 0.42, "wall")
        roof = prism([(-0.62, 0.55), (0, 1.2), (0.62, 0.55), (0.62, 0.44), (0, 1.07), (-0.62, 0.44)],
                     -0.53, 0.53, "roof")
        door = prism(arch(0.27, 0.44), 0.40, 0.455, "door")
        m.add(walls, roof, door,
              box(0.27, 1.0, -0.15, 0.15, 0.42, 0.15, "wall", taper=0.9),
              box(0.27, 1.23, -0.15, 0.2, 0.06, 0.2, "roof"),
              prism([(0.085 * cos(TAU * i / 8 + pi / 8), 0.8 + 0.085 * sin(TAU * i / 8 + pi / 8))
                     for i in range(8)], 0.40, 0.445, "window"))
        for x in (-0.3, 0.3):
            m.add(box(x, 0.4, 0.425, 0.16, 0.18, 0.045, "window"), box(x, 0.295, 0.44, 0.22, 0.035, 0.07, "roof"))
        for x in (-1, 1):
            m.add(box(0.425 * x, 0.38, -0.05, 0.045, 0.18, 0.2, "window"),
                  box(0.44 * x, 0.275, -0.05, 0.07, 0.035, 0.26, "roof"))
        m.wobble(0.035, 1.6, 1, lean=0.025)
    elif variant == 1:  # workshop: shed roof, barn door with a brace, stove pipe
        walls = prism([(-0.55, 0), (0.55, 0), (0.55, 0.72), (-0.55, 1.0)], -0.45, 0.45, "wall")
        roof = prism([(-0.68, 1.09), (0.68, 0.74), (0.68, 0.63), (-0.68, 0.98)], -0.56, 0.56, "roof")
        m.add(walls, roof,
              box(-0.14, 0.29, 0.465, 0.48, 0.58, 0.05, "door"),
              box(-0.14, 0.11, 0.495, 0.42, 0.05, 0.025, "door"),
              box(-0.14, 0.47, 0.495, 0.42, 0.05, 0.025, "door"),
              box(0.33, 0.47, 0.465, 0.2, 0.2, 0.045, "window"),
              box(0.33, 0.35, 0.48, 0.26, 0.035, 0.07, "roof"),
              box(-0.565, 0.5, 0.05, 0.045, 0.2, 0.3, "window"))
        brace = box(0, 0, 0, 0.05, 0.46, 0.025, "door").rot_z(0.76).move(-0.14, 0.29, 0.495)
        m.add(brace)
        pipe = loft([ring(0.06, 1.42, 7, cx=0.32, cz=-0.2), ring(0.06, 0.8, 7, cx=0.32, cz=-0.2)],
                    ["roof"], top=("ngon", "roof"), bottom=("ngon", "roof"))
        hat = loft([ring(0.1, 1.5, 7, cx=0.32, cz=-0.2), ring(0.1, 1.44, 7, cx=0.32, cz=-0.2)],
                   ["roof"], top=("ngon", "roof"), bottom=("ngon", "roof"))
        m.add(pipe, hat)
        m.wobble(0.03, 1.7, 2, lean=-0.02)
    elif variant == 2:  # tower: tall, steep curved spire, a flag on top
        m.add(box(0, 0.48, 0, 0.74, 0.96, 0.74, "wall", taper=0.9))
        r0 = 0.52 * sqrt(2)
        spire = loft([ring(r0 * 0.45, 1.16, 4, rot=pi / 4, cx=0.02),
                      ring(r0, 0.96, 4, rot=pi / 4),
                      ring(r0, 0.89, 4, rot=pi / 4),
                      ring(0.3, 0.89, 4, rot=pi / 4)],
                     ["roof", "roof", "roof"], top=("fan", "roof", (0.07, 1.47, 0.0)), bottom=("ngon", "roof"))
        m.add(spire,
              prism(arch(0.25, 0.42), 0.3, 0.355, "door"),
              box(0, 0.7, 0.33, 0.17, 0.17, 0.045, "window"),
              box(0, 0.6, 0.345, 0.23, 0.035, 0.07, "roof"),
              box(0.335, 0.55, 0, 0.045, 0.2, 0.15, "window"),
              box(-0.335, 0.62, 0, 0.045, 0.2, 0.15, "window"),
              box(0.07, 1.52, 0, 0.022, 0.16, 0.022, "door"),
              prism([(0.08, 1.6), (0.25, 1.55), (0.08, 1.5)], -0.012, 0.012, "door"))
        m.wobble(0.03, 1.8, 3, lean=0.03)
    else:  # round hut: drum walls, witch-hat roof with a bent tip
        seg = 11
        walls = loft([ring(0.43, 0.74, seg), ring(0.48, 0.0, seg)], ["wall"], top=("ngon", "wall"),
                     bottom=("ngon", "wall"))
        # (y, r, bend x, bend z) from the bent tip down to the eave, then in under it
        prof = [(1.4, 0.06, 0.15, 0.04), (1.22, 0.15, 0.07, 0.02), (1.02, 0.33, 0.02, 0.0),
                (0.8, 0.6, 0.0, 0.0), (0.7, 0.64, 0.0, 0.0), (0.7, 0.38, 0.0, 0.0)]
        rows = [ring(r, y, seg, cx=dx, cz=dz) for y, r, dx, dz in prof]
        roof = loft(rows, ["roof"] * len(rows), top=("fan", "roof", (0.27, 1.5, 0.06)), bottom=("ngon", "roof"))
        m.add(walls, roof, prism(arch(0.26, 0.44), 0.42, 0.5, "door"))
        for a in (-0.95, 0.95):
            w = M().add(box(0, 0.42, 0.455, 0.15, 0.15, 0.06, "window"),
                        box(0, 0.32, 0.475, 0.2, 0.035, 0.07, "roof")).rot_y(a)
            m.add(w)
        m.wobble(0.03, 1.7, 4, lean=0.0)
    return m


def lighthouse():
    """The gate's lighthouse: white tower with red bands on a stone plinth, a gallery with a railing,
    the lamp room (its glass is 'lamp'), a red roof with a finial; about 5.5 tall."""
    rng = random.Random(300)
    m = M()
    seg = 14
    m.add(loft([ring(0.9, 0.24, 8, rot=0.2), ring(0.95, 0.0, 8, rot=0.2)], ["trim"],
               top=("ngon", "trim"), bottom=("ngon", "trim")))
    bands = [(0.24, "white"), (1.05, "red"), (1.75, "white"), (2.45, "red"), (3.1, "white"), (3.75, None)]
    rows, mats = [], []

    def radius(y):
        return 0.82 - (y - 0.24) * (0.82 - 0.52) / (3.75 - 0.24)
    for k, (y, mat) in enumerate(bands):
        r = radius(y)
        wob = lambda i, a, r=r: r * (1 + rng.uniform(-0.012, 0.012))
        if k and bands[k - 1][1] == "red":  # step back in at a red band's top
            rows.append(ring(r + 0.035, y, seg, rfn=lambda i, a, r=r: (r + 0.035)))
            mats.append("red")
        rows.append(ring(r, y, seg, rfn=wob))
        if mat == "red":  # step out at a red band's bottom
            mats.append("white")
            rows.append(ring(r + 0.035, y, seg, rfn=lambda i, a, r=r: (r + 0.035)))
        if mat:
            mats.append(mat)
    m.add(loft(rows, mats, top=("ngon", "white"), bottom=("ngon", "white")))
    m.add(prism(arch(0.34, 0.66), 0.7, 0.86, "door").move(0, 0.24, 0))
    m.add(box(0, 2.1, 0.645, 0.17, 0.24, 0.06, "window"), box(0, 1.96, 0.66, 0.24, 0.04, 0.08, "trim"))
    w = box(0, 3.4, 0.56, 0.15, 0.2, 0.06, "window").rot_y(1.2)
    m.add(w)
    # gallery: a flared bracket, the deck, posts and a rail
    m.add(loft([ring(0.88, 3.98, seg), ring(0.9, 3.86, seg), ring(0.5, 3.6, seg)],
               ["trim", "trim"], top=("ngon", "trim"), bottom=("ngon", "trim")))
    for i in range(12):
        a = TAU * i / 12 + 0.13
        m.add(box(0.8 * cos(a), 4.12, 0.8 * sin(a), 0.045, 0.3, 0.045, "trim"))
    m.add(loft([ring(0.86, 4.24, seg), ring(0.86, 4.3, seg), ring(0.76, 4.3, seg), ring(0.76, 4.24, seg)],
               ["trim"] * 4, closed=True))
    # lamp room
    m.add(loft([ring(0.48, 4.16, 10), ring(0.5, 3.98, 10)], ["white"], top=("ngon", "white"), bottom=("ngon", "white")))
    m.add(loft([ring(0.43, 4.72, 10), ring(0.43, 4.14, 10)], ["lamp"], top=("ngon", "lamp"), bottom=("ngon", "lamp")))
    for i in range(5):
        a = TAU * i / 5 + 0.3
        m.add(box(0.44 * cos(a), 4.44, 0.44 * sin(a), 0.05, 0.6, 0.05, "trim"))
    # roof: a flared, slightly concave cap, a ball and a spike
    m.add(loft([ring(0.2, 5.08, 10), ring(0.42, 4.88, 10), ring(0.64, 4.74, 10), ring(0.64, 4.67, 10),
                ring(0.4, 4.67, 10)], ["roof"] * 4, top=("fan", "roof", (0.0, 5.26, 0.0)), bottom=("ngon", "roof")))
    m.add(blob(0.075, 0.075, 0.075, 8, 5, "trim").move(0, 5.31, 0))
    m.add(loft([ring(0.022, 5.37, 5)], [], top=("fan", "trim", (0, 5.5, 0)), bottom=("ngon", "trim")))
    m.wobble(0.03, 0.9, 5, lean=0.012)
    return m


# The robot's shape, shared by its parts.
BODY_A, BODY_B, BODY_C, BODY_E = 0.35, 0.36, 0.31, 0.6
VISOR_Y, VISOR_W, VISOR_H = 0.06, 0.245, 0.145


def taper(y):
    """The body narrows toward its bottom (a gumdrop)."""
    return 0.78 + 0.22 * (y + BODY_B) / (2 * BODY_B)


def robot_parts():
    """The robot's parts as {name: (M, sharp angle or None, recalc normals?)}."""
    body = blob(BODY_A, BODY_B, BODY_C, 20, 12, "body", e=BODY_E)
    body.map(lambda p: (p.x * taper(p.y), p.y, p.z * taper(p.y)))
    for s in (-1, 1):  # ears: a disc and a nub on each side
        ear = loft([ring(0.1, 0.035, 12), ring(0.1, -0.035, 12)], ["body"],
                   top=("ngon", "body"), bottom=("ngon", "body"))
        nub = loft([ring(0.05, 0.075, 8), ring(0.055, 0.0, 8)], ["body"],
                   top=("ngon", "body"), bottom=("ngon", "body"))
        e = M().add(ear, nub).rot_z(-s * pi / 2).move(s * 0.3, 0.04, 0.0)
        body.add(e)
    body.add(loft([ring(0.15, -0.32, 12), ring(0.135, -0.39, 12), ring(0.11, -0.41, 12)],
                  ["body", "body"], top=("ngon", "body"), bottom=("ngon", "body")))

    shell = BVHTree.FromPolygons(body.v, body.f)

    def on_body(x, y, d):
        """The point d in front of the body's front surface at (x, y)."""
        hit = shell.ray_cast(Vector((x, y, 1.0)), Vector((0, 0, -1)))[0]
        return (x, y, hit.z + d)
    n = 28
    outline = []
    for i in range(n):
        t = TAU * i / n
        c, s = cos(t), sin(t)
        outline.append((VISOR_W * math.copysign(abs(c) ** 0.3, c), VISOR_H * math.copysign(abs(s) ** 0.3, s)))
    scales = [1.0, 0.72, 0.42, 0.14]
    rows = [[on_body(u * k, VISOR_Y + v * k, 0.022) for u, v in outline] for k in scales]
    back = [on_body(u, VISOR_Y + v, -0.04) for u, v in outline]
    visor = loft([back] + rows, ["visor"] * (len(rows) + 1), top=("ngon", "visor"),
                 bottom=("fan", "visor", on_body(0, VISOR_Y, 0.022)))

    eyes = M()
    for x in (-0.105, 0.105):
        eye = blob(0.056, 0.078, 0.03, 10, 6, "eyes")
        eyes.add(eye.rot_y(x * 2.2).move(*on_body(x, VISOR_Y + 0.01, 0.026)))

    path = [(0.0, 0.32, 0.0), (0.0, 0.43, -0.004), (0.012, 0.51, -0.018), (0.03, 0.56, -0.04)]
    antenna = M().add(loft([ring(0.055, 0.385, 10), ring(0.065, 0.32, 10)], ["antenna"],
                           top=("ngon", "antenna"), bottom=("ngon", "antenna")))
    antenna.add(loft([ring(0.02 - 0.003 * k, y, 6, cx=x, cz=z) for k, (x, y, z) in enumerate(path)],
                     ["antenna"] * 3, top=("ngon", "antenna"), bottom=("ngon", "antenna")))
    tip = blob(0.06, 0.06, 0.06, 12, 8, "tip").move(0.035, 0.605, -0.047)

    jet = loft([ring(0.1, -0.38, 10), ring(0.115, -0.43, 10, rot=0.15), ring(0.08, -0.52, 10, rot=0.3)],
               ["jet", "jet"], bottom=("fan", "jet", (0, -0.64, 0)))
    return {"body": (body, None, True), "visor": (visor, 50, True), "eyes": (eyes, None, True),
            "antenna": (antenna, 50, True), "tip": (tip, None, True), "jet": (jet, None, False)}


def tree(variant):
    """A small tree about 1 tall: a round bush, a pine, a slim poplar."""
    rng = random.Random(400 + variant)
    m = M()
    if variant == 0:
        m.add(loft([ring(0.05, 0.5, 6, cx=0.03), ring(0.065, 0.22, 6), ring(0.09, 0.0, 6)],
                   ["trunk", "trunk"], top=("ngon", "trunk"), bottom=("ngon", "trunk")))
        m.add(blob(0.36, 0.3, 0.34, 10, 6, "leaves", rng, 0.06).move(0.02, 0.7, 0))
        m.add(blob(0.18, 0.15, 0.17, 7, 4, "leaves", rng, 0.05).move(-0.24, 0.55, 0.12))
    elif variant == 1:
        m.add(loft([ring(0.05, 0.3, 5), ring(0.075, 0.0, 5)], ["trunk"], top=("ngon", "trunk"),
                   bottom=("ngon", "trunk")))
        for k, (y0, h, r) in enumerate([(0.18, 0.5, 0.4), (0.42, 0.42, 0.31), (0.64, 0.38, 0.21)]):
            sg = 7
            rim = ring(r, y0, sg, rot=k * 0.5, rfn=lambda i, a: r * (1.08 if i % 2 else 0.9),
                       yfn=lambda i, a: y0 - (0.05 if i % 2 else 0.0))
            under = ring(r * 0.5, y0 + 0.02, sg, rot=k * 0.5)
            m.add(loft([rim, under], ["leaves"], top=("fan", "leaves", (0.02 * k, y0 + h, -0.01 * k)),
                       bottom=("ngon", "leaves")))
    else:
        m.add(loft([ring(0.045, 0.4, 6), ring(0.07, 0.0, 6)], ["trunk"], top=("ngon", "trunk"),
                   bottom=("ngon", "trunk")))
        m.add(blob(0.24, 0.42, 0.23, 9, 7, "leaves", rng, 0.05).move(0, 0.64, 0))
    m.wobble(0.03, 2.2, 6 + variant)
    return m


def rock(variant):
    """A boulder, or a little pile of them, sitting on y = 0."""
    rng = random.Random(500 + variant)
    m = M()
    stones = [[(0, 0, 0.32, (1.25, 0.72, 1.0), 0.3)],
              [(0, 0, 0.26, (1.1, 0.8, 1.0), 0.2), (0.3, 0.12, 0.15, (1.0, 0.85, 1.1), 1.1),
               (-0.17, 0.27, 0.09, (1, 0.9, 1), 2.0)]][variant]
    for x, z, r, (sx, sy, sz), yaw in stones:
        s = ico(r, "rock", rng, 0.12, sub=2).scale(sx, sy, sz).rot_y(yaw)
        low = min(p.y for p in s.v)
        s.map(lambda p, low=low: (p.x, max(p.y - low - r * 0.12, 0.0), p.z))
        m.add(s.move(x, 0, z))
    return m


# ---------------------------------------------------------------- export

def export(path, objs):
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.export_scene.gltf(
        filepath=path, export_format="GLB", use_selection=True, export_yup=True, export_apply=True,
        export_texcoords=False, export_normals=True, export_tangents=False,
        export_vertex_color="ACTIVE", export_all_vertex_colors=False,
        export_materials="EXPORT", export_extras=False, export_cameras=False, export_lights=False,
        export_animations=False)


PIECES = []  # (name, collection, objects, triangles)


def piece(name, parts, palette, reach, ground, dark=0.4):
    """Builds a piece's objects in its own collection, bakes its occlusion, exports it, then renames
    its objects and materials so the next piece can use the same names."""
    coll = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(coll)
    mats = {k: material(k, *v) if isinstance(v, tuple) else material(k, v) for k, v in palette.items()}
    objs = [make_object(oname, m, mats, coll, sharp, recalc) for oname, (m, sharp, recalc) in parts.items()]
    bake_ao(objs, reach, ground, dark)
    export(os.path.join(OUT, name + ".glb"), objs)
    tris = sum(len(o.data.polygons) for o in objs)
    for o in objs:
        o.name = name + ":" + o.name
        o.data.name = o.name
    for mt in mats.values():
        mt.name = name + ":" + mt.name
    PIECES.append((name, coll, objs, tris))


ROOFS = [0xC8553D, 0x4F6D8F, 0x6E8F4E, 0xB9874A]
WALLS = [0xF1E4C8, 0xDDE6EA, 0xF3DDA6, 0xEBD2C6]


def build_all():
    for v in range(3):
        piece(f"island-{v + 1}", {"island": (island(v), 32, True)},
              {"grass": GRASS, "soil": SOIL, "rock": ROCK}, reach=0.45, ground=False)
    for v in range(4):
        piece(f"building-{v + 1}", {"building": (building(v), 35, True)},
              {"wall": WALLS[v], "roof": ROOFS[v], "door": 0x6B4A35, "window": 0x34405A},
              reach=0.35, ground=True)
    piece("lighthouse", {"lighthouse": (lighthouse(), 35, True)},
          {"white": 0xF2EFE6, "red": 0xD2553F, "trim": 0x4A4F5A, "door": 0x6B4A35, "window": 0x34405A,
           "lamp": (0xFFE39A, True), "roof": 0xC94A36},
          reach=0.6, ground=True)
    piece("robot", robot_parts(),
          {"body": 0xEDEDE8, "visor": 0x161A24, "eyes": (0xD9F6FF, True), "antenna": 0x8D939E,
           "tip": 0xFFFFFF, "jet": (0x9FDCFF, True, True)},
          reach=0.14, ground=False, dark=0.45)
    for v in range(3):
        piece(f"tree-{v + 1}", {"tree": (tree(v), [None, 40, None][v], True)},
              {"trunk": TRUNK, "leaves": LEAVES}, reach=0.3, ground=True)
    for v in range(2):
        piece(f"rock-{v + 1}", {"rock": (rock(v), 28, True)}, {"rock": ROCK}, reach=0.25, ground=True)


# ---------------------------------------------------------------- preview (cel bands + chalk outline)

CHALK = 0xF3EFE6
BOARD = 0x353535


def toon(mt):
    """Rewires a material for the preview: three light bands x base colour x occlusion (emission)."""
    hexrgb, glow = mt["hex"], mt["glow"]
    nt = mt.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    em = nt.nodes.new("ShaderNodeEmission")
    rgb = nt.nodes.new("ShaderNodeRGB")
    rgb.outputs[0].default_value = lin(hexrgb)
    ao = nt.nodes.new("ShaderNodeVertexColor")
    ao.layer_name = "Color"
    mul = nt.nodes.new("ShaderNodeVectorMath")
    mul.operation = "MULTIPLY"
    nt.links.new(rgb.outputs[0], mul.inputs[0])
    nt.links.new(ao.outputs[0], mul.inputs[1])
    last = mul.outputs[0]
    if not glow:
        diff = nt.nodes.new("ShaderNodeBsdfDiffuse")
        s2r = nt.nodes.new("ShaderNodeShaderToRGB")
        ramp = nt.nodes.new("ShaderNodeValToRGB")
        cr = ramp.color_ramp
        cr.interpolation = "CONSTANT"
        cr.elements[0].position, cr.elements[0].color = 0.0, (0.47, 0.47, 0.47, 1)
        cr.elements[1].position, cr.elements[1].color = 0.12, (0.78, 0.78, 0.78, 1)
        e = cr.elements.new(0.5)
        e.color = (1, 1, 1, 1)
        nt.links.new(diff.outputs[0], s2r.inputs[0])
        nt.links.new(s2r.outputs[0], ramp.inputs[0])
        mul2 = nt.nodes.new("ShaderNodeVectorMath")
        mul2.operation = "MULTIPLY"
        nt.links.new(last, mul2.inputs[0])
        nt.links.new(ramp.outputs[0], mul2.inputs[1])
        last = mul2.outputs[0]
    nt.links.new(last, em.inputs[0])
    nt.links.new(em.outputs[0], out.inputs[0])


def flat(name, hexrgb, cull):
    mt = bpy.data.materials.new(name)
    nt = mt.node_tree
    nt.nodes.clear()
    em = nt.nodes.new("ShaderNodeEmission")
    em.inputs[0].default_value = lin(hexrgb)
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    nt.links.new(em.outputs[0], out.inputs[0])
    mt.use_backface_culling = cull
    return mt


def outline(ob, width, chalk):
    ob.data.materials.append(chalk)
    mod = ob.modifiers.new("outline", "SOLIDIFY")
    mod.thickness = width
    mod.offset = 1.0
    mod.use_flip_normals = True
    mod.use_rim = False
    mod.use_even_offset = False
    mod.material_offset = len(ob.data.materials) - 1


def frame(cam, objs, label_obj, pad=1.18):
    """Points the orthographic camera at objs from the front-right, above; fits them in."""
    pts = [o.matrix_world @ Vector(c) for o in objs for c in o.bound_box]
    lo = Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts)))
    hi = Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
    centre = (lo + hi) / 2
    view = w2b((0.75, 0.55, 1.35)).normalized()
    rot = (-view).to_track_quat("-Z", "Y")
    right, up = rot @ Vector((1, 0, 0)), rot @ Vector((0, 1, 0))
    xs = [(p - centre).dot(right) for p in pts]
    ys = [(p - centre).dot(up) for p in pts]
    size = max(max(xs) - min(xs), max(ys) - min(ys)) * pad
    shift = right * (max(xs) + min(xs)) / 2 + up * (max(ys) + min(ys)) / 2
    cam.location = centre + shift + view * 30 - up * size * 0.06
    cam.rotation_euler = rot.to_euler()
    cam.data.ortho_scale = size
    label_obj.data.size = size * 0.07
    label_obj.location = (0, -size * 0.44, -1)
    return size


def preview(path):
    import numpy as np
    sc = bpy.context.scene
    sc.render.engine = "BLENDER_EEVEE"
    sc.render.resolution_x = sc.render.resolution_y = 400
    sc.render.film_transparent = False
    sc.view_settings.view_transform = "Standard"
    sc.eevee.taa_render_samples = 32
    world = bpy.data.worlds.new("board")
    sc.world = world
    nt = world.node_tree
    nt.nodes.clear()
    bg = nt.nodes.new("ShaderNodeBackground")
    bg.inputs[0].default_value = lin(BOARD)
    lp = nt.nodes.new("ShaderNodeLightPath")
    black = nt.nodes.new("ShaderNodeBackground")
    black.inputs[0].default_value = (0, 0, 0, 1)
    mix = nt.nodes.new("ShaderNodeMixShader")
    wout = nt.nodes.new("ShaderNodeOutputWorld")
    nt.links.new(lp.outputs["Is Camera Ray"], mix.inputs[0])
    nt.links.new(black.outputs[0], mix.inputs[1])
    nt.links.new(bg.outputs[0], mix.inputs[2])
    nt.links.new(mix.outputs[0], wout.inputs[0])

    sun = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN"))
    sun.data.energy = pi
    sun.data.angle = 0.0
    sun.rotation_euler = (-w2b((-1.0, 1.1, 0.55)).normalized()).to_track_quat("-Z", "Y").to_euler()
    sc.collection.objects.link(sun)
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    cam.data.type = "ORTHO"
    cam.data.clip_end = 200
    sc.collection.objects.link(cam)
    sc.camera = cam
    chalk = flat("chalk", CHALK, True)
    label = bpy.data.objects.new("label", bpy.data.curves.new("label", "FONT"))
    label.data.align_x = "CENTER"
    label.data.materials.append(flat("chalk-text", CHALK, False))
    label.parent = cam
    sc.collection.objects.link(label)

    for mt in list(bpy.data.materials):
        if "hex" in mt:
            toon(mt)

    tiles = []
    tile_dir = "/tmp/world-models-tiles"
    os.makedirs(tile_dir, exist_ok=True)
    # an assembled vignette too: one island with a few pieces on it
    scene_coll = bpy.data.collections.new("vignette")
    sc.collection.children.link(scene_coll)
    by_name = {n: objs for n, _, objs, _ in PIECES}
    place = [("island-1", (0, 0, 0), 0, 3.2), ("building-1", (-0.9, 0, -0.9), 0.35, 1),
             ("building-3", (1.15, 0, -0.95), -0.4, 1), ("tree-1", (-2.1, 0, 0.3), 0, 1.1),
             ("tree-2", (2.2, 0, 0.2), 0, 1.2), ("tree-3", (-1.7, 0, -1.7), 0, 1),
             ("rock-1", (0.9, 0, 1.6), 0.5, 1), ("robot", (0.0, 0.85, 1.1), 0.3, 1)]
    for n, (x, y, z), yaw, s in place:
        for o in by_name[n]:
            c = o.copy()
            c.location = w2b((x, y, z))
            c.rotation_euler = (0, 0, yaw)
            c.scale = (s, s, s)
            scene_coll.objects.link(c)
    groups = [(n, coll, objs) for n, coll, objs, _ in PIECES] + [("together", scene_coll, list(scene_coll.objects))]

    for n, coll, objs in groups:
        if n == "together":
            continue
        size = 1.0
        for o in objs:
            pts = [Vector(c) for c in o.bound_box]
            size = max(size, max((a - b).length for a in pts for b in pts))
        for o in objs:
            outline(o, 0.0065 * size, chalk)
    for o in scene_coll.objects:  # copies share their originals' modifiers by value; thin them
        for mod in o.modifiers:
            mod.thickness = 0.022 / o.scale[0]

    for n, coll, objs in groups:
        for _, c2, _ in groups:
            c2.hide_render = c2 is not coll
        label.data.body = n
        frame(cam, objs, label)
        sc.render.filepath = os.path.join(tile_dir, n + ".png")
        bpy.ops.render.render(write_still=True)
        tiles.append(sc.render.filepath)

    cols = 5
    rows_n = (len(tiles) + cols - 1) // cols
    t = 400
    sheet = np.zeros((rows_n * t, cols * t, 4), dtype=np.float32)
    sheet[..., :3] = [c / 255 for c in ((BOARD >> 16) & 255, (BOARD >> 8) & 255, BOARD & 255)]
    sheet[..., 3] = 1
    for i, f in enumerate(tiles):
        img = bpy.data.images.load(f)
        px = np.empty(t * t * 4, dtype=np.float32)
        img.pixels.foreach_get(px)
        r, c = i // cols, i % cols
        y0 = (rows_n - 1 - r) * t
        sheet[y0:y0 + t, c * t:(c + 1) * t] = px.reshape(t, t, 4)
    out = bpy.data.images.new("sheet", cols * t, rows_n * t, alpha=False)
    out.pixels.foreach_set(sheet.ravel())
    out.filepath_raw = path
    out.file_format = "PNG"
    out.save()


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    bpy.ops.wm.read_factory_settings(use_empty=True)
    os.makedirs(OUT, exist_ok=True)
    build_all()
    for name, _, _, tris in PIECES:
        size = os.path.getsize(os.path.join(OUT, name + ".glb"))
        print(f"{name:12s} {tris:5d} triangles {size / 1024:6.1f} KB")
    if "--preview" in argv:
        preview(argv[argv.index("--preview") + 1])


main()
