"""Build the RTP poster/map GUI, native item icons, and review previews."""
import json
import sys
from pathlib import Path
from PIL import Image
from gui import ASSETS, ROOT, CLEAR, DARK, LIGHT, PANEL_GRAY, panel, bar, plate_piece, rect, sunken, write_json
from pause_menu import Font


def text(image, font, label, center, y, color=(80, 80, 80, 255)):
    x = center - font.width(label) // 2
    for ch in label:
        if ch in font.glyphs:
            cell, scale, ascent, advance = font.glyphs[ch]
            glyph = cell.resize((round(cell.width * scale), round(cell.height * scale)), Image.Resampling.NEAREST)
            ink = Image.new('RGBA', glyph.size, color)
            ink.putalpha(glyph.getchannel('A'))
            image.alpha_composite(ink, (x, y + 7 - ascent))
        else:
            advance = font.advance(ch)
        x += advance


def title_plate(image, font, label):
    width = min(164, font.width(label) + 14)
    x = (176 - width) // 2
    image.alpha_composite(plate_piece('l'), (x, 4))
    middle = plate_piece('m1')
    for column in range(x + 4, x + width - 4):
        image.alpha_composite(middle, (column, 4))
    image.alpha_composite(plate_piece('r'), (x + width - 4, 4))
    text(image, font, label, 88, 6)


def pin(image, x, y):
    # x/y is the actual point on the map. Its white top and dark lip add shine.
    px = image.load()
    rect(px, x - 2, y - 10, x + 2, y - 10, (255, 126, 126, 255))
    rect(px, x - 3, y - 9, x + 3, y - 5, (219, 43, 45, 255))
    rect(px, x - 2, y - 4, x + 2, y - 3, (219, 43, 45, 255))
    rect(px, x - 1, y - 2, x + 1, y - 1, (150, 27, 32, 255))
    px[x, y] = (150, 27, 32, 255)
    rect(px, x - 1, y - 8, x + 1, y - 6, LIGHT)


def model(name, image):
    image.save(ASSETS / f'textures/item/rtp/{name}.png')
    write_json(ASSETS / f'models/rtp/{name}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'taxsmp:item/rtp/{name}'}})
    write_json(ASSETS / f'items/rtp/{name}.json', {'model': {'type': 'minecraft:model', 'model': f'taxsmp:rtp/{name}'}})


def build(jar_path):
    if not (ROOT / 'src/rtp/overworld.png').exists():
        return
    font = Font(jar_path)
    font.add_vanilla()
    (ASSETS / 'textures/item/rtp').mkdir(parents=True, exist_ok=True)
    posters = panel(None, 5)
    title_plate(posters, font, 'RANDOM TELEPORT')
    for index, dimension in enumerate(('overworld', 'nether', 'end')):
        source = Image.open(ROOT / f'src/rtp/{dimension}.png').convert('RGBA')
        x = 8 + index * 56
        px = posters.load()
        sunken(px, x - 1, 21, x + 49, 101, (0, 0, 0, 255))
        sunken(px, x, 22, x + 48, 100, (174, 174, 174, 255))
        sunken(px, x + 1, 23, x + 47, 98, (0, 0, 0, 255))
        posters.alpha_composite(source.resize((45, 72), Image.Resampling.NEAREST), (x + 2, 24))
        icon = Image.new('RGBA', (32, 32), CLEAR)
        sunken(icon.load(), 4, 0, 27, 31, (210, 210, 210, 255))
        icon.alpha_composite(source.resize((20, 28), Image.Resampling.NEAREST), (6, 2))
        model(dimension, icon)
    posters.save(ASSETS / 'textures/g/rtp.png')

    regions = panel(None, 6)
    title_plate(regions, font, 'SELECT REGION')
    sunken(regions.load(), 6, 20, 169, 103, (0, 0, 0, 255))
    world = Image.open(ROOT / 'src/rtp/map.png').convert('RGBA').resize((160, 80), Image.Resampling.NEAREST)
    regions.alpha_composite(world, (8, 22))
    # Pin tips sit on the eastern US and central Europe in this particular map.
    # Captions stay inside their matching inventory click regions.
    end_regions = regions.copy()
    for region, x, y, label, label_x, label_y in (('na', 46, 50, 'NA EAST', 51, 69), ('eu', 83, 45, 'EU CENTRAL', 112, 61)):
        width = font.width(label)
        for target in ((regions, end_regions) if region == 'eu' else (regions,)):
            pin(target, x, y)
            rect(target.load(), label_x - width // 2 - 2, label_y - 1, label_x + width // 2 + 2, label_y + 8, (33, 41, 50, 255))
            text(target, font, label, label_x, label_y, LIGHT)
        icon = Image.new('RGBA', (32, 32), (27, 72, 116, 255))
        icon.alpha_composite(world.resize((32, 16), Image.Resampling.NEAREST), (0, 8))
        pin(icon, round((x - 8) / 5), 8 + round((y - 22) / 5))
        model('region_' + region, icon)
    back = Image.open(ASSETS / 'textures/item/b/back.png').resize((16, 16), Image.Resampling.NEAREST)
    for name, target in (('regions', regions), ('regions_end', end_regions)):
        target.alpha_composite(bar(), (7, 107))
        target.alpha_composite(back, (80, 108))
        target.save(ASSETS / f'textures/g/{name}.png')
    model('hit', Image.new('RGBA', (16, 16), CLEAR))

    path = ASSETS / 'font/gui.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    data['providers'] = [p for p in data['providers'] if p.get('file') not in ('taxsmp:g/rtp.png', 'taxsmp:g/regions.png', 'taxsmp:g/regions_end.png')]
    for name, code, height in (('rtp', 0xE170, 107), ('regions', 0xE171, 125), ('regions_end', 0xE172, 125)):
        data['providers'].insert(0, {'type': 'bitmap', 'file': f'taxsmp:g/{name}.png', 'height': height, 'ascent': 13, 'chars': [chr(code)]})
    write_json(path, data)
    (ROOT / 'preview').mkdir(exist_ok=True)
    for name, image in (('rtp-menu', posters), ('rtp-regions', regions), ('rtp-end-region', end_regions)):
        image.resize((image.width * 4, image.height * 4), Image.Resampling.NEAREST).save(ROOT / f'preview/{name}.png')
    print('RTP artwork: three clickable Java posters, two-region and EU-only maps, five native Bedrock icons.')


if __name__ == '__main__':
    build(sys.argv[1])
