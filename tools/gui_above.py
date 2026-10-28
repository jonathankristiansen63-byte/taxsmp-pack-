"""Raised title for menus whose window grows one slot taller with the title plate in the new space (vaults, loot
chests, /payhistory, /orderlogs).

Font taxsmp:gup holds U+E134, the taller header: the window's top border and title strip, HEADER px higher than the
panel's own, drawn over the panel's top so the window simply starts higher. GuiSkin's plate pieces (taxsmp:gui
E130-E133) and the ASCII letters (font taxsmp:gtext) sit RAISE px higher, centred in that header. Fonts cannot place a
glyph higher than its own height, so the raised copies are padded with RAISE transparent rows below.

For six-row windows the frame also runs down around the player's inventory: U+E135 continues the window's sides from
the end of the slots to the bottom of the screen (where it closes), and U+E136-E139 are slot squares for the three
inventory rows and the hotbar. taxsmp:gtext also has the small capitals (from nonlatin_european) for the titles.
com.taxsmp.settings.PayHistory.skinAbove draws them.

    python tools/gui_above.py
"""
import io
import json
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).absolute().parent.parent
ASSETS = ROOT / "pack/assets/taxsmp"
HEADER = 18  # one slot
RAISE = 10   # plate top 6 px above the window's old top edge: equal room above and below it in the header
SMALL_CAPS = "\u1d00\u0299\u1d04\u1d05\u1d07\ua730\u0262\u029c\u026a\u1d0a\u1d0b\u029f\u1d0d\u0274\u1d0f\u1d18\u01eb\u0280\ua731\u1d1b\u1d1c\u1d20\u1d21x\u028f\u1d22"
ASCII_ROWS = ["\u0000" * 16] * 2 + [
    "\u0000!\"#$%&'()*+,-./", "0123456789:;<=>?", "@ABCDEFGHIJKLMNO",
    "PQRSTUVWXYZ[\\]^_", "`abcdefghijklmno", "pqrstuvwxyz{|}~\u0000",
] + ["\u0000" * 16] * 8


def padded(img, cell_h, rows):
    out = Image.new("RGBA", (img.width, rows * (cell_h + RAISE)), (0, 0, 0, 0))
    for r in range(rows):
        out.alpha_composite(img.crop((0, r * cell_h, img.width, (r + 1) * cell_h)), (0, r * (cell_h + RAISE)))
    return out


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=True), encoding="utf-8")


def main():
    gui = json.loads((ASSETS / "font/gui.json").read_text(encoding="utf-8"))
    providers = []
    for p in gui["providers"]:
        if p.get("type") == "bitmap" and p["chars"][0] in "":
            name = p["file"].split("/")[-1]
            img = Image.open(ASSETS / "textures/g" / name).convert("RGBA")
            padded(img, img.height, 1).save(ASSETS / "textures/g" / ("up_" + name))
            providers.append({"type": "bitmap", "file": "taxsmp:g/up_" + name, "ascent": p["ascent"] + RAISE,
                              "height": p["height"] + RAISE, "chars": p["chars"]})
    # The header: the panel's top border rows, then its title strip rows, HEADER rows taller; it ends where the
    # panel's inset frame starts (row 15), so it covers the panel's own top border.
    panel = Image.open(ASSETS / "textures/g/p6.png").convert("RGBA")
    header = Image.new("RGBA", (panel.width, 15 + HEADER), (0, 0, 0, 0))
    header.alpha_composite(panel.crop((0, 0, panel.width, 3)), (0, 0))
    strip = panel.crop((0, 3, panel.width, 4))
    for y in range(3, header.height):
        header.alpha_composite(strip, (0, y))
    header.save(ASSETS / "textures/g/header.png")
    providers.append({"type": "bitmap", "file": "taxsmp:g/header.png", "ascent": 13 + HEADER, "height": header.height, "chars": ["\ue134"]})
    # Six-row windows: from y 119 (the grid frame's bottom bevel, under the chest's last slot row) to the screen's
    # bottom at 222. The frame's sides run on under that row and the bevel is drawn again just below it (y 125-127),
    # above the "Inventory" label.
    lower = Image.new("RGBA", (panel.width, 103), (0, 0, 0, 0))
    for y in range(6):
        lower.alpha_composite(panel.crop((0, 118, panel.width, 119)), (0, y))
    for y in range(3):
        lower.alpha_composite(panel.crop((0, 119 + y, panel.width, 120 + y)), (0, 6 + y))
    for y in range(9, 100):
        lower.alpha_composite(strip, (0, y))
    lower.alpha_composite(panel.crop((0, 122, panel.width, 125)), (0, 100))
    lower.save(ASSETS / "textures/g/lower.png")
    providers.append({"type": "bitmap", "file": "taxsmp:g/lower.png", "ascent": 13 - 119, "height": 103, "chars": ["\ue135"]})
    for i, top in enumerate((138, 156, 174, 196)):  # inventory rows and hotbar (vanilla 6-row chest layout)
        providers.append({"type": "bitmap", "file": "taxsmp:g/s.png", "ascent": 13 - top, "height": 18, "chars": [chr(0xE136 + i)]})
    write_json(ASSETS / "font/gup.json", {"providers": providers})

    jar = next(p for p in (*Path.home().glob("AppData/Roaming/ModrinthApp/meta/versions/1.21.11*/*.jar"),)
               if p.stat().st_size > 20_000_000)
    with zipfile.ZipFile(jar) as z:
        ascii_png = Image.open(io.BytesIO(z.read("assets/minecraft/textures/font/ascii.png"))).convert("RGBA")
        nonlatin = json.loads(z.read("assets/minecraft/font/include/default.json"))
        nonlatin = next(p for p in nonlatin["providers"] if p.get("file") == "minecraft:font/nonlatin_european.png")
        sheet = Image.open(io.BytesIO(z.read("assets/minecraft/textures/font/nonlatin_european.png"))).convert("RGBA")
    padded(ascii_png, 8, 16).save(ASSETS / "textures/font/ascii_up.png")
    write_json(ASSETS / "font/gtext.json", {"providers": [
        {"type": "space", "advances": {" ": 4}},
        {"type": "bitmap", "file": "taxsmp:font/ascii_up.png", "ascent": 7 + RAISE, "height": 8 + RAISE, "chars": ASCII_ROWS},
        {"type": "bitmap", "file": "taxsmp:font/nonlatin_up.png", "ascent": 7 + RAISE, "height": 8 + RAISE, "chars": nonlatin["chars"]},
    ]})
    cell = sheet.width // len(nonlatin["chars"][0])
    padded(sheet, cell, len(nonlatin["chars"])).save(ASSETS / "textures/font/nonlatin_up.png")
    # Advances of the small capitals, for PayHistory's title widths: last opaque column + 2.
    widths = {}
    for r, row in enumerate(nonlatin["chars"]):
        for c, ch in enumerate(row):
            if ch in SMALL_CAPS:
                g = sheet.crop((c * cell, r * cell, (c + 1) * cell, (r + 1) * cell))
                widths[ch] = max([x for x in range(cell) for y in range(cell) if g.getpixel((x, y))[3]], default=-1) + 2
    print("small caps advances (a-z, - = not in the sheet):", "".join(str(widths.get(ch, "-")) for ch in SMALL_CAPS))
    print(f"raised title: header {HEADER} px taller, plate and text {RAISE} px up (fonts taxsmp:gup, taxsmp:gtext)")


if __name__ == "__main__":
    main()
