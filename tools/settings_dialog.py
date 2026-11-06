"""Generate the /settings dialog look: font taxsmp:dlg plus the two text fonts taxsmp:dt / taxsmp:dd.

com.taxsmp.settings.TaxSettings shows /settings as a dialog with two columns of buttons: categories on the
left (130 wide), settings on the right (300 wide). Every button's label has a total width of 0 and moves the
pen with space characters, so its pictures land at exact spots relative to the button: a row picture that
covers the vanilla button, a 16 px item icon, the name and a grey description on two lines, and a switch.

A button label's text line starts 6 px below the button's top, and a picture's top edge is at
(6 + 7 - ascent), so ascent = 13 - y puts it at y inside the button. Buttons are 20 px tall with 2 px
between them, so row pictures are 22 px tall to join up.

TaxSMP look (Nov 2026): a silver window (black outline, silver rim) around a darker inset well; every row is a
raised silver plate in that well, the selected category is a gold plate, headers are silver plates in a gold
ring, and the switches are little ON / OFF / SOON badges. Text is dark ink (TaxSettings.Dialogs colours).

Keep characters and sizes in sync with TaxSettings.Dialogs.

    python tools/settings_dialog.py
"""
import io
import json
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).absolute().parent.parent
ASSETS = ROOT / "pack/assets/taxsmp"
TEX = ASSETS / "textures/s"

LEFT_W, RIGHT_W, ROW_H = 130, 300, 22
# Pictures start 3 px left of the button and run 3 px past it, so no edge of the vanilla button shows.
PAD = 3
TOP = 3  # header rows reach 3 px above their button for the window's top edge
PLATE_BOTTOM = 20  # a row's plate covers the 20 px button and 1 px of the gap, so descenders clear its bottom edge
L_PIC, R_PIC = LEFT_W + 2 + PAD, RIGHT_W // 2 + PAD

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
RED = (201, 74, 74, 255)
RED_LIGHT = (240, 128, 128, 255)
RED_DARK = (122, 31, 31, 255)
STONE = (96, 96, 102, 255)
STONE_LIGHT = (130, 130, 138, 255)
STONE_DARK = (52, 52, 58, 255)

# Pictures (each the char for one bitmap provider).
L_ROW, L_SEL, L_HEAD = 0xE400, 0xE401, 0xE402
R_ROW_A, R_ROW_B, R_HEAD_A, R_HEAD_B = 0xE410, 0xE411, 0xE412, 0xE413
L_BOTTOM, R_BOTTOM_A, R_BOTTOM_B = 0xE420, 0xE421, 0xE422
SW_ON, SW_OFF, SW_SOON = 0xE430, 0xE431, 0xE432
ICON_BASE = 0xE500
MOVE_LEFT = {2 ** i: chr(0xE140 + i) for i in range(10)}
MOVE_RIGHT = {2 ** i: chr(0xE150 + i) for i in range(10)}

# Icons -> vanilla texture. Only flat item/block textures (no animated or entity-drawn ones).
ICONS = [
    "item/oak_sign", "item/bell", "item/ender_pearl", "block/glowstone", "item/netherite_sword", "item/spyglass",
    "item/emerald", "item/comparator", "item/painting", "item/barrier", "item/writable_book", "item/iron_chestplate",
    "item/paper", "item/name_tag", "item/gold_nugget", "block/target_side", "item/gold_ingot", "item/hopper",
    "block/barrel_side", "item/compass_00", "item/clock_00", "item/feather", "item/ender_eye", "item/map",
    "block/red_wool", "item/blaze_powder", "item/firework_rocket", "block/note_block", "block/glass", "block/spawner",
    "item/shulker_shell", "item/bone", "item/iron_sword", "item/end_crystal", "item/chainmail_helmet",
    "block/gold_block", "block/emerald_block", "item/book", "item/glowstone_dust", "item/rotten_flesh",
    "item/potion", "item/lime_candle", "item/music_disc_cat", "item/white_dye", "item/golden_apple",
    "item/nautilus_shell", "block/jukebox_side", "item/diamond", "item/egg", "item/trial_key", "item/diamond_sword",
    "item/totem_of_undying", "item/arrow",
]

ASCII_ROWS = ["\u0000" * 16] * 2 + [
    "\u0000!\"#$%&'()*+,-./", "0123456789:;<=>?", "@ABCDEFGHIJKLMNO",
    "PQRSTUVWXYZ[\\]^_", "`abcdefghijklmno", "pqrstuvwxyz{|}~\u0000",
] + ["\u0000" * 16] * 8

# 3x5 / 4x5 pixel letters for the badges.
TINY = {
    "O": [".#.", "#.#", "#.#", "#.#", ".#."],
    "N": ["#..#", "##.#", "#.##", "#..#", "#..#"],
    "F": ["###", "#..", "##.", "#..", "#.."],
    "S": ["###", "#..", "###", "..#", "###"],
}


def vline(d, x, y0, y1, color):
    d.line([x, y0, x, y1], fill=color)


def hline(d, x0, x1, y, color):
    d.line([x0, y, x1, y], fill=color)


def plate(d, x0, x1, y0, y1, face=FACE, light=WHITE, dark=SHADOW, left=True, right=True, outline=None):
    """A raised plate from x0..x1, y0..y1 (either side may be cut off where the picture is split in halves)."""
    if outline:
        d.rectangle([x0, y0, x1, y1], fill=outline)
        y0, y1 = y0 + 1, y1 - 1
        x0 = x0 + 1 if left else x0
        x1 = x1 - 1 if right else x1
    d.rectangle([x0, y0, x1, y1], fill=face)
    hline(d, x0, x1 - (1 if right else 0), y0, light)
    hline(d, x0 + (1 if left else 0), x1, y1, dark)
    if left:
        vline(d, x0, y0, y1 - 1, light)
    if right:
        vline(d, x1, y0 + 1, y1, dark)


def ring_plate(d, x0, x1, y0, y1, left=True, right=True):
    """Header: silver plate inside a black / gold ring."""
    d.rectangle([x0, y0, x1, y1], fill=BLACK)
    gx0, gx1 = (x0 + 1 if left else x0), (x1 - 1 if right else x1)
    d.rectangle([gx0, y0 + 1, gx1, y1 - 1], fill=GOLD)
    hline(d, gx0, gx1, y0 + 1, GOLD_LIGHT)
    hline(d, gx0, gx1, y1 - 1, GOLD_DARK)
    fx0, fx1 = (x0 + 2 if left else x0), (x1 - 2 if right else x1)
    d.rectangle([fx0, y0 + 2, fx1, y1 - 2], fill=FACE)
    hline(d, fx0, fx1, y0 + 2, WHITE)


def left_pads(d, y0, y1):
    vline(d, 0, y0, y1, BLACK)
    vline(d, 1, y0, y1, FACE)
    vline(d, 2, y0, y1, WELL_DARK)


def right_pads(d, x, y0, y1):
    vline(d, x, y0, y1, WHITE)
    vline(d, x + 1, y0, y1, FACE)
    vline(d, x + 2, y0, y1, BLACK)


def top_frame(d, width, left_edge=False, right_edge=False):
    """Rows 0..2 of a header picture: the window's top edge, then the well's dark top edge."""
    hline(d, 0, width - 1, 0, BLACK)
    hline(d, 0, width - 1, 1, FACE)
    hline(d, 0, width - 1, 2, WELL_DARK)
    if left_edge:
        vline(d, 0, 0, 2, BLACK)
        d.point((1, 2), fill=FACE)
    if right_edge:
        vline(d, width - 1, 0, 2, BLACK)
        d.point((width - 2, 2), fill=FACE)
        d.point((width - 3, 2), fill=WHITE)


def left_picture(kind):
    """kind: row / sel / head."""
    head = kind == "head"
    oy = TOP if head else 0
    img = Image.new("RGBA", (L_PIC, ROW_H + oy), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if head:
        top_frame(d, L_PIC, left_edge=True)
    d.rectangle([3, oy, L_PIC - 1, oy + ROW_H - 1], fill=WELL)
    left_pads(d, oy, oy + ROW_H - 1)
    x0, x1 = PAD, PAD + LEFT_W - 1
    if head:
        ring_plate(d, x0, x1, oy, oy + ROW_H - 1)
    elif kind == "sel":
        plate(d, x0, x1, 0, PLATE_BOTTOM, GOLD, GOLD_LIGHT, GOLD_DARK, outline=BLACK)
        for j, row in enumerate(["#...", "##..", "###.", "####", "###.", "##..", "#..."]):
            for i, ch in enumerate(row):
                if ch == "#":
                    d.point((x1 - 10 + i, 7 + j), fill=GOLD_INK)
    else:
        plate(d, x0, x1, 0, PLATE_BOTTOM)
    return img


def right_picture(half, head=False):
    """half: 'a' (button x -3..149, left edge) or 'b' (150..302, right edge)."""
    oy = TOP if head else 0
    img = Image.new("RGBA", (R_PIC, ROW_H + oy), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if head:
        top_frame(d, R_PIC, right_edge=half == "b")
        if half == "a":
            d.point((0, 1), fill=(0, 0, 0, 0))
            d.point((0, 2), fill=(0, 0, 0, 0))
    if half == "a":
        # x 0 overlaps the left column's plate edge: leave it clear. x 1..2 are the 2 px column gap.
        d.rectangle([1, oy, R_PIC - 1, oy + ROW_H - 1], fill=WELL)
        x0, x1, left, right = PAD, R_PIC - 1, True, False
    else:
        d.rectangle([0, oy, R_PIC - PAD - 1, oy + ROW_H - 1], fill=WELL)
        right_pads(d, R_PIC - PAD, oy, oy + ROW_H - 1)
        x0, x1, left, right = 0, R_PIC - PAD - 1, False, True
    if head:
        ring_plate(d, x0, x1, oy, oy + ROW_H - 1, left=left, right=right)
    else:
        plate(d, x0, x1, 0, PLATE_BOTTOM, left=left, right=right)
    return img


def bottom(width, half):
    """3 px under the last row: the well's light bottom edge, the silver rim and the black outline."""
    img = Image.new("RGBA", (width, 3), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    hline(d, 0, width - 1, 0, WHITE)
    hline(d, 0, width - 1, 1, FACE)
    hline(d, 0, width - 1, 2, BLACK)
    if half in ("l", "a"):
        vline(d, 0, 0, 2, BLACK)
        d.point((1, 0), fill=FACE)
    if half == "b":
        vline(d, width - 1, 0, 2, BLACK)
        d.point((width - 2, 0), fill=FACE)
    return img


def badge(state):
    """The switch: a 20x10 stamped chip with tiny letters. ON mint, OFF red, SOON grey."""
    label, face, light, dark = {
        "on": ("ON", MINT, MINT_LIGHT, MINT_DARK),
        "off": ("OFF", RED, RED_LIGHT, RED_DARK),
        "soon": ("SOON", STONE, STONE_LIGHT, STONE_DARK),
    }[state]
    img = Image.new("RGBA", (20, 10), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    plate(d, 0, 19, 0, 9, face, light, dark, outline=BLACK)
    glyphs = [TINY[c] for c in label]
    width = sum(len(g[0]) for g in glyphs) + len(glyphs) - 1
    x = (20 - width) // 2
    for g in glyphs:
        for j, row in enumerate(g):
            for i, ch in enumerate(row):
                if ch == "#":
                    d.point((x + i + 1, 3 + j), fill=dark)   # shadow
        for j, row in enumerate(g):
            for i, ch in enumerate(row):
                if ch == "#":
                    d.point((x + i, 2 + j), fill=WHITE)
        x += len(g[0]) + 1
    return img


def bitmap(file, ascent, height, chars):
    return {"type": "bitmap", "file": file, "ascent": ascent, "height": height, "chars": chars}


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=True), encoding="utf-8")


def main():
    TEX.mkdir(parents=True, exist_ok=True)
    pictures = {
        "d_lrow": (left_picture("row"), L_ROW, 13),
        "d_lsel": (left_picture("sel"), L_SEL, 13),
        "d_lhead": (left_picture("head"), L_HEAD, 13 + TOP),
        "d_rrow_a": (right_picture("a"), R_ROW_A, 13),
        "d_rrow_b": (right_picture("b"), R_ROW_B, 13),
        "d_rhead_a": (right_picture("a", head=True), R_HEAD_A, 13 + TOP),
        "d_rhead_b": (right_picture("b", head=True), R_HEAD_B, 13 + TOP),
    }
    providers = []
    for name, (img, char, ascent) in pictures.items():
        img.save(TEX / f"{name}.png")
        providers.append(bitmap(f"taxsmp:s/{name}.png", ascent, img.height, [chr(char)]))
    # Bottom edge, right under the last row.
    for name, width, half, char in (("d_lbot", L_PIC, "l", L_BOTTOM), ("d_rbot_a", R_PIC, "a", R_BOTTOM_A), ("d_rbot_b", R_PIC, "b", R_BOTTOM_B)):
        bottom(width, half).save(TEX / f"{name}.png")
        providers.append(bitmap(f"taxsmp:s/{name}.png", 13 - ROW_H, 3, [chr(char)]))
    for name, state, char in (("d_on", "on", SW_ON), ("d_off", "off", SW_OFF), ("d_soon", "soon", SW_SOON)):
        badge(state).save(TEX / f"{name}.png")
        providers.append(bitmap(f"taxsmp:s/{name}.png", 8, 10, [chr(char)]))  # top at y=5
    for i, path in enumerate(ICONS):
        providers.append(bitmap(f"minecraft:{path}.png", 11, 16, [chr(ICON_BASE + i)]))  # top at y=2
    providers.append({"type": "space", "advances": {ch: -n for n, ch in MOVE_LEFT.items()} | {ch: n for n, ch in MOVE_RIGHT.items()}})
    write_json(ASSETS / "font/dlg.json", {"providers": providers})

    # Name line (top at y=4) and description line (top at y=12): one pixel higher than before so descenders
    # stay on the plate. A bitmap font's ascent may not be larger than its height (Minecraft drops the whole
    # font and draws boxes), so the name font uses a copy of ascii.png with every 8 px glyph row padded to 9 px.
    jar = next(p for p in (*Path.home().glob("AppData/Roaming/ModrinthApp/meta/versions/1.21.11*/*.jar"),
                           *Path.home().glob("AppData/Roaming/.minecraft/versions/1.21.11*/*.jar")) if p.stat().st_size > 20_000_000)
    with zipfile.ZipFile(jar) as z:
        ascii_png = Image.open(io.BytesIO(z.read("assets/minecraft/textures/font/ascii.png"))).convert("RGBA")
    padded = Image.new("RGBA", (ascii_png.width, 16 * 9), (0, 0, 0, 0))
    for r in range(16):
        padded.alpha_composite(ascii_png.crop((0, r * 8, ascii_png.width, r * 8 + 8)), (0, r * 9))
    (ASSETS / "textures/font").mkdir(parents=True, exist_ok=True)
    padded.save(ASSETS / "textures/font/ascii_dt.png")
    write_json(ASSETS / "font/dt.json", {"providers": [
        {"type": "space", "advances": {" ": 4}},
        bitmap("taxsmp:font/ascii_dt.png", 9, 9, ASCII_ROWS),
    ]})
    write_json(ASSETS / "font/dd.json", {"providers": [
        {"type": "space", "advances": {" ": 4}},
        bitmap("minecraft:font/ascii.png", 1, 8, ASCII_ROWS),
    ]})
    (ROOT / "tools/settings_dialog_icons.txt").write_text("\n".join(f"{ICON_BASE + i:04X} {p}" for i, p in enumerate(ICONS)) + "\n")
    print(f"settings dialog: font taxsmp:dlg with {len(ICONS)} icons, fonts taxsmp:dt / taxsmp:dd")


if __name__ == "__main__":
    main()
