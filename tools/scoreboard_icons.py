"""Cut the ChatGPT icon sheet (src/scoreboard-icons.webp) into 16 x 16 item textures.

The sheet has the icons in rows on a transparent background, in the order of ICONS below. Each
icon is cut out, made square and shrunk to 16 x 16 with hard (on/off) transparency, like a
Minecraft item texture. They end up in textures/item/sb/<name>.png (sprite taxsmp:item/sb/<name>
in the items atlas, used by the TaxSMP scoreboard) and the gem also gets an item model
(taxsmp:gem) for the /shop.

    python tools/scoreboard_icons.py
"""
import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).absolute().parent.parent
PACK = ROOT / "pack"
SIZE = 16

# Sheet order. None = an icon on the sheet that the scoreboard doesn't use.
ICONS = ["money", "gem", None, "key", "clock", "swords", "skull", None, "shield", "globe", "ping"]


def spans(values, min_gap=8):
    """Runs of non-zero values (ignoring gaps shorter than min_gap)."""
    out, start, empty = [], None, 0
    for i, v in enumerate(values):
        if v:
            if start is None:
                start = i
            empty = 0
        elif start is not None:
            empty += 1
            if empty >= min_gap:
                out.append((start, i - empty + 1))
                start, empty = None, 0
    if start is not None:
        out.append((start, len(values) - empty))
    return out


def cut(sheet):
    alpha = sheet.getchannel("A").point(lambda a: 255 if a > 128 else 0)
    w, h = sheet.size
    data = alpha.load()
    icons = []
    for y0, y1 in spans([any(data[x, y] for x in range(w)) for y in range(h)]):
        row = [any(data[x, y] for y in range(y0, y1)) for x in range(w)]
        for x0, x1 in spans(row):
            box = alpha.crop((x0, y0, x1, y1)).getbbox()
            icons.append(sheet.crop((x0 + box[0], y0 + box[1], x0 + box[2], y0 + box[3])))
    return icons


def shrink(icon):
    side = max(icon.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(icon, ((side - icon.width) // 2, (side - icon.height) // 2))
    small = square.convert("RGBa").resize((SIZE, SIZE), Image.LANCZOS).convert("RGBA")
    px = small.load()
    for y in range(SIZE):
        for x in range(SIZE):
            r, g, b, a = px[x, y]
            px[x, y] = (r, g, b, 255) if a >= 100 else (0, 0, 0, 0)
    return small


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8", newline="\n")


def main():
    sheet = Image.open(ROOT / "src/scoreboard-icons.webp").convert("RGBA")
    icons = cut(sheet)
    if len(icons) != len(ICONS):
        raise SystemExit(f"found {len(icons)} icons on the sheet, expected {len(ICONS)}")
    out = PACK / "assets/taxsmp/textures/item/sb"
    out.mkdir(parents=True, exist_ok=True)
    for name, icon in zip(ICONS, icons):
        if name is None:
            continue
        shrink(icon).save(out / f"{name}.png", optimize=True)
    # /shop gem item: item model taxsmp:gem with the scoreboard gem picture.
    write_json(PACK / "assets/taxsmp/items/gem.json", {"model": {"type": "minecraft:model", "model": "taxsmp:gem"}})
    write_json(PACK / "assets/taxsmp/models/gem.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "taxsmp:item/sb/gem"}})
    print(f"{sum(1 for n in ICONS if n)} scoreboard icons -> {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
