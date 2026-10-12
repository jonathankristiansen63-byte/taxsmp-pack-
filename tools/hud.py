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


def main():
    frame, frame_def = make_frame()
    textures = PACK / "assets/taxsmp/textures/font"
    textures.mkdir(parents=True, exist_ok=True)
    frame.save(textures / "sb_frame.png", optimize=True)

    providers = [
        {"type": "bitmap", "file": "taxsmp:font/sb_frame.png", **frame_def, "chars": [FRAME]},
        # Small TaxSMP logo for dialog titles ("Amount", "Price"...), text height.
        {"type": "bitmap", "file": "taxsmp:font/logo.png", "height": 10, "ascent": 8, "chars": [chr(0xE001)]},
        {"type": "space", "advances": {ch: -n for n, ch in MOVE_LEFT.items()} | {ch: n for n, ch in MOVE_RIGHT.items()}},
    ]
    font = PACK / "assets/taxsmp/font/hud.json"
    font.parent.mkdir(parents=True, exist_ok=True)
    font.write_text(json.dumps({"providers": providers}, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"frame {frame.size[0]}x{frame.size[1]}, board {BOARD_WIDTH} px wide, {LINES} lines")


if __name__ == "__main__":
    main()
