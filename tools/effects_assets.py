"""TaxSMP kill & death effects (plugin TaxSMPEffects): 3D effect models and 32x32 menu icons, folder fx/.

Models are designed in a "logical" frame: forward is +Z, up is +Y, the model's own left is +X, and every
part turns around its pivot at model (8, 8, 8). Item displays draw item models turned 180 degrees around Y,
so the files are written turned 180 degrees (x -> 16 - x, z -> 16 - z). The plugin can then treat +Z as forward.

Writes (nothing outside the fx/ folders is changed; the tombstone effect uses the existing taxsmp:tomb/rich):
  pack/assets/taxsmp/textures/item/fx/dragon.png         dragon texture sheet
  pack/assets/taxsmp/models|items/fx/dragon/<part>.json  dragon parts for item displays
  pack/assets/taxsmp/textures/item/fx/i/<effect>[_locked|_on].png + models|items fx/i/...  menu icons
  pack/assets/taxsmp/models|items/fx/ui/kill|death.json  hub buttons (the scoreboard swords and skull art)

    python tools/effects_assets.py [--preview DIR]
"""
import json
import math
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).absolute().parent.parent
ASSETS = ROOT / "pack/assets/taxsmp"
TEX = ASSETS / "textures/item/fx"
MODELS = ASSETS / "models/fx"
ITEMS = ASSETS / "items/fx"


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=1) + "\n", encoding="utf-8")


def item_def(path_key):
    write_json(ITEMS / f"{path_key}.json", {"model": {"type": "minecraft:model", "model": f"taxsmp:fx/{path_key}"}})


# ------------------------------------------------------------------ texture sheet + boxes

class Atlas:
    """A square texture sheet cut into tiles; face UVs map one texel to one model unit."""

    def __init__(self, size=64, tile=16):
        self.size, self.tile = size, tile
        self.img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        self.tiles = {}
        self.cells = size // tile

    def add(self, key, painter, seed=0):
        i = len(self.tiles)
        x0, y0 = i % self.cells * self.tile, i // self.cells * self.tile
        tile = Image.new("RGBA", (self.tile, self.tile))
        painter(tile, random.Random(f"{key}{seed}"))
        self.img.paste(tile, (x0, y0))
        self.tiles[key] = (x0, y0)

    def uv(self, key, w, h):
        x0, y0 = self.tiles[key]
        s = 16 / self.size
        pw, ph = max(0.5, min(self.tile, w)), max(0.5, min(self.tile, h))
        return [x0 * s, y0 * s, (x0 + pw) * s, (y0 + ph) * s]


def box(atlas, frm, to, tex, light=0):
    """tex: a tile name, or a dict with 'side'/'top'/'bottom' (or face names). light: light_emission 0-15."""
    (x0, y0, z0), (x1, y1, z1) = frm, to
    dims = {"north": (x1 - x0, y1 - y0), "south": (x1 - x0, y1 - y0), "east": (z1 - z0, y1 - y0),
            "west": (z1 - z0, y1 - y0), "up": (x1 - x0, z1 - z0), "down": (x1 - x0, z1 - z0)}
    faces = {}
    for f, (w, h) in dims.items():
        if isinstance(tex, str):
            key = tex
        else:
            key = tex.get(f) or tex.get({"up": "top", "down": "bottom"}.get(f, "side")) or tex.get("side")
        if key:
            faces[f] = {"uv": atlas.uv(key, w, h), "texture": "#t"}
    el = {"from": [float(v) for v in frm], "to": [float(v) for v in to], "faces": faces}
    if light:
        el["light_emission"] = light
    return el


def mirror_x(el):
    """The same element on the other side (x -> 16 - x)."""
    (x0, y0, z0), (x1, y1, z1) = el["from"], el["to"]
    out = dict(el, **{"from": [16 - x1, y0, z0], "to": [16 - x0, y1, z1]})
    f = dict(el["faces"])
    if "east" in f or "west" in f:
        f["east"], f["west"] = el["faces"].get("west"), el["faces"].get("east")
        f = {k: v for k, v in f.items() if v}
    out["faces"] = f
    return out


SWAP = {"north": "south", "south": "north", "east": "west", "west": "east", "up": "up", "down": "down"}


def to_file_frame(el):
    """Logical frame -> item model file (turned 180 degrees around Y, undone by the item display)."""
    (x0, y0, z0), (x1, y1, z1) = el["from"], el["to"]
    out = dict(el, **{"from": [16 - x1, y0, 16 - z1], "to": [16 - x0, y1, 16 - z0]})
    faces = {}
    for k, v in el["faces"].items():
        v = dict(v)
        if k in ("up", "down"):
            v["rotation"] = (v.get("rotation", 0) + 180) % 360
        faces[SWAP[k]] = v
    out["faces"] = faces
    if "rotation" in el:
        r = dict(el["rotation"])
        o = r["origin"]
        r["origin"] = [16 - o[0], o[1], 16 - o[2]]
        if r["axis"] in ("x", "z"):
            r["angle"] = -r["angle"]
        out["rotation"] = r
    return out


def write_part(key, texture, elements):
    els = [to_file_frame(e) for e in elements]  # model coordinates must stay within -16..32
    for e in els:
        for c in e["from"] + e["to"]:
            assert -16 <= c <= 32, (key, e)
    write_json(MODELS / f"{key}.json", {"textures": {"t": texture, "particle": texture}, "elements": els})
    item_def(key)


# ------------------------------------------------------------------ painters

def shade(c, d):
    return tuple(max(0, min(255, int(v + d))) for v in c[:3]) + (255,)


def p_scales(base, dark, light):
    def paint(im, rnd):
        for y in range(16):
            for x in range(16):
                row = y // 3
                cx, cy = (x + (row % 2) * 2) % 4, y % 3
                c = base
                if cy == 2:
                    c = dark
                elif cy == 0 and cx in (1, 2):
                    c = light
                elif cx == 0 and cy == 1:
                    c = shade(base, -18)
                im.putpixel((x, y), shade(c, rnd.randint(-7, 7)))
    return paint


def p_plates(base, dark, light):
    def paint(im, rnd):
        for y in range(16):
            for x in range(16):
                c = dark if y % 4 == 3 else light if y % 4 == 0 else base
                im.putpixel((x, y), shade(c, rnd.randint(-6, 6)))
    return paint


def p_flat(base, noise=8):
    def paint(im, rnd):
        for y in range(16):
            for x in range(16):
                im.putpixel((x, y), shade(base, rnd.randint(-noise, noise)))
    return paint


def p_horn(top, bottom):
    def paint(im, rnd):
        for y in range(16):
            t = y / 15
            c = tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3)) + (255,)
            for x in range(16):
                im.putpixel((x, y), shade(c, (-20 if y % 4 == 3 else 0) + rnd.randint(-5, 5)))
    return paint


def p_membrane(base, vein):
    def paint(im, rnd):
        for y in range(16):
            for x in range(16):
                c = vein if (x + y // 2) % 6 == 0 else base
                im.putpixel((x, y), shade(c, rnd.randint(-6, 6) + (8 if y < 2 else 0)))
    return paint


# ------------------------------------------------------------------ dragon (kill effect "Dragon Flyby")
# Keep these numbers in sync with DragonFlyby.java (segment lengths and wing joints, in model units).
DRAGON_SEGMENTS = {"neck": 8, "body": 24, "tail1": 12, "tail2": 12, "tail3": 11}
DRAGON_SHOULDER = (4.5, 2.5, -5)   # left shoulder from the body pivot; the right one is x mirrored
DRAGON_WRIST = 16                  # inner wing length (outer wing pivot, along the wing's own x)

RED, RED_D, RED_L = (150, 28, 34), (96, 14, 22), (196, 62, 54)
BACK, BACK_D, BACK_L = (98, 16, 26), (58, 8, 16), (128, 30, 38)
GOLD, GOLD_D, GOLD_L = (222, 158, 60), (170, 104, 34), (246, 196, 98)


def dragon():
    a = Atlas(64, 16)
    a.add("scales", p_scales(RED, RED_D, RED_L))
    a.add("back", p_scales(BACK, BACK_D, BACK_L))
    a.add("belly", p_plates(GOLD, GOLD_D, GOLD_L))
    a.add("horn", p_horn((240, 232, 210), (170, 148, 116)))
    a.add("eye", p_flat((255, 222, 90), 0))
    a.add("membrane", p_membrane((132, 26, 34), (92, 14, 22)))
    a.add("spike", p_flat((52, 10, 16), 6))
    a.add("claw", p_flat((40, 34, 34), 5))
    a.add("tooth", p_flat((244, 238, 222), 3))
    a.add("mouth", p_flat((70, 10, 14), 4))
    body = {"top": "back", "bottom": "belly", "side": "scales"}
    sym = lambda els: els + [mirror_x(e) for e in els]

    head = [
        box(a, [5, 6, 7], [11, 12, 14], {"top": "back", "bottom": "mouth", "side": "scales"}),
        box(a, [4.5, 11, 11.5], [11.5, 12.5, 14.5], "back"),
        box(a, [5.75, 7, 14], [10.25, 10.5, 20.5], {"top": "back", "bottom": "mouth", "side": "scales"}),
        box(a, [6, 5, 9], [10, 7, 20], {"top": "mouth", "bottom": "belly", "side": "scales"}),
    ] + sym([
        box(a, [9, 10.5, 19], [10, 11, 20.5], "spike"),                      # nostril ridge
        box(a, [9.25, 6.5, 17.5], [9.75, 7, 18.25], "tooth"),
        box(a, [9.25, 6.5, 19.25], [9.75, 7, 20], "tooth"),
        box(a, [11, 10, 12], [11.25, 11, 13.5], "eye", light=15),
        box(a, [9.5, 11.5, 4], [10.75, 12.75, 9], "horn"),
        box(a, [9.75, 12.25, 1], [10.5, 13.25, 4], "horn"),
        box(a, [11, 7, 7], [12, 9, 10], "spike"),                              # cheek frill
    ])
    neck = [box(a, [5.5, 5.75, 0], [10.5, 10.75, 9.5], body),
            box(a, [7.4, 10.75, 5], [8.6, 12.25, 7], "spike"),
            box(a, [7.4, 10.75, 1.5], [8.6, 12, 3.5], "spike")]
    torso = [box(a, [3.5, 3.5, -2], [12.5, 11, 8.5], body),
             box(a, [3, 3, -11], [13, 11.5, -2], body),
             box(a, [4, 4, -16], [12, 10.5, -11], body)]
    torso += [box(a, [7.25, 11, z], [8.75, 13.5, z + 2], "spike") for z in (-15, -10, -6, -1, 4)]
    torso += sym([
        box(a, [11.5, 3, 3], [13.5, 6.5, 7], "scales"),       # front leg
        box(a, [11.5, 1, 4], [13.5, 3, 7.5], "scales"),
        box(a, [11.5, 0.25, 6.5], [13.5, 1, 8.5], "claw"),
        box(a, [11.5, 3.5, -15], [13.5, 8, -10], "scales"),   # back leg
        box(a, [11.5, 1, -13], [13.5, 3.5, -9.5], "scales"),
        box(a, [11.5, 0.25, -10], [13.5, 1, -7.5], "claw"),
    ])
    tail1 = [box(a, [5, 5.25, -4], [11, 10.75, 9.5], body),
             box(a, [7.4, 10.75, -2], [8.6, 12.5, 0], "spike"),
             box(a, [7.4, 10.75, 3], [8.6, 12.5, 5], "spike")]
    tail2 = [box(a, [5.75, 6, -4], [10.25, 10, 9], body),
             box(a, [7.5, 10, -1], [8.5, 11.5, 1], "spike"),
             box(a, [7.5, 10, 4], [8.5, 11.5, 6], "spike")]
    tail3 = [box(a, [6.5, 6.75, -3], [9.5, 9.25, 9], body),
             box(a, [7.6, 9.25, 2], [8.4, 10.5, 4], "spike"),
             box(a, [7, 7.25, -10], [9, 8.75, -9], "spike"),
             box(a, [5.5, 7.25, -9], [10.5, 8.75, -7], "spike"),
             box(a, [4.5, 7.25, -7], [11.5, 8.75, -5], "spike"),
             box(a, [6, 7.25, -5], [10, 8.75, -3], "spike")]

    def wing_in():
        els = [box(a, [8, 7.25, 6.5], [24, 9, 8.5], {"top": "back", "bottom": "scales", "side": "scales"})]
        for x0, z0 in ((8, -7), (12, -6), (16, -4.5), (20, -3)):
            els.append(box(a, [x0, 7.75, z0], [x0 + 4, 8.25, 7], "membrane"))
        return els

    def wing_out():
        els = [box(a, [8, 7.25, 6.5], [26, 8.75, 8], {"top": "back", "bottom": "scales", "side": "scales"}),
               box(a, [7.5, 8.75, 7], [9, 10, 9], "claw"),
               box(a, [11, 8.25, -2], [11.75, 8.6, 6.5], "spike"),
               box(a, [17, 8.25, 0], [17.75, 8.6, 6.5], "spike")]
        for x0, z0 in ((8, -4), (11, -2), (14, -3), (17, -1), (20, 0), (23, 3)):
            els.append(box(a, [x0, 7.75, z0], [x0 + 3, 8.25, 6.5], "membrane"))
        return els

    parts = {"head": head, "neck": neck, "body": torso, "tail1": tail1, "tail2": tail2, "tail3": tail3,
             "wing_l_in": wing_in(), "wing_l_out": wing_out(),
             "wing_r_in": [mirror_x(e) for e in wing_in()], "wing_r_out": [mirror_x(e) for e in wing_out()]}
    TEX.mkdir(parents=True, exist_ok=True)
    a.img.save(TEX / "dragon.png")
    for name, els in parts.items():
        write_part(f"dragon/{name}", "taxsmp:item/fx/dragon", els)
    return parts, a.img


# ------------------------------------------------------------------ software renderer for the menu icons

def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rot_z(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def face_quad(el, face):
    (x0, y0, z0), (x1, y1, z1) = el["from"], el["to"]
    return {
        "north": [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
        "south": [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
        "east": [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
        "west": [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
        "up": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        "down": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
    }[face]


JAR = None  # zipfile.ZipFile of a Minecraft client jar: vanilla textures for icons (only needed to redraw icons)


def load_image(ref):
    """'ns:path' texture -> RGBA image (first animation frame). '@rrggbb' at the end tints it (e.g. oak leaves)."""
    tint = None
    if "@" in ref:
        ref, tint = ref.split("@")
    ns, path = ref.split(":", 1) if ":" in ref else ("minecraft", ref)
    file = ROOT / f"pack/assets/{ns}/textures/{path}.png"
    if file.exists():
        im = Image.open(file).convert("RGBA")
    elif JAR is not None:
        import io
        im = Image.open(io.BytesIO(JAR.read(f"assets/{ns}/textures/{path}.png"))).convert("RGBA")
    else:
        raise FileNotFoundError(f"{ref}: pass a Minecraft client jar to draw vanilla textures")
    im = im.crop((0, 0, im.width, im.width))
    if tint:
        t = np.array([int(tint[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float32) / 255
        a = np.asarray(im).astype(np.float32)
        a[..., :3] *= t
        im = Image.fromarray(a.astype(np.uint8), "RGBA")
    return im


def load_texture(ref):
    return np.asarray(load_image(ref), dtype=np.float32)


class Scene:
    """Collects textured quads in world space (blocks) and renders them orthographically with a z-buffer."""

    def __init__(self):
        self.quads = []

    def add(self, elements, textures, rot=np.eye(3), pos=(0, 0, 0), scale=1.0):
        cache = {}
        for el in elements:
            er = el.get("rotation")
            for face, fd in el["faces"].items():
                ref = fd["texture"]
                while ref.startswith("#"):
                    ref = textures[ref[1:]]
                if ref not in cache:
                    cache[ref] = load_texture(ref)
                pts = np.array(face_quad(el, face), dtype=np.float64)
                if er:
                    o = np.array(er["origin"], dtype=np.float64)
                    ang = math.radians(er["angle"])
                    r = {"x": rot_x, "y": rot_y, "z": rot_z}[er["axis"]](ang)
                    pts = (pts - o) @ r.T + o
                pts = ((pts - 8) / 16 * scale) @ np.asarray(rot).T + np.asarray(pos)
                u0, v0, u1, v1 = fd["uv"]
                uvs = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
                k = fd.get("rotation", 0) // 90 % 4
                if k:
                    uvs = uvs[-k:] + uvs[:-k]
                n = np.cross(pts[3] - pts[0], pts[1] - pts[0])  # outward face normal
                ln = np.linalg.norm(n)
                if ln < 1e-12:
                    continue
                self.quads.append((pts, np.array(uvs), cache[ref], n / ln, el.get("light_emission", 0) > 0))

    def project(self, point, size):
        """Pixel position of a world point in the last render, scaled to an image `size` pixels wide."""
        view, mid, k, rsize = self.proj
        p = view @ np.asarray(point, dtype=np.float64)
        f = size / rsize
        return ((p[0] - mid[0]) * k + rsize / 2) * f, (-(p[1] - mid[1]) * k + rsize / 2) * f

    def render(self, size=256, yaw=30, pitch=28, margin=0.06, light=1.0, inside_out=False):
        """inside_out draws the far faces instead of the near ones; only the approved dragon icon uses it."""
        view = rot_x(math.radians(pitch)) @ rot_y(math.radians(yaw))
        allp = np.concatenate([q[0] for q in self.quads]) @ view.T
        lo, hi = allp[:, :2].min(0), allp[:, :2].max(0)
        span = (hi - lo).max() * (1 + 2 * margin)
        mid = (hi + lo) / 2
        k = size / span
        self.proj = (view, mid, k, size)
        zbuf = np.full((size, size), -1e9)
        col = np.zeros((size, size, 4), dtype=np.float32)
        for pts, uvs, tex, n, glow in self.quads:
            if inside_out:
                n = -n
            vn = view @ n
            if vn[2] <= 1e-6:
                continue  # facing away
            lit = 1.0 if glow else (n[0] ** 2 * 0.62 + n[2] ** 2 * 0.82 + n[1] ** 2 * (1.0 if n[1] > 0 else 0.5)) * light
            p = pts @ view.T
            sx = (p[:, 0] - mid[0]) * k + size / 2
            sy = -(p[:, 1] - mid[1]) * k + size / 2
            for tri in ((0, 1, 2), (0, 2, 3)):
                self._tri(sx[list(tri)], sy[list(tri)], p[list(tri), 2], uvs[list(tri)], tex, lit, zbuf, col)
        return Image.fromarray(np.clip(col, 0, 255).astype(np.uint8), "RGBA")

    @staticmethod
    def _tri(xs, ys, zs, uvs, tex, light, zbuf, col):
        size = zbuf.shape[0]
        x0, x1 = max(0, int(math.floor(xs.min()))), min(size - 1, int(math.ceil(xs.max())))
        y0, y1 = max(0, int(math.floor(ys.min()))), min(size - 1, int(math.ceil(ys.max())))
        if x1 < x0 or y1 < y0:
            return
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        area = (xs[1] - xs[0]) * (ys[2] - ys[0]) - (xs[2] - xs[0]) * (ys[1] - ys[0])
        if abs(area) < 1e-9:
            return
        w0 = ((xs[1] - gx) * (ys[2] - gy) - (xs[2] - gx) * (ys[1] - gy)) / area
        w1 = ((xs[2] - gx) * (ys[0] - gy) - (xs[0] - gx) * (ys[2] - gy)) / area
        w2 = 1 - w0 - w1
        inside = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
        if not inside.any():
            return
        z = w0 * zs[0] + w1 * zs[1] + w2 * zs[2]
        u = w0 * uvs[0, 0] + w1 * uvs[1, 0] + w2 * uvs[2, 0]
        v = w0 * uvs[0, 1] + w1 * uvs[1, 1] + w2 * uvs[2, 1]
        th, tw = tex.shape[:2]
        tx = np.clip((u / 16 * tw).astype(int), 0, tw - 1)
        ty = np.clip((v / 16 * th).astype(int), 0, th - 1)
        texel = tex[ty, tx]
        sub = zbuf[y0:y1 + 1, x0:x1 + 1]
        ok = inside & (texel[..., 3] > 127) & (z > sub)
        sub[ok] = z[ok]
        c = col[y0:y1 + 1, x0:x1 + 1]
        c[ok, :3] = texel[ok, :3] * light
        c[ok, 3] = 255


OUTLINE = (24, 18, 22, 255)


def finish_icon(big, size=32):
    """256 px render -> 32 px item icon: area-averaged colours, hard alpha and a 1 px dark outline."""
    small = big.convert("RGBa").reduce(big.width // size).convert("RGBA")
    px = np.asarray(small).copy()
    solid = px[..., 3] >= 96
    px[..., 3] = np.where(solid, 255, 0)
    grow = solid.copy()
    grow[1:] |= solid[:-1]
    grow[:-1] |= solid[1:]
    grow[:, 1:] |= solid[:, :-1]
    grow[:, :-1] |= solid[:, 1:]
    edge = grow & ~solid
    px[edge] = OUTLINE
    return Image.fromarray(px, "RGBA")


PADLOCK = ["..kkkk..", ".kssssk.", ".ks..sk.", "kkkkkkkk", "kggggggk", "kgggdggk", "kgggdggk", "kggggggk", "kkkkkkkk"]
CHECK = ["..kkkkk..", ".kgggggk.", "kgggggwgk", "kggggwwgk", "kgwgwwggk", "kgwwwgggk", "kggwggggk", ".kgggggk.", "..kkkkk.."]
BADGE_COLORS = {"k": (20, 16, 18, 255), "s": (200, 204, 214, 255), "g": (240, 186, 60, 255), "d": (70, 46, 20, 255),
                "w": (255, 255, 255, 255)}


def badge(icon, art, colors):
    out = icon.copy()
    h, w = len(art), len(art[0])
    ox, oy = out.width - w, out.height - h
    for y, row in enumerate(art):
        for x, ch in enumerate(row):
            if ch != ".":
                out.putpixel((ox + x, oy + y), colors[ch])
    return out


def icon_variants(name, icon):
    """<name> (owned), <name>_locked (grey + padlock), <name>_on (equipped: green check)."""
    gray = icon.convert("LA").convert("RGBA")
    g = np.asarray(gray).astype(np.float32)
    g[..., :3] = g[..., :3] * 0.62 + np.array([18, 20, 30])
    locked = badge(Image.fromarray(np.clip(g, 0, 255).astype(np.uint8), "RGBA"), PADLOCK, BADGE_COLORS)
    green = dict(BADGE_COLORS, g=(80, 200, 70, 255))
    on = badge(icon, CHECK, green)
    out = {name: icon, f"{name}_locked": locked, f"{name}_on": on}
    for key, im in out.items():
        (TEX / "i").mkdir(parents=True, exist_ok=True)
        im.save(TEX / f"i/{key}.png")
        write_json(MODELS / f"i/{key}.json", {"parent": "minecraft:item/generated",
                                               "textures": {"layer0": f"taxsmp:item/fx/i/{key}"}})
        item_def(f"i/{key}")
    return out


def quat_pose(forward, roll=0.0):
    """Rotation matrix for a part pointing along `forward` (logical +Z) with a roll around it."""
    f = np.asarray(forward, dtype=np.float64)
    f = f / np.linalg.norm(f)
    yaw = math.atan2(f[0], f[2])
    pitch = -math.asin(max(-1, min(1, f[1])))
    return rot_y(yaw) @ rot_x(pitch) @ rot_z(roll)


def dragon_icon(parts):
    """The whole dragon flying diagonally across the icon, seen from below: gold belly, wings spread."""
    s = 1.0
    scene = Scene()
    tex = {"t": "taxsmp:item/fx/dragon"}

    def path(d):  # d = distance behind the head (blocks); the tail curls to one side and up
        return np.array([-0.3 * d * d * 0.08, 0.02 * d * d, -d])

    head_pos = path(0)
    scene.add(parts["head"], tex, quat_pose(head_pos - path(0.3)), head_pos, s)
    d = 0.0
    for seg in ("neck", "body", "tail1", "tail2", "tail3"):
        length = DRAGON_SEGMENTS[seg] / 16 * s
        front, back = path(d), path(d + length)
        r = quat_pose(front - back)
        scene.add(parts[seg], tex, r, front, s)
        if seg == "body":
            body_rot, body_front = r, front
        d += length
    for side, sign in (("l", 1), ("r", -1)):
        shoulder = body_front + body_rot @ (np.array([sign * DRAGON_SHOULDER[0], DRAGON_SHOULDER[1], DRAGON_SHOULDER[2]]) / 16 * s)
        r_in = body_rot @ rot_z(sign * math.radians(20))
        scene.add(parts[f"wing_{side}_in"], tex, r_in, shoulder, s)
        wrist = shoulder + r_in @ (np.array([sign * DRAGON_WRIST, 0, 0]) / 16 * s)
        r_out = r_in @ rot_z(sign * math.radians(-14))
        scene.add(parts[f"wing_{side}_out"], tex, r_out, wrist, s)
    return finish_icon(scene.render(256, yaw=200, pitch=50, light=1.12, inside_out=True))


def tomb_icon():
    """TaxSMP's own rich tombstone (models/tomb/rich.json, read only), drawn as it is (the PNG is for Bedrock)."""
    model = json.loads((ASSETS / "models/tomb/rich.json").read_text(encoding="utf-8"))
    scene = Scene()
    scene.add(model["elements"], model["textures"], rot_y(math.pi))  # its engraved front is model north
    return finish_icon(scene.render(256, yaw=-24, pitch=14, margin=0.1, light=1.35))


def tomb_java_icons():
    """On Java the Tombstone icon IS the real tombstone item model, so it matches what rises exactly. Locked and
    equipped add the padlock / check badge on top (composite item model)."""
    for key, art, colors in (("badge_lock", PADLOCK, BADGE_COLORS), ("badge_on", CHECK, dict(BADGE_COLORS, g=(80, 200, 70, 255)))):
        badge(Image.new("RGBA", (32, 32), (0, 0, 0, 0)), art, colors).save(TEX / f"i/{key}.png")
        write_json(MODELS / f"i/{key}.json", {"parent": "minecraft:item/generated",
                                               "textures": {"layer0": f"taxsmp:item/fx/i/{key}"},
                                               "display": {"gui": {"rotation": [0, 0, 0], "translation": [0, 0, 16],
                                                                   "scale": [1, 1, 1]}}})
    tomb = {"type": "minecraft:model", "model": "taxsmp:tomb/rich"}
    write_json(ITEMS / "i/tombstone.json", {"model": tomb})
    for suffix, badge_key in (("_locked", "badge_lock"), ("_on", "badge_on")):
        write_json(ITEMS / f"i/tombstone{suffix}.json", {"model": {"type": "minecraft:composite", "models": [
            tomb, {"type": "minecraft:model", "model": f"taxsmp:fx/i/{badge_key}"}]}})


def ui_buttons():
    """Hub buttons: the scoreboard's swords (kills) and skull (deaths), as their own item models."""
    for key, texture in (("ui/kill", "taxsmp:item/sb/swords"), ("ui/death", "taxsmp:item/sb/skull")):
        write_json(MODELS / f"{key}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": texture}})
        item_def(key)


def main():
    """python tools/effects_assets.py [path/to/client.jar] [--preview DIR]
    The client jar (any 1.21.4+ / 26.x) is only needed to redraw the icons that show vanilla blocks and items."""
    import zipfile
    import effects_models
    preview = None
    if "--preview" in sys.argv:
        preview = Path(sys.argv[sys.argv.index("--preview") + 1])
    jars = [a for a in sys.argv[1:] if a.lower().endswith(".jar")]
    if jars:
        effects_models.fx.JAR = zipfile.ZipFile(jars[0])
    parts, sheet = dragon()
    icons = {}
    icons.update(icon_variants("dragon_flyby", dragon_icon(parts)))
    icons.update(icon_variants("tombstone", tomb_icon()))
    tomb_java_icons()
    ui_buttons()
    icons.update(effects_models.build())
    if preview:
        preview.mkdir(parents=True, exist_ok=True)
        sheet.resize((256, 256), Image.NEAREST).save(preview / "fx-dragon-sheet.png")
        names = [n for n in icons if not n.endswith(("_locked", "_on"))]
        cols = 8
        board = Image.new("RGBA", (cols * 140 + 10, (len(names) + cols - 1) // cols * 140 + 10), (139, 139, 139, 255))
        for i, n in enumerate(names):
            board.alpha_composite(icons[n].resize((128, 128), Image.NEAREST), (10 + i % cols * 140, 10 + i // cols * 140))
        board.save(preview / "fx-icons.png")
        print("icon order:", ", ".join(names))
    print("effects assets written |", len(icons), "icons")


if __name__ == "__main__":
    main()
