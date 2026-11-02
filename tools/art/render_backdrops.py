"""Render the hero backdrops: one 1920x600 image per HeroMotif, from procedural geometry only (no external assets).

Run with Blender as a Python module (bpy 5.2.x) plus Pillow, from the repo root:

    python -m venv /tmp/blender-venv && /tmp/blender-venv/bin/pip install bpy==5.2.3 pillow
    /tmp/blender-venv/bin/python tools/art/render_backdrops.py [--out app/src/main/res/drawable-nodpi]
        [--preview DIR] [--only DAWN,SNOW] [--samples 48] [--quality 90] [--work DIR --reuse]

Everything is seeded, so a run reproduces the same images. Composition rules (see Hero.kt and HeroTheme.kt):
- the left 45% stays calm and dark, because the title sits there; the interest is on the right;
- the bottom edge fades to the screen background (Tokens.BACKGROUND), because the tile row sits over it;
- the title and clock zones must keep at least 4.5:1 contrast against the theme's text colours (checked below).

Renders use Cycles on the CPU with denoising; the float EXR gets the plain sRGB curve (the Standard view transform), so
palette colours come out as written.
Post (numpy): bloom, the calm left wash, the bottom fade and a light dither, then WebP and PNG previews. No .blend is saved.
"""

import argparse
import math
import os
import random
import sys
import time
import warnings

import bpy
import numpy as np
from PIL import Image, ImageDraw, ImageFont

W, H = 1920, 600
BACKGROUND = 0x101418  # Tokens.BACKGROUND
TEXT, SECONDARY = 0xF4F1EA, 0xD5DCE8  # HeroTheme defaults

# Camera: on a rise 40 m up, looking along +Y and pitched up a little. 3.2:1 frame, so the vertical view is narrow.
CAM_Z = 40.0
LENS, SENSOR = 30.0, 36.0
PITCH = math.radians(2.0)
TAN_H = (SENSOR / 2) / LENS
TAN_V = TAN_H * H / W
# Screen height (fraction from the top) of the horizon; the hill crests and sky stops are set relative to it.
HZ = 0.5 + math.tan(PITCH) / (2 * TAN_V)


def lin(c):
    """0xRRGGBB (sRGB) to a linear RGBA tuple for Blender."""
    out = []
    for s in ((c >> 16) & 255, (c >> 8) & 255, c & 255):
        v = s / 255.0
        out.append(v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4)
    return (out[0], out[1], out[2], 1.0)


def mixc(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(4))


def place(fx, fy, depth):
    """World position that lands at screen fraction (fx, fy) (0,0 top left) at the given forward depth."""
    x = depth * (fx - 0.5) * 2 * TAN_H
    up = math.tan(PITCH + math.atan((0.5 - fy) * 2 * TAN_V))
    return (x, depth, CAM_Z + depth * up)


def direction(fx, fy):
    x, y, z = place(fx, fy, 1.0)
    z -= CAM_Z
    n = math.sqrt(x * x + y * y + z * z)
    return (x / n, y / n, z / n)


# ---------------------------------------------------------------------------------------------------------------------
# Scene and node helpers


def reset_scene(samples):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    s = bpy.context.scene
    s.render.engine = "CYCLES"
    s.cycles.device = "CPU"
    s.cycles.samples = samples
    s.cycles.use_adaptive_sampling = True
    s.cycles.use_denoising = True
    try:
        s.cycles.denoiser = "OPENIMAGEDENOISE"
    except TypeError:
        pass
    s.cycles.max_bounces = 4
    s.cycles.caustics_reflective = False
    s.cycles.caustics_refractive = False
    s.cycles.seed = 7
    s.render.resolution_x, s.render.resolution_y, s.render.resolution_percentage = W, H, 100
    s.render.film_transparent = False
    s.view_settings.view_transform = "Standard"
    s.view_settings.look = "None"
    s.render.image_settings.file_format = "OPEN_EXR"
    s.render.image_settings.color_depth = "32"
    s.render.image_settings.color_mode = "RGB"
    cam_data = bpy.data.cameras.new("cam")
    cam_data.lens, cam_data.sensor_width, cam_data.sensor_fit = LENS, SENSOR, "HORIZONTAL"
    cam_data.clip_end = 5000
    cam = bpy.data.objects.new("cam", cam_data)
    cam.location = (0, 0, CAM_Z)
    cam.rotation_euler = (math.pi / 2 + PITCH, 0, 0)
    s.collection.objects.link(cam)
    s.camera = cam
    return s


def node(nt, kind, **props):
    n = nt.nodes.new(kind)
    for k, v in props.items():
        setattr(n, k, v)
    return n


def sock(sockets, name, kind=None):
    for x in sockets:
        if x.name == name and (kind is None or x.type == kind):
            return x
    raise KeyError(name)


def mix_rgb(nt, a, b, fac, blend="MIX"):
    """Mix two colour outputs (or constant tuples) by fac (output or float)."""
    m = node(nt, "ShaderNodeMix", data_type="RGBA", blend_type=blend)
    m.clamp_result = True
    for slot, val in (("A", a), ("B", b)):
        s = sock(m.inputs, slot, "RGBA")
        if isinstance(val, tuple):
            s.default_value = val
        else:
            nt.links.new(val, s)
    f = sock(m.inputs, "Factor", "VALUE")
    if isinstance(fac, (int, float)):
        f.default_value = fac
    else:
        nt.links.new(fac, f)
    return sock(m.outputs, "Result", "RGBA")


def math_node(nt, op, a, b=None, clamp=False):
    m = node(nt, "ShaderNodeMath", operation=op, use_clamp=clamp)
    for i, val in enumerate((a, b)):
        if val is None:
            continue
        if isinstance(val, (int, float)):
            m.inputs[i].default_value = val
        else:
            nt.links.new(val, m.inputs[i])
    return m.outputs[0]


def ramp(nt, fac, stops):
    r = node(nt, "ShaderNodeValToRGB")
    els = r.color_ramp.elements
    while len(els) > len(stops):
        els.remove(els[-1])
    while len(els) < len(stops):
        els.new(0.5)
    for e, (pos, col) in zip(els, stops):
        e.position, e.color = pos, col
    nt.links.new(fac, r.inputs[0])
    return r.outputs[0]


def material(name, color, rough=0.9, emit=None, emit_strength=0.0, fog=None):
    """Principled surface; [fog] = (haze colour, amount) blends it towards the haze (aerial perspective).

    Each hill band gets one flat amount: blending by per-pixel depth made the back slope of every mound lighter than its
    crest, which read as fog banks rather than hills.
    """
    m = bpy.data.materials.new(name)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        try:
            m.use_nodes = True
        except AttributeError:
            pass
    nt = m.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = node(nt, "ShaderNodeOutputMaterial")
    bsdf = node(nt, "ShaderNodeBsdfPrincipled")
    sock(bsdf.inputs, "Base Color").default_value = color
    sock(bsdf.inputs, "Roughness").default_value = rough
    sock(bsdf.inputs, "Specular IOR Level").default_value = 0.0  # no grazing sheen on the crests
    if emit is not None:
        sock(bsdf.inputs, "Emission Color").default_value = emit
        sock(bsdf.inputs, "Emission Strength").default_value = emit_strength
    shader = bsdf.outputs[0]
    if fog is not None:
        haze, amount = fog
        em = node(nt, "ShaderNodeEmission")
        em.inputs["Color"].default_value = haze
        mix = node(nt, "ShaderNodeMixShader")
        mix.inputs[0].default_value = amount
        nt.links.new(shader, mix.inputs[1])
        nt.links.new(em.outputs[0], mix.inputs[2])
        shader = mix.outputs[0]
    nt.links.new(shader, out.inputs["Surface"])
    return m


def emission_material(name, color, strength=1.0):
    m = bpy.data.materials.new(name)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        try:
            m.use_nodes = True
        except AttributeError:
            pass
    nt = m.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = node(nt, "ShaderNodeOutputMaterial")
    em = node(nt, "ShaderNodeEmission")
    em.inputs["Color"].default_value = color
    em.inputs["Strength"].default_value = strength
    nt.links.new(em.outputs[0], out.inputs["Surface"])
    return m


def link_mesh(name, verts, faces, mat, smooth=True):
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    me.update()
    if smooth:
        for p in me.polygons:
            p.use_smooth = True
    me.materials.append(mat)
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    return ob


# ---------------------------------------------------------------------------------------------------------------------
# Procedural shapes


class Noise1D:
    """Seeded smooth value noise with octaves."""

    def __init__(self, seed, n=512):
        r = random.Random(seed)
        self.v = [r.random() * 2 - 1 for _ in range(n)]
        self.n = n

    def base(self, x):
        i = math.floor(x)
        f = x - i
        a, b = self.v[i % self.n], self.v[(i + 1) % self.n]
        f = f * f * (3 - 2 * f)
        return a + (b - a) * f

    def fbm(self, x, octaves=5):
        s, amp, freq, norm = 0.0, 1.0, 1.0, 0.0
        for o in range(octaves):
            s += amp * self.base(x * freq + o * 17.31)
            norm += amp
            amp *= 0.5
            freq *= 2.03
        return s / norm


def ridge(name, depth, fx0, fx1, crest_fy, amp_fy, mat, seed, freq=3.0, thick=None, nx=220, nz=14, lift=None):
    """A hill band at [depth]: a mound whose crest is a noisy line, spanning screen fractions fx0..fx1. Returns the crest
    as a function fx -> world point, for placing trees on it.

    crest_fy is the crest's mean screen height (fraction from the top) and amp_fy its swing. [lift] (fx -> fy offset) shapes
    the crest across the frame, e.g. lower on the left to keep the title area calm.
    """
    nse = Noise1D(seed)
    thick = thick or depth * 0.35

    def crest_y(fx):
        calm = 0.35 + 0.65 * min(1.0, max(0.0, (fx - 0.25) / 0.45))  # flatter under the title, livelier on the right
        return crest_fy + amp_fy * calm * nse.fbm(fx * freq) + (lift(fx) if lift else 0.0)

    verts, faces = [], []
    ground = 0.0
    for j in range(nz + 1):
        v = j / nz
        z_rel = (v - 0.5) * 2  # -1 front .. 1 back
        shape = max(0.0, 1 - z_rel * z_rel) ** 0.6
        d = depth + z_rel * thick
        for i in range(nx + 1):
            fx = fx0 + (fx1 - fx0) * i / nx
            fy = crest_y(fx)
            top = place(fx, fy, depth)[2]
            x = place(fx, 0.5, d)[0]
            h = ground + (top - ground) * shape + 0.004 * depth * nse.fbm(fx * 40 + v * 7, 3) * shape
            verts.append((x, d, h))
    for j in range(nz):
        for i in range(nx):
            a = j * (nx + 1) + i
            faces.append((a, a + 1, a + nx + 2, a + nx + 1))
    link_mesh(name, verts, faces, mat)

    def crest(fx):
        """World point on the crest line at screen fraction fx."""
        return place(fx, crest_y(fx), depth)

    return crest


def ground_plane(mat, depth=4000):
    return link_mesh("ground", [(-depth, 1, 0), (depth, 1, 0), (depth, depth, 0), (-depth, depth, 0)],
                     [(0, 1, 2, 3)], mat, smooth=False)


def disc(name, fx, fy, depth, radius_fy, mat, segments=96):
    """A camera-facing disc (sun, moon) of radius given as a fraction of the frame height."""
    cx, cy, cz = place(fx, fy, depth)
    r = depth * radius_fy * 2 * TAN_V
    verts = [(cx, cy, cz)] + [(cx + r * math.cos(a), cy, cz + r * math.sin(a))
                              for a in (2 * math.pi * k / segments for k in range(segments))]
    faces = [(0, k + 1, (k + 1) % segments + 1) for k in range(segments)]
    return link_mesh(name, verts, faces, mat, smooth=False)


def crescent(name, fx, fy, depth, radius_fy, mat, segments=64):
    """A camera-facing crescent: a disc minus an equal disc shifted towards the upper right."""
    cx, cy, cz = place(fx, fy, depth)
    r = depth * radius_fy * 2 * TAN_V
    phi, d = math.radians(35), 0.38 * r
    ox, oz = d * math.cos(phi), d * math.sin(phi)
    alpha = math.acos(d / (2 * r))  # equal radii: the circles cross at phi +- alpha on both
    pts = []
    for k in range(segments + 1):  # lit limb, the side away from the bite
        a = phi + alpha + (2 * math.pi - 2 * alpha) * k / segments
        pts.append((r * math.cos(a), r * math.sin(a)))
    a0, a1 = phi + math.pi - alpha, phi + math.pi + alpha  # bite edge, walked back to the start
    for k in range(1, segments):
        a = a1 - (a1 - a0) * k / segments
        pts.append((ox + r * math.cos(a), oz + r * math.sin(a)))
    verts = [(cx + x, cy, cz + z) for x, z in pts]
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], [list(range(len(verts)))])
    me.update()
    me.materials.append(mat)
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    return ob


def sun_lamp(direction_to_light, strength, color, angle_deg=2.0):
    lamp = bpy.data.lights.new("sun", "SUN")
    lamp.energy, lamp.color, lamp.angle = strength, color[:3], math.radians(angle_deg)
    ob = bpy.data.objects.new("sun", lamp)
    dx, dy, dz = direction_to_light
    # A sun lamp shines along its local -Z; point -Z away from the light direction.
    ob.rotation_euler = (math.atan2(math.hypot(dx, dy), dz), 0, math.atan2(dx, -dy))
    ob.rotation_mode = "XYZ"
    bpy.context.scene.collection.objects.link(ob)
    return ob


def point_lamp(loc, energy, color, radius=0.5):
    lamp = bpy.data.lights.new("pt", "POINT")
    lamp.energy, lamp.color, lamp.shadow_soft_size = energy, color[:3], radius
    ob = bpy.data.objects.new("pt", lamp)
    ob.location = loc
    bpy.context.scene.collection.objects.link(ob)
    return ob


def tapered_curve(name, strokes, mat):
    """strokes: list of point lists [(x, y, z, radius)]. One poly spline each, bevelled round."""
    cu = bpy.data.curves.new(name, "CURVE")
    cu.dimensions = "3D"
    cu.bevel_depth = 1.0
    cu.bevel_resolution = 1
    cu.use_fill_caps = True
    for pts in strokes:
        sp = cu.splines.new("POLY")
        sp.points.add(len(pts) - 1)
        for p, (x, y, z, r) in zip(sp.points, pts):
            p.co = (x, y, z, 1.0)
            p.radius = r
    cu.materials.append(mat)
    ob = bpy.data.objects.new(name, cu)
    bpy.context.scene.collection.objects.link(ob)
    return ob


def bare_tree(rng, base, height, mat, name="tree"):
    """A gnarled leafless tree: a recursive branch skeleton as tapered curves."""
    strokes = []

    def grow(p, dirv, length, radius, level):
        pts = [(p[0], p[1], p[2], radius)]
        x, y, z = p
        dx, dy, dz = dirv
        steps = 5
        for k in range(1, steps + 1):
            dx += rng.uniform(-0.18, 0.18)
            dz += rng.uniform(-0.08, 0.12) - 0.04 * level
            n = math.sqrt(dx * dx + dy * dy + dz * dz)
            dx, dy, dz = dx / n, dy / n, dz / n
            x, y, z = x + dx * length / steps, y + dy * length / steps * 0.3, z + dz * length / steps
            pts.append((x, y, z, radius * (1 - 0.55 * k / steps)))
        strokes.append(pts)
        if level >= 4:
            return
        for _ in range(rng.choice((2, 2, 3))):
            side = rng.uniform(0.5, 1.1) * rng.choice((-1, 1))
            nd = (dx + side, dy, dz + rng.uniform(-0.1, 0.35))
            grow((x, y, z), nd, length * rng.uniform(0.55, 0.75), radius * 0.45, level + 1)

    grow(base, (rng.uniform(-0.1, 0.1), 0, 1), height * 0.45, height * 0.035, 0)
    return tapered_curve(name, strokes, mat)


def pine(rng, base, height, needles, snow, name="pine"):
    """A stylised pine: stacked cones, each capped with a thin snow cone."""
    x, y, z = base
    tiers = 5
    for t in range(tiers):
        frac = t / tiers
        r = height * 0.28 * (1 - frac * 0.75)
        z0 = z + height * (0.12 + frac * 0.7)
        h = height * 0.32
        for mat, rr, hz, dz in ((needles, r, h, 0.0), (snow, r * 0.8, h * 0.55, h * 0.48)):
            bpy.ops.mesh.primitive_cone_add(vertices=14, radius1=rr, radius2=0, depth=hz,
                                            location=(x, y, z0 + dz + hz / 2))
            ob = bpy.context.active_object
            ob.name = name
            ob.rotation_euler = (rng.uniform(-0.03, 0.03), rng.uniform(-0.03, 0.03), rng.uniform(0, 6.28))
            ob.data.materials.append(mat)
    bpy.ops.mesh.primitive_cylinder_add(vertices=8, radius=height * 0.03, depth=height * 0.2,
                                        location=(x, y, z + height * 0.1))
    bpy.context.active_object.data.materials.append(needles)


def bat(name, fx, fy, depth, size_fy, flap, mat):
    """A flat bat silhouette facing the camera (same outline as the Canvas fallback in Hero.kt)."""
    cx, cy, cz = place(fx, fy, depth)
    s = depth * size_fy * 2 * TAN_V
    up = 1 - flap  # wing tip height
    outline = [(0, 0.25), (0.6, 0.75 * up + 0.1), (1.6, 0.45 * up), (1.25, 0.05), (1.15, -0.2), (0.9, -0.05),
               (0.6, -0.35), (0.3, -0.12), (0.0, -0.5)]
    pts = outline + [(-x, y) for x, y in reversed(outline[1:-1])]
    verts = [(cx, cy, cz)] + [(cx + x * s, cy, cz + y * s) for x, y in pts]
    faces = [(0, k + 1, (k + 1) % len(pts) + 1) for k in range(len(pts))]
    link_mesh(name, verts, faces, mat, smooth=False)
    for ex in (-0.12, 0.12):  # ears
        e = [(cx + (ex - 0.07) * s, cy, cz + 0.2 * s), (cx + (ex + 0.07) * s, cy, cz + 0.2 * s),
             (cx + ex * s, cy, cz + 0.42 * s)]
        link_mesh(name + "_ear", e, [(0, 1, 2)], mat, smooth=False)


def box(name, loc, dims, mat, rot_z=0.0):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc)
    ob = bpy.context.active_object
    ob.name = name
    ob.scale = dims
    ob.rotation_euler = (0, 0, rot_z)
    ob.data.materials.append(mat)
    return ob


# ---------------------------------------------------------------------------------------------------------------------
# World: sky gradient by elevation, a glow around the sun or moon, optional stars and clouds.


def world(stops, glows=(), stars=None, clouds=None, strength=1.0):
    """stops: [(screen fy at the frame centre, colour)], top to bottom. glows: [(fx, fy, colour, tightness, amount, stretch)].
    stars: (density threshold, brightness). clouds: (colour, coverage, amount)."""
    wd = bpy.data.worlds.new("sky")
    bpy.context.scene.world = wd
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        try:
            wd.use_nodes = True
        except AttributeError:
            pass
    nt = wd.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = node(nt, "ShaderNodeOutputWorld")
    tc = node(nt, "ShaderNodeTexCoord")
    dirv = node(nt, "ShaderNodeVectorMath", operation="NORMALIZE")
    nt.links.new(sock(tc.outputs, "Generated"), dirv.inputs[0])
    sep = node(nt, "ShaderNodeSeparateXYZ")
    nt.links.new(dirv.outputs[0], sep.inputs[0])
    # Elevation -> screen fy at the centre column, so the stops line up with the frame.
    elev = math_node(nt, "ARCSINE", sep.outputs[2])
    top_e = PITCH + math.atan(TAN_V)
    bot_e = PITCH - math.atan(TAN_V)
    fy = math_node(nt, "DIVIDE", math_node(nt, "SUBTRACT", top_e, elev), top_e - bot_e)
    fy = math_node(nt, "MAXIMUM", math_node(nt, "MINIMUM", fy, 1.0), 0.0)
    col = ramp(nt, fy, stops)
    if clouds is not None:
        ccol, coverage, amount = clouds
        # Project onto a high plane so clouds flatten towards the horizon.
        inv = math_node(nt, "DIVIDE", 1.0, math_node(nt, "MAXIMUM", sep.outputs[2], 0.02))
        proj = node(nt, "ShaderNodeVectorMath", operation="SCALE")
        nt.links.new(dirv.outputs[0], proj.inputs[0])
        nt.links.new(inv, sock(proj.inputs, "Scale"))
        mapping = node(nt, "ShaderNodeMapping")
        nt.links.new(proj.outputs[0], mapping.inputs["Vector"])
        mapping.inputs["Scale"].default_value = (0.9, 0.35, 1.0)
        mapping.inputs["Location"].default_value = (3.1, 0.7, 0.0)
        nz = node(nt, "ShaderNodeTexNoise")
        nz.inputs["Scale"].default_value = 0.55
        nz.inputs["Detail"].default_value = 9.0
        nz.inputs["Roughness"].default_value = 0.58
        nt.links.new(mapping.outputs[0], nz.inputs["Vector"])
        cover = node(nt, "ShaderNodeMapRange", clamp=True)
        nt.links.new(sock(nz.outputs, "Factor"), cover.inputs["Value"])
        cover.inputs["From Min"].default_value = 1 - coverage
        cover.inputs["From Max"].default_value = 1 - coverage + 0.22
        # Fewer clouds on the left (title) and none near the horizon line.
        horiz = node(nt, "ShaderNodeMapRange", clamp=True)
        nt.links.new(sep.outputs[2], horiz.inputs["Value"])
        horiz.inputs["From Min"].default_value, horiz.inputs["From Max"].default_value = 0.0, 0.08
        left = node(nt, "ShaderNodeMapRange", clamp=True)
        nt.links.new(sep.outputs[0], left.inputs["Value"])
        left.inputs["From Min"].default_value, left.inputs["From Max"].default_value = -0.15, 0.25
        f = math_node(nt, "MULTIPLY", math_node(nt, "MULTIPLY", cover.outputs[0], horiz.outputs[0]), left.outputs[0])
        col = mix_rgb(nt, col, ccol, math_node(nt, "MULTIPLY", f, amount))
    for gx, gy, gcol, tight, amount, stretch in glows:
        # Light scattered around the sun or moon; stretch > 1 widens it along the horizon.
        d = direction(gx, gy)
        sq = node(nt, "ShaderNodeVectorMath", operation="MULTIPLY")
        nt.links.new(dirv.outputs[0], sq.inputs[0])
        sq.inputs[1].default_value = (1.0, 1.0, stretch)
        sqn = node(nt, "ShaderNodeVectorMath", operation="NORMALIZE")
        nt.links.new(sq.outputs[0], sqn.inputs[0])
        dn = (d[0], d[1], d[2] * stretch)
        ln = math.sqrt(sum(v * v for v in dn))
        dot = node(nt, "ShaderNodeVectorMath", operation="DOT_PRODUCT")
        nt.links.new(sqn.outputs[0], dot.inputs[0])
        dot.inputs[1].default_value = tuple(v / ln for v in dn)
        g = math_node(nt, "POWER", math_node(nt, "MAXIMUM", dot.outputs["Value"], 0.0), tight)
        col = mix_rgb(nt, col, gcol, math_node(nt, "MULTIPLY", g, amount, clamp=True), blend="SCREEN")
    if stars is not None:
        thresh, bright = stars
        vor = node(nt, "ShaderNodeTexVoronoi")
        vor.inputs["Scale"].default_value = 420.0
        nt.links.new(dirv.outputs[0], vor.inputs["Vector"])
        sharp = node(nt, "ShaderNodeMapRange", clamp=True)
        nt.links.new(sock(vor.outputs, "Distance"), sharp.inputs["Value"])
        sharp.inputs["From Min"].default_value, sharp.inputs["From Max"].default_value = 0.09, 0.0
        sel = node(nt, "ShaderNodeSeparateColor")
        nt.links.new(sock(vor.outputs, "Color"), sel.inputs[0])
        pick = math_node(nt, "GREATER_THAN", sel.outputs[0], thresh)
        tw = math_node(nt, "MULTIPLY", math_node(nt, "POWER", sel.outputs[1], 2.0), bright)
        star = math_node(nt, "MULTIPLY", math_node(nt, "MULTIPLY", sharp.outputs[0], pick), tw)
        above = node(nt, "ShaderNodeMapRange", clamp=True)
        nt.links.new(sep.outputs[2], above.inputs["Value"])
        above.inputs["From Min"].default_value, above.inputs["From Max"].default_value = 0.0, 0.06
        star = math_node(nt, "MULTIPLY", star, above.outputs[0])
        col = mix_rgb(nt, col, lin(0xDCE6FF), star, blend="ADD")
    bg = node(nt, "ShaderNodeBackground")
    bg.inputs["Strength"].default_value = strength
    nt.links.new(col, bg.inputs["Color"])
    nt.links.new(bg.outputs[0], out.inputs["Surface"])


def calm_left(fx):
    """Crest offset: dark land rises a little on the left so the title sits over sky and quiet hills, not a bright
    horizon."""
    return -0.04 * max(0.0, 0.5 - fx) / 0.5


# ---------------------------------------------------------------------------------------------------------------------
# Motifs. Palettes are HeroTheme's (backdropTop, backdropBottom, accent) for the theme that uses each motif.


def scene_dawn():
    top, accent = lin(0x1F3B66), lin(0xFFD27A)
    sx, sy = 0.74, HZ - 0.10
    world([(0.0, mixc(top, lin(0x0C1830), 0.5)), (0.30, top), (HZ - 0.10, lin(0x34466E)), (HZ, lin(0x56608A)),
           (1.0, lin(0x56608A))],
          glows=[(sx, sy + 0.06, lin(0xE89A6A), 30, 0.85, 8.0), (sx, sy, lin(0xFFD9A0), 350, 0.6, 1.0),
                 (sx, sy, accent, 900, 0.9, 1.0)])
    sun_lamp(direction(sx, sy + 0.04), 2.5, lin(0xFFD9A0), 4)
    disc("sun", sx, sy, 3000, 0.05, emission_material("sun", lin(0xFFF0C8), 3.0))
    haze = lin(0xA88A88)
    layers = [(2400, -0.06, 0.040, 0x40445F, 0.42), (1300, -0.02, 0.050, 0x2A304C, 0.30),
              (650, 0.04, 0.060, 0x1C2238, 0.30), (260, 0.12, 0.060, 0x0E1220, 0.08)]
    for k, (d, off, amp, c, fogmax) in enumerate(layers):
        m = material(f"hill{k}", lin(c), fog=(haze, fogmax))
        ridge(f"ridge{k}", d, -0.1, 1.1, HZ + off, amp, m, seed=11 + k, freq=2.0 + k, lift=calm_left)
    ground_plane(material("ground", lin(0x141A2A)))


def scene_day():
    top, bottom, accent = lin(0x1B4A86), lin(0x245C96), lin(0xFFE08A)
    sx, sy = 0.72, 0.36
    world([(0.0, mixc(top, lin(0x0E2A56), 0.6)), (0.35, top), (HZ - 0.06, bottom), (HZ + 0.02, lin(0x4A78A8)),
           (1.0, lin(0x4A78A8))],
          glows=[(sx, sy, lin(0xFFF0C8), 300, 0.35, 1.0), (sx, sy, accent, 2500, 0.9, 1.0)],
          clouds=(lin(0xB8CCE4), 0.42, 0.7))
    sun_lamp(direction(sx, sy), 3.5, lin(0xFFF4DC), 2)
    disc("sun", sx, sy, 3000, 0.04, emission_material("sun", lin(0xFFF6D8), 2.5))
    haze = lin(0x4A74A4)
    layers = [(2400, -0.06, 0.040, 0x2E4E74, 0.50), (1200, -0.02, 0.050, 0x1E3C52, 0.38),
              (520, 0.04, 0.060, 0x163034, 0.28), (220, 0.12, 0.055, 0x0C1C1E, 0.08)]
    for k, (d, off, amp, c, fogmax) in enumerate(layers):
        m = material(f"hill{k}", lin(c), fog=(haze, fogmax))
        ridge(f"ridge{k}", d, -0.1, 1.1, HZ + off, amp, m, seed=31 + k, freq=2.2 + k, lift=calm_left)
    ground_plane(material("ground", lin(0x10201E)))


def scene_sunset():
    top, bottom, accent = lin(0x2E2250), lin(0x7A3E3A), lin(0xFFB86B)
    sx, sy = 0.74, HZ - 0.09
    world([(0.0, mixc(top, lin(0x120D26), 0.6)), (0.30, top), (HZ - 0.12, lin(0x4A2C4C)), (HZ, lin(0x6A3640)),
           (1.0, lin(0x6A3640))],
          glows=[(sx, sy + 0.05, lin(0xE07048), 20, 0.85, 8.0), (sx, sy, lin(0xFFB070), 260, 0.6, 1.0),
                 (sx, sy, accent, 700, 0.9, 1.0)],
          clouds=(lin(0xC87060), 0.30, 0.35))
    sun_lamp(direction(sx, sy + 0.03), 2.0, lin(0xFFA060), 3)
    disc("sun", sx, sy, 3000, 0.065, emission_material("sun", lin(0xFFD08A), 3.0))
    haze = lin(0xB0605A)
    layers = [(2200, -0.05, 0.040, 0x4A2440, 0.65), (1100, -0.01, 0.050, 0x2E1830, 0.42),
              (450, 0.05, 0.060, 0x1A0F20, 0.18), (200, 0.13, 0.050, 0x0E0812, 0.04)]
    for k, (d, off, amp, c, fogmax) in enumerate(layers):
        m = material(f"hill{k}", lin(c), fog=(haze, fogmax))
        ridge(f"ridge{k}", d, -0.1, 1.1, HZ + off, amp, m, seed=51 + k, freq=2.4 + k, lift=calm_left)
    ground_plane(material("ground", lin(0x120A12)))


def scene_stars():
    # Night: very dim on purpose (a TV in a dark room).
    top, bottom = lin(0x04060B), lin(0x0A1020)
    mx, my = 0.80, 0.30
    world([(0.0, top), (0.35, mixc(top, bottom, 0.5)), (HZ - 0.05, bottom), (HZ + 0.02, lin(0x101A34)),
           (1.0, lin(0x101A34))],
          glows=[(mx, my, lin(0x24365E), 25, 0.35, 1.0)], stars=(0.78, 1.0))
    sun_lamp(direction(mx, my), 0.05, lin(0xA8C4FF), 1)
    crescent("moon", mx, my, 3000, 0.07, emission_material("moon", lin(0xB8C8E8), 0.85))
    layers = [(1800, -0.04, 0.045, 0x0A1020), (520, 0.06, 0.055, 0x05080F)]
    for k, (d, off, amp, c) in enumerate(layers):
        m = material(f"hill{k}", lin(c), fog=(lin(0x0E1730), 0.5))
        ridge(f"ridge{k}", d, -0.1, 1.1, HZ + off, amp, m, seed=71 + k, freq=2.0 + k, lift=calm_left)
    ground_plane(material("ground", lin(0x05070E)))


def scene_moon_bats():
    top, bottom = lin(0x120A1E), lin(0x3A1A12)
    mx, my = 0.73, 0.36
    world([(0.0, mixc(top, lin(0x08040E), 0.5)), (0.35, top), (HZ - 0.06, lin(0x2A1422)), (HZ + 0.03, bottom),
           (1.0, bottom)],
          glows=[(mx, my + 0.10, lin(0xB8582A), 14, 0.32, 3.0), (mx, my, lin(0xFFB070), 220, 0.45, 1.0)],
          stars=(0.9, 0.5))
    sun_lamp(direction(mx, my), 0.6, lin(0xFFC890), 1.5)
    disc("moon", mx, my, 3000, 0.15, emission_material("moon", lin(0xF2E3B8), 1.5))
    rng = random.Random(5)
    crater = emission_material("crater", lin(0xD8C792), 1.35)
    for _ in range(7):
        a, r = rng.uniform(0, 6.28), rng.uniform(0, 0.10)
        disc("crater", mx + r * math.cos(a) * H / W, my + r * math.sin(a), 2990, rng.uniform(0.010, 0.026), crater)
    hill_far = material("hillfar", lin(0x1E0E1C), fog=(lin(0x3A1A26), 0.5))
    ridge("far", 1300, -0.1, 1.1, HZ - 0.04, 0.035, hill_far, seed=91, freq=2.5, lift=calm_left)
    near = material("hillnear", lin(0x08040A), rough=1.0)
    near_lift = lambda fx: calm_left(fx) - 0.05 * math.exp(-((fx - 0.64) / 0.12) ** 2)  # noqa: E731
    crest = ridge("near", 170, -0.1, 1.1, HZ + 0.07, 0.04, near, seed=93, freq=1.6, lift=near_lift)
    ground_plane(material("ground", lin(0x060308)))
    # Bare trees on the near crest, right side, silhouetted against the moon and sky.
    for fx, s in ((0.62, 0.30), (0.69, 0.22), (0.88, 0.26), (0.96, 0.17), (0.53, 0.12)):
        x, y, z = crest(fx)
        bare_tree(rng, (x, y, z - 1.0), s * 170 * 2 * TAN_V, near, name=f"tree{fx}")
    batm = emission_material("bat", lin(0x0A0610), 0.0)
    for k, (fx, fy, s, flap) in enumerate(((0.68, 0.22, 0.034, 0.1), (0.84, 0.18, 0.025, 0.5),
                                          (0.62, 0.40, 0.022, 0.3), (0.90, 0.38, 0.017, 0.7),
                                          (0.78, 0.47, 0.013, 0.2))):
        bat(f"bat{k}", fx, fy, 900, s, flap, batm)


def scene_snow():
    top, bottom = lin(0x0A2418), lin(0x1B4A36)
    world([(0.0, mixc(top, lin(0x04120C), 0.5)), (0.35, top), (HZ - 0.04, bottom), (HZ + 0.03, lin(0x285A46)),
           (1.0, lin(0x285A46))],
          glows=[(0.86, 0.16, lin(0x3A6A5A), 30, 0.2, 1.0)], stars=(0.86, 0.55))
    sun_lamp((-0.45, -0.55, 0.7), 1.1, lin(0xB8D8FF), 2)  # moonlight from behind the viewer, so the snow catches it
    snow_far = material("snowfar", lin(0x7E9AA0), rough=0.8, fog=(lin(0x24503E), 0.55))
    ridge("far", 1500, -0.1, 1.1, HZ - 0.05, 0.035, snow_far, seed=101, freq=2.2, lift=calm_left)
    snow_mid = material("snowmid", lin(0xB0C6CE), rough=0.7, fog=(lin(0x24503E), 0.3))
    mid = ridge("mid", 420, -0.1, 1.1, HZ + 0.0, 0.035, snow_mid, seed=103, freq=2.0, lift=calm_left)
    snow_near = material("snownear", lin(0xD6E2E8), rough=0.6, fog=(lin(0x1B4A36), 0.1))
    near = ridge("near", 120, -0.1, 1.1, HZ + 0.09, 0.03, snow_near, seed=105, freq=1.4, lift=calm_left)
    ground_plane(material("ground", lin(0x0C1E18)))
    needles = material("needles", lin(0x0E2A1C), fog=(lin(0x1B4A36), 0.12))
    snowcap = material("snowcap", lin(0xD8E6EC), rough=0.6, fog=(lin(0x1B4A36), 0.12))
    rng = random.Random(9)
    for k in range(14):  # a stand of pines on the near ridge, right side, leaving room for the cabin
        fx = 0.56 + 0.44 * (k / 13) + rng.uniform(-0.015, 0.015)
        if abs(fx - 0.74) < 0.035:
            continue
        x, y, z = near(fx)
        y += rng.uniform(-6, 10)
        pine(rng, (x, y, z - 1.5), rng.uniform(0.16, 0.30) * y * 2 * TAN_V, needles, snowcap, name=f"pine{k}")
    for k in range(12):  # smaller pines on the mid ridge
        fx = 0.5 + 0.5 * rng.random()
        x, y, z = mid(fx)
        pine(rng, (x, y, z - 2), rng.uniform(0.07, 0.11) * y * 2 * TAN_V, needles, snowcap, name=f"mpine{k}")
    # A small cabin with warm windows among the near pines.
    cx, cy, cz = near(0.74)
    cy -= 4
    wood = material("wood", lin(0x2A1810), fog=(lin(0x1B4A36), 0.08))
    box("cabin", (cx, cy, cz + 1.6), (7.0, 5.0, 4.0), wood, rot_z=0.2)
    roof = material("roof", lin(0xE4EEF2), rough=0.6)
    bpy.ops.mesh.primitive_cone_add(vertices=4, radius1=5.6, radius2=0, depth=3.0, location=(cx, cy, cz + 5.1))
    r = bpy.context.active_object
    r.scale = (1.0, 0.75, 1.0)
    r.rotation_euler = (0, 0, 0.2 + math.pi / 4)
    r.data.materials.append(roof)
    glow = emission_material("window", lin(0xFFB070), 8.0)
    for wx in (-1.8, 1.8):
        ox, oy = wx * math.cos(0.2), wx * math.sin(0.2)
        box("win", (cx + ox - 0.5 * math.sin(0.2), cy + oy - 2.55, cz + 1.9), (1.1, 0.12, 1.1), glow, rot_z=0.2)
    point_lamp((cx, cy - 5, cz + 1.8), 1800, lin(0xFF9A50), radius=1.5)


def beam_material(name, color, strength):
    """Additive light shaft: transparent plus emission that fades from the lamp (cone tip, generated Z = 1) outwards."""
    m = bpy.data.materials.new(name)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        try:
            m.use_nodes = True
        except AttributeError:
            pass
    nt = m.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = node(nt, "ShaderNodeOutputMaterial")
    tc = node(nt, "ShaderNodeTexCoord")
    sep = node(nt, "ShaderNodeSeparateXYZ")
    nt.links.new(sock(tc.outputs, "Generated"), sep.inputs[0])
    lw = node(nt, "ShaderNodeLayerWeight")
    lw.inputs["Blend"].default_value = 0.5
    soft = math_node(nt, "SUBTRACT", 1.0, sock(lw.outputs, "Facing"))  # fade the silhouette edges
    k = math_node(nt, "MULTIPLY", math_node(nt, "POWER", sep.outputs[2], 2.0), math_node(nt, "POWER", soft, 1.5))
    em = node(nt, "ShaderNodeEmission")
    em.inputs["Color"].default_value = color
    nt.links.new(math_node(nt, "MULTIPLY", k, strength), em.inputs["Strength"])
    tr = node(nt, "ShaderNodeBsdfTransparent")
    add = node(nt, "ShaderNodeAddShader")
    nt.links.new(tr.outputs[0], add.inputs[0])
    nt.links.new(em.outputs[0], add.inputs[1])
    nt.links.new(add.outputs[0], out.inputs["Surface"])
    return m


def beam(tip, target, radius, mat):
    from mathutils import Vector
    t, g = Vector(tip), Vector(target)
    axis = t - g
    bpy.ops.mesh.primitive_cone_add(vertices=32, radius1=radius, radius2=0.3, depth=axis.length,
                                    end_fill_type="NOTHING", location=(t + g) / 2)
    ob = bpy.context.active_object
    ob.rotation_mode = "QUATERNION"
    ob.rotation_quaternion = axis.normalized().to_track_quat("Z", "Y")
    ob.data.materials.append(mat)
    ob.visible_shadow = False
    return ob


def scene_game():
    # A generic arena glow at night: an oval stadium on a far plateau, floodlight masts throwing shafts of light down
    # into the bowl, and the glow of the pitch lighting the haze above it. Neutral colours; the app tints the image with
    # the team's backdrop colours. No logos, no text.
    top, bottom = lin(0x0A1220), lin(0x1A2638)
    ax, depth = 0.73, 680
    world([(0.0, mixc(top, lin(0x04080F), 0.5)), (0.35, top), (HZ - 0.03, bottom), (HZ + 0.03, lin(0x22324A)),
           (1.0, lin(0x22324A))],
          glows=[(ax, HZ - 0.02, lin(0x4A6898), 10, 0.40, 3.0), (ax, HZ - 0.07, lin(0xB8CCEC), 70, 0.35, 1.6)],
          stars=(0.93, 0.35))
    cx, cy, _ = place(ax, 0.5, depth)
    gz, rx, ry, wall = 22.0, 110.0, 62.0, 34.0
    shell = material("shell", lin(0x0E1420), rough=0.8)
    bpy.ops.mesh.primitive_cylinder_add(vertices=128, radius=rx, depth=wall, end_fill_type="NOTHING",
                                        location=(cx, cy, gz + wall / 2))
    bowl = bpy.context.active_object
    bowl.scale = (1.0, ry / rx, 1.0)
    bowl.data.materials.append(shell)
    # Roof canopy: a flattened ring that overhangs the wall; its underside band glows from the lit stands.
    bpy.ops.mesh.primitive_torus_add(major_radius=rx + 2, minor_radius=4.0, major_segments=128, minor_segments=12,
                                     location=(cx, cy, gz + wall + 1.5))
    roof = bpy.context.active_object
    roof.scale = (1.0, (ry + 2) / (rx + 2), 0.45)
    roof.data.materials.append(material("roof", lin(0x1A2232), rough=0.5))
    band = emission_material("band", lin(0xFFE6BE), 3.0)
    rng = random.Random(3)
    for k in range(90):  # lit concourse windows in rows along the front of the wall
        a = math.pi + math.pi * (k + 0.5) / 90
        if rng.random() < 0.7:
            for row in (0, 1):
                if rng.random() < 0.75:
                    box("win", (cx + (rx + 0.2) * math.cos(a), cy + (ry + 0.2) * math.sin(a),
                                gz + 9 + row * 9), (1.6, 0.3, 1.4), band, rot_z=a + math.pi / 2)
    field = emission_material("field", lin(0xDDEBFF), 10.0)
    bpy.ops.mesh.primitive_cylinder_add(vertices=64, radius=rx * 0.95, depth=1, location=(cx, cy, gz + 4))
    f = bpy.context.active_object
    f.scale = (1.0, ry / rx, 1.0)
    f.data.materials.append(field)
    mast = material("mast", lin(0x0A0E16), rough=0.7)
    flood = emission_material("flood", lin(0xF4F8FF), 10.0)
    shaft = beam_material("shaft", lin(0xDCE8FF), 0.9)
    for dx, dy in ((-0.92, -0.85), (0.92, -0.85), (-0.85, 0.9), (0.85, 0.9)):
        mx, my = cx + dx * rx, cy + dy * ry
        top_z = gz + 92
        box("mast", (mx, my, (gz + top_z) / 2), (1.4, 1.4, top_z - gz), mast)
        for r_ in range(3):
            for c_ in range(6):
                box("fl", (mx + (c_ - 2.5) * 2.2, my - 1.0, top_z + r_ * 2.2), (1.8, 0.5, 1.8), flood)
        beam((mx, my, top_z + 2), (cx + dx * rx * 0.25, cy + dy * ry * 0.2, gz + 2), 34.0, shaft)
        point_lamp((mx, my - 6, top_z - 4), 1.2e5, lin(0xE8F0FF), radius=5)
    plateau = material("plateau", lin(0x0A0F18), rough=1.0, fog=(lin(0x1A2638), 0.5))
    ridge("plateau", depth + 60, -0.1, 1.1, HZ + 0.03, 0.02, plateau, seed=119, freq=2.0, thick=300)
    near = material("near", lin(0x070B12), rough=1.0)
    ridge("near", 220, -0.1, 1.1, HZ + 0.10, 0.025, near, seed=121, freq=1.5, lift=calm_left)
    ground_plane(material("ground", lin(0x080C14)))


MOTIFS = {
    "DAWN": (scene_dawn, (0x1F3B66, 0x4A5F86)),
    "DAY": (scene_day, (0x1B4A86, 0x245C96)),
    "SUNSET": (scene_sunset, (0x2E2250, 0x7A3E3A)),
    "STARS": (scene_stars, (0x04060B, 0x0A1020)),
    "MOON_BATS": (scene_moon_bats, (0x120A1E, 0x3A1A12)),
    "SNOW": (scene_snow, (0x0A2418, 0x1B4A36)),
    "GAME": (scene_game, (0x0A1220, 0x1A2638)),
}

# ---------------------------------------------------------------------------------------------------------------------
# Post: bloom, calm left wash, bottom fade, dither. All in sRGB floats 0..1.


def srgb(c):
    return np.array([((c >> 16) & 255) / 255, ((c >> 8) & 255) / 255, (c & 255) / 255], dtype=np.float32)


def box_blur(a, r, axis):
    pad = [(0, 0)] * a.ndim
    pad[axis] = (r + 1, r)
    c = np.cumsum(np.pad(a, pad, mode="edge"), axis=axis)
    n = a.shape[axis]
    hi = np.take(c, np.arange(2 * r + 1, 2 * r + 1 + n), axis=axis)
    lo = np.take(c, np.arange(0, n), axis=axis)
    return (hi - lo) / (2 * r + 1)


def blur(a, r):
    for _ in range(3):
        a = box_blur(box_blur(a, r, 0), r, 1)
    return a


def post(img, top):
    h, w, _ = img.shape
    # Bloom: blur what is bright and screen it back, for a soft cinematic glow around lights.
    bright = np.clip(img - 0.55, 0, None) * 1.6
    glow = 0.6 * blur(bright, 14) + 0.4 * blur(bright, 48)
    img = 1 - (1 - img) * (1 - np.clip(glow, 0, 1))
    xs = np.linspace(0, 1, w, dtype=np.float32)[None, :, None]
    ys = np.linspace(0, 1, h, dtype=np.float32)[:, None, None]
    # Calm left: wash the title area towards the theme's top colour, strongest at the left edge, gone by 55%.
    t = np.clip((0.6 - xs) / 0.6, 0, 1) ** 1.3 * 0.62
    img = img * (1 - t) + srgb(top) * t
    # Vignette along the top edge, deeper in the top right corner under the clock and weather chip.
    corner = np.clip((xs - 0.55) / 0.35, 0, 1)
    v = np.clip((0.32 - ys) / 0.32, 0, 1) ** 1.5 * (0.18 + 0.40 * corner)
    img = img * (1 - v)
    # Bottom fade to the screen background: from 60% of the height to fully opaque at the edge.
    f = np.clip((ys - 0.6) / 0.4, 0, 1)
    f = f * f * (3 - 2 * f)
    img = img * (1 - f) + srgb(BACKGROUND) * f
    return np.clip(img, 0, 1)


def luminance(rgb):
    lin_ = np.where(rgb <= 0.04045, rgb / 12.92, ((rgb + 0.055) / 1.055) ** 2.4)
    return lin_ @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)


def contrast_check(name, img):
    """The title zone (left 45%, top 55%) and the clock zone (top right) against the text colours (Tokens.contrast)."""
    h, w, _ = img.shape
    zones = {"title": img[: int(h * 0.55), : int(w * 0.45)], "clock": img[int(h * 0.08): int(h * 0.26), int(w * 0.82):]}
    report = []
    ok = True
    for zname, z in zones.items():
        lum = float(np.percentile(luminance(z.reshape(-1, 3)), 99))
        for cname, c in (("text", TEXT), ("secondary", SECONDARY)):
            lt = float(luminance(srgb(c)[None, :])[0])
            ratio = (lt + 0.05) / (lum + 0.05)
            ok &= ratio >= 4.5
            report.append(f"{zname}/{cname} {ratio:.1f}")
    print(f"[{name}] contrast (99th percentile pixel): " + ", ".join(report))
    return ok


def to_8bit(img, seed):
    rng = np.random.default_rng(seed)
    noise = (rng.random(img.shape, dtype=np.float32) - rng.random(img.shape, dtype=np.float32)) / 255.0
    return np.clip(np.round((img + noise) * 255), 0, 255).astype(np.uint8)


def load_render(path):
    im = bpy.data.images.load(path)
    px = np.array(im.pixels[:], dtype=np.float32).reshape(im.size[1], im.size[0], 4)[::-1, :, :3]
    bpy.data.images.remove(im)
    # The EXR holds scene-linear light; the Standard view transform is the plain sRGB curve, so apply it here.
    px = np.clip(px, 0, 1)
    return np.where(px <= 0.0031308, px * 12.92, 1.055 * np.power(px, 1 / 2.4) - 0.055)


def contact_sheet(previews, path):
    cols, pad, label_h = 2, 16, 34
    pw, ph = previews[0][1].size
    rows = (len(previews) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * pw + (cols + 1) * pad, rows * (ph + label_h) + (rows + 1) * pad), (16, 20, 24))
    d = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.load_default(size=22)
    except TypeError:
        font = ImageFont.load_default()
    for i, (name, im) in enumerate(previews):
        x = pad + (i % cols) * (pw + pad)
        y = pad + (i // cols) * (ph + label_h + pad)
        d.text((x, y + 4), name, fill=(244, 241, 234), font=font)
        sheet.paste(im, (x, y + label_h))
    sheet.save(path)


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="app/src/main/res/drawable-nodpi")
    ap.add_argument("--work", default=os.environ.get("TMPDIR", "/tmp") + "/tvhome-backdrops")
    ap.add_argument("--preview", default=None, help="also write 960x300 PNG previews and contact.png here")
    ap.add_argument("--only", default=None)
    ap.add_argument("--samples", type=int, default=48)
    # q90: at q80 the dim night sky showed WebP block banding; every image still stays far under the 120 KB budget.
    ap.add_argument("--quality", type=int, default=90)
    ap.add_argument("--reuse", action="store_true", help="skip rendering when the raw EXR is already in --work")
    args = ap.parse_args(argv)
    os.makedirs(args.out, exist_ok=True)
    os.makedirs(args.work, exist_ok=True)
    names = args.only.split(",") if args.only else list(MOTIFS)
    previews, failed = [], []
    for name in names:
        build, (top, _bottom) = MOTIFS[name]
        t0 = time.time()
        raw = os.path.join(args.work, f"{name.lower()}_raw.exr")
        if not (args.reuse and os.path.exists(raw)):
            reset_scene(args.samples)
            build()
            bpy.context.scene.render.filepath = raw
            bpy.ops.render.render(write_still=True)
        img = post(load_render(raw), top)
        if not contrast_check(name, img):
            failed.append(name)
        out8 = Image.fromarray(to_8bit(img, seed=len(name)))
        webp = os.path.join(args.out, f"hero_{name.lower()}.webp")
        out8.save(webp, "WEBP", quality=args.quality, method=6)
        size_kb = os.path.getsize(webp) / 1024
        print(f"[{name}] {time.time() - t0:.0f}s render+post, {webp} {size_kb:.0f} KB")
        if size_kb > 120:
            failed.append(name + " (size)")
        prev = out8.resize((W // 2, H // 2), Image.LANCZOS)
        previews.append((name, prev))
        if args.preview:
            os.makedirs(args.preview, exist_ok=True)
            prev.save(os.path.join(args.preview, f"hero_{name.lower()}.png"))
    if args.preview and len(previews) > 1:
        contact_sheet(previews, os.path.join(args.preview, "contact.png"))
    if failed:
        print("FAILED checks: " + ", ".join(failed))
        return 1
    return 0


if __name__ == "__main__":
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else sys.argv[1:]
    sys.exit(main(argv))
