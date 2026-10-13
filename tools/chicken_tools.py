"""Draw the TaxSMP chicken tools (Sell Axe, Tree Chopper, Drill, Shovel) and write their pack files.

The pictures are 32x32 pixel art redrawn from the ChatGPT references in src/chicken/. Each tool
is a stack of parts (blade, handle, gem...). A part is a set of pixels, either listed per row or
filled from a shape; shapes are tested at pixel centers. Every part is shaded on its own (light
from the top-left), then the whole tool gets a dark outline and its animation:
  - a band of light sweeps over the gold,
  - the ice gems and windows pulse,
  - the drill bit spins and the chainsaw chain runs.
The 3D held model is the same picture with depth: every part has its own thickness.

    python tools/chicken_tools.py

Writes, for each tool:
  pack/assets/taxsmp/textures/item/tool/<name>.png (+ .mcmeta, animated)
  pack/assets/taxsmp/models/tool/<name>.json      flat icon for the inventory
  pack/assets/taxsmp/models/tool/<name>_3d.json   thick model for hands, ground and item frames
                                                  (from <name>_held.png when the tool has a held drawing)
  pack/assets/taxsmp/items/tool/<name>.json       picks the model by display context
plus preview/chicken-tools.png. The TaxSMP plugin (ui/ChickenToolSkin) sets the item model
taxsmp:tool/<name> on the chicken items. Run tools/bedrock_buttons.py afterwards for Bedrock.
"""
import json
import math
import os
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
LONG_PATH = '\\\\?\\'  # the Windows long path prefix \\?\
if os.name == 'nt' and not str(ROOT).startswith(LONG_PATH):
    ROOT = Path(LONG_PATH + str(ROOT))
ASSETS = ROOT / 'pack/assets/taxsmp'
SIZE = 32
FRAMES = 24       # one animation loop
FRAME_TIME = 2    # ticks per frame: the loop takes 2.4 s

# Shading ramps, darkest to brightest.
RAMPS = {
    'gold': ['#4a2604', '#7e4206', '#c8730e', '#f5a623', '#ffd23f', '#fff4b0'],
    'ice': ['#08304f', '#0d4c7e', '#1e8fcb', '#4cd3f5', '#a8f0ff', '#f0ffff'],
    'dark': ['#140f18', '#1c1920', '#2e2a33', '#47414c', '#6b6470', '#8a8390'],
    'steel': ['#22252d', '#5a5f6e', '#8e94a3', '#b4b9c4', '#c9cdd6', '#eef0f4'],
}
OUTLINE_COLOR = '#140f18'
OUTLINE, DEEP, SHADE, BASE, LIGHT, SHINE = range(6)


def rgba(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5)) + (255,)


# ---------------------------------------------------------------- shapes

class Frame:
    """Coordinates along a line: u runs from origin toward angle (degrees, 0 = right, 90 = up),
    v runs across it, positive toward the lower right side."""

    def __init__(self, ox, oy, angle):
        a = math.radians(angle)
        self.o = (ox, oy)
        self.d = (math.cos(a), -math.sin(a))
        self.n = (math.sin(a), math.cos(a))

    def local(self, x, y):
        dx, dy = x - self.o[0], y - self.o[1]
        return dx * self.d[0] + dy * self.d[1], dx * self.n[0] + dy * self.n[1]

    def cells(self, test):
        """Pixels whose center passes test(u, v)."""
        return {(x, y) for y in range(SIZE) for x in range(SIZE) if test(*self.local(x + 0.5, y + 0.5))}

    def point(self, u, v):
        return (self.o[0] + u * self.d[0] + v * self.n[0], self.o[1] + u * self.d[1] + v * self.n[1])


def box(u0, u1, v0, v1, cut=0.0):
    """Rectangle in a frame; cut bevels its corners."""
    return lambda u, v: (u0 <= u <= u1 and v0 <= v <= v1
                         and min(u - u0, u1 - u) + min(v - v0, v1 - v) >= cut)


def profile(points):
    """Shape symmetric around v = 0 whose half-width follows (u, w) points."""
    def test(u, v):
        for (u0, w0), (u1, w1) in zip(points, points[1:]):
            if u0 <= u <= u1:
                return abs(v) <= w0 + (w1 - w0) * (u - u0) / (u1 - u0)
        return False
    return test


def rows(table):
    """Pixels listed per row: {y: (x0, x1)} or {y: [(x0, x1), ...]}, ends included."""
    cells = set()
    for y, segs in table.items():
        for x0, x1 in ([segs] if isinstance(segs[0], int) else segs):
            cells.update((x, y) for x in range(x0, x1 + 1))
    return cells


def columns(cells, x0, x1, first, last, from_bottom=False):
    """In each column x0..x1 of a shape, its pixels number first..last counted from the top
    (or bottom): stripes and panels that step with a tilted body."""
    out = set()
    for x in range(x0, x1 + 1):
        col = sorted((y for cx, y in cells if cx == x), reverse=from_bottom)
        out.update((x, y) for y in col[first:last + 1])
    return out


def mirror_diagonal(cells):
    """Flips pixels across the bottom-left to top-right diagonal (the handle line of a tool)."""
    return {(SIZE - 1 - y, SIZE - 1 - x) for x, y in cells}


def bitmap(x0, y0, lines, mark='#'):
    return {(x0 + i, y0 + j) for j, line in enumerate(lines) for i, c in enumerate(line) if c == mark}


def disk_cells(cx, cy, r):
    return {(x, y) for y in range(SIZE) for x in range(SIZE) if math.hypot(x + 0.5 - cx, y + 0.5 - cy) <= r}


class Part:
    """Pixels of one material. Later parts cover earlier ones.

    cells: a set of (x, y), or a function of the animation frame returning one.
    depth: thickness in the 3D model, in model units (a vanilla item is 1 thick).
    flat:  no edge shading, for inlays (one tone, picked with tone).
    tag:   'glow' (pulses), 'bit' (spinning drill stripes), 'chain' (running chain) or
           'teeth' (chainsaw teeth, held on in 3D by the outline next to them).
    """

    def __init__(self, material, cells, depth, flat=False, tone=BASE, tag=None, axis=None, lit_as=None, offset=0):
        self.material, self.cells, self.depth = material, cells, depth
        self.flat, self.flat_tone, self.tag, self.axis = flat, tone, tag, axis
        self.lit_as = lit_as  # shade as if the part had this shape (for halves of one surface)
        self.offset = offset  # tone shift, e.g. -1 for the side of a fold facing away
        self.anchor = tag == 'teeth'

    def at(self, f):
        return self.cells(f) if callable(self.cells) else self.cells


class Tool:
    """held: another drawing of the same tool for the 3D model in the hand, when the inventory
    picture would point the wrong way there."""

    def __init__(self, name, parts, display, held=None):
        self.name, self.parts, self.display, self.held = name, parts, display, held

    def owners(self, f):
        owner = {}
        for i, part in enumerate(self.parts):
            for c in part.at(f):
                if 0 <= c[0] < SIZE and 0 <= c[1] < SIZE:
                    owner[c] = i
        for c in owner:
            if c[0] in (0, SIZE - 1) or c[1] in (0, SIZE - 1):
                print(f'WARNING {self.name}: pixel {c} touches the edge, no room for the outline')
        return owner

    def frame(self, f):
        owner = self.owners(f)
        img = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
        px = img.load()
        shapes = [part.lit_as or part.at(f) for part in self.parts]
        for (x, y), i in owner.items():
            part = self.parts[i]
            material, t = shade(shapes[i], part, x, y)
            t = max(DEEP, min(SHINE, t + part.offset))
            material, t = animate(part, material, t, x, y, f)
            px[x, y] = rgba(RAMPS[material][t])
        for y in range(SIZE):
            for x in range(SIZE):
                if (x, y) not in owner and any((x + dx, y + dy) in owner for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    px[x, y] = rgba(OUTLINE_COLOR)
        return img, owner

    def depth_map(self):
        """Thickest part over the whole loop at every pixel.

        The outline gets no thickness, so in 3D the sides show the part colors and holes are
        open; only outline pixels touching an anchored part (the chainsaw teeth) stay, to hold
        it to the rest.
        """
        depth = {}
        for f in range(FRAMES):
            owner = self.owners(f)
            for (x, y), i in owner.items():
                depth[(x, y)] = max(depth.get((x, y), 0), self.parts[i].depth)
            for y in range(SIZE):
                for x in range(SIZE):
                    if (x, y) in owner:
                        continue
                    near = [self.parts[owner[c]] for c in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)) if c in owner]
                    if any(p.anchor for p in near):
                        depth[(x, y)] = max(depth.get((x, y), 0), min(p.depth for p in near))
        return depth


# Neighbor offsets by distance, for finding the nearest pixel outside a part.
NEAR = sorted(((dx, dy) for dx in range(-3, 4) for dy in range(-3, 4) if (dx, dy) != (0, 0)),
              key=lambda o: (o[0] ** 2 + o[1] ** 2, o))


def shade(shape, part, x, y):
    """Tone of a pixel from its part's shape, lit from the top-left.

    Finds the nearest pixel outside the part: edges facing the light are bright, edges facing
    away are dark, and gold and ice get a second, softer bevel row. Corners get the extremes.
    """
    if part.flat:
        return part.material, part.flat_tone

    def out(dx, dy):
        return (x + dx, y + dy) not in shape

    nearest = next(((dx, dy) for dx, dy in NEAR if out(dx, dy)), None)
    if nearest is None:
        return part.material, BASE
    r = math.hypot(*nearest)
    # Average direction to the outside pixels at that distance, against the light direction.
    ring = [(dx, dy) for dx, dy in NEAR if math.hypot(dx, dy) <= r + 0.01 and out(dx, dy)]
    fx = sum(dx for dx, _ in ring)
    fy = sum(dy for _, dy in ring)
    norm = math.hypot(fx, fy) or 1.0
    facing = -(fx + fy) / norm * math.sqrt(0.5)  # 1 = faces the light, -1 = faces away
    shiny = part.material in ('gold', 'ice', 'steel')
    if r <= 1.0:
        if facing > 0.85 and out(-1, 0) and out(0, -1):
            return part.material, SHINE
        if facing > 0.3:
            return part.material, LIGHT
        if facing < -0.85 and out(1, 0) and out(0, 1):
            return part.material, DEEP
        if facing < -0.3:
            return part.material, SHADE
        return part.material, BASE
    if r <= 1.5 and shiny:
        if facing > 0.3:
            return part.material, LIGHT
        if facing < -0.3:
            return part.material, BASE
    return part.material, BASE


def animate(part, material, t, x, y, f):
    cx, cy = x + 0.5, y + 0.5
    if part.tag == 'bit':
        # Spiral flutes, gold and ice, running back toward the chuck (the bit spins).
        u, v = part.axis.local(cx, cy)
        k = math.floor((u - v * 0.9) / 1.5 + f / 2) % 4
        material = 'ice' if k < 2 else 'gold'
        t = {SHINE: SHINE, LIGHT: LIGHT, BASE: LIGHT if k % 2 == 0 else BASE}.get(t, SHADE)
    elif part.tag == 'glow':
        pulse = math.sin(2 * math.pi * f / FRAMES)
        if pulse > 0.5 and t < SHINE:
            t += 1
        elif pulse < -0.5 and t > DEEP:
            t -= 1
    elif part.tag == 'chain':
        u, v = part.axis.local(cx, cy)
        side = 1 if v < 0 else -1
        t = (LIGHT, BASE, SHADE)[(math.floor(u * 0.71) - side * f) % 3]
    if material == 'gold' and f < FRAMES // 2:
        # A band of light sweeps over the gold from the lower left to the upper right.
        band = -30 + f * 5.5
        w = x - y
        if abs(w - band) <= 1.5 and t < SHINE:
            t = min(SHINE, t + 2)
    return material, t


HANDHELD = {
    'thirdperson_righthand': {'rotation': [0, -90, 55], 'translation': [0, 4, 0.5], 'scale': [0.85, 0.85, 0.85]},
    'thirdperson_lefthand': {'rotation': [0, 90, -55], 'translation': [0, 4, 0.5], 'scale': [0.85, 0.85, 0.85]},
    'firstperson_righthand': {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'firstperson_lefthand': {'rotation': [0, 90, -25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.5, 0.5, 0.5]},
    'head': {'rotation': [0, 180, 0], 'translation': [0, 13, 7], 'scale': [1, 1, 1]},
    'fixed': {'rotation': [0, 180, 0], 'scale': [1, 1, 1]},
}


# ---------------------------------------------------------------- the four tools

def sell_axe():
    # Drawn with the blade on the right like the reference, then mirrored across the diagonal, so
    # the blade sits on the upper-left of the handle like a vanilla axe: that side faces forward
    # in the hand. The dollar sign is placed after the mirror, to stay upright.
    h = Frame(4.2, 27.2, math.degrees(math.atan2(15.7, 12.0)))
    handle = h.cells(box(1.0, 19.0, -1.75, 1.75))
    pommel = rows({24: (3, 5), 25: (2, 6), 26: (1, 6), 27: (1, 6), 28: (2, 5), 29: (3, 4)})
    pommel_gem = rows({26: (3, 4), 27: (3, 4)})
    band1 = h.cells(box(5.4, 7.0, -2.6, 2.6))
    band2 = h.cells(box(11.0, 12.6, -2.6, 2.6))
    socket = rows({5: (15, 17), 6: (14, 18), 7: (13, 19), 8: (13, 19), 9: (13, 19), 10: (13, 19),
                   11: (14, 18), 12: (15, 17)})
    socket_gem = rows({6: (16, 16), 7: (15, 17), 8: (14, 18), 9: (15, 17), 10: (16, 16)})
    blade = rows({2: (22, 23), 3: (22, 26), 4: (22, 27), 5: (22, 28), 6: (21, 28), 7: (20, 29),
                  8: (20, 29), 9: (20, 29), 10: (20, 30), 11: (19, 30), 12: (18, 30), 13: (18, 30),
                  14: (19, 29), 15: (19, 29), 16: (20, 29), 17: (20, 28), 18: (20, 28), 19: (20, 27),
                  20: (19, 27), 21: (19, 26), 22: (18, 24), 23: (17, 22)})
    edge = rows({2: (23, 23), 3: (24, 26), 4: (25, 27), 5: (26, 28), 6: (26, 28), 7: (27, 29),
                 8: (27, 29), 9: (27, 29), 10: (28, 30), 11: (28, 30), 12: (28, 30), 13: (28, 30),
                 14: (27, 29), 15: (27, 29), 16: (27, 29), 17: (26, 28), 18: (25, 28), 19: (24, 27),
                 20: (23, 27), 21: (22, 26), 22: (20, 24), 23: (17, 22)})
    coin = ['..#####..', '.#######.', '#########', '#########', '#########', '#########', '#########',
            '.#######.', '..#####..']
    face = ['.........', '..#####..', '.#######.', '.#######.', '.#######.', '.#######.', '.#######.',
            '..#####..', '.........']
    dollar = ['....#....', '...###...', '...#.....', '...###...', '.....#...', '...###...', '....#....']
    m = mirror_diagonal
    return Tool('sell_axe', [
        Part('dark', m(handle), 1.5),
        Part('gold', m(band1), 2.0),
        Part('gold', m(band2), 2.0),
        Part('gold', m(pommel), 2.4),
        Part('ice', m(pommel_gem), 2.8, tag='glow', offset=-1),
        Part('gold', m(blade), 1.2),
        Part('ice', m(edge), 1.0),
        Part('gold', m(socket), 2.6),
        Part('ice', m(socket_gem), 3.0, tag='glow', offset=-1),
        Part('gold', m(bitmap(17, 7, coin)), 1.8, flat=True, tone=DEEP),
        Part('gold', m(bitmap(17, 7, face)), 2.0),
        Part('gold', bitmap(16, 7, dollar), 2.2, flat=True, tone=DEEP),
    ], HANDHELD)


def tree_chopper():
    # The bar is a 45 degree band: s = x + y across it, d = x - y along it (toward the tip).
    s_mid, d0, d1 = 31, -4, 21

    def bar_test(x, y):
        s, d = x + y, x - y
        if not d0 <= d <= d1:
            return False
        half = {d1 - 2: 3, d1 - 1: 2, d1: 1}.get(d, 4)  # rounded tip
        return abs(s - s_mid) <= half

    bar_cells = {(x, y) for y in range(SIZE) for x in range(SIZE) if bar_test(x, y)}
    edge = {c for c in bar_cells if not all((c[0] + dx, c[1] + dy) in bar_cells for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            and c[0] - c[1] > d0 + 1}
    rivet = {(23, 7), (24, 7)}

    # Teeth ride on the ring two pixels outside the bar (the outline sits in between).
    near = {(x + dx, y + dy) for x, y in bar_cells for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))} - bar_cells
    ring = {(x + dx, y + dy) for x, y in near for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))} - near - bar_cells
    ring = {c for c in ring if c[0] - c[1] >= d0 + 4 and 0 < c[0] < SIZE - 1 and 0 < c[1] < SIZE - 1}

    def loop_pos(c):
        s, d = c[0] + c[1], c[0] - c[1]
        if s < s_mid - 2 and d < d1:
            return d                           # upper-left run, toward the tip
        if s > s_mid + 2 and d < d1:
            return 200 - d                     # lower-right run, back down
        return 100 + (s - s_mid) * 0.5 + d     # around the tip

    order = sorted(ring, key=loop_pos)

    def teeth(f):
        return {c for k, c in enumerate(order) if (k - f) % 4 == 0}

    housing = rows({17: (4, 14), 18: (3, 15), 19: (3, 15), 20: (3, 15), 21: (3, 15), 22: (3, 15), 23: (3, 15),
                    24: (3, 15), 25: (3, 15), 26: (3, 15), 27: (3, 15), 28: (4, 14)})
    front = rows({18: (10, 15), 19: (9, 15), 20: (9, 15), 21: (9, 15), 22: (9, 15), 23: (9, 15), 24: (9, 15),
                  25: (9, 15), 26: (9, 15), 27: (10, 15)})
    window = rows({20: (11, 13), 21: (11, 14), 22: (11, 14), 23: (11, 14), 24: (11, 14), 25: (12, 14)})
    rear = rows({19: (2, 3), 20: (2, 3), 21: (2, 3), 22: (2, 3), 23: (2, 3), 24: (2, 3), 25: (2, 3), 26: (2, 3), 27: (2, 3)})
    plate = rows({16: (4, 7), 17: (3, 8), 18: (3, 8)})
    plate_gem = rows({17: (5, 6)})
    vents = rows({21: (5, 7), 23: (5, 7), 25: (5, 7)})
    loop = rows({11: (8, 12), 12: (7, 13), 13: [(7, 8), (12, 13)], 14: [(7, 8), (12, 13)], 15: [(7, 8), (12, 13)],
                 16: [(8, 8), (12, 12)]})
    loop_grip = rows({11: (9, 11), 12: (9, 11)})
    return Tool('tree_chopper', [
        Part('ice', edge, 1.5, tag='chain', axis=Frame(0, 0, 45)),
        Part('gold', bar_cells - edge, 1.3),
        Part('gold', rivet, 1.5, flat=True, tone=SHADE),
        Part('steel', teeth, 1.6, tag='teeth'),
        Part('dark', loop, 2.4),
        Part('gold', loop_grip, 2.6),
        Part('dark', housing, 4.4),
        Part('gold', rear, 4.6),
        Part('gold', plate, 4.6),
        Part('ice', plate_gem, 4.8, tag='glow'),
        Part('dark', vents, 4.5, flat=True, tone=DEEP),
        Part('gold', front, 4.6),
        Part('ice', window, 4.8, tag='glow'),
    ], HANDHELD)


def drill():
    """The inventory icon: side view like the reference, bit up to the right like vanilla tool icons."""
    ax = Frame(3.5, 11.5, math.degrees(math.atan2(1, 3)))  # 1:3 slope, up to the right
    body = ax.cells(box(-1.0, 13.0, -4.6, 4.2, cut=2.2))
    stripe = columns(body, 4, 15, 0, 1)
    cap = columns(body, 1, 3, 0, 99)
    vents = columns(body, 5, 8, 3, 3) | columns(body, 5, 8, 5, 5) | columns(body, 5, 8, 7, 7)
    window = columns(body, 10, 13, 3, 6)
    chuck = ax.cells(lambda u, v: 13.0 <= u <= 17.5 and abs(v + 0.2) <= 3.0 - (u - 13.0) * 0.22)
    rings = {(x, y) for x, y in chuck if x in (17, 20)}
    bit = ax.cells(lambda u, v: 17.5 <= u <= 24.0 and abs(v + 0.2) <= 1.55)
    tip = ax.cells(lambda u, v: 24.0 <= u <= 27.2 and abs(v + 0.2) <= 1.55 * (27.2 - u) / 3.2 + 0.25)
    g = Frame(10.4, 13.6, -102)  # grip, down and a little back
    grip = g.cells(box(-1.0, 9.6, -2.5, 2.3, cut=0.8))
    grip_stripe = g.cells(box(0.5, 8.5, 0.9, 2.3))
    trigger = rows({13: (13, 13), 14: (13, 14), 15: (13, 14), 16: (13, 13)})
    battery = rows({22: (5, 15), 23: (4, 16), 24: (4, 16), 25: (4, 16), 26: (4, 16), 27: (4, 16), 28: (4, 16), 29: (5, 15)})
    band = rows({22: (5, 15), 23: (4, 16), 24: (4, 16)})
    cells_l = rows({26: (5, 7), 27: (5, 7), 28: (5, 7)})
    cells_r = rows({26: (11, 14), 27: (11, 14), 28: (11, 14)})
    return Tool('drill', [
        Part('dark', grip, 2.8),
        Part('gold', grip_stripe, 3.0),
        Part('gold', trigger, 2.0),
        Part('dark', battery, 4.2),
        Part('gold', band, 4.4),
        Part('ice', cells_l, 4.4, tag='glow'),
        Part('ice', cells_r, 4.4, tag='glow'),
        Part('dark', body, 4.0),
        Part('gold', stripe, 4.2),
        Part('gold', cap, 4.2),
        Part('dark', vents, 4.3, flat=True, tone=DEEP),
        Part('ice', window, 4.3, tag='glow'),
        Part('gold', chuck, 3.4),
        Part('gold', rings, 3.4, flat=True, tone=SHADE),
        Part('gold', bit, 2.0, tag='bit', axis=ax),
        Part('ice', tip, 2.0),
    ], HANDHELD, held=drill_held())


def drill_held():
    """The drill as held: the hand holds a tool at the bottom-left of its picture and the left side
    faces forward, so the grip runs down that diagonal to the battery and the bit points up-left."""
    ax = Frame(19.5, 15.0, 135)  # u toward the bit tip, v toward the top of the drill
    shell = box(-7.0, 6.0, -4.0, 4.6, cut=2.4)
    body = ax.cells(shell)
    stripe = ax.cells(lambda u, v: shell(u, v) and v >= 3.1)
    cap = ax.cells(lambda u, v: shell(u, v) and u <= -5.6)
    vents = ax.cells(lambda u, v: -4.6 <= u <= -1.0 and -1.8 <= v <= 1.8 and math.floor(u * 0.71 + 10) % 2 == 0)
    window = ax.cells(box(0.8, 4.4, -1.8, 2.2, cut=0.7))
    chuck_test = lambda u, v: 6.0 <= u <= 9.8 and abs(v - 0.3) <= 3.1 - (u - 6.0) * 0.25
    chuck = ax.cells(chuck_test)
    rings = ax.cells(lambda u, v: chuck_test(u, v) and math.floor((u - 6.0) / 1.42) % 2 == 1)
    bit = ax.cells(lambda u, v: 9.8 <= u <= 15.2 and abs(v - 0.3) <= 1.5)
    tip = ax.cells(lambda u, v: 15.2 <= u <= 18.0 and abs(v - 0.3) <= 1.5 * (18.0 - u) / 2.8 + 0.25)
    trigger = ax.cells(box(-0.6, 1.0, -5.8, -3.8))
    g = Frame(*ax.point(-3.2, -3.6), 225)  # grip: down-left along the diagonal, v toward the bit side
    grip = g.cells(box(-0.5, 5.6, -2.4, 2.4, cut=0.8))
    grip_stripe = g.cells(box(0.6, 4.8, 1.1, 2.4))
    bat = Frame(*g.point(8.0, 0.0), 135)
    shell_b = box(-5.4, 5.0, -2.4, 2.4, cut=1.0)
    battery = bat.cells(shell_b)
    band = bat.cells(lambda u, v: shell_b(u, v) and v >= 0.9)
    cells_l = bat.cells(box(-4.2, -1.2, -1.8, 0.3))
    cells_r = bat.cells(box(0.8, 3.8, -1.8, 0.3))
    return Tool('drill_held', [
        Part('dark', grip, 2.8),
        Part('gold', grip_stripe, 3.0),
        Part('gold', trigger, 2.0),
        Part('dark', battery, 4.2),
        Part('gold', band, 4.4),
        Part('ice', cells_l, 4.4, tag='glow'),
        Part('ice', cells_r, 4.4, tag='glow'),
        Part('dark', body, 4.0),
        Part('gold', stripe, 4.2),
        Part('gold', cap, 4.2),
        Part('dark', vents, 4.3, flat=True, tone=DEEP),
        Part('ice', window, 4.3, tag='glow'),
        Part('gold', chuck, 3.4),
        Part('gold', rings, 3.4, flat=True, tone=SHADE),
        Part('gold', bit, 2.0, tag='bit', axis=ax),
        Part('ice', tip, 2.0),
    ], HANDHELD)


def shovel():
    ax = Frame(19.5, 12.5, 45)  # u = 0 at the collar, the blade toward the top-right
    outer = profile([(0.0, 1.8), (0.8, 5.4), (7.4, 5.6), (13.4, 0.0)])
    inner = profile([(0.0, 0.9), (1.6, 4.1), (7.1, 4.2), (11.0, 0.0)])
    blade = ax.cells(outer)
    right = ax.cells(lambda u, v: outer(u, v) and v >= 0.35)
    fold = {(x, y) for x, y in blade - right if (x + 1, y) in right or (x, y + 1) in right}
    edge = ax.cells(lambda u, v: outer(u, v) and not inner(u, v) and u > 6.0)
    collar = ax.cells(box(-3.4, 0.4, -2.4, 2.4, cut=0.6))
    crystal = ax.cells(box(-2.7, 0.6, -1.6, 1.6, cut=1.2))
    shaft = ax.cells(box(-15.0, -3.0, -1.5, 1.5))
    band1 = ax.cells(box(-7.6, -6.2, -2.2, 2.2))
    band2 = ax.cells(box(-12.4, -11.0, -2.2, 2.2))
    dgrip = ax.cells(lambda u, v: box(-21.0, -14.4, -3.9, 3.9, cut=2.4)(u, v) and not box(-19.4, -16.2, -2.3, 2.3, cut=0.5)(u, v))
    grip_bar = ax.cells(box(-21.0, -19.6, -3.4, 3.4))
    return Tool('shovel', [
        Part('dark', shaft, 1.6),
        Part('gold', band1, 2.0),
        Part('gold', band2, 2.0),
        Part('gold', dgrip, 1.8),
        Part('dark', grip_bar, 2.4),
        Part('gold', blade, 1.4),
        Part('gold', right, 1.4, lit_as=blade, offset=-1),
        Part('gold', fold, 1.5, flat=True, tone=LIGHT),
        Part('ice', edge, 1.2),
        Part('gold', collar, 2.4),
        Part('ice', crystal, 2.8, tag='glow'),
    ], HANDHELD)


TOOLS = [sell_axe, tree_chopper, drill, shovel]


# ---------------------------------------------------------------- pack files

def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8', newline='\n')


def voxel_elements(depth, tex):
    """Greedy boxes of equal thickness; every face samples the pixels it covers."""
    done = set()
    elements = []
    s = 16 / SIZE  # model units per pixel
    eps = 0.04 * s
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in done or (x, y) not in depth:
                continue
            d = depth[(x, y)]
            x1 = x
            while depth.get((x1 + 1, y)) == d and (x1 + 1, y) not in done:
                x1 += 1
            y1 = y
            while all(depth.get((xx, y1 + 1)) == d and (xx, y1 + 1) not in done for xx in range(x, x1 + 1)):
                y1 += 1
            done.update((xx, yy) for yy in range(y, y1 + 1) for xx in range(x, x1 + 1))

            def exposed(cells):
                return any(depth.get(c, 0) < d for c in cells)

            faces = {
                'south': {'uv': [x * s, y * s, (x1 + 1) * s, (y1 + 1) * s], 'texture': tex},
                'north': {'uv': [(x1 + 1) * s, y * s, x * s, (y1 + 1) * s], 'texture': tex},
            }
            if exposed([(xx, y - 1) for xx in range(x, x1 + 1)]):
                faces['up'] = {'uv': [x * s, y * s + eps, (x1 + 1) * s, (y + 1) * s - eps], 'texture': tex}
            if exposed([(xx, y1 + 1) for xx in range(x, x1 + 1)]):
                faces['down'] = {'uv': [x * s, y1 * s + eps, (x1 + 1) * s, (y1 + 1) * s - eps], 'texture': tex}
            if exposed([(x - 1, yy) for yy in range(y, y1 + 1)]):
                faces['west'] = {'uv': [x * s + eps, y * s, (x + 1) * s - eps, (y1 + 1) * s], 'texture': tex}
            if exposed([(x1 + 1, yy) for yy in range(y, y1 + 1)]):
                faces['east'] = {'uv': [x1 * s + eps, y * s, (x1 + 1) * s - eps, (y1 + 1) * s], 'texture': tex}
            r = lambda n: round(n, 4)
            elements.append({
                'from': [r(x * s), r(16 - (y1 + 1) * s), r(8 - d / 2)],
                'to': [r((x1 + 1) * s), r(16 - y * s), r(8 + d / 2)],
                'faces': faces,
            })
    return elements


def write_texture(tool):
    """The animation strip and its .mcmeta; returns the frames and the texture id."""
    frames = [tool.frame(f)[0] for f in range(FRAMES)]
    strip = Image.new('RGBA', (SIZE, SIZE * FRAMES))
    for f, img in enumerate(frames):
        strip.paste(img, (0, f * SIZE))
    tex_path = ASSETS / f'textures/item/tool/{tool.name}.png'
    tex_path.parent.mkdir(parents=True, exist_ok=True)
    strip.save(tex_path)
    write_json(tex_path.with_name(tool.name + '.png.mcmeta'),
               {'animation': {'frametime': FRAME_TIME, 'interpolate': False}})
    return frames, f'taxsmp:item/tool/{tool.name}'


def build(tool):
    frames, tex = write_texture(tool)
    write_json(ASSETS / f'models/tool/{tool.name}.json',
               {'parent': 'minecraft:item/handheld', 'textures': {'layer0': tex}})
    held = tool.held or tool
    held_frames, held_tex = write_texture(held) if tool.held else (frames, tex)
    depth = held.depth_map()
    elements = voxel_elements(depth, '#0')
    write_json(ASSETS / f'models/tool/{tool.name}_3d.json', {
        'textures': {'0': held_tex, 'particle': held_tex},
        'elements': elements,
        'display': held.display,
    })
    write_json(ASSETS / f'items/tool/{tool.name}.json', {'model': {
        'type': 'minecraft:select',
        'property': 'minecraft:display_context',
        'cases': [{'when': 'gui', 'model': {'type': 'minecraft:model', 'model': f'taxsmp:tool/{tool.name}'}}],
        'fallback': {'type': 'minecraft:model', 'model': f'taxsmp:tool/{tool.name}_3d'},
    }})
    print(f'{tool.name}: {FRAMES} frames, {len(elements)} model boxes')
    return frames, held_frames, depth


def render_3d(img, depth, scale=8):
    """Rough look at the 3D model from the side, for the preview sheet only."""
    out = Image.new('RGBA', (SIZE * scale + 2 * scale * 4, SIZE * scale + 2 * scale * 4), (0, 0, 0, 0))
    draw = ImageDraw.Draw(out)
    px = img.load()
    yaw, pitch = math.radians(-38), math.radians(18)

    def proj(x, y, z):
        cx = x - SIZE / 2
        rx = cx * math.cos(yaw) + z * math.sin(yaw)
        rz = -cx * math.sin(yaw) + z * math.cos(yaw)
        ry = (y - SIZE / 2) * math.cos(pitch) - rz * math.sin(pitch)
        rz2 = (y - SIZE / 2) * math.sin(pitch) + rz * math.cos(pitch)
        return (out.width / 2 + rx * scale, out.height / 2 + ry * scale), rz2

    quads = []
    for (x, y), d in depth.items():
        col = px[x, y]
        if col[3] == 0:
            continue
        h = d * SIZE / 16 / 2
        faces = [
            ([(x, y, h), (x + 1, y, h), (x + 1, y + 1, h), (x, y + 1, h)], 1.0),
            ([(x + 1, y, -h), (x + 1, y, h), (x + 1, y + 1, h), (x + 1, y + 1, -h)], 0.62),
            ([(x, y, -h), (x, y, h), (x, y + 1, h), (x, y + 1, -h)], 0.62),
            ([(x, y, -h), (x + 1, y, -h), (x + 1, y, h), (x, y, h)], 0.85),
            ([(x, y + 1, -h), (x + 1, y + 1, -h), (x + 1, y + 1, h), (x, y + 1, h)], 0.5),
        ]
        for pts, light in faces:
            p = [proj(*q) for q in pts]
            quads.append((sum(q[1] for q in p) / 4, [q[0] for q in p], tuple(int(c * light) for c in col[:3]) + (255,)))
    for _, pts, col in sorted(quads, key=lambda q: -q[0]):
        draw.polygon(pts, fill=col)
    return out


def main():
    results = []
    for make in TOOLS:
        tool = make()
        frames, held_frames, depth = build(tool)
        results.append((tool, frames, held_frames, depth))
    cell = SIZE * 8
    pad = 12
    sheet = Image.new('RGBA', (pad + 4 * (cell + pad), pad + len(results) * (cell + pad)), (139, 139, 139, 255))
    for row, (tool, frames, held_frames, depth) in enumerate(results):
        y = pad + row * (cell + pad)
        for col, f in enumerate((FRAMES - 1, 4)):
            sheet.alpha_composite(frames[f].resize((cell, cell), Image.NEAREST), (pad + col * (cell + pad), y))
        small = Image.new('RGBA', (cell, cell), (0, 0, 0, 0))
        for i in range(4):
            small.alpha_composite(frames[FRAMES - 1].resize((SIZE * 2, SIZE * 2), Image.NEAREST), (i * SIZE * 2, 0))
            small.alpha_composite(frames[FRAMES - 1], (i * SIZE * 2 + 16, SIZE * 2 + 24))
        sheet.alpha_composite(small, (pad + 2 * (cell + pad), y))
        r3 = render_3d(held_frames[FRAMES - 1], depth, scale=6)
        r3.thumbnail((cell, cell), Image.NEAREST)
        sheet.alpha_composite(r3, (pad + 3 * (cell + pad), y))
    (ROOT / 'preview').mkdir(exist_ok=True)
    sheet.save(ROOT / 'preview/chicken-tools.png')
    print('Wrote preview/chicken-tools.png')


if __name__ == '__main__':
    main()
