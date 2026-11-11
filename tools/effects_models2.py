"""Effects batch 3 (24 more: 12 kill, 12 death) + tier auras: models, sprites and menu icons. Called by effects_models.build().

Same rules as effects_assets.py: logical frame (forward +Z, up +Y, own left +X, pivot at model 8,8,8), written turned
180 degrees for item displays. Keep pivots/offsets in sync with KillFx2.java / DeathFx2.java.
"""
import math
import random

import numpy as np
from PIL import Image, ImageDraw

import effects_assets as fx
from effects_assets import Atlas, Scene, box, finish_icon, icon_variants, item_def, mirror_x, rot_x, rot_y, rot_z, \
    shade, write_json, write_part, p_flat, MODELS
from effects_models import GOLD, grad, stripes, feathers, art, sprite, save_atlas, cube, render, canvas, paste, vanilla, big


def glass_tile(rgb, alpha, clear=False):
    """Glass: a light edge and a see-through middle. clear=True makes the middle fully clear (no blending problems)."""
    def paint(im, rnd):
        for y in range(16):
            for x in range(16):
                edge = x in (0, 15) or y in (0, 15)
                if clear:
                    im.putpixel((x, y), (230, 245, 255, 255) if edge or (x == 3 and 2 < y < 8) else (0, 0, 0, 0))
                else:
                    im.putpixel((x, y), tuple(min(255, c + (40 if edge else 0)) for c in rgb) + (min(255, alpha + (60 if edge else 0)),))
    return paint


def p_checker(a, b, size=2):
    def paint(im, rnd):
        for y in range(16):
            for x in range(16):
                c = a if (x // size + y // size) % 2 == 0 else b
                im.putpixel((x, y), shade(c, rnd.randint(-6, 6)))
    return paint


# ================================================================ KILL models

def shark():
    a = Atlas()
    a.add("top", grad((120, 140, 164), (92, 110, 132), noise=4))
    a.add("belly", p_flat((236, 240, 246), 3))
    a.add("tooth", p_flat((255, 255, 255), 0))
    a.add("eye", p_flat((20, 22, 30), 0))
    a.add("shine", p_flat((255, 255, 255), 0))
    a.add("gill", p_flat((70, 84, 100), 2))
    body = {"top": "top", "bottom": "belly", "side": "top"}
    els = [box(a, [5, 4, -6], [11, 11, 12], body), box(a, [5.5, 4.5, 12], [10.5, 10, 18], body),
           box(a, [6.5, 5, 18], [9.5, 9, 21], body), box(a, [6.5, 4.6, 15], [9.5, 5.4, 20.5], {"bottom": "tooth", "side": "tooth"}),
           box(a, [6.5, 5.5, -12], [9.5, 9.5, -6], body),
           box(a, [7.5, 11, 0], [8.5, 15, 5], "top"), box(a, [7.5, 15, 1], [8.5, 17, 3], "top"),
           box(a, [7.5, 2, -16], [8.5, 9, -12], "top"), box(a, [7.5, 9, -16], [8.5, 15, -13], "top")]
    for e in (box(a, [11, 5, 6], [15, 6, 9], "top"), box(a, [10.5, 8, 15], [10.7, 9, 16.5], "eye"),
              box(a, [10.7, 8.6, 15.2], [10.75, 8.9, 15.6], "shine", 15), box(a, [10.9, 6, 9], [11.05, 9, 9.4], "gill"),
              box(a, [10.9, 6, 10], [11.05, 9, 10.4], "gill")):
        els += [e, mirror_x(e)]
    save_atlas("shark", a)
    write_part("shark", "taxsmp:item/fx/shark", els)
    return els


def cannon():
    a = Atlas()
    a.add("iron", grad((70, 74, 84), (30, 32, 38), noise=4))
    a.add("wood", stripes((132, 84, 44), (96, 58, 28), every=4))
    a.add("dark", p_flat((70, 44, 24), 4))
    a.add("gold", GOLD)
    els = [box(a, [3.5, 0.5, 1], [12.5, 4.5, 13], "wood"),
           box(a, [5, 5, 0], [11, 11, 22], "iron"), box(a, [4.5, 4.5, 19.5], [11.5, 11.5, 22], "iron"),
           box(a, [6.5, 6.5, -2], [9.5, 9.5, 0], "iron"), box(a, [4.8, 4.8, 8], [11.2, 11.2, 9], "gold"),
           box(a, [4.8, 4.8, 14], [11.2, 11.2, 15], "gold"), box(a, [6, 4.5, 4], [10, 5, 10], "dark")]
    for e in (box(a, [1.5, 0, 2], [3.5, 5, 7], "dark"), box(a, [1.5, 0, 8], [3.5, 5, 13], "dark"),
              box(a, [1.3, 2, 4], [1.5, 3, 5], "gold"), box(a, [1.3, 2, 10], [1.5, 3, 11], "gold")):
        els += [e, mirror_x(e)]
    save_atlas("cannon", a)
    write_part("cannon", "taxsmp:item/fx/cannon", els)
    return els


def katana():
    """Blade up +Y, pivot at the grip (model 8, 8, 8)."""
    a = Atlas()
    a.add("blade", grad((250, 252, 255), (176, 186, 200), noise=3))
    a.add("edge", p_flat((255, 255, 255), 0))
    a.add("wrap", p_checker((30, 30, 38), (130, 24, 30), 2))
    a.add("gold", GOLD)
    els = [box(a, [7, -1, 7], [9, 0, 9], "gold"), box(a, [7.25, 0, 7.25], [8.75, 8, 8.75], "wrap"),
           box(a, [5.5, 8, 6.5], [10.5, 8.75, 9.5], "gold"), box(a, [7.4, 8.75, 7.6], [8.6, 30, 8.4], "blade"),
           box(a, [8.6, 8.75, 7.75], [8.75, 29.5, 8.25], "edge", 12), box(a, [7.6, 30, 7.7], [8.4, 31.5, 8.3], "edge", 12)]
    save_atlas("katana", a)
    write_part("katana", "taxsmp:item/fx/katana", els)
    return els


def disco_ball():
    a = Atlas()

    def mirror(im, rnd):
        for y in range(16):
            for x in range(16):
                base = 150 + rnd.randint(0, 90)
                c = (base, base, min(255, base + 20), 255)
                if x % 2 == 1 or y % 2 == 1:
                    c = (80, 84, 96, 255)
                im.putpixel((x, y), c)
    a.add("mirror", mirror)
    a.add("chain", p_flat((90, 90, 100), 4))
    els = [box(a, b0, b1, "mirror", 12) for b0, b1 in (([3, 4, 3], [13, 12, 13]), ([4, 3, 4], [12, 13, 12]),
                                                        ([2, 5, 5], [14, 11, 11]), ([5, 5, 2], [11, 11, 14]),
                                                        ([5, 2, 5], [11, 14, 11]))]
    els.append(box(a, [7.7, 14, 7.7], [8.3, 26, 8.3], "chain"))
    save_atlas("disco", a)
    write_part("disco", "taxsmp:item/fx/disco", els)
    return els


def pot_of_gold():
    a = Atlas()
    a.add("iron", grad((60, 62, 70), (24, 24, 30), noise=4))
    a.add("coins", p_flat((255, 210, 70), 18))
    a.add("gold", GOLD)
    els = [box(a, [4, 0, 4], [12, 6, 12], "iron"), box(a, [3.5, 6, 3.5], [12.5, 7, 12.5], "iron"),
           box(a, [4.5, 7, 4.5], [11.5, 8.5, 11.5], "coins", 12), box(a, [6, 8.5, 6], [10, 9.5, 10], "coins", 12),
           box(a, [7, 9.5, 7], [9, 10.2, 9], "gold", 12)]
    for x, z in ((4, 4), (11, 4), (4, 11), (11, 11)):
        els.append(box(a, [x, -1, z], [x + 1, 0, z + 1], "iron"))
    save_atlas("pot", a)
    write_part("pot", "taxsmp:item/fx/pot", els)
    return els


def trophy():
    a = Atlas()
    a.add("gold", GOLD)
    a.add("base", p_flat((40, 30, 26), 4))
    a.add("plate", p_flat((230, 230, 236), 3))
    a.add("star", art(["...y...", "..yyy..", "yyyyyyy", ".yyyyy.", "..y.y.."], {"y": (255, 255, 200, 255)}))
    els = [box(a, [4, 0, 4], [12, 2, 12], "base"), box(a, [6, 0.6, 11.9], [10, 1.6, 12.05], {"south": "plate"}),
           box(a, [7, 2, 7], [9, 6, 9], "gold"), box(a, [6, 5, 6], [10, 6.5, 10], "gold"),
           box(a, [4.5, 6.5, 4.5], [11.5, 13, 11.5], "gold"), box(a, [4, 12.5, 4], [12, 13.5, 12], "gold"),
           box(a, [5, 8, 11.5], [11, 12, 11.6], {"south": "star"}, 14)]
    for e in (box(a, [2.5, 8.5, 7.25], [3.5, 12, 8.75], "gold"), box(a, [3.5, 11, 7.25], [4.5, 12, 8.75], "gold"),
              box(a, [3.5, 8.5, 7.25], [4.5, 9.5, 8.75], "gold")):
        els += [e, mirror_x(e)]
    save_atlas("trophy", a)
    write_part("trophy", "taxsmp:item/fx/trophy", els)
    return els


# ================================================================ DEATH models

def hot_air_balloon():
    a = Atlas()

    def cloth(im, rnd):
        for y in range(16):
            for x in range(16):
                c = (230, 50, 60) if (x // 3) % 2 == 0 else (255, 210, 60)
                im.putpixel((x, y), shade(c, rnd.randint(-5, 5) - (12 if y > 12 else 0)))
    a.add("cloth", cloth)
    a.add("wicker", p_checker((150, 104, 54), (112, 74, 36), 2))
    a.add("rope", p_flat((210, 190, 150), 3))
    a.add("metal", p_flat((80, 80, 90), 4))
    els = [box(a, [2, 14, 2], [14, 26, 14], "cloth"), box(a, [1, 16, 1], [15, 24, 15], "cloth"),
           box(a, [3, 26, 3], [13, 28, 13], "cloth"), box(a, [5, 28, 5], [11, 29, 11], "cloth"),
           box(a, [4, 11, 4], [12, 14, 12], "cloth"), box(a, [6, 9, 6], [10, 11, 10], "cloth"),
           box(a, [5, -4, 5], [11, 1, 11], "wicker"), box(a, [7, 2, 7], [9, 4, 9], "metal")]
    for x, z in ((5.2, 5.2), (10.4, 5.2), (5.2, 10.4), (10.4, 10.4)):
        els.append(box(a, [x, 1, z], [x + 0.4, 10, z + 0.4], "rope"))
    save_atlas("airballoon", a)
    write_part("airballoon", "taxsmp:item/fx/airballoon", els)
    return els


def ufo():
    a = Atlas()
    a.add("hull", grad((230, 234, 242), (150, 156, 170), noise=3, lines=4, line_shade=-14))
    a.add("under", p_flat((90, 94, 106), 4))
    a.add("dome", glass_tile((120, 255, 140), 150))
    a.add("red", p_flat((255, 70, 80), 0))
    a.add("cyan", p_flat((80, 230, 255), 0))
    a.add("yellow", p_flat((255, 230, 80), 0))
    els = [box(a, [0, 6, 0], [16, 8, 16], {"top": "hull", "bottom": "under", "side": "hull"}),
           box(a, [-2, 6.5, 2], [18, 7.5, 14], "hull"), box(a, [2, 6.5, -2], [14, 7.5, 18], "hull"),
           box(a, [3, 4, 3], [13, 6, 13], "under"), box(a, [4.5, 8, 4.5], [11.5, 12, 11.5], "dome", 12),
           box(a, [6, 12, 6], [10, 13, 10], "dome", 12)]
    lights = ["red", "cyan", "yellow"]
    for i, (x, z) in enumerate(((-2.4, 7.5), (17.6, 7.5), (7.5, -2.4), (7.5, 17.6), (1, 1), (14.2, 1), (1, 14.2), (14.2, 14.2))):
        els.append(box(a, [x, 6.6, z], [x + 0.8, 7.4, z + 0.8], lights[i % 3], 15))
    save_atlas("ufo", a)
    write_part("ufo", "taxsmp:item/fx/ufo", els)
    return els


def dove():
    a = Atlas()
    a.add("white", grad((255, 255, 255), (220, 224, 232), noise=3))
    a.add("feather", feathers((252, 252, 255), (218, 222, 234)))
    a.add("beak", p_flat((250, 160, 60), 0))
    a.add("eye", p_flat((20, 20, 26), 0))
    body = [box(a, [6.5, 6.5, 4], [9.5, 9.5, 12], "white"), box(a, [6.75, 8.5, 11], [9.25, 11, 14], "white"),
            box(a, [7.6, 9, 14], [8.4, 9.6, 15], "beak"), box(a, [7, 7, 1], [9, 8, 4], "feather"),
            box(a, [6, 7.2, -1], [10, 7.8, 1], "feather")]
    eye = box(a, [9.25, 9.8, 12.6], [9.4, 10.4, 13.2], "eye")
    body += [eye, mirror_x(eye)]

    def wing():
        return [box(a, [8, 8.5, 5], [16, 9, 11], "feather"), box(a, [16, 8.5, 6], [19, 9, 10], "feather")]
    save_atlas("dove", a)
    parts = {"body": body, "wing_l": wing(), "wing_r": [mirror_x(e) for e in wing()]}
    for name, els in parts.items():
        write_part(f"dove/{name}", "taxsmp:item/fx/dove", els)
    return parts


def hourglass():
    a = Atlas()
    a.add("gold", GOLD)
    a.add("glass", glass_tile((200, 230, 255), 70, clear=True))
    a.add("sand", p_flat((236, 206, 130), 10))
    frame = [box(a, [3, 22, 3], [13, 23.5, 13], "gold"), box(a, [3, 0, 3], [13, 1.5, 13], "gold")]
    for x, z in ((3.5, 3.5), (11.5, 3.5), (3.5, 11.5), (11.5, 11.5)):
        frame.append(box(a, [x, 1.5, z], [x + 1, 22, z + 1], "gold"))
    glass = [box(a, [5, 13, 5], [11, 21, 11], "glass"), box(a, [7, 11, 7], [9, 13, 9], "glass"), box(a, [5, 2, 5], [11, 10, 11], "glass")]
    sand = [box(a, [5.6, 13, 5.6], [10.4, 15.5, 10.4], "sand"), box(a, [5.6, 2, 5.6], [10.4, 6.5, 10.4], "sand"),
            box(a, [7.6, 10, 7.6], [8.4, 13, 8.4], "sand")]
    save_atlas("hourglass", a)
    # ONE model (frame + sand + glass), moved down 3.75 units so its pivot is its middle: it spins and flips smoothly
    allp = []
    for e in frame + sand + glass:
        allp.append(dict(e, **{"from": [e["from"][0], e["from"][1] - 3.75, e["from"][2]],
                               "to": [e["to"][0], e["to"][1] - 3.75, e["to"][2]]}))
    write_part("hourglass/all", "taxsmp:item/fx/hourglass", allp)
    return {"all": allp}


def gift():
    a = Atlas()
    a.add("paper", grad((236, 56, 70), (180, 30, 46), noise=4))
    a.add("ribbon", GOLD)
    box_ = [box(a, [2, 0, 2], [14, 10, 14], "paper"), box(a, [7, 0, 1.9], [9, 10, 14.1], "ribbon"),
            box(a, [1.9, 0, 7], [14.1, 10, 9], "ribbon")]
    lid = [box(a, [1.5, 10, 1.5], [14.5, 13, 14.5], "paper"), box(a, [7, 10, 1.4], [9, 13, 14.6], "ribbon"),
           box(a, [1.4, 10, 7], [14.6, 13, 9], "ribbon"), box(a, [4.5, 13, 7], [7.5, 15.5, 9], "ribbon", 6),
           box(a, [8.5, 13, 7], [11.5, 15.5, 9], "ribbon", 6), box(a, [7, 13, 7], [9, 14.5, 9], "ribbon", 6)]
    save_atlas("gift", a)
    write_part("gift/box", "taxsmp:item/fx/gift", box_)
    write_part("gift/lid", "taxsmp:item/fx/gift", lid)
    return box_, lid


JELLY = {"pink": (255, 120, 200), "blue": (100, 190, 255), "purple": (190, 120, 255)}


def jellyfish():
    els = None
    for name, rgb in JELLY.items():
        a = Atlas()
        a.add("bell", glass_tile(rgb, 150))
        a.add("core", p_flat(tuple(min(255, c + 60) for c in rgb), 4))
        a.add("tent", p_flat(tuple(min(255, c + 30) for c in rgb), 6))
        els = [box(a, [3, 10, 3], [13, 16, 13], "bell", 13), box(a, [4, 16, 4], [12, 18, 12], "bell", 13),
               box(a, [5, 18, 5], [11, 19, 11], "bell", 13), box(a, [5, 11, 5], [11, 15, 11], "core", 15)]
        for x, z, ln in ((4.5, 4.5, 7), (11, 4.5, 9), (4.5, 11, 8), (11, 11, 6), (7.75, 4, 10), (7.75, 11.5, 8),
                         (4, 7.75, 7), (11.5, 7.75, 9)):
            els.append(box(a, [x, 10 - ln, z], [x + 0.5, 10, z + 0.5], "tent", 15))
        save_atlas(f"jelly_{name}", a)
        write_part(f"jelly_{name}", f"taxsmp:item/fx/jelly_{name}", els)
    return els


def paper_plane():
    a = Atlas()
    a.add("paper", p_flat((250, 250, 246), 3))
    a.add("fold", p_flat((214, 218, 226), 3))
    els = [box(a, [7.5, 6, 0], [8.5, 8, 16], "fold")]
    for e in (box(a, [8.5, 7.5, 2], [11, 8, 14], "paper"), box(a, [11, 7.5, 2], [13, 8, 10], "paper"),
              box(a, [13, 7.5, 2], [14.5, 8, 6], "paper")):
        els += [e, mirror_x(e)]
    save_atlas("plane", a)
    write_part("plane", "taxsmp:item/fx/plane", els)
    return els


# ================================================================ sprites

def draw(size, fn):
    im = Image.new("RGBA", (size * 4, size * 4), (0, 0, 0, 0))
    fn(ImageDraw.Draw(im), size * 4)
    im = im.convert("RGBa").resize((size, size), Image.LANCZOS).convert("RGBA")
    a = np.asarray(im).copy()
    a[..., 3] = np.where(a[..., 3] >= 90, 255, 0)
    return Image.fromarray(a, "RGBA")


def sprites2():
    out = {}

    def bill(d, s):
        d.rectangle([4, 16, 60, 48], fill=(70, 160, 80, 255), outline=(30, 90, 40, 255), width=4)
        d.ellipse([22, 22, 42, 42], fill=(140, 210, 140, 255), outline=(30, 90, 40, 255), width=3)
        d.line([(32, 24), (32, 40)], fill=(30, 90, 40, 255), width=4)
    out["bill"] = draw(16, bill)

    def slash(d, s):
        d.arc([4, 4, s - 4, s - 4], 200, 340, fill=(200, 250, 255, 255), width=14)
        d.arc([10, 10, s - 10, s - 10], 210, 330, fill=(255, 255, 255, 255), width=6)
    out["slash"] = draw(32, slash)

    def shuriken(d, s):
        c = s / 2
        for k in range(4):
            ang = k * math.pi / 2
            pts = [(c + 28 * math.cos(ang), c + 28 * math.sin(ang)), (c + 8 * math.cos(ang + 0.8), c + 8 * math.sin(ang + 0.8)),
                   (c + 8 * math.cos(ang - 0.8), c + 8 * math.sin(ang - 0.8))]
            d.polygon(pts, fill=(200, 206, 218, 255), outline=(90, 94, 110, 255))
        d.ellipse([c - 5, c - 5, c + 5, c + 5], fill=(40, 40, 50, 255))
    out["shuriken"] = draw(16, shuriken)

    def ball(d, s):
        d.ellipse([6, 6, s - 6, s - 6], fill=(36, 36, 44, 255))
        d.ellipse([16, 14, 28, 26], fill=(110, 110, 126, 255))
    out["cannonball"] = draw(16, ball)

    rb = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(rb)
    cols = [(255, 60, 60), (255, 150, 40), (255, 230, 60), (80, 220, 90), (60, 160, 255), (110, 90, 230), (190, 90, 230)]
    for i, c in enumerate(cols):
        m = 8 + i * 14
        d.arc([m, m, 256 - m, 512 - m], 180, 360, fill=c + (255,), width=15)
    for cx in (26, 230):
        for dx, dy, r in ((0, 0, 20), (-16, 6, 14), (16, 6, 14)):
            d.ellipse([cx + dx - r, 118 + dy - r, cx + dx + r, 118 + dy + r], fill=(255, 255, 255, 255))
    rb = rb.crop((0, 0, 256, 140)).resize((64, 35), Image.LANCZOS)
    c64 = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    c64.alpha_composite(rb, (0, 14))
    arr = np.asarray(c64).copy()
    arr[..., 3] = np.where(arr[..., 3] >= 90, 255, 0)
    out["rainbow"] = Image.fromarray(arr, "RGBA")

    def runes(d, s):
        c = s / 2
        for r, w, col in ((s * 0.47, 8, (150, 90, 255)), (s * 0.40, 4, (90, 220, 255)), (s * 0.22, 6, (150, 90, 255))):
            d.ellipse([c - r, c - r, c + r, c + r], outline=col + (255,), width=w)
        for k in range(6):
            a1, a2 = k * math.pi / 3, k * math.pi / 3 + 2 * math.pi / 3
            d.line([(c + s * 0.40 * math.cos(a1), c + s * 0.40 * math.sin(a1)), (c + s * 0.40 * math.cos(a2), c + s * 0.40 * math.sin(a2))],
                   fill=(90, 220, 255, 255), width=4)
        rnd = random.Random(3)
        for k in range(16):
            ang = k * math.tau / 16
            x, y = c + s * 0.435 * math.cos(ang), c + s * 0.435 * math.sin(ang)
            d.rectangle([x - 5, y - 5, x + 5, y + 5], outline=(220, 200, 255, 255), width=3)
            if rnd.random() < 0.5:
                d.line([(x - 4, y), (x + 4, y)], fill=(220, 200, 255, 255), width=3)
    out["runes"] = draw(64, runes)

    def star(d, s):
        c = s / 2
        pts = []
        for k in range(10):
            r = s * 0.45 if k % 2 == 0 else s * 0.2
            ang = -math.pi / 2 + k * math.pi / 5
            pts.append((c + r * math.cos(ang), c + r * math.sin(ang)))
        d.polygon(pts, fill=(255, 236, 110, 255), outline=(255, 255, 220, 255))
    out["star"] = draw(32, star)

    for name, col in (("wing_blue", (70, 160, 255)), ("wing_orange", (255, 150, 40)), ("wing_pink", (255, 120, 200)),
                      ("wing_yellow", (255, 220, 70))):
        def wing(d, s, col=col):
            d.ellipse([8, 4, 60, 40], fill=col + (255,), outline=(30, 30, 40, 255), width=3)
            d.ellipse([10, 30, 46, 60], fill=col + (255,), outline=(30, 30, 40, 255), width=3)
            d.ellipse([30, 14, 42, 26], fill=(255, 255, 255, 255))
        out[name] = draw(16, wing)

    def firefly(d, s):
        d.ellipse([16, 16, s - 16, s - 16], fill=(230, 255, 120, 255))
        d.ellipse([24, 24, s - 24, s - 24], fill=(255, 255, 220, 255))
    out["firefly"] = draw(16, firefly)

    def aura(col, col2):
        def fn(d, s):
            c = s / 2
            d.ellipse([4, 4, s - 4, s - 4], outline=col + (255,), width=10)
            d.ellipse([22, 22, s - 22, s - 22], outline=col2 + (255,), width=5)
            for k in range(8):
                ang = k * math.pi / 4
                x, y = c + s * 0.38 * math.cos(ang), c + s * 0.38 * math.sin(ang)
                d.polygon([(x, y - 10), (x + 6, y), (x, y + 10), (x - 6, y)], fill=col2 + (255,))
        return fn
    out["aura_gold"] = draw(64, aura((255, 200, 60), (255, 245, 170)))
    out["aura_red"] = draw(64, aura((255, 50, 70), (255, 170, 90)))
    for name, im in out.items():
        sprite(name, im)
    return out


# ================================================================ icons

def icons2(m, sp):
    T = lambda name: {"t": f"taxsmp:item/fx/{name}"}
    out = {}

    img = canvas()
    for ang, xy in ((-25, (0, 2)), (20, (14, 0)), (-10, (6, 12)), (30, (16, 16))):
        b = sp["bill"].rotate(ang, resample=Image.NEAREST, expand=True)
        img.alpha_composite(b.crop((0, 0, min(16, b.width), min(16, b.height))), xy)
    out["money_rain"] = finish_icon(img, 32)

    sc = Scene()
    sc.add([cube("minecraft:block/anvil", (0, 0, 2), (16, 10, 14), top="minecraft:block/anvil_top")], {}, rot_y(0.5), (0, 0, 0), 1.0)
    anv = render(sc, 24, yaw=-30, pitch=25)

    def lines(d, im):
        for x in (30, 64, 98):
            d.line([(x, 0), (x, 40)], fill=(255, 255, 255, 255), width=8)
    out["anvil_drop"] = paste(big(lines), anv, (4, 8))

    sc = Scene()
    sc.add(m["shark"], T("shark"), rot_x(-0.6), (0, 0, 0), 1.0)
    sh = render(sc, 28, yaw=-60, pitch=10)

    def splash(d, im):
        d.ellipse([10, 100, 118, 124], fill=(90, 170, 255, 255))
        for x, y in ((20, 80), (40, 70), (90, 72), (108, 84)):
            d.ellipse([x - 7, y - 7, x + 7, y + 7], fill=(150, 210, 255, 255))
    out["shark_attack"] = paste(big(splash), sh, (2, 0))

    sc = Scene()
    sc.add(m["cannon"], T("cannon"), np.eye(3), (0, 0, 0), 1.0)
    can = render(sc, 28, yaw=-50, pitch=20)

    def puff(d, im):
        for (cx, cy, r) in ((104, 30, 18), (116, 52, 12), (88, 14, 12)):
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(214, 214, 220, 255))
    out["pirate_cannon"] = paste(big(puff), can, (0, 4))

    img = canvas()
    paste(img, sp["slash"], (0, 0), 32)
    sc = Scene()
    sc.add(m["katana"], T("katana"), rot_z(-0.8), (0, 0, 0), 1.0)
    out["samurai_slash"] = paste(img, render(sc, 30, yaw=0, pitch=0), (1, 1))

    img = canvas()
    for xy in ((0, 0), (16, 4), (6, 16)):
        img.alpha_composite(sp["shuriken"], xy)
    out["shuriken_storm"] = finish_icon(img, 32)

    def rays(d, im):
        for k, col in enumerate(((255, 80, 90), (90, 220, 255), (255, 230, 60), (120, 255, 120), (200, 110, 255))):
            ang = math.pi * (0.15 + k * 0.17)
            d.line([(64, 50), (64 + 70 * math.cos(ang), 50 + 70 * math.sin(ang))], fill=col + (255,), width=8)
    sc = Scene()
    sc.add(m["disco"], T("disco"), np.eye(3), (0, 0, 0), 1.0)
    out["disco_party"] = paste(big(rays), render(sc, 22, yaw=-20, pitch=15), (5, 0))

    img = canvas()
    paste(img, sp["rainbow"], (0, -4), 32)
    sc = Scene()
    sc.add(m["pot"], T("pot"), np.eye(3), (0, 0, 0), 1.0)
    out["pot_of_gold"] = paste(img, render(sc, 18, yaw=-30, pitch=25), (14, 14))

    img = canvas()
    for xy in ((0, 12), (16, 12), (8, 2)):
        img.alpha_composite(vanilla("block/amethyst_cluster"), xy)
    out["crystal_burst"] = finish_icon(img, 32)

    out["magic_circle"] = finish_icon(sp["runes"].convert("RGBa").resize((32, 32), Image.LANCZOS).convert("RGBA"), 32)

    img = canvas()
    cols = [(255, 40, 200), (60, 255, 120), (40, 220, 255), (255, 255, 255), (20, 20, 26)]
    rnd = random.Random(9)
    for y in range(4, 28, 4):
        for x in range(4, 28, 4):
            if rnd.random() < 0.55:
                c = cols[rnd.randrange(len(cols))]
                for dy in range(3):
                    for dx in range(3):
                        img.putpixel((x + dx, y + dy), c + (255,))
    out["pixel_glitch"] = finish_icon(img, 32)

    sc = Scene()
    sc.add(m["trophy"], T("trophy"), np.eye(3), (0, 0, 0), 1.0)
    out["champion_trophy"] = render(sc, 32, yaw=-20, pitch=15)

    sc = Scene()
    sc.add(m["airballoon"], T("airballoon"), np.eye(3), (0, 0, 0), 1.0)
    out["hot_air_balloon"] = render(sc, 32, yaw=-25, pitch=12)

    def beam(d, im):
        d.polygon([(44, 50), (84, 50), (112, 128), (16, 128)], fill=(140, 255, 150, 255))
    sc = Scene()
    sc.add(m["ufo"], T("ufo"), np.eye(3), (0, 0, 0), 1.0)
    out["ufo_beam"] = paste(big(beam), render(sc, 28, yaw=-20, pitch=25), (2, 0))

    sc = Scene()
    for (x, y, z, r) in ((-0.4, 0, 0, 0.4), (0.45, 0.5, -0.2, -0.3)):
        sc.add(m["dove"]["body"], T("dove"), rot_y(r), (x, y, z), 1.0)
        for side, sign in (("l", 1), ("r", -1)):
            sc.add(m["dove"][f"wing_{side}"], T("dove"), rot_y(r) @ rot_z(sign * 0.7), (x, y, z), 1.0)
    out["white_doves"] = render(sc, 32, yaw=-20, pitch=15)

    sc = Scene()
    sc.add(m["hourglass"]["all"], T("hourglass"), np.eye(3), (0, 0, 0), 1.0)
    out["hourglass"] = render(sc, 32, yaw=-25, pitch=15)

    sc = Scene()
    bx, lid = m["gift"]
    sc.add(bx, T("gift"), np.eye(3), (0, 0, 0), 1.0)
    sc.add(lid, T("gift"), rot_z(0.5), (0.15, 0.35, 0), 1.0)
    out["gift_box"] = render(sc, 32, yaw=-30, pitch=25)

    sc = Scene()
    sc.add([cube("minecraft:block/snow", (2, 0, 2), (14, 12, 14)), cube("minecraft:block/snow", (3.5, 12, 3.5), (12.5, 21, 12.5)),
            cube("minecraft:block/pumpkin_side", (4.5, 21, 4.5), (11.5, 28, 11.5), top="minecraft:block/pumpkin_top")],
           {}, np.eye(3), (0, 0, 0), 1.0)
    sc.add([{"from": [4.5, 21, 11.5], "to": [11.5, 28, 11.6],
             "faces": {"south": {"uv": [0, 0, 16, 16], "texture": "minecraft:block/carved_pumpkin"}}}], {}, np.eye(3), (0, 0, 0), 1.0)
    out["melting_snowman"] = render(sc, 32, yaw=-25, pitch=15)

    def trail(d, im):
        for k, w in enumerate((26, 16, 8)):
            col = ((255, 220, 90), (255, 245, 170), (255, 255, 255))[k]
            d.line([(0, 128), (70, 58)], fill=col + (255,), width=w)
    out["shooting_star"] = paste(big(trail), sp["star"], (8, 0), 24)

    img = canvas()
    for name, xy, flip in (("wing_blue", (0, 2), False), ("wing_blue", (10, 2), True), ("wing_orange", (14, 16), False),
                           ("wing_orange", (24, 16), True)):
        w = sp[name].resize((10, 10), Image.NEAREST)
        if flip:
            w = w.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        img.alpha_composite(w, xy)
    out["butterflies"] = finish_icon(img, 32)

    img = canvas()
    for (x, y, s) in ((4, 6, 10), (18, 2, 8), (14, 18, 12), (2, 22, 6), (24, 14, 6)):
        img.alpha_composite(sp["firefly"].convert("RGBa").resize((s, s), Image.LANCZOS).convert("RGBA"), (x, y))
    arr = np.asarray(img).copy()
    arr[..., 3] = np.where(arr[..., 3] >= 90, 255, 0)
    out["fireflies"] = finish_icon(Image.fromarray(arr, "RGBA"), 32)

    sc = Scene()
    py = [cube("minecraft:block/iron_block", (x, 0, z), (x + 8, 6, z + 8)) for x in (0, 8, 16) for z in (0, 8, 16)]
    py.append(cube("minecraft:block/beacon", (8, 6, 8), (16, 14, 16)))
    sc.add(py, {}, np.eye(3), (0, 0, 0), 1.0)
    bc = render(sc, 24, yaw=-30, pitch=25)

    def bbeam(d, im):
        d.rectangle([54, 0, 74, 70], fill=(170, 240, 255, 255))
        d.rectangle([60, 0, 68, 70], fill=(255, 255, 255, 255))
    out["beacon_beam"] = paste(big(bbeam), bc, (4, 8))

    sc = Scene()
    sc.add(m["jelly"], T("jelly_pink"), np.eye(3), (-0.35, 0, 0), 1.0)
    sc.add(m["jelly"], T("jelly_blue"), np.eye(3), (0.4, 0.5, -0.2), 0.8)
    out["jellyfish"] = render(sc, 32, yaw=-20, pitch=12)

    sc = Scene()
    sc.add(m["plane"], T("plane"), rot_y(-0.7) @ rot_x(-0.2), (-0.2, 0, 0), 1.0)
    sc.add(m["plane"], T("plane"), rot_y(-0.5) @ rot_x(-0.3), (0.5, 0.5, -0.3), 0.7)
    out["paper_planes"] = render(sc, 32, yaw=-10, pitch=40)
    return out


def build2():
    models = {"shark": shark(), "cannon": cannon(), "katana": katana(), "disco": disco_ball(), "pot": pot_of_gold(),
              "trophy": trophy(), "airballoon": hot_air_balloon(), "ufo": ufo(), "dove": dove(), "hourglass": hourglass(),
              "gift": gift(), "jelly": jellyfish(), "plane": paper_plane()}
    sp = sprites2()
    out = {}
    if fx.JAR is None:
        return out
    for name, icon in icons2(models, sp).items():
        out.update(icon_variants(name, icon))
    return out
