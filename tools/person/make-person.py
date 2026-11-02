"""Makes web/person/person.glb: one rigged, clothed, low-poly adult for the
3D scenes.

What it is: a gender-neutral (MakeHuman gender 0.5) average adult, 1.75 m to
the top of the head, on the male1591 low-poly proxy body, in a t-shirt and
long trousers (male_casualsuit04), sneakers (shoes06), short hair (short02),
eyebrows and low-poly eyes. Skinned to MakeHuman's game_engine rig with its
own bone names, in the rig's rest pose; no animations, no shape keys, no
textures. One mesh with flat materials named skin, top, bottom, shoes, hair,
eyes, so a page can recolour any of them by name.

glTF: Y up, metres, feet on y = 0, facing +Z, centred on x = z = 0 between
the feet; the person's left side is +X.

Run (needs Blender with the MPFB extension and the MakeHuman system asset
pack at DATA):

    blender -b --python tools/person/make-person.py

Every asset (body, proxy mesh, rig, clothes, shoes, hair, eyebrows, eyes)
comes from the MakeHuman system asset pack, released as CC0.
"""
import pathlib

import bpy
from mathutils import Vector

from bl_ext.user_default.mpfb.services.humanservice import HumanService
from bl_ext.user_default.mpfb.services.targetservice import TargetService

DATA = pathlib.Path('/mnt/fast/blender-assets/mpfb-data')
OUT = pathlib.Path(__file__).resolve().parents[2] / 'web' / 'person' / 'person.glb'
RIG = 'game_engine'
HEIGHT = 1.75   # metres, floor to the top of the head (the body, not the hair)
INSET = 0.012   # metres the body under the clothes is sunk in
PROXY = 'proxymeshes/male1591/male1591.proxy'
CLOTHES = 'clothes/male_casualsuit04/male_casualsuit04.mhclo'   # t-shirt + jeans, one mesh
SHOES = 'clothes/shoes06/shoes06.mhclo'
HAIR = 'hair/short02/short02.mhclo'
BROWS = 'eyebrows/eyebrow001/eyebrow001.mhclo'
EYES = 'eyes/low-poly/low-poly.mhclo'
# Flat colours (sRGB hex); the page tints top and bottom by material name.
COLOURS = {'skin': '#c8a080', 'top': '#5a7fb0', 'bottom': '#3c4250', 'shoes': '#2e2e30', 'hair': '#3a2b22',
           'eyes': '#231d1a'}


def srgb(hexcode):
    def lin(c):
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    return tuple(lin(int(hexcode[i:i + 2], 16) / 255) for i in (1, 3, 5)) + (1.0,)


def macro(height):
    m = TargetService.get_default_macro_info_dict()
    m.update({'gender': 0.5, 'age': 0.5, 'weight': 0.5, 'muscle': 0.5, 'proportions': 0.5, 'height': height})
    return m


def new_body(height):
    body = HumanService.create_human(macro_detail_dict=macro(height))
    TargetService.bake_targets(body)
    return body


def top_of(body):
    g = body.vertex_groups['body'].index
    return max(v.co.z for v in body.data.vertices if any(e.group == g for e in v.groups))


def remove(obj):
    data = obj.data
    bpy.data.objects.remove(obj, do_unlink=True)
    if data is not None and data.users == 0:
        if isinstance(data, bpy.types.Mesh):
            bpy.data.meshes.remove(data)
        elif isinstance(data, bpy.types.Armature):
            bpy.data.armatures.remove(data)


def solve_height():
    """MakeHuman's height value whose body stands HEIGHT tall."""
    lo, hi = 0.0, 1.0
    for _ in range(12):
        mid = (lo + hi) / 2
        body = new_body(mid)
        z = top_of(body)
        remove(body)
        lo, hi = (mid, hi) if z < HEIGHT else (lo, mid)
    return (lo + hi) / 2


def wear(body, rel, asset_type, material_type='MAKESKIN'):
    return HumanService.add_mhclo_asset(str(DATA / rel), body, asset_type=asset_type, subdiv_levels=0,
                                        material_type=material_type)


def islands(me):
    parent = list(range(len(me.vertices)))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    for e in me.edges:
        a, b = find(e.vertices[0]), find(e.vertices[1])
        if a != b:
            parent[a] = b
    return [find(p.vertices[0]) for p in me.polygons]


def bake(obj):
    """Drops shape keys and MPFB's modifiers (the rig's armature modifier is
    made afresh on the joined mesh). MPFB hides the body under the clothes
    with delete masks; deleting it would leave a hollow seen through sleeve
    and trouser openings, so the hidden body is kept and sunk INSET inwards
    instead, where it cannot poke through the clothes."""
    bpy.context.view_layer.objects.active = obj
    if obj.data.shape_keys:
        obj.shape_key_add(name='mix', from_mix=True)
        mix = obj.data.shape_keys.key_blocks['mix'].data
        co = [mix[i].co.copy() for i in range(len(obj.data.vertices))]
        obj.shape_key_clear()
        for v, c in zip(obj.data.vertices, co):
            v.co = c
    hidden = set()
    for m in list(obj.modifiers):
        if m.type == 'MASK' and m.vertex_group in obj.vertex_groups and m.invert_vertex_group:
            g = obj.vertex_groups[m.vertex_group].index
            hidden |= {v.index for v in obj.data.vertices if any(e.group == g and e.weight > 0 for e in v.groups)}
        elif m.type != 'ARMATURE':
            raise RuntimeError(f'unexpected modifier {m.name} on {obj.name}')
        obj.modifiers.remove(m)
    normals = {i: obj.data.vertices[i].normal.copy() for i in hidden}
    for i, n in normals.items():
        obj.data.vertices[i].co -= n * INSET


def set_material(obj, name_of_face):
    """Replaces obj's materials with the flat ones; name_of_face(i) -> name."""
    names = sorted({name_of_face(p.index) for p in obj.data.polygons})
    obj.data.materials.clear()
    for n in names:
        obj.data.materials.append(MATS[n])
    for p in obj.data.polygons:
        p.material_index = names.index(name_of_face(p.index))


def flat_materials():
    mats = {}
    for name, colour in COLOURS.items():
        m = bpy.data.materials.new(name)
        # Clothes and hair are single sheets seen from inside too (sleeves, cuffs).
        m.use_backface_culling = name in ('skin', 'eyes')
        bsdf = next(n for n in m.node_tree.nodes if n.type == 'BSDF_PRINCIPLED')
        bsdf.inputs['Base Color'].default_value = srgb(colour)
        bsdf.inputs['Roughness'].default_value = 0.8 if name != 'eyes' else 0.3
        bsdf.inputs['Metallic'].default_value = 0.0
        mats[name] = m
    return mats


# ---------------------------------------------------------------- build

for o in list(bpy.data.objects):
    remove(o)
for m in list(bpy.data.materials):
    bpy.data.materials.remove(m)

height = solve_height()
body = new_body(height)
arm = HumanService.add_builtin_rig(body, RIG)
proxy = wear(body, PROXY, 'Proxymeshes', material_type='NONE')
eyes = wear(body, EYES, 'Eyes')
brows = wear(body, BROWS, 'Eyebrows')
hair = wear(body, HAIR, 'Hair')
clothes = wear(body, CLOTHES, 'Clothes')
shoes = wear(body, SHOES, 'Clothes')
print('person: height value', round(height, 4), 'body top', round(top_of(body), 4))

parts = {'skin': proxy, 'eyes': eyes, 'brows': brows, 'hair': hair, 'clothes': clothes, 'shoes': shoes}
for o in parts.values():
    bake(o)
    mw = o.matrix_world.copy()
    o.parent = arm
    o.matrix_world = mw
# The full base mesh (with its helper geometry) goes; the proxy is the body.
for o in list(bpy.data.objects):
    if o is not arm and o not in parts.values():
        remove(o)

MATS = flat_materials()
set_material(proxy, lambda i: 'skin')
set_material(eyes, lambda i: 'eyes')
set_material(brows, lambda i: 'hair')
set_material(hair, lambda i: 'hair')
set_material(shoes, lambda i: 'shoes')
# The suit is one mesh: each connected piece is the top when it sits above the
# hips (the thigh joints), else the bottom.
hip_z = (arm.matrix_world @ arm.data.bones['thigh_l'].head_local).z
me = clothes.data
isl = islands(me)
mid = {}
for p in me.polygons:
    s = mid.setdefault(isl[p.index], [Vector(), 0])
    s[0] += clothes.matrix_world @ p.center
    s[1] += 1
piece = {k: ('top' if (c / n).z > hip_z + 0.12 else 'bottom') for k, (c, n) in mid.items()}
for k, (c, n) in sorted(mid.items(), key=lambda kv: -kv[1][1]):
    print('person: suit piece', n, 'faces, mid z', round((c / n).z, 3), '->', piece[k])
set_material(clothes, lambda i: piece[isl[i]])

# One mesh, skinned to the rig; keep only the bones' vertex groups.
bpy.ops.object.select_all(action='DESELECT')
for o in parts.values():
    o.select_set(True)
bpy.context.view_layer.objects.active = proxy
bpy.ops.object.join()
person = proxy
person.name = person.data.name = 'person'
bones = set(arm.data.bones.keys())
for g in list(person.vertex_groups):
    if g.name not in bones:
        person.vertex_groups.remove(g)
for m in list(person.modifiers):
    if m.type == 'ARMATURE':
        person.modifiers.remove(m)
mod = person.modifiers.new('rig', 'ARMATURE')
mod.object = arm
while person.data.uv_layers:
    person.data.uv_layers.remove(person.data.uv_layers[0])
for a in [a for a in person.data.color_attributes]:
    person.data.color_attributes.remove(a)

# Feet on the floor, centred between the ankles; scale and rotation applied.
bpy.ops.object.select_all(action='DESELECT')
arm.select_set(True)
person.select_set(True)
bpy.context.view_layer.objects.active = arm
bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
low = min((person.matrix_world @ v.co).z for v in person.data.vertices)
feet = (arm.data.bones['foot_l'].head_local + arm.data.bones['foot_r'].head_local) / 2
arm.location = (-feet.x, -feet.y, -low)
bpy.context.view_layer.update()
bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
person.parent = arm
arm.name = arm.data.name = 'rig'

OUT.parent.mkdir(parents=True, exist_ok=True)
bpy.ops.object.select_all(action='DESELECT')
arm.select_set(True)
person.select_set(True)
bpy.ops.export_scene.gltf(
    filepath=str(OUT), export_format='GLB', use_selection=True, export_yup=True,
    export_apply=False, export_texcoords=False, export_normals=True, export_tangents=False,
    export_materials='EXPORT', export_image_format='NONE', export_vertex_color='NONE',
    export_skins=True, export_def_bones=False, export_morph=False, export_animations=False,
    export_draco_mesh_compression_enable=False, export_extras=False, export_cameras=False, export_lights=False)
print('person: wrote', OUT, OUT.stat().st_size, 'bytes')
