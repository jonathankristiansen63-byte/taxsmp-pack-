"""Generate the TaxSMP menu look (shop, auction house): font taxsmp:gui + button item models.

The TaxSMP plugin builds each menu's background inside its title, from pieces of this font:
a panel for the number of rows, a slot square under every item, a dark bar for the button row,
and a title plate sized to the title text. Menu titles start at x=8, y=6 inside the window, and a
font picture's top edge is at (6 + 7 - ascent), so ascent = 13 - y puts it at window row y.

Keep the characters and sizes in sync with com.taxsmp.ui.GuiSkin in the plugin.

The 10 button icons are cut from src/gui-buttons.png (made with ChatGPT): 5 columns x 2 rows.
File names are short on purpose: Windows has a 260 character path limit.

    python tools/gui.py "path/to/26.3.jar"
"""
import io
import json
import sys
import zipfile
from pathlib import Path

from PIL import Image

# absolute(), not resolve(): resolving can turn the path into a much longer one on Windows.
ROOT = Path(__file__).absolute().parent.parent
ASSETS = ROOT / "pack/assets/taxsmp"

PANEL, SLOT, BAR = 0xE100, 0xE110, 0xE120          # + rows / + row / + row
PLATE_LEFT, PLATE_MID16, PLATE_MID1, PLATE_RIGHT = 0xE130, 0xE131, 0xE132, 0xE133
MOVE_LEFT = {2 ** i: chr(0xE140 + i) for i in range(9)}
MOVE_RIGHT = {2 ** i: chr(0xE150 + i) for i in range(9)}

BUTTONS = ["search", "sort", "filter", "refresh", "mine", "prev", "next", "back", "yes", "no"]

PANEL_GRAY = (198, 198, 198, 255)
DARK = (55, 55, 55, 255)
LIGHT = (255, 255, 255, 255)
SHADE = (160, 160, 160, 255)
SLOT_FILL = (139, 139, 139, 255)
TRAY_FILL = (176, 176, 176, 255)
BAR_FILL = (74, 74, 74, 255)
BAR_EDGE = (42, 42, 42, 255)
PLATE_FILL = (226, 226, 226, 255)
CLEAR = (0, 0, 0, 0)


def rect(px, x0, y0, x1, y1, color):
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            px[x, y] = color


def sunken(px, x0, y0, x1, y1, fill):
    """Minecraft-style inset box: dark top/left edge, white bottom/right edge."""
    rect(px, x0, y0, x1, y1, fill)
    rect(px, x0, y0, x1, y0, DARK)
    rect(px, x0, y0, x0, y1, DARK)
    rect(px, x0 + 1, y1, x1, y1, LIGHT)
    rect(px, x1, y0 + 1, x1, y1, LIGHT)


def panel(vanilla, rows):
    """The chest window's top part with the slots removed and an inset tray where they were."""
    h = 17 + rows * 18
    img = vanilla.crop((0, 0, 176, h)).copy()
    px = img.load()
    rect(px, 7, 17, 168, h - 1, PANEL_GRAY)
    sunken(px, 6, 16, 169, h - 1, TRAY_FILL)
    return img


def slot():
    img = Image.new("RGBA", (18, 18), CLEAR)
    sunken(img.load(), 0, 0, 17, 17, SLOT_FILL)
    return img


def bar():
    img = Image.new("RGBA", (162, 18), BAR_FILL)
    px = img.load()
    rect(px, 0, 0, 161, 0, BAR_EDGE)
    rect(px, 0, 17, 161, 17, BAR_EDGE)
    rect(px, 0, 0, 0, 17, BAR_EDGE)
    rect(px, 161, 0, 161, 17, BAR_EDGE)
    return img


def plate_piece(kind):
    """Title plate, 12 px tall: left cap (4 px), middles (16 px / 1 px) and right cap (4 px)."""
    w = {"l": 4, "m16": 16, "m1": 1, "r": 4}[kind]
    img = Image.new("RGBA", (w, 12), PLATE_FILL)
    px = img.load()
    for x in range(w):
        px[x, 0] = DARK
        px[x, 1] = LIGHT
        px[x, 10] = SHADE
        px[x, 11] = DARK
    if kind == "l":
        rect(px, 0, 0, 0, 11, DARK)
        rect(px, 1, 1, 1, 10, LIGHT)
        px[0, 0] = px[0, 11] = CLEAR
    if kind == "r":
        rect(px, 3, 0, 3, 11, DARK)
        rect(px, 2, 1, 2, 10, SHADE)
        px[3, 0] = px[3, 11] = CLEAR
    return img


def runs(values):
    out, start, prev = [], None, None
    for v in values:
        if start is None:
            start = prev = v
        elif v == prev + 1:
            prev = v
        else:
            out.append((start, prev))
            start = prev = v
    out.append((start, prev))
    return out


def buttons(sheet_path):
    """Cut the 10 icons out of the ChatGPT sheet (5 x 2, transparent background) at 32x32."""
    sheet = Image.open(sheet_path).convert("RGBA")
    alpha = sheet.getchannel("A").point(lambda a: 255 if a > 32 else 0).load()
    w, h = sheet.size
    cols = runs([x for x in range(w) if any(alpha[x, y] for y in range(0, h, 2))])
    rows = runs([y for y in range(h) if any(alpha[x, y] for x in range(0, w, 2))])
    if len(cols) != 5 or len(rows) != 2:
        sys.exit(f"Expected 5 x 2 icons in the button sheet, found {len(cols)} x {len(rows)}")
    icons = []
    for (y0, y1) in rows:
        for (x0, x1) in cols:
            icon = sheet.crop((x0, y0, x1 + 1, y1 + 1))
            size = max(icon.size)
            square = Image.new("RGBA", (size, size), CLEAR)
            square.paste(icon, ((size - icon.width) // 2, (size - icon.height) // 2))
            icons.append(square.convert("RGBa").resize((32, 32), Image.LANCZOS).convert("RGBA"))
    return icons


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8", newline="\n")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    jar = zipfile.ZipFile(sys.argv[1])
    vanilla = Image.open(io.BytesIO(jar.read("assets/minecraft/textures/gui/container/generic_54.png"))).convert("RGBA")
    tex = ASSETS / "textures/g"
    tex.mkdir(parents=True, exist_ok=True)
    providers = []

    for rows in range(1, 7):
        panel(vanilla, rows).save(tex / f"p{rows}.png")
        providers.append({"type": "bitmap", "file": f"taxsmp:g/p{rows}.png", "height": 17 + rows * 18,
                          "ascent": 13, "chars": [chr(PANEL + rows)]})
    slot().save(tex / "s.png")
    bar().save(tex / "b.png")
    for row in range(6):
        y = 17 + row * 18
        providers.append({"type": "bitmap", "file": "taxsmp:g/s.png", "height": 18, "ascent": 13 - y, "chars": [chr(SLOT + row)]})
        providers.append({"type": "bitmap", "file": "taxsmp:g/b.png", "height": 18, "ascent": 13 - y, "chars": [chr(BAR + row)]})
    for kind, code in (("l", PLATE_LEFT), ("m16", PLATE_MID16), ("m1", PLATE_MID1), ("r", PLATE_RIGHT)):
        plate_piece(kind).save(tex / f"t{kind}.png")
        # Plate covers window rows 4..15, around the title text (rows 6..13).
        providers.append({"type": "bitmap", "file": f"taxsmp:g/t{kind}.png", "height": 12, "ascent": 13 - 4, "chars": [chr(code)]})
    providers.append({"type": "space", "advances": {ch: -n for n, ch in MOVE_LEFT.items()} | {ch: n for n, ch in MOVE_RIGHT.items()}})
    write_json(ASSETS / "font/gui.json", {"providers": providers})

    (ASSETS / "textures/b").mkdir(parents=True, exist_ok=True)
    for name, icon in zip(BUTTONS, buttons(ROOT / "src/gui-buttons.png")):
        icon.save(ASSETS / f"textures/b/{name}.png")
        write_json(ASSETS / f"models/b/{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"taxsmp:b/{name}"}})
        write_json(ASSETS / f"items/b/{name}.json", {"model": {"type": "minecraft:model", "model": f"taxsmp:b/{name}"}})
    print(f"gui font: {len(providers)} providers, {len(BUTTONS)} buttons")


if __name__ == "__main__":
    main()
