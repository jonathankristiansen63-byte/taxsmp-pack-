"""Generate the /homes dialog look: font taxsmp:hm (grid cells, buttons, small icons) and taxsmp:hi (item icons).

com.taxsmp.settings.TaxSettings.Homes draws /homes as a 5-column dialog. Every button label has a total width of 0
and moves the pen with the space characters of taxsmp:dlg (see settings_dialog.py), so these pictures land at exact
spots relative to the button. A cell is two buttons stacked (name row, action row), each 20 px tall with 2 px between
rows and columns, so row pictures are 22 px tall and run 2 px past the button's right edge to join up.

taxsmp:hi holds one 16 px icon per line of server-plugin/src/main/resources/item-sprites.txt, at 0xE000 + line
index (comments skipped), pointing at the vanilla texture, except beds, which have no flat texture and are drawn here.

Keep characters and sizes in sync with TaxSettings.Homes.

    python tools/homes_dialog.py
"""
import io
import json
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).absolute().parent.parent
ASSETS = ROOT / "pack/assets/taxsmp"
TEX = ASSETS / "textures/h"
SPRITES = ROOT / "server-plugin/src/main/resources/item-sprites.txt"

CW, ROW_H, PAD, GAP = 112, 22, 3, 2
BTN_X, BTN_W, BTN_H = 6, CW - 12, 16
TOP = 3  # the first row reaches 3 px above its button for the window's top edge

# TaxSMP look (same palette as settings_dialog.py): silver window, darker well, raised silver cards.
BLACK = (0, 0, 0, 255)
WHITE = (255, 255, 255, 255)
FACE = (198, 198, 198, 255)
SHADOW = (85, 85, 85, 255)
WELL = (139, 139, 139, 255)
WELL_DARK = (55, 55, 55, 255)
GOLD = (245, 183, 41, 255)
GOLD_LIGHT = (255, 226, 122, 255)
GOLD_DARK = (170, 112, 10, 255)
GOLD_INK = (70, 44, 0, 255)
MINT = (70, 214, 154, 255)
MINT_LIGHT = (150, 255, 210, 255)
MINT_DARK = (24, 122, 82, 255)
STONE = (60, 60, 66, 255)
STONE_LIGHT = (96, 96, 104, 255)
STONE_DARK = (34, 34, 38, 255)
LOCKED = {"face": (136, 136, 136, 255), "light": (166, 166, 166, 255), "dark": (80, 80, 80, 255), "hatch": (124, 124, 124, 255),
          "slot": (104, 104, 104, 255)}
OPEN = {"face": FACE, "light": WHITE, "dark": SHADOW, "hatch": None, "slot": WELL}

# Pictures in taxsmp:hm.
CELL_BASE = 0xE600  # + kind * 6 + column * 2 + edge  (kind 0 = name row, 1 = action row, 2 / 3 = the same, locked)
BTN_ON, BTN_OFF, GEAR, LOCK, CHECK, CROSS, BED_LOCKED = 0xE620, 0xE621, 0xE622, 0xE623, 0xE624, 0xE625, 0xE626
VBORDER, VBORDER_LAST = 0xE627, 0xE628
END_A, END_B, END_L, END_R = 0xE629, 0xE62A, 0xE62B, 0xE62C  # list ends: list halves, /settings left and right column
BTN_ADD, BTN_LOCKED = 0xE62D, 0xE62E  # mint "+ Set Here", stone "Unlock"
BIG_YES, BIG_NO = 0xE630, 0xE631  # 148 px confirm buttons (TaxSettings.Dialogs.confirmReset)
BIG_W, BIG_H = 148, 20
RED = (201, 74, 74, 255)
RED_LIGHT = (240, 128, 128, 255)
RED_DARK = (122, 31, 31, 255)
END_H = ROW_H - 2  # a closing row stops at its button's bottom edge
R_PIC, L_PIC = 300 // 2 + PAD, 130 + 2 + PAD  # TaxSettings.Dialogs row picture widths
ICON_BASE = 0xE000

BEDS = {"WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY", "LIGHT_GRAY", "CYAN",
        "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"}


def render_bed(tex, dark=1.0):
    """The real bed item: both halves of the bed model, textured from entity/bed/<color>.png and drawn like the
    inventory icon (30 degrees down, turned 160), at 64 px so the 16 px glyph stays sharp. The last column carries an
    almost invisible pixel so every bed glyph is exactly 16 wide."""
    import math
    S = 64
    yaw, pitch = math.radians(45), math.radians(30)

    def project(p):
        x, y, z = p[0] - 8, p[1] - 4.5, p[2] - 16
        x, z = x * math.cos(yaw) + z * math.sin(yaw), -x * math.sin(yaw) + z * math.cos(yaw)
        y, z = y * math.cos(pitch) - z * math.sin(pitch), y * math.sin(pitch) + z * math.cos(pitch)
        return x, y, z

    def texel(u, v, light):
        r, g, b, a = tex.getpixel((u, v))
        f = dark * light
        return (int(r * f), int(g * f), int(b * f), a)

    def turn(n):  # rotate a direction without moving it: (screen x, toward the viewer when > 0)
        x, y, z = n
        x, z = x * math.cos(yaw) + z * math.sin(yaw), -x * math.sin(yaw) + z * math.cos(yaw)
        return x, y * math.sin(pitch) + z * math.cos(pitch)

    def light(n):  # inventory lighting: top full, the face turned left a little darker, the one turned right darker
        return 1.0 if n[1] > 0 else 0.8 if turn(n)[0] < 0 else 0.6

    # Faces: origin, u axis, v axis (unit steps in model pixels), texture origin, size in texels, outward normal,
    # and whether the texture runs along the bed's length on its v axis. y is up.
    faces = []
    for half, (tv, z0) in enumerate(((0, 0), (22, 16))):
        top = 9
        faces.append(((0, top, z0), (1, 0, 0), (0, 0, 1), (6, tv + 6), (16, 16), (0, 1, 0), False))          # sleeping surface
        faces.append(((0, top, z0), (0, 0, 1), (0, -1, 0), (0, tv + 6), (16, 6), (-1, 0, 0), True))         # left side
        faces.append(((16, top, z0 + 16), (0, 0, -1), (0, -1, 0), (22, tv + 6), (16, 6), (1, 0, 0), True))  # right side
        if half == 0:
            faces.append(((16, top, 0), (-1, 0, 0), (0, -1, 0), (6, 0), (16, 6), (0, 0, -1), False))        # head end
        else:
            faces.append(((0, top, 32), (1, 0, 0), (0, -1, 0), (22, 22), (16, 6), (0, 0, 1), False))        # foot end
    # The four 3x3x3 legs under the corners, textured from their own boxes in the bed texture (x 50-62).
    for x0, z0, lu, lv in ((0, 0, 50, 6), (13, 0, 50, 18), (0, 29, 50, 0), (13, 29, 50, 12)):
        faces.append(((x0 + 3, 3, z0), (-1, 0, 0), (0, -1, 0), (lu + 3, lv + 3), (3, 3), (0, 0, -1), False))
        faces.append(((x0, 3, z0 + 3), (1, 0, 0), (0, -1, 0), (lu + 9, lv + 3), (3, 3), (0, 0, 1), False))
        faces.append(((x0, 3, z0), (0, 0, 1), (0, -1, 0), (lu, lv + 3), (3, 3), (-1, 0, 0), False))
        faces.append(((x0 + 3, 3, z0 + 3), (0, 0, -1), (0, -1, 0), (lu + 6, lv + 3), (3, 3), (1, 0, 0), False))
    raw = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(raw)
    # Fill the icon like the inventory model does: fit the whole bed, 2 px from the edges.
    corners = [project((x, y, z)) for x in (0, 16) for y in (0, 9) for z in (0, 32)]
    xs, ys = [p[0] for p in corners], [-p[1] for p in corners]
    scale = (S - 4) / max(max(xs) - min(xs), max(ys) - min(ys))
    cx, cy = S / 2 - (max(xs) + min(xs)) / 2 * scale, S / 2 + (max(ys) + min(ys)) / 2 * scale
    drawn = []
    for face in faces:
        (ox, oy, oz), (ux, uy, uz), (vx, vy, vz), (tu, tv), (w, h), normal, side = face
        if turn(normal)[1] <= 0:
            continue
        depth = project((ox + ux * w / 2 + vx * h / 2, oy + uy * w / 2 + vy * h / 2, oz + uz * w / 2 + vz * h / 2))[2]
        drawn.append((depth, face, side))
    for depth, face, side in sorted(drawn, key=lambda f: f[0]):
        (ox, oy, oz), (ux, uy, uz), (vx, vy, vz), (tu, tv), (w, h), normal = face[:6]
        for i in range(w):
            for j in range(h):
                # Side textures run along the bed's length on their v axis; swap so u walks the length.
                u, v = (tu + j, tv + i) if side else (tu + i, tv + j)
                color = texel(u, v, light(normal))
                if color[3] == 0:
                    continue
                quad = []
                for di, dj in ((0, 0), (1, 0), (1, 1), (0, 1)):
                    p = project((ox + ux * (i + di) + vx * (j + dj), oy + uy * (i + di) + vy * (j + dj), oz + uz * (i + di) + vz * (j + dj)))
                    quad.append((cx + p[0] * scale, cy - p[1] * scale))
                d.polygon(quad, fill=color)
    raw.putpixel((S - 1, S - 1), (0, 0, 0, 1))
    return raw


def hline(d, x0, x1, y, color):
    d.line([x0, y, x1, y], fill=color)


def vline(d, x, y0, y1, color):
    d.line([x, y0, x, y1], fill=color)


def inset(d, x, y, w, h, fill):
    """Minecraft slot: dark top/left edge, white bottom/right edge."""
    d.rectangle([x, y, x + w - 1, y + h - 1], fill=fill)
    hline(d, x, x + w - 2, y, WELL_DARK)
    vline(d, x, y, y + h - 2, WELL_DARK)
    hline(d, x + 1, x + w - 1, y + h - 1, WHITE)
    vline(d, x + w - 1, y + 1, y + h - 1, WHITE)


def cell(kind, column, edge):
    """kind 0: name row, 1: action row (2 / 3: the same for a locked slot). One home is one raised card over both
    rows: the name row draws its top, sides and icon slot and runs on through the 2 px gap, the action row draws the
    rest and its bottom edge. column 0 first, 1 middle, 2 last; the first and last columns carry the window's left
    and right edges. edge: first row (name, gets the window's top edge 3 px above the button) / last row (action).
    The dialog clips just under its last button, so the last row closes the window inside the button (y=19)."""
    look = LOCKED if kind >= 2 else OPEN
    name_row = kind % 2 == 0
    width = PAD + CW + GAP if column == 0 else CW + GAP if column == 1 else CW + PAD
    cx = PAD if column == 0 else 0
    top = TOP if (name_row and edge) else 0
    height = (END_H if (not name_row and edge) else ROW_H) + top
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, top, width - 1, height - 1], fill=WELL)
    x0, x1 = cx, cx + CW - 1
    y0, y1 = (top, height - 1) if name_row else (0, 17 if edge else 19)
    d.rectangle([x0, y0, x1, y1], fill=look["face"])
    if look["hatch"]:
        for y in range(y0, y1 + 1):
            card_y = (y - top) if name_row else (y + ROW_H)
            for x in range(x0 + 1, x1):
                if (x - x0 - card_y) % 6 == 0:
                    img.putpixel((x, y), look["hatch"])
    vline(d, x0, y0, y1 if name_row else y1 - 1, look["light"])
    vline(d, x1, y0 + (1 if name_row else 0), y1, look["dark"])
    if name_row:
        hline(d, x0, x1 - 1, y0, look["light"])
        inset(d, cx + 5, top + 1, 18, 18, look["slot"])  # under the 16 px icon at x 6, y 2
    else:
        hline(d, x0 + 1, x1, y1, look["dark"])
    if column == 0:
        vline(d, 0, top, height - 1, BLACK)
        vline(d, 1, top, height - 1, FACE)
        vline(d, 2, top, height - 1, WELL_DARK)
    if column == 2:
        vline(d, width - 3, top, height - 1, WHITE)
        vline(d, width - 2, top, height - 1, FACE)
        vline(d, width - 1, top, height - 1, BLACK)
    if top:
        hline(d, 0, width - 1, 0, BLACK)
        hline(d, 0, width - 1, 1, FACE)
        hline(d, 0, width - 1, 2, WELL_DARK)
        if column == 0:
            vline(d, 0, 0, 2, BLACK)
            d.point((1, 2), fill=FACE)
        if column == 2:
            vline(d, width - 1, 0, 2, BLACK)
            d.point((width - 2, 2), fill=FACE)
            d.point((width - 3, 2), fill=WHITE)
    if not name_row and edge:
        hline(d, 0, width - 1, height - 2, FACE)
        hline(d, 0, width - 1, height - 1, BLACK)
        if column == 0:
            d.point((0, height - 2), fill=BLACK)
        if column == 2:
            d.point((width - 1, height - 2), fill=BLACK)
    return img


def list_end(width, kind):
    """The closing row of a list (Manage Home, Delete Home, /settings): empty well, then the window's bottom edge.
    kind: 'a' / 'b' one-column list halves, 'l' / 'r' the /settings left and right column."""
    img = Image.new("RGBA", (width, END_H), WELL)
    d = ImageDraw.Draw(img)
    hline(d, 0, width - 1, END_H - 3, WHITE)
    hline(d, 0, width - 1, END_H - 2, FACE)
    hline(d, 0, width - 1, END_H - 1, BLACK)
    if kind in ("a", "l"):
        vline(d, 0, 0, END_H - 1, BLACK)
    if kind == "l":
        vline(d, 1, 0, END_H - 2, FACE)
        vline(d, 2, 0, END_H - 4, WELL_DARK)
    if kind == "b":
        vline(d, width - 3, 0, END_H - 3, WHITE)
        vline(d, width - 2, 0, END_H - 2, FACE)
        vline(d, width - 1, 0, END_H - 1, BLACK)
    return img


def button(kind):
    """on: gold (Manage, Teleport, Back...), add: mint (+ Set Here), locked: stone (Unlock), off: a recessed slot."""
    img = Image.new("RGBA", (BTN_W, BTN_H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if kind == "off":
        inset(d, 0, 0, BTN_W, BTN_H, (176, 176, 176, 255))
        return img
    face, light, dark = {"on": (GOLD, GOLD_LIGHT, GOLD_DARK), "add": (MINT, MINT_LIGHT, MINT_DARK),
                         "locked": (STONE, STONE_LIGHT, STONE_DARK)}[kind]
    d.rectangle([0, 0, BTN_W - 1, BTN_H - 1], fill=BLACK)
    d.rectangle([1, 1, BTN_W - 2, BTN_H - 2], fill=face)
    hline(d, 1, BTN_W - 3, 1, light)
    vline(d, 1, 1, BTN_H - 3, light)
    hline(d, 2, BTN_W - 2, BTN_H - 2, dark)
    vline(d, BTN_W - 2, 2, BTN_H - 2, dark)
    return img


def big_button(yes):
    """A whole 148x20 dialog button: green Yes / red No, black outline, bevelled face."""
    face, light, dark = (MINT, MINT_LIGHT, MINT_DARK) if yes else (RED, RED_LIGHT, RED_DARK)
    img = Image.new("RGBA", (BIG_W, BIG_H), BLACK)
    d = ImageDraw.Draw(img)
    d.rectangle([1, 1, BIG_W - 2, BIG_H - 2], fill=face)
    hline(d, 1, BIG_W - 3, 1, light)
    vline(d, 1, 1, BIG_H - 3, light)
    hline(d, 2, BIG_W - 2, BIG_H - 2, dark)
    vline(d, BIG_W - 2, 2, BIG_H - 2, dark)
    hline(d, 2, BIG_W - 3, 2, light)  # a second highlight row: the big buttons look raised
    for x, y in ((0, 0), (BIG_W - 1, 0), (0, BIG_H - 1), (BIG_W - 1, BIG_H - 1)):
        img.putpixel((x, y), (0, 0, 0, 0))
    return img


def pixels(rows, color):
    img = Image.new("RGBA", (len(rows[0]), len(rows)), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == "#":
                img.putpixel((x, y), color)
    return img


def mark(check):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if check:
        d.line([(2, 8), (6, 12), (14, 3)], fill=(76, 196, 92, 255), width=3)
    else:
        d.line([(3, 3), (13, 13)], fill=(226, 64, 58, 255), width=3)
        d.line([(13, 3), (3, 13)], fill=(226, 64, 58, 255), width=3)
    img.putpixel((15, 15), (0, 0, 0, 1))  # keeps the glyph exactly 16 wide
    return img


def bitmap(file, ascent, height, chars):
    return {"type": "bitmap", "file": file, "ascent": ascent, "height": height, "chars": chars}


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=True), encoding="utf-8")


def main():
    TEX.mkdir(parents=True, exist_ok=True)
    # Bed renders and icon widths come from a client jar.
    jars = [p for p in (*Path.home().glob("AppData/Roaming/ModrinthApp/meta/versions/1.21.11*/*.jar"),
                        *Path.home().glob("AppData/Roaming/.minecraft/versions/1.21.11*/*.jar")) if p.stat().st_size > 20_000_000]
    if not jars:
        raise SystemExit("Need a 1.21.11 client jar (Modrinth or the vanilla launcher) for the bed textures and icon widths")
    client = jars[0]
    jar = zipfile.ZipFile(client)

    def bed_texture(color):
        return Image.open(io.BytesIO(jar.read(f"assets/minecraft/textures/entity/bed/{color.lower()}.png"))).convert("RGBA")

    providers = []

    def add(name, img, ascent, char):
        img.save(TEX / f"{name}.png")
        providers.append(bitmap(f"taxsmp:h/{name}.png", ascent, img.height, [chr(char)]))

    for kind in range(4):
        for column in range(3):
            for edge in range(2):
                img = cell(kind, column, edge)
                add(f"cell_{kind}{column}{edge}", img, 13 + (img.height - (END_H if kind % 2 and edge else ROW_H)),
                    CELL_BASE + kind * 6 + column * 2 + edge)
    add("btn_on", button("on"), 11, BTN_ON)    # top at y=2
    add("btn_off", button("off"), 11, BTN_OFF)
    add("btn_add", button("add"), 11, BTN_ADD)
    add("btn_locked", button("locked"), 11, BTN_LOCKED)
    add("big_yes", big_button(True), 13, BIG_YES)  # top at y=0, covers the whole button
    add("big_no", big_button(False), 13, BIG_NO)
    gear = ["..#.#..", ".#####.", "##...##", ".#...#.", "##...##", ".#####.", "..#.#.."]
    add("gear", pixels(gear, GOLD_INK), 7, GEAR)  # top at y=6, on the gold button
    lock = ["..###..", ".#...#.", ".#...#.", "#######", "###.###", "###.###", "#######"]
    add("lock", pixels(lock, GOLD), 7, LOCK)      # on the stone button
    # Left edge for the one-column lists (Manage Home, Delete Home), which reuse the /settings right-column rows.
    add("vborder", Image.new("RGBA", (1, ROW_H), BLACK), 13, VBORDER)
    add("vborder_last", Image.new("RGBA", (1, ROW_H + 1), BLACK), 13, VBORDER_LAST)
    add("end_a", list_end(R_PIC, "a"), 13, END_A)
    add("end_b", list_end(R_PIC, "b"), 13, END_B)
    add("end_l", list_end(L_PIC, "l"), 13, END_L)
    add("end_r", list_end(R_PIC, "r"), 13, END_R)
    add("check", mark(True), 11, CHECK)
    add("cross", mark(False), 11, CROSS)
    locked = render_bed(bed_texture("red"), dark=0.45)
    locked.save(TEX / "bed_locked.png")
    providers.append(bitmap("taxsmp:h/bed_locked.png", 11, 16, [chr(BED_LOCKED)]))  # 64 px drawn at 16
    write_json(ASSETS / "font/hm.json", {"providers": providers})

    # Every icon is copied into two sheets of our own (16 px items, 64 px beds) instead of pointing at the vanilla
    # textures: newer clients renamed some of those, and a sheet is two font entries instead of 1504. The client sizes
    # a glyph by its last non-empty column, so the plugin gets each icon's width from home_icon_widths.txt.
    materials = []
    for line in SPRITES.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#"):
            material, texture = line.split(" ", 1)
            materials.append((material, texture.strip()))
    cols, rows = 32, (len(materials) + 31) // 32
    items = Image.new("RGBA", (cols * 16, rows * 16), (0, 0, 0, 0))
    item_chars = [["\u0000"] * cols for _ in range(rows)]
    beds = [m for m, _ in materials if m.endswith("_BED") and m[:-4] in BEDS]
    bed_sheet = Image.new("RGBA", (4 * 64, ((len(beds) + 3) // 4) * 64), (0, 0, 0, 0))
    bed_chars = [["\u0000"] * 4 for _ in range((len(beds) + 3) // 4)]
    widths = []
    with jar:
        for index, (material, texture) in enumerate(materials):
            char = chr(ICON_BASE + index)
            if material in beds:
                slot = beds.index(material)
                bed_sheet.alpha_composite(render_bed(bed_texture(material[:-4])), ((slot % 4) * 64, (slot // 4) * 64))
                bed_chars[slot // 4][slot % 4] = char
                widths.append(16)
                continue
            try:
                img = Image.open(io.BytesIO(jar.read(f"assets/minecraft/textures/{texture}.png"))).convert("RGBA")
                img = img.crop((0, 0, img.width, img.width)).resize((16, 16), Image.NEAREST)  # first frame of animations
            except KeyError:
                img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
            items.alpha_composite(img, ((index % cols) * 16, (index // cols) * 16))
            item_chars[index // cols][index % cols] = char
            widths.append(max((x + 1 for x in range(16) for y in range(16) if img.getpixel((x, y))[3]), default=0))
    items.save(TEX / "icons.png")
    bed_sheet.save(TEX / "beds.png")
    write_json(ASSETS / "font/hi.json", {"providers": [
        bitmap("taxsmp:h/icons.png", 11, 16, ["".join(r) for r in item_chars]),
        bitmap("taxsmp:h/beds.png", 11, 16, ["".join(r) for r in bed_chars]),  # 64 px drawn at 16
    ]})
    (ROOT / "tools/home_icon_widths.txt").write_text("\n".join(map(str, widths)) + "\n")
    print(f"homes dialog: font taxsmp:hm with {len(providers)} pictures, taxsmp:hi with {len(materials)} icons in 2 sheets (from {client.name})")


if __name__ == "__main__":
    main()
