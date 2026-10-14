"""Generate the TaxSMP scoreboard frame (font taxsmp:hud).

The frame is a font picture inside the sidebar title. Minecraft 26.3 draws the sidebar like this
(Hud.displayScoreboardSidebar), for a board W px wide with N lines, `top` = first line's y:

    title background:  x from left-2 to left+W+2, y from top-10 to top-1
    lines background:  x from left-2 to left+W+2, y from top-1 to top+N*9
    title text:        starts at left + W/2 - titleWidth/2, line top = top-9, no shadow
    line i text:       starts at left, y = top + i*9, no shadow

The TaxSMP plugin makes the title exactly W px wide (so it starts at `left`), puts the frame
first, and starts every line 3 px in so text doesn't touch the border. Keep BOARD_WIDTH and
LINES in sync with ScoreboardManager in the plugin.

    python tools/hud.py
"""
import json
from pathlib import Path

from PIL import Image

# absolute(), not resolve(): resolving can turn the path into a much longer one on Windows (260 char limit).
ROOT = Path(__file__).absolute().parent.parent
PACK = ROOT / "pack"

BOARD_WIDTH = 144  # W
LINES = 12         # N (name to ping, plus one empty line)

FRAME = ""
MOVE_LEFT = {2 ** i: chr(0xE010 + i) for i in range(9)}
MOVE_RIGHT = {2 ** i: chr(0xE020 + i) for i in range(9)}

BLACK = (12, 12, 12, 255)
GOLD = (242, 177, 15, 255)
GOLD_LIGHT = (255, 214, 92, 255)


def make_frame():
    # x: from left-2 to left+W+2 (on top of the background's left/right edges).
    # y: 4 px above the title background down to 4 px under the lines background.
    w = BOARD_WIDTH + 4
    lines_top = 14                      # = top, in frame pixels (frame starts at top-14)
    bottom = lines_top + LINES * 9      # = top + N*9
    h = bottom + 4
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()

    def ring(inset, color):
        for x in range(inset, w - inset):
            px[x, inset] = color
            px[x, h - 1 - inset] = color
        for y in range(inset, h - inset):
            px[inset, y] = color
            px[w - 1 - inset, y] = color

    ring(0, BLACK)
    ring(1, GOLD_LIGHT)
    ring(2, GOLD)
    ring(3, BLACK)
    # Minecraft draws the title row at 40% black and the lines at 30%. A 14% black layer over
    # the lines (drawn before their text) makes the whole board one even shade: 1 - 0.7 * 0.86 = 0.4.
    for x in range(4, w - 4):
        for y in range(lines_top - 1, bottom):
            px[x, y] = (0, 0, 0, 36)

    # Glyph top = title line top + 7 - ascent = (top - 9) + 7 - ascent = top - 14.
    return img, {"height": h, "ascent": 12}


def frame_from_art(path):
    """The ChatGPT frame (src/scoreboard-frame.png: a box plus a logo holder on top, pixel art on a
    rough grid) turned into crisp art pixels and fitted to the sidebar:
      - art pixel size = the side border's thickness / 4 (it has 4 color layers)
      - colors snapped to the 3 frame colors
      - wider: repeat a plain column in each top bar / wing (k) and the empty middle (gap) (g)
      - taller: repeat a plain row of the box sides
    The box's top edge lands at top-14 and its bottom 4 px under the last line, like make_frame().
    """
    src = Image.open(path).convert("RGBA")
    a = src.getchannel("A").load()
    bbox = src.getchannel("A").point(lambda v: 255 if v > 128 else 0).getbbox()
    x0, y0, x1, y1 = bbox
    mid = (y0 + y1) // 2
    border = 0
    while a[x0 + border, mid] > 128:
        border += 1
    size = border / 4
    cols, rows = round((x1 - x0) / size), round((y1 - y0) / size)
    px = src.load()
    art = [[None] * cols for _ in range(rows)]
    for j in range(rows):
        for i in range(cols):
            p = px[int(x0 + (i + 0.5) * size), int(y0 + (j + 0.5) * size)]
            if p[3] < 128:
                continue
            if p[0] + p[1] + p[2] < 200:
                art[j][i] = BLACK
            elif p[1] > 190:
                art[j][i] = GOLD_LIGHT
            else:
                art[j][i] = GOLD

    crest = next(j for j in range(rows) if sum(1 for c in art[j] if c) > cols * 0.6)  # box top edge
    extra = BOARD_WIDTH + 4 - cols
    gap = min(extra, 16)
    side = (extra - gap) // 2
    gap = extra - 2 * side
    left_col, mid_col, right_col = 12, cols // 2, cols - 1 - 12
    order = []
    for i in range(cols):
        order.append(i)
        if i == left_col:
            order += [i] * side
        elif i == mid_col:
            order += [i] * gap
        elif i == right_col:
            order += [i] * side
    box_rows = 14 + LINES * 9 + 4
    grow = box_rows - (rows - crest)
    mid_row = crest + (rows - crest) // 2
    row_order = []
    for j in range(rows):
        row_order.append(j)
        if j == mid_row:
            row_order += [j] * grow

    w, h = len(order), len(row_order)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out = img.load()
    for y, j in enumerate(row_order):
        for x, i in enumerate(order):
            if art[j][i]:
                out[x, y] = art[j][i]
    # Same even background as make_frame(): 14% black over the lines, only where the frame is clear.
    lines_top = crest + 14
    for x in range(4, w - 4):
        for y in range(lines_top - 1, lines_top + LINES * 9):
            if out[x, y][3] == 0:
                out[x, y] = (0, 0, 0, 36)
    print(f"GPT frame: art pixel {size:.2f} px, {cols}x{rows} art -> {w}x{h}, logo holder {crest} px tall")
    # Box top edge at top-14: glyph top = (top - 9) + 7 - ascent = top - 14 - crest.
    return img, {"height": h, "ascent": 12 + crest}


LOGO_HEIGHT = 48
LOGO_RES = 4  # texture pixels per GUI pixel (glyph textures max 256 px)


LOGO_PIXEL_WIDTH = 72  # the pixel-art logo is 72 art pixels wide (see the ChatGPT logo prompt)


def make_logo():
    """The scoreboard logo. With src/scoreboard-logo.png (ChatGPT pixel art) it is cut back to its art
    pixels at 1x, like the frame; otherwise the smooth logo at 4x. The glyph's bottom = title line top
    + 7 - ascent + height = (top - 9) + 8 = top - 1: right above the first line. Returns (image, height)."""
    art = ROOT / "src/scoreboard-logo.png"
    if art.exists():
        src = Image.open(art).convert("RGBA")
        x0, y0, x1, y1 = src.getchannel("A").point(lambda v: 255 if v > 128 else 0).getbbox()
        size = (x1 - x0) / LOGO_PIXEL_WIDTH
        rows = round((y1 - y0) / size)
        px = src.load()
        logo = Image.new("RGBA", (LOGO_PIXEL_WIDTH, rows), (0, 0, 0, 0))
        out = logo.load()
        for j in range(rows):
            for i in range(LOGO_PIXEL_WIDTH):
                c = px[int(x0 + (i + 0.5) * size), int(y0 + (j + 0.5) * size)]
                if c[3] >= 128:
                    out[i, j] = c[:3] + (255,)
        print(f"pixel-art logo: {size:.2f} px per art pixel -> {LOGO_PIXEL_WIDTH}x{rows}, drawn {logo.getbbox()[2]} px wide")
        return logo, rows
    logo = Image.open(ROOT / "src/logo-original.png").convert("RGBA")
    logo = logo.crop(logo.getchannel("A").point(lambda a: 255 if a > 8 else 0).getbbox())
    h = LOGO_HEIGHT * LOGO_RES
    w = round(logo.width * h / logo.height)
    logo = logo.convert("RGBa").resize((w, h), Image.LANCZOS).convert("RGBA")
    print(f"logo {w}x{h} texture, drawn {w / LOGO_RES:.0f} px wide (advance {round(w / LOGO_RES) + 1})")
    return logo, LOGO_HEIGHT


def main():
    art = ROOT / "src/scoreboard-frame.png"
    frame, frame_def = frame_from_art(art) if art.exists() else make_frame()
    textures = PACK / "assets/taxsmp/textures/font"
    textures.mkdir(parents=True, exist_ok=True)
    frame.save(textures / "sb_frame.png", optimize=True)
    logo, logo_height = make_logo()
    logo.save(textures / "sb_logo.png", optimize=True)

    providers = [
        {"type": "bitmap", "file": "taxsmp:font/sb_frame.png", **frame_def, "chars": [FRAME]},
        # Small TaxSMP logo for dialog titles ("Amount", "Price"...), text height.
        {"type": "bitmap", "file": "taxsmp:font/logo.png", "height": 10, "ascent": 8, "chars": [chr(0xE001)]},
        # Scoreboard logo (HugoSMP style): 48 px tall, bottom edge just above the first line,
        # so it sits on top of the frame's top edge. ascent = height - 1 (see make_logo).
        {"type": "bitmap", "file": "taxsmp:font/sb_logo.png", "height": logo_height, "ascent": logo_height - 1, "chars": [chr(0xE002)]},
        {"type": "space", "advances": {ch: -n for n, ch in MOVE_LEFT.items()} | {ch: n for n, ch in MOVE_RIGHT.items()}},
    ]
    font = PACK / "assets/taxsmp/font/hud.json"
    font.parent.mkdir(parents=True, exist_ok=True)
    font.write_text(json.dumps({"providers": providers}, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"frame {frame.size[0]}x{frame.size[1]}, board {BOARD_WIDTH} px wide, {LINES} lines")


if __name__ == "__main__":
    main()
