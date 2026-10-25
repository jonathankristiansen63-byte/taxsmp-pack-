"""Generate the /settings look: font taxsmp:settings, row text fonts taxsmp:s<row><line>, item model b/blank.

The TaxSMP plugin (com.taxsmp.settings.SettingsMenu) draws the whole menu inside a 6 row chest's title:
a dark panel over the window, a highlight under the selected category tab (top row), and for each of
the 5 setting rows the name, a grey description and an on/off switch on the right. Keep the
characters, positions and sizes in sync with SettingsMenu.

Window coordinates: titles start at x=8, y=6, and a picture's top edge lands at y = 13 - ascent.
Slot row r has its top at y = 17 + 18 r (row 0 = category tabs, rows 1-5 = settings).

    python tools/settings_gui.py
"""
import json
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).absolute().parent.parent
ASSETS = ROOT / "pack/assets/taxsmp"
TEX = ASSETS / "textures/s"

BG, TAB = 0xE200, 0xE201
TOGGLE_ON, TOGGLE_OFF, TOGGLE_SOON = 0xE210, 0xE220, 0xE230   # + row (1..5)
MOVE_LEFT = {2 ** i: chr(0xE140 + i) for i in range(9)}
MOVE_RIGHT = {2 ** i: chr(0xE150 + i) for i in range(9)}

PANEL = (27, 28, 32, 255)
TAB_BG = (34, 35, 40, 255)
TAB_SELECTED = (46, 48, 55, 255)
LINE = (44, 45, 51, 255)
SLOT_DARK = (20, 21, 24, 255)
BORDER = (61, 142, 216, 255)
GREEN, GREEN_DARK = (74, 176, 108, 255), (52, 138, 82, 255)
OFF, OFF_DARK = (62, 63, 70, 255), (48, 49, 55, 255)
KNOB, KNOB_OFF = (240, 240, 240, 255), (150, 151, 158, 255)

# The ASCII rows of minecraft:font/ascii.png; everything else is left out (space comes from a space provider).
ASCII_ROWS = ["\u0000" * 16] * 2 + [
    "\u0000!\"#$%&'()*+,-./", "0123456789:;<=>?", "@ABCDEFGHIJKLMNO",
    "PQRSTUVWXYZ[\\]^_", "`abcdefghijklmno", "pqrstuvwxyz{|}~\u0000",
] + ["\u0000" * 16] * 8


def row_top(row):
    return 17 + 18 * row


def background():
    """176 x 222: the chest rows and the player's inventory, dark with a blue border."""
    img = Image.new("RGBA", (176, 222), PANEL)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 175, 221], outline=BORDER)
    # Category tab row.
    for col in range(9):
        x = 7 + 18 * col
        d.rectangle([x, 17, x + 17, 34], fill=TAB_BG)
    d.line([1, 36, 174, 36], fill=LINE)
    # Lines between the setting rows.
    for row in range(1, 6):
        y = row_top(row) + 18
        d.line([4, y, 171, y], fill=LINE)
    # The player's inventory: dark squares under each slot.
    d.line([1, 127, 174, 127], fill=LINE)
    for row in range(4):
        y = 139 + 18 * row + (4 if row == 3 else 0)
        for col in range(9):
            x = 7 + 18 * col
            d.rectangle([x, y, x + 17, y + 17], fill=SLOT_DARK)
    return img


def tab_selected():
    img = Image.new("RGBA", (18, 18), TAB_SELECTED)
    ImageDraw.Draw(img).rectangle([0, 16, 17, 17], fill=BORDER)
    return img


def switch(on, soon=False):
    """20 x 10 switch, knob right when on."""
    img = Image.new("RGBA", (20, 10), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    track, edge = (GREEN, GREEN_DARK) if on else (OFF, OFF_DARK)
    if soon:
        track, edge = (40, 41, 46, 255), (34, 35, 39, 255)
    d.rounded_rectangle([0, 0, 19, 9], radius=3, fill=track, outline=edge)
    kx = 11 if on else 1
    d.rectangle([kx, 1, kx + 7, 8], fill=(90, 91, 98, 255) if soon else (KNOB if on else KNOB_OFF))
    return img


def bitmap(file, ascent, height, chars):
    return {"type": "bitmap", "file": file, "ascent": ascent, "height": height, "chars": chars}


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=True), encoding="utf-8")


def main():
    TEX.mkdir(parents=True, exist_ok=True)
    background().save(TEX / "bg.png")
    tab_selected().save(TEX / "tab.png")
    switch(True).save(TEX / "on.png")
    switch(False).save(TEX / "off.png")
    switch(False, soon=True).save(TEX / "soon.png")

    providers = [bitmap("taxsmp:s/bg.png", 13, 222, [chr(BG)]),
                 bitmap("taxsmp:s/tab.png", 13 - row_top(0), 18, [chr(TAB)])]
    for row in range(1, 6):
        ascent = 13 - (row_top(row) + 4)  # switch 10 px tall, centred in the 18 px row
        providers.append(bitmap("taxsmp:s/on.png", ascent, 10, [chr(TOGGLE_ON + row)]))
        providers.append(bitmap("taxsmp:s/off.png", ascent, 10, [chr(TOGGLE_OFF + row)]))
        providers.append(bitmap("taxsmp:s/soon.png", ascent, 10, [chr(TOGGLE_SOON + row)]))
    providers.append({"type": "space", "advances": {ch: -n for n, ch in MOVE_LEFT.items()} | {ch: n for n, ch in MOVE_RIGHT.items()}})
    write_json(ASSETS / "font/settings.json", {"providers": providers})

    # Text at three heights in each setting row: name (t), description (d), value in the middle (m).
    for row in range(1, 6):
        for line, offset in (("t", 1), ("d", 10), ("m", 5)):
            write_json(ASSETS / f"font/s{row}{line}.json", {"providers": [
                {"type": "space", "advances": {" ": 4}},
                bitmap("minecraft:font/ascii.png", 13 - (row_top(row) + offset), 8, ASCII_ROWS),
            ]})

    # Invisible item for the click spots the switches are drawn over.
    blank = ASSETS / "textures/item/b/blank.png"
    blank.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGBA", (16, 16), (0, 0, 0, 0)).save(blank)
    write_json(ASSETS / "models/b/blank.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "taxsmp:item/b/blank"}})
    write_json(ASSETS / "items/b/blank.json", {"model": {"type": "minecraft:model", "model": "taxsmp:b/blank"}})
    print("settings gui: font taxsmp:settings, 15 row fonts, b/blank")


if __name__ == "__main__":
    main()
