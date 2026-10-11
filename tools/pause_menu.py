"""Generate the TaxSMP pause menu (Esc menu).

- The TaxSMP logo sits right above "Back to Game".
- The Disconnect button turns yellow and says "ᴄᴏᴍᴇ ʙᴀᴄᴋ ꜱᴏᴏɴ....".
- The banner sits right under the Disconnect button.

A server pack can't add things to the pause menu directly, so everything is drawn
with font characters inside the button labels (lang file). The labels have a total
width of 0 and use space characters to move the pen, so each picture lands at an
exact spot relative to its button, whatever the screen size.

The numbers come from Minecraft 26.3's PauseScreen, button and font code. Text widths
are measured from the real Minecraft font, so this needs the 26.3 client jar:

    python tools/pause_menu.py "path/to/26.3.jar"

It rewrites pack/assets/minecraft/font/default.json and pack/assets/minecraft/lang/en_us.json,
the three textures below, and preview/pause-menu.png. Then run build.py as usual.
"""
import io
import json
import sys
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "pack"
TEXTURES = PACK / "assets/taxsmp/textures/font"

BACK_TEXT = "Back to Game"
LEAVE_TEXT = "ᴄᴏᴍᴇ ʙᴀᴄᴋ ꜱᴏᴏɴ...."

# Font characters (private use area, so they never clash with real text).
TAB_LOGO = ""
PAUSE_LOGO = ""
LEAVE_BUTTON = ""
BANNER = ""
MOVE_LEFT = {2 ** i: chr(0xE8C0 + i) for i in range(9)}
MOVE_RIGHT = {2 ** i: chr(0xE8D0 + i) for i in range(9)}

# Minecraft 26.3 layout. A button label's line starts 6 px below the button's top edge,
# and a font picture is drawn with its top edge at (line top + 7 - ascent).
BUTTON_W, BUTTON_H = 204, 20
LABEL_TOP = (BUTTON_H - 9) // 2 + 1

# Sizes in GUI pixels. Font pictures can be at most 256x256 texture pixels.
LOGO_H, LOGO_GAP, LOGO_RES = 56, 3, 3.5
BANNER_W, BANNER_H, BANNER_GAP, BANNER_RES = 204, 39, 4, 4

GOLD = (237, 180, 25)
GOLD_LIGHT = (255, 224, 122)
GOLD_DARK = (168, 116, 0)
GOLD_SIDE = (201, 143, 7)


def fit_height(img, height):
    w = round(img.width * height / img.height)
    return img.convert("RGBa").resize((w, height), Image.LANCZOS).convert("RGBA")


def actual_width(img, x0=0, w=None):
    """Same as Minecraft's BitmapProvider: the right-most column that has any visible pixel."""
    w = img.width if w is None else w
    alpha = img.getchannel("A").load()
    for x in range(w - 1, -1, -1):
        if any(alpha[x0 + x, y] for y in range(img.height)):
            return x + 1
    return 0


def make_pause_logo():
    """The logo, with see-through space under it so it can float above its button."""
    logo = Image.open(ROOT / "src/logo-original.png").convert("RGBA")
    logo = logo.crop(logo.getchannel("A").point(lambda a: 255 if a > 8 else 0).getbbox())
    # ascent == height puts the picture's bottom edge at (line top + 7), i.e. 13 px into the button.
    pad = LABEL_TOP + 7 + LOGO_GAP
    height = LOGO_H + pad
    tex_h = round(height * LOGO_RES)
    logo = fit_height(logo, round(LOGO_H * LOGO_RES))
    tex = Image.new("RGBA", (logo.width, tex_h), (0, 0, 0, 0))
    tex.paste(logo, (0, 0))
    return tex, {"height": height, "ascent": height}


def make_leave_button():
    """A yellow button face that covers the vanilla button but leaves its 1 px outline
    visible, so the outline still lights up when you hover it."""
    w, h = BUTTON_W - 2, BUTTON_H - 2
    tex = Image.new("RGBA", (w, h), GOLD + (255,))
    px = tex.load()
    for x in range(w):
        px[x, 0] = GOLD_LIGHT + (255,)
        px[x, h - 1] = GOLD_DARK + (255,)
        px[x, h - 2] = GOLD_DARK + (255,)
    for y in range(h - 2):
        px[0, y] = GOLD_LIGHT + (255,)
        px[w - 1, y] = GOLD_SIDE + (255,)
    # Top edge at the button top + 1.
    return tex, {"height": h, "ascent": LABEL_TOP + 7 - 1}


def make_banner():
    src = Image.open(ROOT / "src/banner-original.webp").convert("RGBA")
    src = src.crop(src.getchannel("A").point(lambda a: 255 if a > 8 else 0).getbbox())
    tex_w, tex_h = BANNER_W * BANNER_RES, BANNER_H * BANNER_RES
    want = tex_w / tex_h
    if src.width / src.height > want:
        cut = src.width - round(src.height * want)
        src = src.crop((cut // 2, 0, src.width - (cut - cut // 2), src.height))
    else:
        cut = src.height - round(src.width / want)
        src = src.crop((0, cut // 2, src.width, src.height - (cut - cut // 2)))
    tex = src.resize((tex_w, tex_h), Image.LANCZOS)
    tex.putalpha(255)
    # Top edge BANNER_GAP px under the button.
    return tex, {"height": BANNER_H, "ascent": LABEL_TOP + 7 - (BUTTON_H + BANNER_GAP)}


class Font:
    """Advances and pictures for the characters we use, the same way Minecraft works them out."""

    def __init__(self, jar_path):
        self.jar = zipfile.ZipFile(jar_path)
        self.glyphs = {}
        self.spaces = {}

    def read_json(self, name):
        return json.loads(self.jar.read(name).decode("utf-8"))

    def add_providers(self, providers, load_texture):
        for p in providers:
            if p["type"] == "space":
                for ch, adv in p["advances"].items():
                    self.spaces.setdefault(ch, adv)
            elif p["type"] == "bitmap":
                img = load_texture(p["file"])
                rows = p["chars"]
                cols = max(len(r) for r in rows)
                cw, chh = img.width // cols, img.height // len(rows)
                height = p.get("height", 8)
                scale = height / chh
                for r, row in enumerate(rows):
                    for c, ch in enumerate(row):
                        if ch in ("\0", " ") or ch in self.glyphs or ch in self.spaces:
                            continue
                        cell = img.crop((c * cw, r * chh, (c + 1) * cw, (r + 1) * chh))
                        adv = int(0.5 + actual_width(cell) * scale) + 1
                        self.glyphs[ch] = (cell, scale, p["ascent"], adv)

    def add_vanilla(self):
        def texture(name):
            ns, _, path = name.partition(":")
            return Image.open(io.BytesIO(self.jar.read(f"assets/{ns}/textures/{path}"))).convert("RGBA")
        self.add_providers(self.read_json("assets/minecraft/font/include/space.json")["providers"], texture)
        self.add_providers(self.read_json("assets/minecraft/font/include/default.json")["providers"], texture)

    def advance(self, ch):
        if ch in self.spaces:
            return self.spaces[ch]
        return self.glyphs[ch][3]

    def width(self, text):
        return sum(self.advance(ch) for ch in text)


def move(n):
    """Characters that move the pen n GUI pixels (negative = left)."""
    table = MOVE_RIGHT if n > 0 else MOVE_LEFT
    out, n = "", abs(n)
    for step in sorted(table, reverse=True):
        while n >= step:
            out += table[step]
            n -= step
    return out


def label(parts, font):
    """Build a label of total width 0 from (text, left edge relative to the button center) pairs.
    A width of 0 makes Minecraft start drawing exactly at the button center."""
    out, pen = "", 0
    for text, left in parts:
        out += move(left - pen) + text
        pen = left + font.width(text)
    return out + move(-pen)


def banner_text(font):
    """The 4 banner tiles, each followed by a 1 px step back (every picture advances 1 px extra)."""
    return "".join(ch + move(-1) for ch in BANNER)


def render_preview(font, labels, out_path, jar):
    """Draw a 1080p-style pause menu (GUI scale 4 -> 480x270 GUI px) the way Minecraft lays it out."""
    gui_w, gui_h, s = 480, 270, 3
    img = Image.new("RGBA", (gui_w * s, gui_h * s), (52, 70, 92, 255))
    for y in range(img.height):  # a simple sky gradient so the pictures stand out
        shade = int(30 * y / img.height)
        img.paste((70 - shade, 96 - shade, 130 - shade, 255), (0, y, img.width, y + 1))

    sprite = Image.open(io.BytesIO(jar.read("assets/minecraft/textures/gui/sprites/widget/button.png"))).convert("RGBA")

    def button(x, y, w, text):
        border = 3
        sw, sh = sprite.size
        tile = Image.new("RGBA", (w, 20))
        for (sx0, sx1, dx0, dx1) in ((0, border, 0, border), (border, sw - border, border, w - border), (sw - border, sw, w - border, w)):
            col = sprite.crop((sx0, 0, sx1, sh)).resize((dx1 - dx0, 20), Image.NEAREST)
            tile.paste(col, (dx0, 0))
        img.alpha_composite(tile.resize((w * s, 20 * s), Image.NEAREST), (x * s, y * s))
        draw_text(text, x + w // 2, y + LABEL_TOP)

    def draw_text(text, center_x, line_y):
        start = center_x - font.width(text) // 2
        for shadow in (True, False):
            pen = start
            for ch in text:
                if ch in font.glyphs:
                    cell, scale, ascent, adv = font.glyphs[ch]
                    w, h = round(cell.width * scale * s), round(cell.height * scale * s)
                    g = cell.resize((w, h), Image.LANCZOS if scale < 1 else Image.NEAREST)
                    if shadow:
                        r, gg, b, a = g.split()
                        g = Image.merge("RGBA", (r.point(lambda v: v // 4), gg.point(lambda v: v // 4), b.point(lambda v: v // 4), a))
                    off = 1 if shadow else 0
                    img.alpha_composite(g, (round((pen + off) * s), round((line_y + 7 - ascent + off) * s)))
                pen += font.advance(ch)

    grid_h = 50 + 20 + 4 * 24
    top = int((gui_h - grid_h) * 0.25)
    x = (gui_w - 212) // 2 + 4
    y = top + 50
    button(x, y, 204, labels["menu.returnToGame"])
    button(x, y + 24, 98, "Advancements")
    button(x + 106, y + 24, 98, "Statistics")
    for i in range(4):
        button(gui_w // 2 - 46 + i * 24, y + 48, 20, "")
    button(x, y + 72, 98, "Options...")
    button(x + 106, y + 72, 98, "Player Reporting")
    button(x, y + 96, 204, labels["menu.disconnect"])
    out_path.parent.mkdir(exist_ok=True)
    img.convert("RGB").save(out_path)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    jar = zipfile.ZipFile(sys.argv[1])
    TEXTURES.mkdir(parents=True, exist_ok=True)

    pause_logo, pause_logo_def = make_pause_logo()
    leave, leave_def = make_leave_button()
    banner, banner_def = make_banner()
    pause_logo.save(TEXTURES / "pause_logo.png", optimize=True)
    leave.save(TEXTURES / "leave_button.png", optimize=True)
    banner.save(TEXTURES / "banner.png", optimize=True)

    providers = [
        {"type": "bitmap", "file": "taxsmp:font/logo.png", "height": 56, "ascent": 7, "chars": [TAB_LOGO]},
        {"type": "bitmap", "file": "taxsmp:font/pause_logo.png", **pause_logo_def, "chars": [PAUSE_LOGO]},
        {"type": "bitmap", "file": "taxsmp:font/leave_button.png", **leave_def, "chars": [LEAVE_BUTTON]},
        {"type": "bitmap", "file": "taxsmp:font/banner.png", **banner_def, "chars": [BANNER]},
        {"type": "space", "advances": {ch: -n for n, ch in MOVE_LEFT.items()} | {ch: n for n, ch in MOVE_RIGHT.items()}},
    ]
    font_path = PACK / "assets/minecraft/font/default.json"
    font_path.write_text(json.dumps({"providers": providers}, indent=2) + "\n", encoding="utf-8")

    font = Font(sys.argv[1])

    def texture(name):
        return Image.open(PACK / "assets" / name.split(":")[0] / "textures" / name.split(":")[1]).convert("RGBA")
    font.add_providers(providers, texture)  # the server pack wins over vanilla, like in game
    font.add_vanilla()

    logo_w = font.glyphs[PAUSE_LOGO][0].width * font.glyphs[PAUSE_LOGO][1]
    back_w, leave_w = font.width(BACK_TEXT), font.width(LEAVE_TEXT)
    labels = {
        "menu.game": "",
        "menu.returnToGame": label([
            (BACK_TEXT, -(back_w // 2)),
            (PAUSE_LOGO, -round(logo_w / 2)),
        ], font),
        "menu.disconnect": label([
            (LEAVE_BUTTON, -(BUTTON_W // 2) + 1),
            (LEAVE_TEXT, -(leave_w // 2)),
            (banner_text(font), -(BANNER_W // 2)),
        ], font),
    }
    lang_path = PACK / "assets/minecraft/lang/en_us.json"
    lang_path.write_text(json.dumps(labels, indent=2) + "\n", encoding="utf-8")

    render_preview(font, labels, ROOT / "preview/pause-menu.png", jar)
    print(f'"{BACK_TEXT}" is {back_w} px, "{LEAVE_TEXT}" is {leave_w} px, logo is {logo_w:.1f} px wide')
    for key, value in labels.items():
        print(f"{key}: width {font.width(value)}, {len(value)} chars")


if __name__ == "__main__":
    main()
