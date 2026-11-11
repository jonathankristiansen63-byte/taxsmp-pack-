# Season Pass menu pictures for the /pass menu (TaxLink). Writes only pack/assets/taxsmp/{items,models,textures/item}/pass/.
#   python tools/pass_assets.py <store-keys-dir> <season-1-pass.png>
#   store-keys-dir: key-common.png ... key-tax.png (the key art from store.taxsmp.net)
#   season-1-pass.png: the Tebex package picture (TaxSMP Wrapped/tebex/season-1-pass.png)
import json, pathlib, sys, colorsys
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent / "pack" / "assets" / "taxsmp"
TEX = ROOT / "textures" / "item" / "pass"
MOD = ROOT / "models" / "pass"
ITM = ROOT / "items" / "pass"
for d in (TEX, MOD, ITM):
    d.mkdir(parents=True, exist_ok=True)

def register(name, texture):
    (MOD / f"{name}.json").write_text(json.dumps({"parent": "minecraft:item/generated", "textures": {"layer0": texture}}, indent=2) + "\n")
    (ITM / f"{name}.json").write_text(json.dumps({"model": {"type": "minecraft:model", "model": f"taxsmp:pass/{name}"}}, indent=2) + "\n")

def square(im, size):
    im = im.convert("RGBA")
    box = im.getchannel("A").point(lambda a: 255 if a > 8 else 0).getbbox() or (0, 0, im.width, im.height)
    im = im.crop(box)
    side = max(im.size)
    sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    sq.paste(im, ((side - im.width) // 2, (side - im.height) // 2))
    return sq.resize((size, size), Image.LANCZOS)

keys_dir, ticket_path = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])

# money: the TaxSMP $ bill already in the pack (scoreboard icon)
register("money", "taxsmp:item/sb/money")

# crate keys: the store art
for p in sorted(keys_dir.glob("key-*.png")):
    n = p.stem.split("-", 1)[1]
    square(Image.open(p), 64).save(TEX / f"key_{n}.png", optimize=True)
    register(f"key_{n}", f"taxsmp:item/pass/key_{n}")

# tickets: premium (the Tebex art without the sparkles), free (green), premium_locked (gray)
art = Image.open(ticket_path).convert("RGBA")
ticket = square(art.crop((130, 280, 1120, 975)), 64)
ticket.save(TEX / "premium.png", optimize=True)
register("premium", "taxsmp:item/pass/premium")

def recolor(im, fn):
    out = im.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a:
                px[x, y] = fn(r, g, b) + (a,)
    return out

def to_green(r, g, b):
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    if 0.03 < h < 0.18 and s > 0.25:            # gold/orange -> green
        h = 0.30 + (h - 0.03) * 0.4
    elif 0.65 < h < 0.85 and s > 0.2:            # purple star -> white-ish mint
        h, s = 0.42, s * 0.35
    r2, g2, b2 = colorsys.hsv_to_rgb(h, s, v)
    return int(r2 * 255), int(g2 * 255), int(b2 * 255)

recolor(ticket, to_green).save(TEX / "free.png", optimize=True)
register("free", "taxsmp:item/pass/free")

def to_gray(r, g, b):
    v = int((0.3 * r + 0.59 * g + 0.11 * b) * 0.62)
    return v, v, v

recolor(ticket, to_gray).save(TEX / "premium_locked.png", optimize=True)
register("premium_locked", "taxsmp:item/pass/premium_locked")

# level badges, 32x32 pixel art: done (green check), now (gold star), locked (gray lock)
OUT = (34, 20, 8, 255)
def badge(fill_top, fill_bot, symbol):
    im = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse((1, 1, 30, 30), fill=OUT)
    for y in range(3, 29):
        t = (y - 3) / 25
        col = tuple(int(fill_top[i] + (fill_bot[i] - fill_top[i]) * t) for i in range(3)) + (255,)
        d.line((3, y, 28, y), fill=col)
    mask = Image.new("L", (32, 32), 0)
    ImageDraw.Draw(mask).ellipse((3, 3, 28, 28), fill=255)
    ring = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    ring.paste(im, (0, 0), mask)
    base = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    ImageDraw.Draw(base).ellipse((1, 1, 30, 30), fill=OUT)
    base.alpha_composite(ring)
    d = ImageDraw.Draw(base)
    d.arc((5, 5, 26, 26), 200, 290, fill=(255, 255, 255, 150), width=2)    # shine
    symbol(d)
    return base

def check(d):
    d.line((9, 16, 14, 21), fill=OUT, width=5); d.line((14, 21, 23, 10), fill=OUT, width=5)
    d.line((9, 16, 14, 21), fill=(255, 255, 255, 255), width=3); d.line((14, 21, 23, 10), fill=(255, 255, 255, 255), width=3)

def star(d):
    import math
    pts = []
    for i in range(10):
        r = 10 if i % 2 == 0 else 4.4
        a = math.radians(-90 + i * 36)
        pts.append((16 + r * math.cos(a), 16.5 + r * math.sin(a)))
    d.polygon(pts, fill=(255, 255, 255, 255), outline=OUT)

def lock(d):
    d.arc((11, 7, 21, 18), 180, 360, fill=OUT, width=4)
    d.arc((12, 8, 20, 17), 180, 360, fill=(205, 205, 205, 255), width=2)
    d.rectangle((9, 14, 23, 24), fill=OUT)
    d.rectangle((10, 15, 22, 23), fill=(205, 205, 205, 255))
    d.rectangle((15, 17, 17, 21), fill=OUT)

badge((140, 255, 120), (30, 160, 60), check).save(TEX / "lv_done.png")
badge((255, 236, 140), (240, 150, 20), star).save(TEX / "lv_now.png")
badge((150, 150, 160), (80, 80, 92), lock).save(TEX / "lv_locked.png")
for n in ("lv_done", "lv_now", "lv_locked"):
    register(n, f"taxsmp:item/pass/{n}")

for f in sorted(ITM.iterdir()):
    print("items/pass/" + f.name)
