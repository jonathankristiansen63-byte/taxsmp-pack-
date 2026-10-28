"""3D tombstone models for the loot chests (taxsmp:tomb/t1..t7) from their 32x32 texture sheets.

Crosses (t1, t2, t4), in sheet pixels: the arm's front and back are the 12x4 strips at (0,0) and (16,0); the post's
front, back and sides are the 4x12 columns at x 4, 20, 12 and 28 from y 4; the arm's end caps and the post's top are
the 4x4 squares at y 8. The base is an 8x8 stone top (with the dark post socket) at (8,16), its bottom at (0,16), and
four 8x8 cobblestone sides in the bottom-right quarter.

Headstones (t3, t5, t6, t7): two 12x16 faces at (0,16) and (16,16) with rounded tops, 4x16 sides at x 12 and 28,
and a 12x4 top at (0,12). The rounded top is built as steps that follow the face's opaque pixels, so no flat corners
stick out. The engraved face is the front (model north, which an item display turns to face its yaw).

Model units are sheet pixels (a stone is 16 tall = one block); UVs are sheet pixels / 2.

    python tools/tomb_models.py
"""
import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).absolute().parent.parent
ASSETS = ROOT / "pack/assets/taxsmp"
CROSSES = {1, 2, 4}
FRONT = {3: 16, 5: 0, 6: 0, 7: 16}  # sheet x of each headstone's engraved face


def face(x0, y0, x1, y1):
    return {"uv": [x0 / 2, y0 / 2, x1 / 2, y1 / 2], "texture": "#t"}


def box(lo, hi, faces):
    return {"from": lo, "to": hi, "faces": faces}


def cross():
    base = box([4, 0, 4], [12, 4, 12], {
        "north": face(16, 16, 24, 20), "east": face(24, 16, 32, 20),
        "south": face(16, 24, 24, 28), "west": face(24, 24, 32, 28),
        "up": face(8, 16, 16, 24), "down": face(0, 16, 8, 24)})

    def post(y0, y1, v0, v1):  # a run of the 4x4 post, model y0..y1 = sheet rows v0..v1
        return box([6, y0, 6], [10, y1, 10], {
            "north": face(4, v0, 8, v1), "south": face(20, v0, 24, v1),
            "east": face(12, v0, 16, v1), "west": face(28, v0, 32, v1)})

    # Post sheet rows 4..16 stand on the base (model y 4..16); the arm covers rows 8..12 (model y 8..12).
    top = post(12, 16, 4, 8)
    top["faces"]["up"] = face(16, 8, 20, 12)
    arm = box([2, 8, 6], [14, 12, 10], {
        "north": face(0, 0, 12, 4), "south": face(16, 0, 28, 4),
        "up": face(16, 0, 28, 4), "down": face(0, 0, 12, 4),
        "west": face(0, 8, 4, 12), "east": face(8, 8, 12, 12)})
    return [base, post(4, 8, 12, 16), arm, top]


def headstone(img, front):
    back = 16 - front
    # Opaque span of each face row; rows with the same span become one step of the stone.
    spans = []
    for row in range(16, 32):
        xs = [x for x in range(12) if img.getpixel((front + x, row))[3] > 0]
        spans.append((min(xs), max(xs) + 1) if xs else None)
    elements, row = [], 16
    while row < 32:
        span = spans[row - 16]
        end = row + 1
        while end < 32 and spans[end - 16] == span:
            end += 1
        if span:
            l, r = span
            y0, y1 = 32 - end, 32 - row  # sheet row 31 is model y 0
            faces = {
                "north": face(front + l, row, front + r, end),
                # Seen from behind the back face runs the other way, so its columns mirror.
                "south": face(back + 12 - r, row, back + 12 - l, end),
                "east": face(12, row, 16, end), "west": face(28, row, 32, end),
                "up": face(l, 12, r, 16)}
            if y0 == 0:
                faces["down"] = face(l, 12, r, 16)
            # Seen from the north, face column c is model x 14 - c.
            elements.append(box([14 - r, y0, 6], [14 - l, y1, 10], faces))
        row = end
    return elements


def main():
    for n in range(1, 8):
        img = Image.open(ASSETS / f"textures/item/tomb/t{n}.png").convert("RGBA")
        elements = cross() if n in CROSSES else headstone(img, FRONT[n])
        model = {"textures": {"t": f"taxsmp:item/tomb/t{n}", "particle": f"taxsmp:item/tomb/t{n}"}, "elements": elements}
        (ASSETS / f"models/tomb/t{n}.json").write_text(json.dumps(model, indent=2), encoding="utf-8")
    print("tombstones: 3 crosses, 4 headstones")


if __name__ == "__main__":
    main()
