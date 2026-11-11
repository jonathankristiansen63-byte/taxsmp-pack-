"""The other 22 kill & death effects: their 3D models, 2D sprites and menu icons (called by effects_assets.py).

Same rules as effects_assets.py: models are designed in the logical frame (forward +Z, up +Y, own left +X, pivot at
model 8,8,8) and written turned 180 degrees for item displays. Effects that use vanilla blocks and items (ice, leaves,
swords, flowers, glass, fireworks) only need an icon here; icons of those draw vanilla textures from a client jar.
"""
import math
import random

import numpy as np
from PIL import Image, ImageDraw

import effects_assets as fx
from effects_assets import (Atlas, Scene, box, finish_icon, icon_variants, item_def, mirror_x, rot_x, rot_y, rot_z,
                            shade, write_json, write_part, p_flat, p_scales, MODELS, TEX)


# ------------------------------------------------------------------ painters

def grad(top, bottom, noise=5, lines=0, line_shade=-18):
    """Vertical colour gradient; `lines` > 0 draws a darker line every that many pixels."""
    def paint(im, rnd):
        for y in range(16):
            t = y / 15
            c = tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3)) + (255,)
            for x in range(16):
                d = line_shade if lines and x % lines == lines - 1 else 0
                im.putpixel((x, y), shade(c, d + rnd.randint(-noise, noise)))
    return paint


def stripes(base, line, every=4, noise=5, diagonal=False):
    def paint(im, rnd):
        for y in range(16):
            for x in range(16):
                k = (x + y) if diagonal else y
                c = line if k % every == every - 1 else base
                im.putpixel((x, y), shade(c, rnd.randint(-noise, noise)))
    return paint


def feathers(base, shaft, every=3):
    def paint(im, rnd):
        for y in range(16):
            for x in range(16):
                c = shaft if x % every == 0 else base
                im.putpixel((x, y), shade(c, rnd.randint(-4, 4) - (10 if y > 12 else 0)))
    return paint


def art(rows, palette):
    """Pixel art from strings, top-left aligned on the tile; '.' stays transparent."""
    def paint(im, rnd):
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch != ".":
                    im.putpixel((x, y), palette[ch])
    return paint


GOLD = grad((255, 222, 110), (196, 132, 30), noise=4)


def sprite(name, image):
    """A flat 2D item (item/generated): used for coins, the halo, bubbles and the black hole."""
    TEX.mkdir(parents=True, exist_ok=True)
    image.save(TEX / f"{name}.png")
    write_json(MODELS / f"{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"taxsmp:item/fx/{name}"}})
    item_def(name)


def save_atlas(name, atlas):
    TEX.mkdir(parents=True, exist_ok=True)
    atlas.img.save(TEX / f"{name}.png")


# ------------------------------------------------------------------ 3D models

def hammer():
    """Thunder God: a war hammer, handle up +Y, pivot near the pommel (model y 8)."""
    a = Atlas()
    a.add("steel", grad((214, 220, 232), (120, 128, 142), noise=6, lines=5, line_shade=-14))
    a.add("gold", GOLD)
    a.add("leather", stripes((104, 62, 34), (70, 40, 22), every=3, diagonal=True))
    a.add("bolt", art(["......YY..", ".....YYC..", "....YYC...", "...YYYYYY.", "......YYC.", ".....YYC.."],
                      {"Y": (255, 236, 90, 255), "C": (120, 230, 255, 255)}))
    els = [
        box(a, [6.5, 5.5, 6.5], [9.5, 8, 9.5], "gold"),
        box(a, [7, 8, 7], [9, 22, 9], "leather"),
        box(a, [6.75, 13, 6.75], [9.25, 14, 9.25], "gold"),
        box(a, [6.75, 21, 6.75], [9.25, 22.5, 9.25], "gold"),
        box(a, [6.5, 22.5, 6.5], [9.5, 24, 9.5], "steel"),
        box(a, [1.5, 24, 4.5], [14.5, 31.5, 11.5], "steel"),
        box(a, [0.75, 23.5, 4], [2.25, 32, 12], "gold"),
        box(a, [13.75, 23.5, 4], [15.25, 32, 12], "gold"),
        box(a, [3, 25, 11.5], [13, 31, 11.6], {"south": "bolt"}, light=15),
        box(a, [3, 25, 4.4], [13, 31, 4.5], {"north": "bolt"}, light=15),
    ]
    a.add("rune", p_flat((120, 230, 255), 0))
    els += [box(a, [6.9, 16, 6.9], [9.1, 16.6, 9.1], "rune", 15), box(a, [6.9, 18.5, 6.9], [9.1, 19.1, 9.1], "rune", 15)]
    save_atlas("hammer", a)
    write_part("hammer", "taxsmp:item/fx/hammer", els)
    return els


def crown():
    a = Atlas()
    a.add("gold", GOLD)
    a.add("velvet", p_flat((150, 20, 40), 6))
    a.add("ruby", p_flat((235, 40, 70), 6))
    a.add("sapphire", p_flat((50, 110, 240), 6))
    a.add("emerald", p_flat((40, 200, 100), 6))
    a.add("pearl", p_flat((248, 244, 236), 3))
    els = [box(a, [3, 4, 3], [13, 8, 4], "gold"), box(a, [3, 4, 12], [13, 8, 13], "gold"),
           box(a, [3, 4, 4], [4, 8, 12], "gold"), box(a, [12, 4, 4], [13, 8, 12], "gold"),
           box(a, [3.5, 4.5, 3.5], [12.5, 9, 12.5], "velvet"), box(a, [4.5, 9, 4.5], [11.5, 10, 11.5], "velvet"),
           box(a, [7.25, 10, 7.25], [8.75, 11.5, 8.75], "gold")]
    for x, z in ((3, 3), (12, 3), (3, 12), (12, 12)):  # corner points
        els.append(box(a, [x, 8, z], [x + 1, 10.5, z + 1], "gold"))
        els.append(box(a, [x + 0.25, 10.5, z + 0.25], [x + 0.75, 11, z + 0.75], "pearl"))
    for (x0, z0, x1, z1) in ((7, 3, 9, 4), (7, 12, 9, 13), (3, 7, 4, 9), (12, 7, 13, 9)):  # middle points
        els.append(box(a, [x0, 8, z0], [x1, 10, z1], "gold"))
        els.append(box(a, [x0 + 0.5, 10, z0], [x1 - 0.5, 12, z1], "gold") if x1 - x0 > 1 else
                   box(a, [x0, 10, z0 + 0.5], [x1, 12, z1 - 0.5], "gold"))
        els.append(box(a, [x0 + 0.25, 12, z0 + 0.25], [x1 - 0.25, 12.6, z1 - 0.25], "pearl"))
    els += [box(a, [6.75, 5, 12.9], [9.25, 7, 13.4], "ruby", light=8), box(a, [6.75, 5, 2.6], [9.25, 7, 3.1], "sapphire", light=8),
            box(a, [2.6, 5, 6.75], [3.1, 7, 9.25], "emerald", light=8), box(a, [12.9, 5, 6.75], [13.4, 7, 9.25], "emerald", light=8)]
    a.add("trim", p_flat((176, 112, 24), 4))
    for lo, hi in (([3, 4, 13], [13, 4.6, 13.15]), ([3, 7.4, 13], [13, 8, 13.15]), ([3, 4, 2.85], [13, 4.6, 3]), ([3, 7.4, 2.85], [13, 8, 3]),
                   ([2.85, 4, 3], [3, 4.6, 13]), ([2.85, 7.4, 3], [3, 8, 13]), ([13, 4, 3], [13.15, 4.6, 13]), ([13, 7.4, 3], [13.15, 8, 13])):
        els.append(box(a, lo, hi, "trim"))
    save_atlas("crown", a)
    write_part("crown", "taxsmp:item/fx/crown", els)
    return els


def phoenix():
    a = Atlas()
    a.add("red", grad((255, 120, 40), (200, 30, 20), noise=8))
    a.add("orange", p_scales((250, 140, 30), (210, 80, 20), (255, 200, 80)))
    a.add("yellow", grad((255, 245, 150), (255, 170, 40), noise=6))
    a.add("gold", GOLD)
    a.add("eye", p_flat((255, 255, 230), 0))
    a.add("flame", grad((255, 230, 110), (220, 40, 20), noise=8, lines=3, line_shade=-24))
    L = 15
    body = [
        box(a, [6, 6, 1], [10, 10.5, 11], "orange", L),
        box(a, [5.75, 5.5, 7], [10.25, 10, 11.5], "yellow", L),
        box(a, [6.75, 9, 10], [9.25, 12, 13], "orange", L),
        box(a, [6.5, 10.5, 12], [9.5, 13.5, 15.5], "red", L),
        box(a, [7.25, 11, 15.5], [8.75, 12, 17.5], "gold", L),
        box(a, [7.5, 10.5, 17], [8.5, 11.25, 18], "gold", L),
        box(a, [9.5, 12, 13.5], [9.75, 12.75, 14.5], "eye", L), box(a, [6.25, 12, 13.5], [6.5, 12.75, 14.5], "eye", L),
        box(a, [7.5, 13.5, 12], [8.5, 15, 14.5], "yellow", L),
        box(a, [7.5, 14.5, 10.5], [8.5, 16.5, 12.5], "orange", L),
        box(a, [7.5, 15.5, 9], [8.5, 17, 11], "red", L),
        box(a, [7, 7.5, -14], [9, 8.5, 1], "flame", L),
        box(a, [9, 7.25, -12], [10.5, 8.25, 1], "flame", L), box(a, [5.5, 7.25, -12], [7, 8.25, 1], "flame", L),
        box(a, [5, 7.5, -16], [11, 8.25, -12], "flame", L),
        box(a, [6.5, 4.5, 4], [7.5, 6, 6], "gold", L), box(a, [8.5, 4.5, 4], [9.5, 6, 6], "gold", L),
    ]

    def wing():
        els = [box(a, [8, 8, 6], [22, 9.25, 8.5], "orange", L), box(a, [8, 8.25, 1], [20, 9, 6], "yellow", L)]
        for x0, x1, z0 in ((8, 12, -4), (12, 16, -6), (16, 20, -8), (20, 24, -9), (24, 27, -6)):
            els.append(box(a, [x0, 8, z0], [x1, 8.75, 6], "flame", L))
        return els

    save_atlas("phoenix", a)
    parts = {"body": body, "wing_l": wing(), "wing_r": [mirror_x(e) for e in wing()]}
    for name, els in parts.items():
        write_part(f"phoenix/{name}", "taxsmp:item/fx/phoenix", els)
    return parts


ANGEL_TOP = [10, 12, 14, 15.5, 17, 18, 18.5, 18.5, 18, 16.5, 14]
ANGEL_BOTTOM = [5, 3, 1, -1, -3, -5, -6.5, -7.5, -7, -5, -1]


def angel():
    a = Atlas()
    a.add("feather", feathers((250, 250, 255), (220, 224, 240)))
    a.add("covert", p_scales((255, 252, 242), (232, 228, 214), (255, 255, 255)))
    a.add("edge", p_flat((255, 236, 170), 4))

    def wing():
        els = []
        for i, (top, bottom) in enumerate(zip(ANGEL_TOP, ANGEL_BOTTOM)):
            x0 = 8 + 2 * i
            els.append(box(a, [x0, bottom, 7.6], [x0 + 2, top, 8.4], "feather", 6))
            mid = (top + bottom) / 2 + 2
            els.append(box(a, [x0, mid, 8.4], [x0 + 2, top - 0.5, 9], "covert", 6))
            els.append(box(a, [x0, top - 1, 8.4], [x0 + 2, top, 9.2], "edge", 8))
        return els

    save_atlas("angel", a)
    parts = {"wing_l": wing(), "wing_r": [mirror_x(e) for e in wing()]}
    for name, els in parts.items():
        write_part(f"angel/{name}", "taxsmp:item/fx/angel", els)
    return parts


def ghost():
    a = Atlas()
    a.add("white", grad((255, 255, 255), (214, 226, 246), noise=3))
    a.add("eye", p_flat((30, 30, 44), 0))
    a.add("shine", p_flat((255, 255, 255), 0))
    a.add("blush", p_flat((255, 150, 180), 0))
    a.add("mouth", p_flat((90, 50, 70), 0))
    L = 10
    els = [box(a, [3.5, 3, 4], [12.5, 13, 12], "white", L),
           box(a, [4, 13, 4.5], [12, 14.5, 11.5], "white", L), box(a, [5, 14.5, 5.5], [11, 15.5, 10.5], "white", L),
           box(a, [6, 15.5, 6.5], [10, 16, 9.5], "white", L),
           box(a, [3.5, 1, 4], [5.5, 3, 12], "white", L), box(a, [7, 0.5, 4], [9, 3, 12], "white", L),
           box(a, [10.5, 1, 4], [12.5, 3, 12], "white", L),
           box(a, [1.5, 7, 7], [3.5, 9, 9.5], "white", L), box(a, [12.5, 7, 7], [14.5, 9, 9.5], "white", L),
           box(a, [9, 9, 12], [10.5, 11.5, 12.1], {"south": "eye"}, 4), box(a, [5.5, 9, 12], [7, 11.5, 12.1], {"south": "eye"}, 4),
           box(a, [9.5, 10.5, 12.1], [10, 11, 12.15], {"south": "shine"}, 15), box(a, [6, 10.5, 12.1], [6.5, 11, 12.15], {"south": "shine"}, 15),
           box(a, [10.75, 8, 12], [12, 8.75, 12.08], {"south": "blush"}, L), box(a, [4, 8, 12], [5.25, 8.75, 12.08], {"south": "blush"}, L),
           box(a, [7.25, 7.25, 12], [8.75, 7.85, 12.08], {"south": "mouth"}, 4)]
    save_atlas("ghost", a)
    write_part("ghost", "taxsmp:item/fx/ghost", els)
    return els


BALLOONS = {"red": (230, 52, 64), "blue": (60, 124, 232), "yellow": (250, 206, 56), "green": (70, 196, 90),
            "pink": (240, 120, 196)}


def balloons():
    els = None
    for color, rgb in BALLOONS.items():
        a = Atlas()
        a.add("skin", grad(shade(rgb, 40)[:3], shade(rgb, -36)[:3], noise=3))
        a.add("knot", p_flat(shade(rgb, -50)[:3], 3))
        a.add("string", p_flat((236, 236, 240), 2))
        a.add("shine", p_flat((255, 255, 255), 0))
        els = [box(a, [3.5, 7, 3.5], [12.5, 17, 12.5], "skin"), box(a, [4.5, 6, 4.5], [11.5, 7, 11.5], "skin"),
               box(a, [4.5, 17, 4.5], [11.5, 18, 11.5], "skin"), box(a, [5.5, 18, 5.5], [10.5, 18.5, 10.5], "skin"),
               box(a, [5.5, 5, 5.5], [10.5, 6, 10.5], "skin"), box(a, [7.25, 4, 7.25], [8.75, 5, 8.75], "knot"),
               box(a, [7.85, -12, 7.85], [8.15, 4, 8.15], "string"),
               box(a, [9.5, 13, 12.5], [10.5, 15.5, 12.6], {"south": "shine"}, 12)]
        save_atlas(f"balloon_{color}", a)
        write_part(f"balloon_{color}", f"taxsmp:item/fx/balloon_{color}", els)
    return els


def lantern():
    a = Atlas()
    a.add("paper", grad((255, 180, 80), (226, 70, 36), noise=4, lines=4, line_shade=-30))
    a.add("wood", p_flat((90, 30, 26), 5))
    a.add("gold", GOLD)
    els = [box(a, [3.5, 3, 3.5], [12.5, 13, 12.5], "paper", 15), box(a, [3, 4.5, 3], [13, 11.5, 13], "paper", 15),
           box(a, [5, 13, 5], [11, 14, 11], "wood", 8), box(a, [7, 14, 7], [9, 15, 9], "gold", 8),
           box(a, [5, 2, 5], [11, 3, 11], "wood", 8), box(a, [7.5, -1, 7.5], [8.5, 2, 8.5], "gold", 10)]
    for lo, hi in (([2.8, 4.5, 2.8], [3.4, 11.5, 3.4]), ([12.6, 4.5, 2.8], [13.2, 11.5, 3.4]), ([2.8, 4.5, 12.6], [3.4, 11.5, 13.2]),
                   ([12.6, 4.5, 12.6], [13.2, 11.5, 13.2]), ([2.9, 11.4, 2.9], [13.1, 11.8, 13.1]), ([2.9, 4.2, 2.9], [13.1, 4.6, 13.1])):
        els.append(box(a, lo, hi, "gold", 8))
    save_atlas("lantern", a)
    write_part("lantern", "taxsmp:item/fx/lantern", els)
    return els


def rocket():
    a = Atlas()
    a.add("white", grad((255, 255, 255), (200, 206, 216), noise=3, lines=6, line_shade=-16))
    a.add("red", grad((240, 70, 60), (170, 24, 30), noise=4))
    a.add("metal", p_flat((70, 74, 84), 5))
    a.add("window", art([".cc.", "cbwc", "cbbc", ".cc."],
                        {"c": (190, 196, 210, 255), "b": (70, 170, 255, 255), "w": (220, 245, 255, 255)}))
    els = [box(a, [5, 0, 5], [11, 17, 11], "white"), box(a, [4.9, 12, 4.9], [11.1, 13, 11.1], "red"),
           box(a, [5.5, 17, 5.5], [10.5, 19, 10.5], "red"), box(a, [6.5, 19, 6.5], [9.5, 21, 9.5], "red"),
           box(a, [7.25, 21, 7.25], [8.75, 22.5, 8.75], "red"),
           box(a, [6, 7, 11], [10, 11, 11.1], {"south": "window"}, 12),
           box(a, [6, -1.5, 6], [10, 0, 10], "metal")]
    for fin in (box(a, [2, 0, 7.25], [5, 6, 8.75], "red"), box(a, [3, 6, 7.25], [5, 8, 8.75], "red")):
        els += [fin, mirror_x(fin)]
    els += [box(a, [7.25, 0, 2], [8.75, 6, 5], "red"), box(a, [7.25, 6, 3], [8.75, 8, 5], "red"),
            box(a, [7.25, 0, 11], [8.75, 6, 14], "red"), box(a, [7.25, 6, 11], [8.75, 8, 13], "red")]
    els += [box(a, [4.9, 3, 4.9], [11.1, 4, 11.1], "red"), box(a, [6, 7, 4.9], [10, 11, 5], {"north": "window"}, 12)]
    save_atlas("rocket", a)
    write_part("rocket", "taxsmp:item/fx/rocket", els)
    return els


# Chest: the lid turns around its back hinge, which is at base model (8, 9, 3). Keep in sync with DeathFx.Treasure.
def chest():
    a = Atlas()
    a.add("wood", stripes((156, 102, 52), (112, 70, 34), every=4))
    a.add("gold", GOLD)
    a.add("coins", p_flat((255, 206, 70), 14))
    a.add("hole", p_flat((40, 26, 12), 0))
    base = [box(a, [1, 0, 3], [15, 9, 13], "wood"), box(a, [0.75, 8, 2.75], [15.25, 9, 13.25], "gold"),
            box(a, [0.75, 0, 2.75], [15.25, 1, 13.25], "gold"),
            box(a, [7, 5.5, 13], [9, 8.5, 13.75], "gold"), box(a, [7.75, 6.25, 13.75], [8.25, 7.5, 13.8], {"south": "hole"}),
            box(a, [2, 8.6, 4], [14, 9.2, 12], {"up": "coins"}, 12)]
    for x, z in ((0.75, 2.75), (13.75, 2.75), (0.75, 11.75), (13.75, 11.75)):
        base.append(box(a, [x, 0, z], [x + 1.5, 9, z + 1.5], "gold"))
    lid = [box(a, [1, 8, 8], [15, 11, 18], "wood"), box(a, [1, 11, 9], [15, 12.5, 17], "wood"),
           box(a, [1, 12.5, 10.5], [15, 13, 15.5], "wood"),
           box(a, [0.75, 8, 17.75], [15.25, 11.25, 18.25], "gold"), box(a, [0.75, 8, 7.75], [15.25, 11.25, 8.25], "gold"),
           box(a, [7, 8, 18], [9, 10, 18.75], "gold"), box(a, [7.25, 11, 8.5], [8.75, 13.25, 17.5], "gold")]
    for x in (2.5, 5, 11, 13.5):
        for y in (2, 6):
            base.append(box(a, [x - 0.4, y, 13], [x + 0.4, y + 0.8, 13.3], "gold"))
    save_atlas("chest", a)
    write_part("chest/base", "taxsmp:item/fx/chest", base)
    write_part("chest/lid", "taxsmp:item/fx/chest", lid)
    return base, lid


# ------------------------------------------------------------------ 2D sprites

def disc(size, fn):
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    c = (size - 1) / 2
    for y in range(size):
        for x in range(size):
            col = fn(x, y, math.hypot(x - c, y - c) / (size / 2))
            if col:
                im.putpixel((x, y), col)
    return im


def coin_image():
    rows = ["....dddddd....", "..ddgggggggd..", ".dghhhhhhhhggd.", ".dghggg$gggggd.", "dghgg$$$$gggggd",
            "dghg$g$gggggggd", "dghgg$$$ggggggd", "dghgggg$$$ggggd", "dghgggg$g$ggggd", "dghgg$$$$gggggd",
            ".dgggggg$ggggd.", ".dgggggggggggd.", "..ddgggggggdd..", "....dddddd....."]
    pal = {"d": (150, 96, 20, 255), "g": (246, 196, 60, 255), "h": (255, 236, 150, 255), "$": (186, 122, 24, 255)}
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row[:15]):
            if ch in pal:
                im.putpixel((x + 1, y + 1), pal[ch])
    return im


def halo_image():
    def fn(x, y, r):
        if 0.62 <= r <= 0.95:
            t = (math.sin((x + y) * 0.7) + 1) / 2
            return (255, int(214 + 30 * t), int(90 + 80 * t), 255)
        return None
    return disc(16, fn)


def bubble_image():
    def fn(x, y, r):
        if r > 0.98:
            return None
        if (x - 9) ** 2 + (y - 9) ** 2 < 7:
            return (255, 255, 255, 230)
        hue = math.atan2(y - 15.5, x - 15.5) / math.tau + 0.5
        rim = max(0.0, (r - 0.7) / 0.28)
        rgb = [int(200 + 55 * math.sin(math.tau * (hue + k / 3))) for k in range(3)]
        return (rgb[0], rgb[1], rgb[2], int(40 + 170 * rim))
    return disc(32, fn)


def void_core_image():
    def fn(x, y, r):
        if r > 0.98:
            return None
        rim = max(0.0, (r - 0.72) / 0.26)
        return (int(10 + 150 * rim), int(4 + 40 * rim), int(20 + 210 * rim), 255)
    return disc(32, fn)


def void_ring_image():
    def fn(x, y, r):
        if 0.55 <= r <= 0.98:
            t = (r - 0.55) / 0.43
            return (int(255 - 90 * t), int(170 - 140 * t), int(60 + 170 * t), int(255 - 120 * t))
        return None
    return disc(32, fn)


def sprites():
    out = {"coin": coin_image(), "halo": halo_image(), "bubble": bubble_image(), "void_core": void_core_image(),
           "void_ring": void_ring_image()}
    for name, im in out.items():
        sprite(name, im)
    return out


# ------------------------------------------------------------------ icons

def cube(tex, lo=(0, 0, 0), hi=(16, 16, 16), side=None, top=None, light=0):
    """A box with a texture ref on every face (vanilla or pack), for icon scenes."""
    faces = {}
    for f in ("north", "south", "east", "west", "up", "down"):
        ref = top if f in ("up", "down") and top else side if side else tex
        faces[f] = {"uv": [0, 0, 16, 16], "texture": ref}
    el = {"from": list(lo), "to": list(hi), "faces": faces}
    if light:
        el["light_emission"] = light
    return el


def render(scene, size=32, yaw=-30, pitch=25, margin=0.06, light=1.1):
    return finish_icon(scene.render(size * 8, yaw=yaw, pitch=pitch, margin=margin, light=light), size)


def canvas():
    return Image.new("RGBA", (32, 32), (0, 0, 0, 0))


def paste(dst, src, xy, size=None):
    if size:
        src = src.convert("RGBa").resize((size, size), Image.LANCZOS).convert("RGBA")
        a = np.asarray(src).copy()
        a[..., 3] = np.where(a[..., 3] >= 100, 255, 0)
        src = Image.fromarray(a, "RGBA")
    dst.alpha_composite(src, xy)
    return dst


def vanilla(path):
    return fx.load_image(f"minecraft:{path}")


def big(draw_fn):
    """Draw at 128 px with PIL and shrink to a 32 px icon with an outline."""
    im = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), im)
    return finish_icon(im, 32)


def bolt(d, pts, width, color):
    d.line(pts, fill=color, width=width, joint="curve")


def icons(models, sp):
    m = models
    T = lambda name: {"t": f"taxsmp:item/fx/{name}"}
    out = {}

    # --- kill effects
    sc = Scene()
    sc.add([cube("minecraft:block/magma")], {}, rot_y(0.6) @ rot_x(0.5), (0, 0, 0), 1.0)
    sc.add([cube("minecraft:block/blackstone", (4, 4, 4), (12, 12, 12))], {}, rot_y(0.2) @ rot_x(0.9), (0.45, 0.35, 0.1), 1.0)
    rock = render(sc, 22)

    def trail(d, im):
        for w, col in ((34, (255, 120, 30, 255)), (22, (255, 200, 60, 255)), (10, (255, 250, 200, 255))):
            d.line([(124, 4), (52, 76)], fill=col, width=w)
    out["meteor_strike"] = paste(big(trail), rock, (0, 10))

    sc = Scene()
    sc.add(m["hammer"], T("hammer"), rot_z(0.55), (0, 0, 0), 1.0)
    ham = render(sc, 28, yaw=-20, pitch=15)
    img = big(lambda d, im: bolt(d, [(30, 0), (8, 52), (34, 50), (10, 110)], 12, (255, 236, 80, 255)))
    out["thunder_god"] = paste(img, ham, (4, 2))

    img = canvas()
    for tex, xy in (("item/iron_sword", (0, 0)), ("item/diamond_sword", (14, 3)), ("item/golden_sword", (6, 14))):
        img.alpha_composite(vanilla(tex).transpose(Image.Transpose.ROTATE_270), xy)
    out["sword_rain"] = finish_icon(img, 32)

    img = canvas()
    for xy in ((8, 0), (0, 10), (16, 10), (8, 16)):
        img.alpha_composite(sp["coin"], xy)
    out["coin_fountain"] = finish_icon(img, 32)

    sc = Scene()
    ice = []
    for x, z in ((0, 0), (16, 0), (0, 16), (16, 16)):
        ice += [cube("minecraft:block/ice", (x, 0, z), (x + 16, 16, z + 16)),
                cube("minecraft:block/ice", (x, 16, z), (x + 16, 32, z + 16))]
    ice.append(cube("minecraft:block/packed_ice", (0, 32, 0), (32, 40, 32)))
    sc.add(ice, {}, np.eye(3), (0, 0, 0), 1.0)
    out["ice_prison"] = render(sc, 32, yaw=-35, pitch=22)

    def ring(d, im):
        d.ellipse([4, 44, 124, 90], outline=(255, 150, 60, 255), width=10)
        d.ellipse([16, 52, 112, 82], outline=(190, 70, 255, 255), width=6)
    img = big(ring)
    img.alpha_composite(sp["void_core"].convert("RGBa").resize((20, 20), Image.LANCZOS).convert("RGBA"), (6, 6))
    out["black_hole"] = finish_icon(img, 32)

    sc = Scene()
    sc.add(m["phoenix"]["body"], T("phoenix"), np.eye(3), (0, 0, 0), 1.0)
    for side, sign in (("l", 1), ("r", -1)):
        sc.add(m["phoenix"][f"wing_{side}"], T("phoenix"), rot_z(sign * math.radians(40)), (sign * 2 / 16, 2 / 16, 2 / 16), 1.0)
    out["phoenix_rising"] = render(sc, 32, yaw=200, pitch=35)

    def bursts(d, im):
        for (cx, cy, col) in ((84, 36, (255, 80, 90, 255)), (40, 30, (90, 200, 255, 255)), (96, 86, (255, 220, 60, 255))):
            for k in range(8):
                ang = k * math.pi / 4
                d.line([(cx + 8 * math.cos(ang), cy + 8 * math.sin(ang)), (cx + 26 * math.cos(ang), cy + 26 * math.sin(ang))],
                       fill=col, width=8)
    fw = big(bursts)
    fw.alpha_composite(vanilla("item/firework_rocket"), (0, 16))
    out["firework_show"] = finish_icon(fw, 32)

    sc = Scene()
    sc.add(m["crown"], T("crown"), np.eye(3), (0, 0, 0), 1.0)
    out["crown_eliminated"] = render(sc, 32, yaw=-30, pitch=28)

    sc = Scene()
    leaves = ["minecraft:block/cherry_leaves", "minecraft:block/oak_leaves@48b518", "minecraft:block/azalea_leaves"]
    for i in range(16):
        h, ang = i * 2.2, i * 1.1
        rad = 2 + h * 0.55
        sc.add([cube(leaves[i % 3], (0, 0, 0), (6, 6, 6))], {}, rot_y(ang),
               (rad * math.cos(ang) / 16, h / 16, rad * math.sin(ang) / 16), 1.0)
    out["leaf_tornado"] = render(sc, 32, yaw=0, pitch=15)

    def lightning(d, im):
        d.ellipse([6, 84, 122, 120], outline=(110, 220, 255, 255), width=10)
        bolt(d, [(76, 0), (50, 46), (74, 46), (54, 100)], 14, (255, 240, 100, 255))
    out["lightning_ring"] = big(lightning)

    # --- death effects
    sc = Scene()
    for side, sign in (("l", 1), ("r", -1)):
        sc.add(m["angel"][f"wing_{side}"], T("angel"), rot_y(sign * math.radians(25)), (sign * 1.5 / 16, 0, 0), 1.0)
    ring_els = []
    for k in range(12):
        ang = k * math.tau / 12
        cx, cz = 8 + 6 * math.sin(ang), 8 + 6 * math.cos(ang)
        ring_els.append(cube("minecraft:block/gold_block", (cx - 1.4, 23, cz - 1.4), (cx + 1.4, 24.6, cz + 1.4), light=15))
    sc.add(ring_els, {}, np.eye(3), (0, 0, 0), 1.0)
    out["angel_ascension"] = render(sc, 32, yaw=0, pitch=24)

    sc = Scene()
    sc.add(m["ghost"], T("ghost"), np.eye(3), (0, 0, 0), 1.0)
    out["ghost"] = render(sc, 32, yaw=-20, pitch=12)

    sc = Scene()
    for color, (x, y, z) in (("red", (-0.35, 0.1, 0)), ("blue", (0.35, 0.2, 0.1)), ("yellow", (0, 0.45, -0.2))):
        sc.add(m["balloon"], T(f"balloon_{color}"), rot_z(-x * 0.6), (x, y, z), 1.0)
    out["balloons"] = render(sc, 32, yaw=-15, pitch=10)

    sc = Scene()
    for (x, y) in ((-0.55, -0.2), (0.5, 0.15), (0, 0.6)):
        sc.add(m["lantern"], T("lantern"), np.eye(3), (x, y, 0), 0.8)
    out["paper_lanterns"] = render(sc, 32, yaw=-25, pitch=20)

    sc = Scene()
    sc.add(m["rocket"], T("rocket"), rot_z(-0.5), (0, 0, 0), 1.0)
    rk = render(sc, 26, yaw=-20, pitch=10)

    def smoke(d, im):
        for (cx, cy, r) in ((20, 108, 16), (44, 116, 12), (10, 86, 10)):
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(210, 212, 220, 255))
    out["rocket_launch"] = paste(big(smoke), rk, (6, 0))

    sc = Scene()
    tree = [cube("minecraft:block/cherry_log", (6, 0, 6), (10, 16, 10), top="minecraft:block/cherry_log_top")]
    for x in (0, 8, 16):
        for z in (0, 8, 16):
            tree.append(cube("minecraft:block/cherry_leaves", (x - 4, 14, z - 4), (x + 4, 22, z + 4)))
    tree.append(cube("minecraft:block/cherry_leaves", (2, 22, 2), (14, 28, 14)))
    sc.add(tree, {}, np.eye(3), (0, 0, 0), 1.0)
    out["cherry_tree"] = render(sc, 32, yaw=-30, pitch=20)

    def shards(d, im):
        rnd = random.Random(7)
        for i in range(7):
            ang = i * math.tau / 7 + 0.3
            cx, cy = 64 + 38 * math.cos(ang), 64 + 38 * math.sin(ang)
            pts = [(cx + rnd.uniform(14, 24) * math.cos(ang + k * 2.1 + rnd.uniform(-0.3, 0.3)),
                    cy + rnd.uniform(14, 24) * math.sin(ang + k * 2.1 + rnd.uniform(-0.3, 0.3))) for k in range(3)]
            d.polygon(pts, fill=(170, 225, 255, 255), outline=(255, 255, 255, 255), width=4)
        d.polygon([(56, 50), (74, 56), (66, 78), (52, 70)], fill=(210, 240, 255, 255), outline=(255, 255, 255, 255), width=4)
    out["glass_shatter"] = big(shards)

    img = canvas()
    paste(img, sp["bubble"], (0, 6), 22)
    paste(img, sp["bubble"], (18, 0), 14)
    paste(img, sp["bubble"], (20, 18), 12)
    out["bubble_pop"] = finish_icon(img, 32)

    img = canvas()
    for tex, xy in (("block/allium", (0, 4)), ("block/cornflower", (16, 4)), ("block/poppy", (8, 0)),
                    ("block/dandelion", (2, 15)), ("block/oxeye_daisy", (14, 15)), ("block/pink_tulip", (8, 10))):
        img.alpha_composite(vanilla(tex), xy)
    out["flower_burst"] = finish_icon(img, 32)

    img = canvas()
    img.alpha_composite(vanilla("item/soul_lantern").resize((24, 24), Image.NEAREST), (4, 6))
    for (x, y) in ((2, 4), (26, 8), (27, 22), (1, 20)):
        for dx, dy, col in ((0, 0, (120, 240, 255, 255)), (1, 0, (60, 200, 230, 255)), (0, 1, (60, 200, 230, 255)), (0, -1, (200, 255, 255, 255))):
            img.putpixel((x + dx, y + dy), col)
    out["soul_lantern"] = finish_icon(img, 32)

    sc = Scene()
    base, lid = m["chest"]
    sc.add(base, T("chest"), np.eye(3), (0, 0, 0), 1.0)
    sc.add(lid, T("chest"), rot_x(-math.radians(70)), (0, 1 / 16, -5 / 16), 1.0)
    for k, (x, y) in enumerate(((-0.2, 0.75), (0.15, 0.95), (0.3, 0.7))):
        sc.add([cube("taxsmp:item/fx/coin", (4, 4, 7.5), (12, 12, 8.5), light=12)], {}, rot_y(0.6 * k), (x, y, 0.05), 0.8)
    out["treasure_chest"] = render(sc, 32, yaw=-30, pitch=25)
    return out


# ------------------------------------------------------------------ MYTHICAL effects

DRAGON_PARTS = ["head", "neck", "body", "tail1", "tail2", "tail3", "wing_l_in", "wing_l_out", "wing_r_in", "wing_r_out"]
# tile index on the 64x64 dragon sheet (4 tiles per row) -> (dark, light) colour the tile is remapped to
DRAGON_VARIANTS = {
    "gold": {0: ((150, 100, 20), (255, 215, 90)), 1: ((110, 70, 12), (220, 170, 60)), 2: ((220, 210, 190), (255, 250, 235)),
             4: ((200, 30, 20), (255, 110, 80)), 5: ((170, 110, 30), (240, 190, 90)), 6: ((90, 60, 10), (160, 110, 30)),
             9: ((90, 30, 20), (130, 50, 30))},
    "obsidian": {0: ((30, 14, 46), (100, 56, 140)), 1: ((18, 8, 30), (64, 32, 96)), 2: ((110, 40, 140), (200, 120, 240)),
                 4: ((255, 60, 255), (255, 180, 255)), 5: ((60, 20, 90), (140, 64, 190)), 6: ((20, 10, 30), (64, 32, 84)),
                 9: ((40, 10, 40), (74, 20, 74))},
}


def dragon_variants():
    """Gold and obsidian copies of the dragon (same models, recoloured sheet) for Dragon Storm."""
    sheet = Image.open(TEX / "dragon.png").convert("RGBA")
    for name, tiles in DRAGON_VARIANTS.items():
        out = sheet.copy()
        px = out.load()
        for idx, (dark, light) in tiles.items():
            x0, y0 = idx % 4 * 16, idx // 4 * 16
            lums = [sum(px[x, y][:3]) for y in range(y0, y0 + 16) for x in range(x0, x0 + 16)]
            lo, hi = min(lums), max(lums)
            for y in range(y0, y0 + 16):
                for x in range(x0, x0 + 16):
                    t = (sum(px[x, y][:3]) - lo) / max(1, hi - lo)
                    px[x, y] = tuple(int(dark[i] + (light[i] - dark[i]) * t) for i in range(3)) + (255,)
        out.save(TEX / f"dragon_{name}.png")
        for part in DRAGON_PARTS:
            write_json(MODELS / f"dragon_{name}/{part}.json", {"parent": f"taxsmp:fx/dragon/{part}", "textures": {
                "t": f"taxsmp:item/fx/dragon_{name}", "particle": f"taxsmp:item/fx/dragon_{name}"}})
            item_def(f"dragon_{name}/{part}")


def gate():
    """Heaven's Gate: marble posts with a gold arch and a glowing gateway, plus two gold doors that swing open.
    Doors turn around their hinge at door model x 8; the frame's openings are x -2 and 18 (see DeathFx.HeavensGate)."""
    a = Atlas()
    a.add("marble", grad((252, 252, 255), (214, 218, 230), noise=4, lines=7, line_shade=-10))
    a.add("gold", GOLD)
    a.add("glow", lambda im, rnd: [im.putpixel((x, y), (255, 246, 200, 170)) for y in range(16) for x in range(16)])
    a.add("pearl", p_flat((240, 250, 255), 2))
    frame = [box(a, [-6, 0, 6], [-2, 28, 10], "marble"), box(a, [18, 0, 6], [22, 28, 10], "marble"),
             box(a, [-6.5, 28, 5.5], [-1.5, 30, 10.5], "gold"), box(a, [17.5, 28, 5.5], [22.5, 30, 10.5], "gold"),
             box(a, [-6.5, 0, 5.5], [-1.5, 2, 10.5], "gold"), box(a, [17.5, 0, 5.5], [22.5, 2, 10.5], "gold"),
             box(a, [-2, 24, 7], [18, 26, 9], "gold"), box(a, [4, 26, 7], [12, 28, 9], "gold"),
             box(a, [6, 28, 7], [10, 30, 9], "gold"), box(a, [7.25, 26.5, 9], [8.75, 28, 9.4], "pearl", light=15),
             box(a, [-2, 0, 6.6], [18, 24, 6.8], {"south": "glow", "north": "glow"}, light=15)]

    def door(x0, x1):
        els = [box(a, [x0, 0, 7.25], [x1, 1.5, 8.75], "gold"), box(a, [x0, 22.5, 7.25], [x1, 24, 8.75], "gold"),
               box(a, [x0, 11, 7.4], [x1, 12, 8.6], "gold")]
        xs = [x0 + 0.5 + k * 2 for k in range(5)]
        for x in xs:
            els.append(box(a, [x, 1.5, 7.6], [x + 0.75, 22.5, 8.4], "gold"))
        els.append(box(a, [x0, 0, 7.25], [x0 + 0.75, 24, 8.75], "gold"))
        els.append(box(a, [x1 - 0.75, 0, 7.25], [x1, 24, 8.75], "gold"))
        return els

    save_atlas("gate", a)
    parts = {"frame": frame, "door_l": door(8, 18), "door_r": door(-2, 8)}
    for name, els in parts.items():
        write_part(f"gate/{name}", "taxsmp:item/fx/gate", els)
    return parts


MYTHIC_OUTLINE = (235, 40, 70, 255)


def mythic(icon):
    """Mythical icons get a red outline instead of the dark one."""
    a = np.asarray(icon).copy()
    mask = np.all(a == np.array(fx.OUTLINE), axis=-1)
    a[mask] = MYTHIC_OUTLINE
    return Image.fromarray(a, "RGBA")


def mythic_icons(dragon_parts, gate_parts):
    out = {}
    # Dragon Storm: the approved dragon look, three times
    sc = Scene()
    from effects_assets import quat_pose, DRAGON_SEGMENTS, DRAGON_SHOULDER, DRAGON_WRIST
    for tex, (ox, oy, oz) in (("dragon_obsidian", (1.3, 0.6, -1.2)), ("dragon_gold", (-1.3, 0.4, -0.6)), ("dragon", (0, 0, 0))):
        T = {"t": f"taxsmp:item/fx/{tex}"}
        off = np.array([ox, oy, oz])

        def path(d):
            return np.array([-0.3 * d * d * 0.08, 0.02 * d * d, -d]) + off
        hp = path(0)
        sc.add(dragon_parts["head"], T, quat_pose(hp - path(0.3)), hp, 0.8)
        d = 0.0
        for seg in ("neck", "body", "tail1", "tail2", "tail3"):
            length = DRAGON_SEGMENTS[seg] / 16 * 0.8
            front, back = path(d), path(d + length)
            r = quat_pose(front - back)
            sc.add(dragon_parts[seg], T, r, front, 0.8)
            if seg == "body":
                br, bf = r, front
            d += length
        for side, sign in (("l", 1), ("r", -1)):
            sh = bf + br @ (np.array([sign * DRAGON_SHOULDER[0], DRAGON_SHOULDER[1], DRAGON_SHOULDER[2]]) / 16 * 0.8)
            ri = br @ rot_z(sign * math.radians(20))
            sc.add(dragon_parts[f"wing_{side}_in"], T, ri, sh, 0.8)
            wr = sh + ri @ (np.array([sign * DRAGON_WRIST, 0, 0]) / 16 * 0.8)
            sc.add(dragon_parts[f"wing_{side}_out"], T, ri @ rot_z(sign * math.radians(-14)), wr, 0.8)
    out["dragon_storm"] = mythic(finish_icon(sc.render(256, yaw=200, pitch=50, light=1.12, inside_out=True)))

    def strike(d, im):
        d.ellipse([10, 96, 118, 124], outline=(255, 40, 60, 255), width=8)
        d.polygon([(64, 70), (90, 104), (64, 122), (38, 104)], fill=(255, 150, 50, 255))
        d.rectangle([48, 0, 80, 108], fill=(120, 230, 255, 255))
        d.rectangle([56, 0, 72, 112], fill=(255, 255, 255, 255))
        for k in range(8):
            ang = k * math.pi / 4
            d.line([(64 + 14 * math.cos(ang), 108 + 8 * math.sin(ang)), (64 + 34 * math.cos(ang), 108 + 16 * math.sin(ang))],
                   fill=(255, 220, 90, 255), width=6)
    out["orbital_strike"] = mythic(big(strike))

    sc = Scene()
    T = {"t": "taxsmp:item/fx/gate"}
    sc.add(gate_parts["frame"], T, np.eye(3), (0, 0, 0), 1.0)
    sc.add(gate_parts["door_l"], T, rot_y(math.radians(60)), (-10 / 16, 0, 0), 1.0)
    sc.add(gate_parts["door_r"], T, rot_y(math.radians(-60)), (10 / 16, 0, 0), 1.0)
    clouds = [cube("minecraft:block/snow", (x, -8, z), (x + 10, -2, z + 10)) for x, z in ((-8, 2), (2, -2), (12, 3), (-2, 8), (8, 8))]
    sc.add(clouds, {}, np.eye(3), (0, 0, 0), 1.0)
    out["heavens_gate"] = mythic(render(sc, 32, yaw=-20, pitch=18))

    sc = Scene()
    cone = []
    for (lo, hi, tex) in (((-8, 0, -8), (24, 8, 24), "minecraft:block/blackstone"), ((-3, 8, -3), (19, 16, 19), "minecraft:block/basalt_side"),
                          ((2, 16, 2), (14, 22, 14), "minecraft:block/magma")):
        cone.append(cube(tex, lo, hi, light=15 if "magma" in tex else 0))
    sc.add(cone, {}, np.eye(3), (0, 0, 0), 1.0)
    rock = render(sc, 26, yaw=-30, pitch=22)

    def plume(d, im):
        for (cx, cy, r, col) in ((64, 30, 22, (120, 116, 120, 255)), (46, 16, 16, (150, 146, 150, 255)), (84, 12, 14, (150, 146, 150, 255))):
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
        for (x, y) in ((24, 44), (100, 40), (36, 64), (94, 60)):
            d.ellipse([x - 7, y - 7, x + 7, y + 7], fill=(255, 120, 30, 255))
        d.polygon([(52, 60), (76, 60), (70, 40), (58, 40)], fill=(255, 170, 40, 255))
    img = big(plume)
    out["volcano"] = mythic(paste(img, rock, (3, 6)))
    return out


# ------------------------------------------------------------------ entry point

def build():
    models = {"hammer": hammer(), "crown": crown(), "phoenix": phoenix(), "angel": angel(), "ghost": ghost(),
              "balloon": balloons(), "lantern": lantern(), "rocket": rocket(), "chest": chest()}
    sp = sprites()
    dragon_variants()
    gate_parts = gate()
    all_icons = {}
    if fx.JAR is None:
        print("no client jar given: kept the existing icons of the other effects")
        return all_icons
    for name, icon in icons(models, sp).items():
        all_icons.update(icon_variants(name, icon))
    from effects_assets import dragon as build_dragon
    dragon_parts, _ = build_dragon()
    for name, icon in mythic_icons(dragon_parts, gate_parts).items():
        all_icons.update(icon_variants(name, icon))
    import effects_models2  # batch 3: 24 more effects + tier auras
    all_icons.update(effects_models2.build2())
    return all_icons
