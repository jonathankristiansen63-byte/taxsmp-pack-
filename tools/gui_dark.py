"""Dark Menus (TaxSMP /settings -> General -> Dark Menus): a dark copy of the menu font taxsmp:gui.

GuiSkin uses font taxsmp:guid instead of taxsmp:gui for players who turned Dark Menus on. Same characters and
positions as gui.json, only the pictures change:
  * the panels (U+E101-E106) are dark and run down over the whole window, the player's inventory and hotbar
    included (with their own dark slot squares), so nothing bright is left;
  * the slot squares and the button bar are dark;
  * the gold title plate and the RTP artwork stay as they are (the title text is dark gray on the plate).

Run after tools/gui.py:   python tools/gui_dark.py
"""
import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).absolute().parent.parent
ASSETS = ROOT / "pack/assets/taxsmp"
G = ASSETS / "textures/g"
OUT = G / "dark"
LOWER = 97  # the chest window's lower part (player inventory + hotbar), below the 17 + rows*18 top part

# light gray -> dark gray (slight blue tint), keeps the same light/shade structure
STOPS = [(0, 0), (55, 12), (74, 16), (94, 22), (139, 27), (160, 31), (170, 35), (198, 42), (225, 55), (230, 60), (255, 80)]


def dark_value(v):
    for (a, va), (b, vb) in zip(STOPS, STOPS[1:]):
        if v <= b:
            return round(va + (vb - va) * (v - a) / (b - a))
    return STOPS[-1][1]


def darken(img):
    out = img.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            if max(r, g, b) - min(r, g, b) <= 12:  # gray pixels only
                v = dark_value((r + g + b) // 3)
                px[x, y] = (v, v + 1, min(255, v + 5), a)
    return out


def c(v):
    v = dark_value(v)
    return (v, v + 1, min(255, v + 5), 255)


PANEL, LIGHT, SHADE, DARK, SLOT_FILL = c(198), c(255), c(160), c(55), c(139)


def rect(px, x0, y0, x1, y1, color):
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            px[x, y] = color


def sunken(px, x0, y0, x1, y1, fill):
    rect(px, x0, y0, x1, y1, fill)
    rect(px, x0, y0, x1, y0, DARK)
    rect(px, x0, y0, x0, y1, DARK)
    rect(px, x0 + 1, y1, x1, y1, LIGHT)
    rect(px, x1, y0 + 1, x1, y1, LIGHT)


def full_panel(light_panel):
    """Dark top part (tray and all) plus the lower part with the player's inventory and hotbar squares."""
    top = darken(light_panel)
    h = top.height
    H = h + LOWER
    img = Image.new("RGBA", (176, H), (0, 0, 0, 0))
    img.paste(top.crop((0, 0, 176, h - 3)), (0, 0))   # keep the tray, drop the top part's closed bottom edge
    px = img.load()
    rect(px, 3, h - 3, 172, H - 4, PANEL)
    # window sides continue down, then close at the bottom
    rect(px, 0, h - 3, 0, H - 2, DARK)
    rect(px, 175, h - 3, 175, H - 2, DARK)
    rect(px, 1, h - 3, 2, H - 3, LIGHT)
    rect(px, 173, h - 3, 174, H - 3, SHADE)
    rect(px, 3, H - 3, 172, H - 2, SHADE)
    rect(px, 1, H - 1, 174, H - 1, DARK)
    for x, y in ((0, H - 1), (175, H - 1)):
        px[x, y] = (0, 0, 0, 0)
    # player inventory (3 rows) and hotbar slot squares, where Minecraft draws them
    for row in range(3):
        for col in range(9):
            x, y = 7 + col * 18, h + 13 + row * 18
            sunken(px, x, y, x + 17, y + 17, SLOT_FILL)
    for col in range(9):
        x, y = 7 + col * 18, h + 71
        sunken(px, x, y, x + 17, y + 17, SLOT_FILL)
    return img


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    gui = json.loads((ASSETS / "font/gui.json").read_text(encoding="utf-8"))
    providers = []
    for p in gui["providers"]:
        p = dict(p)
        if p["type"] == "bitmap":
            name = p["file"].split("/")[-1]
            if name in {f"p{r}.png" for r in range(1, 7)}:
                img = full_panel(Image.open(G / name).convert("RGBA"))
                img.save(OUT / name)
                p["file"] = "taxsmp:g/dark/" + name
                p["height"] = img.height
            elif name in ("s.png", "b.png"):
                if not (OUT / name).exists() or True:
                    darken(Image.open(G / name).convert("RGBA")).save(OUT / name)
                p["file"] = "taxsmp:g/dark/" + name
            # title plate (t*.png) and RTP artwork stay the same
        providers.append(p)
    (ASSETS / "font/guid.json").write_text(json.dumps({"providers": providers}, indent=2) + "\n", encoding="utf-8")
    print(f"guid font: {len(providers)} providers, dark pictures in textures/g/dark")


if __name__ == "__main__":
    main()
