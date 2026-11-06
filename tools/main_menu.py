"""Generate the /taxsmp main menu look: font taxsmp:mm.

com.taxsmp.ui.TaxMenus.openRoot shows the main menu as a 2-column dialog of 170 px buttons. Each label has a total
width of 0 and moves the pen with the taxsmp:dlg space characters, so these pictures land at exact spots relative to
the button: a silver tile with a gold accent bar, an icon slot (the 16 px icon comes from taxsmp:hi at x 6) and a gold
arrow; the name (taxsmp:dt) and a short description (taxsmp:dd) go at x 26. Same palette and window as
settings_dialog.py: silver window, darker inset well, 3 px edges outside the buttons.

    E700 left tile   (button x -3..171: window's left edge + tile + the 2 px column gap)   175 px wide
    E701 left tile, first row (3 px taller: the window's top edge)
    E702 right tile  (button x 0..172: tile + window's right edge)                            173 px wide
    E703 right tile, first row
    E704 / E705 closing row, left / right: the window's bottom edge, then a dark strip under it for the
    server address (TaxMenus draws the text centred across both columns)

Keep characters and widths in sync with TaxMenus.menuTile / menuFooter.

    python tools/main_menu.py
"""
import json
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).absolute().parent.parent
ASSETS = ROOT / "pack/assets/taxsmp"
TEX = ASSETS / "textures/m"

TILE_W, ROW_H, PAD, GAP, TOP = 170, 22, 3, 2, 3
END_H = 20
LEFT_W, RIGHT_W = PAD + TILE_W + GAP, TILE_W + PAD

BLACK = (0, 0, 0, 255)
WHITE = (255, 255, 255, 255)
FACE = (198, 198, 198, 255)
SHADOW = (85, 85, 85, 255)
WELL = (139, 139, 139, 255)
WELL_DARK = (55, 55, 55, 255)
GOLD = (245, 183, 41, 255)
GOLD_LIGHT = (255, 226, 122, 255)
GOLD_DARK = (170, 112, 10, 255)
STRIP = (28, 28, 32, 255)


def hline(d, x0, x1, y, color):
    d.line([x0, y, x1, y], fill=color)


def vline(d, x, y0, y1, color):
    d.line([x, y0, x, y1], fill=color)


def tile(column, top):
    width = LEFT_W if column == 0 else RIGHT_W
    oy = TOP if top else 0
    img = Image.new("RGBA", (width, ROW_H + oy), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    tx = PAD if column == 0 else 0
    d.rectangle([0, oy, width - 1, oy + ROW_H - 1], fill=WELL)
    # The tile: a raised silver plate over the 20 px button and 1 px of the gap.
    x0, x1, y0, y1 = tx, tx + TILE_W - 1, oy, oy + ROW_H - 2
    d.rectangle([x0, y0, x1, y1], fill=FACE)
    hline(d, x0, x1 - 1, y0, WHITE)
    vline(d, x0, y0, y1 - 1, WHITE)
    hline(d, x0 + 1, x1, y1, SHADOW)
    vline(d, x1, y0 + 1, y1, SHADOW)
    # Gold accent bar on the left edge.
    d.rectangle([x0 + 1, y0 + 1, x0 + 2, y1 - 1], fill=GOLD)
    hline(d, x0 + 1, x0 + 2, y0 + 1, GOLD_LIGHT)
    hline(d, x0 + 1, x0 + 2, y1 - 1, GOLD_DARK)
    # Icon slot under the 16 px icon at x 6, y 2.
    sx, sy = x0 + 5, y0 + 1
    d.rectangle([sx, sy, sx + 17, sy + 17], fill=WELL)
    hline(d, sx, sx + 16, sy, WELL_DARK)
    vline(d, sx, sy, sy + 16, WELL_DARK)
    hline(d, sx + 1, sx + 17, sy + 17, WHITE)
    vline(d, sx + 17, sy + 1, sy + 17, WHITE)
    # Gold arrow on the right.
    for j, row in enumerate(["#..", "##.", "###", "##.", "#.."]):
        for i, ch in enumerate(row):
            if ch == "#":
                d.point((x1 - 8 + i, y0 + 8 + j), fill=GOLD_DARK)
    # Window edges.
    if column == 0:
        vline(d, 0, oy, oy + ROW_H - 1, BLACK)
        vline(d, 1, oy, oy + ROW_H - 1, FACE)
        vline(d, 2, oy, oy + ROW_H - 1, WELL_DARK)
    else:
        vline(d, width - 3, oy, oy + ROW_H - 1, WHITE)
        vline(d, width - 2, oy, oy + ROW_H - 1, FACE)
        vline(d, width - 1, oy, oy + ROW_H - 1, BLACK)
    if top:
        hline(d, 0, width - 1, 0, BLACK)
        hline(d, 0, width - 1, 1, FACE)
        hline(d, 0, width - 1, 2, WELL_DARK)
        if column == 0:
            vline(d, 0, 0, 2, BLACK)
            d.point((1, 2), fill=FACE)
        else:
            vline(d, width - 1, 0, 2, BLACK)
            d.point((width - 2, 2), fill=FACE)
            d.point((width - 3, 2), fill=WHITE)
    return img


def closing(column):
    width = LEFT_W if column == 0 else RIGHT_W
    img = Image.new("RGBA", (width, END_H), STRIP)
    d = ImageDraw.Draw(img)
    hline(d, 0, width - 1, 0, WHITE)
    hline(d, 0, width - 1, 1, FACE)
    hline(d, 0, width - 1, 2, BLACK)
    hline(d, 0, width - 1, END_H - 1, BLACK)
    if column == 0:
        vline(d, 0, 0, END_H - 1, BLACK)
        d.point((1, 0), fill=FACE)
        d.point((2, 0), fill=WHITE)
    else:
        vline(d, width - 1, 0, END_H - 1, BLACK)
        d.point((width - 2, 0), fill=FACE)
    return img


def main():
    TEX.mkdir(parents=True, exist_ok=True)
    providers = []
    for name, img, ascent, char in (
        ("tile_l", tile(0, False), 13, 0xE700),
        ("tile_l_top", tile(0, True), 13 + TOP, 0xE701),
        ("tile_r", tile(1, False), 13, 0xE702),
        ("tile_r_top", tile(1, True), 13 + TOP, 0xE703),
        ("end_l", closing(0), 13, 0xE704),
        ("end_r", closing(1), 13, 0xE705),
    ):
        img.save(TEX / f"{name}.png")
        providers.append({"type": "bitmap", "file": f"taxsmp:m/{name}.png", "ascent": ascent, "height": img.height, "chars": [chr(char)]})
    (ASSETS / "font/mm.json").write_text(json.dumps({"providers": providers}, indent=2, ensure_ascii=True), encoding="utf-8")
    print(f"main menu: font taxsmp:mm with {len(providers)} pictures")


if __name__ == "__main__":
    main()
