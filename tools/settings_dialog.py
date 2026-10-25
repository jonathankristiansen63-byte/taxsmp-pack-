"""Generate the /settings dialog look: font taxsmp:dlg plus the two text fonts taxsmp:dt / taxsmp:dd.

com.taxsmp.settings.TaxSettings shows /settings as a dialog with two columns of buttons: categories on the
left (130 wide), settings on the right (300 wide). Every button's label has a total width of 0 and moves the
pen with space characters, so its pictures land at exact spots relative to the button: a dark row that
covers the vanilla button, a 16 px item icon, the name and a grey description on two lines, and a switch.

A button label's text line starts 6 px below the button's top, and a picture's top edge is at
(6 + 7 - ascent), so ascent = 13 - y puts it at y inside the button. Buttons are 20 px tall with 2 px
between them, so row pictures are 22 px tall to join up.

Keep characters and sizes in sync with TaxSettings.Dialogs.

    python tools/settings_dialog.py
"""
import json
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).absolute().parent.parent
ASSETS = ROOT / "pack/assets/taxsmp"
TEX = ASSETS / "textures/s"

LEFT_W, RIGHT_W, ROW_H = 130, 300, 22
# Pictures start 3 px left of the button and run 3 px past it, so no edge of the vanilla button shows.
PAD = 3
L_PIC, R_PIC = LEFT_W + 2 + PAD, RIGHT_W // 2 + PAD
PANEL = (27, 28, 32, 255)
SELECTED = (43, 45, 52, 255)
LINE = (44, 45, 51, 255)
DIVIDER = (50, 51, 58, 255)
BORDER = (61, 142, 216, 255)

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


def row(width, fill, left_border=False, right_border=False, top_border=False, separator=True, bar=False):
    img = Image.new("RGBA", (width, ROW_H), fill)
    d = ImageDraw.Draw(img)
    if separator:
        d.line([0, ROW_H - 1, width - (7 if right_border else 1), ROW_H - 1], fill=LINE)
    if top_border:
        d.line([0, 0, width - 1, 0], fill=BORDER)
    if left_border:
        d.line([0, 0, 0, ROW_H - 1], fill=BORDER)
    if right_border:
        d.line([width - 1, 0, width - 1, ROW_H - 1], fill=BORDER)
    if bar:
        d.rectangle([PAD + 1, 2, PAD + 2, ROW_H - 3], fill=BORDER)
    return img


def switch(on, soon=False):
    img = Image.new("RGBA", (20, 10), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    track, edge = ((74, 176, 108, 255), (52, 138, 82, 255)) if on else ((62, 63, 70, 255), (48, 49, 55, 255))
    if soon:
        track, edge = (40, 41, 46, 255), (34, 35, 39, 255)
    d.rounded_rectangle([0, 0, 19, 9], radius=3, fill=track, outline=edge)
    kx = 11 if on else 1
    d.rectangle([kx, 1, kx + 7, 8], fill=(90, 91, 98, 255) if soon else ((240, 240, 240, 255) if on else (150, 151, 158, 255)))
    return img


def bitmap(file, ascent, height, chars):
    return {"type": "bitmap", "file": file, "ascent": ascent, "height": height, "chars": chars}


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=True), encoding="utf-8")


def main():
    TEX.mkdir(parents=True, exist_ok=True)
    pictures = {
        "d_lrow": (row(L_PIC, PANEL, left_border=True, separator=False), L_ROW),
        "d_lsel": (row(L_PIC, SELECTED, left_border=True, separator=False, bar=True), L_SEL),
        "d_lhead": (row(L_PIC, PANEL, left_border=True, top_border=True), L_HEAD),
        "d_rrow_a": (row(R_PIC, PANEL), R_ROW_A),
        "d_rrow_b": (row(R_PIC, PANEL, right_border=True), R_ROW_B),
        "d_rhead_a": (row(R_PIC, PANEL, top_border=True), R_HEAD_A),
        "d_rhead_b": (row(R_PIC, PANEL, right_border=True, top_border=True), R_HEAD_B),
    }
    # The divider between the columns sits on the left pictures' right edge.
    for name in ("d_lrow", "d_lsel", "d_lhead"):
        ImageDraw.Draw(pictures[name][0]).line([LEFT_W + PAD, 1, LEFT_W + PAD, ROW_H - 1], fill=DIVIDER)
    providers = []
    for name, (img, char) in pictures.items():
        img.save(TEX / f"{name}.png")
        providers.append(bitmap(f"taxsmp:s/{name}.png", 13, ROW_H, [chr(char)]))
    # Bottom border, 1 px under the last row.
    for name, width, char in (("d_lbot", L_PIC, L_BOTTOM), ("d_rbot_a", R_PIC, R_BOTTOM_A), ("d_rbot_b", R_PIC, R_BOTTOM_B)):
        Image.new("RGBA", (width, 1), BORDER).save(TEX / f"{name}.png")
        providers.append(bitmap(f"taxsmp:s/{name}.png", 13 - ROW_H, 1, [chr(char)]))
    for name, img, char in (("d_on", switch(True), SW_ON), ("d_off", switch(False), SW_OFF), ("d_soon", switch(False, True), SW_SOON)):
        img.save(TEX / f"{name}.png")
        providers.append(bitmap(f"taxsmp:s/{name}.png", 8, 10, [chr(char)]))  # top at y=5
    for i, path in enumerate(ICONS):
        providers.append(bitmap(f"minecraft:{path}.png", 11, 16, [chr(ICON_BASE + i)]))  # top at y=2
    providers.append({"type": "space", "advances": {ch: -n for n, ch in MOVE_LEFT.items()} | {ch: n for n, ch in MOVE_RIGHT.items()}})
    write_json(ASSETS / "font/dlg.json", {"providers": providers})

    # Name line (top at y=5) and description line (top at y=13).
    for font, ascent in (("dt", 8), ("dd", 0)):
        write_json(ASSETS / f"font/{font}.json", {"providers": [
            {"type": "space", "advances": {" ": 4}},
            bitmap("minecraft:font/ascii.png", ascent, 8, ASCII_ROWS),
        ]})
    (ROOT / "tools/settings_dialog_icons.txt").write_text("\n".join(f"{ICON_BASE + i:04X} {p}" for i, p in enumerate(ICONS)) + "\n")
    print(f"settings dialog: font taxsmp:dlg with {len(ICONS)} icons, fonts taxsmp:dt / taxsmp:dd")


if __name__ == "__main__":
    main()
