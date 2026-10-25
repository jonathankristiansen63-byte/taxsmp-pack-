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
PANEL = (27, 28, 32, 255)
LINE = (44, 45, 51, 255)
DIVIDER = (50, 51, 58, 255)
BORDER = (61, 142, 216, 255)

# Pictures in taxsmp:hm.
CELL_BASE = 0xE600  # + kind * 6 + column * 2 + edge  (kind 0 = name row, 1 = action row)
BTN_ON, BTN_OFF, GEAR, LOCK, CHECK, CROSS, BED_LOCKED = 0xE620, 0xE621, 0xE622, 0xE623, 0xE624, 0xE625, 0xE626
VBORDER, VBORDER_LAST = 0xE627, 0xE628
END_A, END_B, END_L, END_R = 0xE629, 0xE62A, 0xE62B, 0xE62C  # list ends: list halves, /settings left and right column
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


def cell(kind, column, edge):
    """kind 0: name row, 1: action row. column 0 first, 1 middle, 2 last. edge: top row (name) / last row (action).
    The dialog clips just under its last button, so the last row closes its box inside the button (y=19)."""
    left, width = (PAD, PAD + CW + GAP) if column == 0 else (0, CW + GAP) if column == 1 else (0, CW + PAD)
    height = END_H if kind == 1 and edge else ROW_H
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, width - 1, height - 1], fill=PANEL)
    if column != 2:
        d.line([left + CW + 1, 0, left + CW + 1, height - 1], fill=DIVIDER)
    if kind == 1:
        d.line([0, height - 1, width - 1, height - 1], fill=BORDER if edge else LINE)
    if kind == 0 and edge:
        d.line([0, 0, width - 1, 0], fill=BORDER)
    if column == 0:
        d.line([0, 0, 0, height - 1], fill=BORDER)
    if column == 2:
        d.line([width - 1, 0, width - 1, height - 1], fill=BORDER)
    return img


def list_end(width, left_border=False, right_border=False, divider=None):
    """The closing row of a list (Manage Home, Delete Home, /settings): empty panel, blue on three sides."""
    img = Image.new("RGBA", (width, END_H), PANEL)
    d = ImageDraw.Draw(img)
    if divider is not None:
        d.line([divider, 0, divider, END_H - 2], fill=DIVIDER)
    d.line([0, END_H - 1, width - 1, END_H - 1], fill=BORDER)
    if left_border:
        d.line([0, 0, 0, END_H - 1], fill=BORDER)
    if right_border:
        d.line([width - 1, 0, width - 1, END_H - 1], fill=BORDER)
    return img


def button(on):
    img = Image.new("RGBA", (BTN_W, BTN_H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if on:
        d.rectangle([0, 0, BTN_W - 1, BTN_H - 1], fill=(50, 52, 60, 255), outline=(82, 84, 94, 255))
        d.line([1, 1, BTN_W - 2, 1], fill=(64, 66, 76, 255))
    else:
        d.rectangle([0, 0, BTN_W - 1, BTN_H - 1], fill=(31, 32, 37, 255), outline=(43, 44, 50, 255))
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

    for kind in range(2):
        for column in range(3):
            for edge in range(2):
                add(f"cell_{kind}{column}{edge}", cell(kind, column, edge), 13, CELL_BASE + kind * 6 + column * 2 + edge)
    add("btn_on", button(True), 11, BTN_ON)    # top at y=2
    add("btn_off", button(False), 11, BTN_OFF)
    gear = ["..#.#..", ".#####.", "##...##", ".#...#.", "##...##", ".#####.", "..#.#.."]
    add("gear", pixels(gear, (236, 236, 240, 255)), 7, GEAR)  # top at y=6
    lock = ["..###..", ".#...#.", ".#...#.", "#######", "###.###", "###.###", "#######"]
    add("lock", pixels(lock, (122, 124, 132, 255)), 7, LOCK)
    # Left border for the one-column lists (Manage Home, Delete Home), which reuse the /settings right-column rows.
    add("vborder", Image.new("RGBA", (1, ROW_H), BORDER), 13, VBORDER)
    add("vborder_last", Image.new("RGBA", (1, ROW_H + 1), BORDER), 13, VBORDER_LAST)
    add("end_a", list_end(R_PIC, left_border=True), 13, END_A)
    add("end_b", list_end(R_PIC, right_border=True), 13, END_B)
    add("end_l", list_end(L_PIC, left_border=True, divider=130 + PAD), 13, END_L)
    add("end_r", list_end(R_PIC), 13, END_R)  # /settings right column, left half: the left column draws the divider
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
