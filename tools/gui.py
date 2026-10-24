"""Generate the TaxSMP menu look (shop, auction house, orders): font taxsmp:gui + button item models.

The TaxSMP plugin builds each menu's background inside its title, from pieces of this font:
a panel for the number of rows, a slot square under every item, a dark bar for the button row,
and a title plate sized to the title text. Menu titles start at x=8, y=6 inside the window, and a
font picture's top edge is at (6 + 7 - ascent), so ascent = 13 - y puts it at window row y.

Keep the characters and sizes in sync with com.taxsmp.ui.GuiSkin in the plugin.

The 10 button icons are cut from src/gui-buttons.png (made with ChatGPT): 5 columns x 2 rows.
The approved plus/minus/yes/no sprites come from src/gui-buttons-pixel/.
File names are short on purpose: Windows has a 260 character path limit.

    python tools/gui.py "path/to/26.3.jar"
    python tools/gui.py --pixel-buttons-only
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
PIXEL_BUTTONS = ("plus", "minus", "yes", "no")

PANEL_GRAY = (198, 198, 198, 255)
DARK = (55, 55, 55, 255)
LIGHT = (255, 255, 255, 255)
SHADE = (160, 160, 160, 255)
SLOT_FILL = (139, 139, 139, 255)
TRAY_FILL = PANEL_GRAY
BAR_FILL = (74, 74, 74, 255)
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
    img = Image.new("RGBA", (176, h), PANEL_GRAY)
    px = img.load()
    # Raised silver shell around an inset tray. Keep the active slot area at x=7.
    rect(px, 1, 0, 174, 0, DARK)
    rect(px, 0, 1, 0, h - 2, DARK)
    rect(px, 175, 1, 175, h - 2, DARK)
    rect(px, 1, h - 1, 174, h - 1, DARK)
    rect(px, 2, 1, 173, 2, LIGHT)
    rect(px, 1, 2, 2, h - 3, LIGHT)
    rect(px, 173, 3, 174, h - 3, SHADE)
    rect(px, 3, h - 3, 172, h - 2, SHADE)
    # Three hard bands: black rim, silver lip and dark inset edge.
    sunken(px, 4, 15, 171, h - 4, (0, 0, 0, 255))
    sunken(px, 5, 16, 170, h - 5, (230, 230, 230, 255))
    sunken(px, 6, 17, 169, h - 6, TRAY_FILL)
    for x, y in ((0, 0), (175, 0), (0, h - 1), (175, h - 1)):
        px[x, y] = CLEAR
    return img


def slot():
    img = Image.new("RGBA", (18, 18), CLEAR)
    sunken(img.load(), 0, 0, 17, 17, SLOT_FILL)
    return img


def bar():
    img = Image.new("RGBA", (162, 18), BAR_FILL)
    px = img.load()
    sunken(px, 0, 0, 161, 17, BAR_FILL)
    # The reference has a silver reflected edge and a lighter upper face.
    rect(px, 1, 1, 160, 7, (94, 94, 94, 255))
    rect(px, 1, 1, 160, 1, (170, 170, 170, 255))
    rect(px, 1, 15, 160, 15, (158, 158, 158, 255))
    rect(px, 1, 16, 160, 16, (225, 225, 225, 255))
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


def information_button():
    """Native 16px Minecraft button: a white lowercase i on a green bevel."""
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    rect(px, 1, 0, 14, 15, (35, 48, 21, 255))
    rect(px, 0, 1, 15, 14, (35, 48, 21, 255))
    rect(px, 1, 1, 14, 14, (112, 169, 28, 255))
    rect(px, 2, 1, 13, 2, (178, 225, 79, 255))
    rect(px, 1, 2, 2, 12, (178, 225, 79, 255))
    rect(px, 2, 13, 14, 14, (62, 108, 11, 255))
    rect(px, 13, 3, 14, 13, (62, 108, 11, 255))
    rect(px, 7, 4, 8, 5, LIGHT)
    rect(px, 6, 7, 8, 8, LIGHT)
    rect(px, 7, 9, 8, 11, LIGHT)
    rect(px, 6, 12, 9, 12, LIGHT)
    return img.resize((32, 32), Image.Resampling.NEAREST)


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


def buttons(sheet_path, grid=(5, 2), resampling=Image.Resampling.LANCZOS):
    """Export isolated sprites from a transparent sheet at inventory texture size (32x32)."""
    sheet = Image.open(sheet_path).convert("RGBA")
    alpha = sheet.getchannel("A").point(lambda a: 255 if a > 32 else 0).load()
    w, h = sheet.size
    cols = runs([x for x in range(w) if any(alpha[x, y] for y in range(0, h, 2))])
    rows = runs([y for y in range(h) if any(alpha[x, y] for x in range(0, w, 2))])
    if (len(cols), len(rows)) != grid:
        sys.exit(f"Expected {grid[0]} x {grid[1]} icons in the button sheet, found {len(cols)} x {len(rows)}")
    icons = []
    for (y0, y1) in rows:
        for (x0, x1) in cols:
            icon = sheet.crop((x0, y0, x1 + 1, y1 + 1))
            size = max(icon.size)
            square = Image.new("RGBA", (size, size), CLEAR)
            square.paste(icon, ((size - icon.width) // 2, (size - icon.height) // 2))
            icons.append(square.convert("RGBa").resize((32, 32), resampling).convert("RGBA"))
    return icons


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8", newline="\n")


def pixel_button(name):
    """Preserve the approved 14x16 sprite, its shadows and binary alpha exactly."""
    icon = Image.open(ROOT / f"src/gui-buttons-pixel/{name}.png").convert("RGBA")
    if icon.size != (14, 16) or set(icon.getchannel("A").getdata()) != {0, 255}:
        sys.exit(f"{name}: expected a transparent 14x16 pixel sprite with binary alpha")
    square = Image.new("RGBA", (16, 16), CLEAR)
    square.paste(icon, (1, 0))
    return square.resize((32, 32), Image.Resampling.NEAREST)


def export_pixel_buttons():
    for name in PIXEL_BUTTONS:
        path = ASSETS / f"textures/item/b/{name}.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        pixel_button(name).save(path)
        write_json(ASSETS / f"models/b/{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"taxsmp:item/b/{name}"}})
        write_json(ASSETS / f"items/b/{name}.json", {"model": {"type": "minecraft:model", "model": f"taxsmp:b/{name}"}})
    print("Exported four transparent pixel buttons (32x32, nearest-neighbor, no backgrounds)")


def main():
    if sys.argv[1:] == ["--pixel-buttons-only"]:
        export_pixel_buttons()
        return
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

    # Item textures must sit under textures/item/ (or block/) or Minecraft never loads them.
    (ASSETS / "textures/item/b").mkdir(parents=True, exist_ok=True)
    icons = dict(zip(BUTTONS, buttons(ROOT / "src/gui-buttons.png")))
    minus, plus = buttons(ROOT / "src/gui-quantity-buttons.png", (2, 1), Image.Resampling.NEAREST)
    icons.update(plus=plus, minus=minus)
    icons.update({name: pixel_button(name) for name in PIXEL_BUTTONS})
    icons['info'] = information_button()
    for name, icon in icons.items():
        icon.save(ASSETS / f"textures/item/b/{name}.png")
        write_json(ASSETS / f"models/b/{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"taxsmp:item/b/{name}"}})
        write_json(ASSETS / f"items/b/{name}.json", {"model": {"type": "minecraft:model", "model": f"taxsmp:b/{name}"}})
    print(f"gui font: {len(providers)} providers, {len(icons)} buttons")
    from rtp_assets import build
    build(sys.argv[1])


if __name__ == "__main__":
    main()
