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


LOGO_WIDTH = 64       # px on screen (the ChatGPT pixel art is 72 art pixels wide, shrunk to fit the holder)
LOGO_GAP = 3          # empty px rows between the logo and the first line
LOGO_ADVANCE = 73     # U+E002's width; the plugin's title moves count on it (FRAME_LOGO_WIDTH + 1)


def make_logo():
    """The scoreboard logo: src/scoreboard-logo.png (ChatGPT pixel art) shrunk to LOGO_WIDTH px,
    averaging each new pixel (keeps the outlines) with hard transparency."""
    src = Image.open(ROOT / "src/scoreboard-logo.png").convert("RGBA")
    src = src.crop(src.getchannel("A").point(lambda v: 255 if v > 128 else 0).getbbox())
    h = round(src.height * LOGO_WIDTH / src.width)
    logo = src.convert("RGBa").resize((LOGO_WIDTH, h), Image.BOX).convert("RGBA")
    px = logo.load()
    for y in range(h):
        for x in range(LOGO_WIDTH):
            r, g, b, a = px[x, y]
            px[x, y] = (r, g, b, 255) if a >= 128 else (0, 0, 0, 0)
    return logo


def add_logo(frame, frame_def, logo):
    """Draws the logo into the frame picture, centered on the board, its last row LOGO_GAP + 1 px above
    the first line. Rows are counted from `top` (the first line's y): the frame starts at
    top - 2 - ascent. Being one picture, the logo can't drift from the frame, and U+E002 becomes an
    empty space of the old logo's width, so older plugin versions still line up."""
    frame_top = -2 - frame_def["ascent"]
    logo_top = -LOGO_GAP - logo.height
    extra = max(0, frame_top - logo_top)
    img = Image.new("RGBA", (frame.width, frame.height + extra), (0, 0, 0, 0))
    img.paste(frame, (0, extra))
    img.alpha_composite(logo, ((frame.width - logo.width) // 2, logo_top - frame_top + extra))
    return img, {"height": img.height, "ascent": frame_def["ascent"] + extra}


def main():
    art = ROOT / "src/scoreboard-frame.png"
    frame, frame_def = frame_from_art(art) if art.exists() else make_frame()
    logo = make_logo()
    frame, frame_def = add_logo(frame, frame_def, logo)
    textures = PACK / "assets/taxsmp/textures/font"
    textures.mkdir(parents=True, exist_ok=True)
    frame.save(textures / "sb_frame.png", optimize=True)
    (textures / "sb_logo.png").unlink(missing_ok=True)
    print(f"logo {logo.width}x{logo.height}, {LOGO_GAP} px above the first line")

    providers = [
        {"type": "bitmap", "file": "taxsmp:font/sb_frame.png", **frame_def, "chars": [FRAME]},
        # Small TaxSMP logo for dialog titles ("Amount", "Price"...), text height.
        {"type": "bitmap", "file": "taxsmp:font/logo.png", "height": 10, "ascent": 8, "chars": [chr(0xE001)]},
        {"type": "space", "advances": {chr(0xE002): LOGO_ADVANCE}
            | {ch: -n for n, ch in MOVE_LEFT.items()} | {ch: n for n, ch in MOVE_RIGHT.items()}},
    ]
    font = PACK / "assets/taxsmp/font/hud.json"
    font.parent.mkdir(parents=True, exist_ok=True)
    font.write_text(json.dumps({"providers": providers}, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"frame {frame.size[0]}x{frame.size[1]}, board {BOARD_WIDTH} px wide, {LINES} lines")


if __name__ == "__main__":
    main()
