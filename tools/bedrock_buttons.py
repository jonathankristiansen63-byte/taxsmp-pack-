"""Build the Bedrock pack (GUI buttons + scoreboard icons) and matching Geyser v2 item mappings.

Uses the exact inventory textures from the Java pack. The scoreboard icons are a Bedrock
glyph sheet (font/glyph_E2.png): character U+E200 + index shows SCOREBOARD_ICONS[index],
the same order as BEDROCK_ICON_ORDER in the TaxSMP plugin's ScoreboardManager.

    python tools/bedrock_buttons.py [path/to/26.3.jar]

The jar is only needed to (re)draw the glyph sheet from Minecraft's item textures.
Run after tools/gui.py, then copy the two dist outputs to the Geyser-Velocity
packs/ and custom_mappings/ folders.
"""
import hashlib
import io
import json
import os
import shutil
import sys
import uuid
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
LONG_PATH = '\\\\?\\'  # the Windows long path prefix \\?\
if os.name == 'nt' and not str(ROOT).startswith(LONG_PATH):
    # Windows skips files past 260 characters without this prefix.
    ROOT = Path(LONG_PATH + str(ROOT))
PACK = ROOT / 'bedrock/pack'
DIST = ROOT / 'dist'
VERSION = [1, 0, 3]  # raise on every change, or Bedrock players keep their old copy

SCOREBOARD_ICONS = ['name_tag', 'gold_ingot', 'amethyst_shard', 'egg', 'trial_key', 'clock_00',
                    'diamond_sword', 'bone', 'iron_chestplate', 'totem_of_undying', 'compass_16']


def draw_glyphs(jar_path):
    """16x16 grid of 16 px cells; cell i is the item picture for character U+E200 + i."""
    jar = zipfile.ZipFile(jar_path)
    sheet = Image.new('RGBA', (256, 256), (0, 0, 0, 0))
    for i, name in enumerate(SCOREBOARD_ICONS):
        icon = Image.open(io.BytesIO(jar.read(f'assets/minecraft/textures/item/{name}.png'))).convert('RGBA')
        sheet.paste(icon.crop((0, 0, 16, 16)), (i % 16 * 16, i // 16 * 16))
    (PACK / 'font').mkdir(parents=True, exist_ok=True)
    sheet.save(PACK / 'font/glyph_E2.png')

# Each (Java material, item_model) pair needs a unique Bedrock identifier.
# Restrict mappings to the actual menu materials, so vanilla gameplay items keep
# their regular textures unless the plugin gives them an explicit button model.
BASE_ITEMS = {
    'search': ['oak_sign'],
    'sort': ['cauldron'],
    'filter': ['hopper'],
    'refresh': ['egg', 'writable_book'],
    'mine': ['chest'],
    'prev': ['arrow'],
    'next': ['arrow'],
    'back': ['red_stained_glass_pane'],
    'yes': ['lime_stained_glass_pane', 'green_stained_glass_pane', 'lime_dye'],
    'no': ['red_stained_glass_pane', 'red_dye', 'red_terracotta'],
    'plus': ['lime_stained_glass_pane'],
    'minus': ['red_stained_glass_pane'],
    'info': ['book', 'lime_stained_glass_pane'],
}

RTP_ITEMS = {'overworld': 'moss_block', 'nether': 'nether_wart_block', 'end': 'end_stone_bricks',
             'region_eu': 'moss_block', 'region_na': 'moss_block'}


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8', newline='\n')


def main():
    if len(sys.argv) > 1:
        draw_glyphs(sys.argv[1])
    if not (PACK / 'font/glyph_E2.png').exists():
        sys.exit('font/glyph_E2.png is missing: run with the 26.3 client jar once to draw it')
    manifest = {
        'format_version': 2,
        'header': {
            'name': 'TaxSMP GUI Buttons',
            'description': 'Shop, auction and orders buttons, and the scoreboard icons.',
            'uuid': str(uuid.uuid5(uuid.NAMESPACE_URL, 'https://taxsmp.net/bedrock/gui-buttons/header')),
            'version': VERSION,
            'min_engine_version': [1, 21, 0],
        },
        'modules': [{
            'type': 'resources',
            'uuid': str(uuid.uuid5(uuid.NAMESPACE_URL, 'https://taxsmp.net/bedrock/gui-buttons/resources')),
            'version': VERSION,
        }],
        'metadata': {'authors': ['TaxSMP']},
    }
    write_json(PACK / 'manifest.json', manifest)
    shutil.copyfile(ROOT / 'pack/pack.png', PACK / 'pack_icon.png')
    texture_data = {}
    items = {}
    identifiers = set()
    for button, bases in BASE_ITEMS.items():
        icon = f'taxsmp_gui_{button}'
        texture_path = f'textures/items/taxsmp/{button}'
        target = PACK / (texture_path + '.png')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / f'pack/assets/taxsmp/textures/item/b/{button}.png', target)
        texture_data[icon] = {'textures': [texture_path]}
        for base in bases:
            identifier = f'taxsmp:gui_{button}_{base}'
            assert identifier not in identifiers
            identifiers.add(identifier)
            items.setdefault('minecraft:' + base, []).append({
                'type': 'definition',
                'model': 'taxsmp:b/' + button,
                'bedrock_identifier': identifier,
                'display_name': button.title(),
                'bedrock_options': {'icon': icon, 'creative_category': 'none'},
            })
    for name, base in RTP_ITEMS.items():
        icon = 'taxsmp_rtp_' + name
        texture_path = 'textures/items/taxsmp/rtp_' + name
        target = PACK / (texture_path + '.png')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / f'pack/assets/taxsmp/textures/item/rtp/{name}.png', target)
        texture_data[icon] = {'textures': [texture_path]}
        identifier = 'taxsmp:rtp_' + name + '_' + base
        assert identifier not in identifiers
        identifiers.add(identifier)
        items.setdefault('minecraft:' + base, []).append({
            'type': 'definition', 'model': 'taxsmp:rtp/' + name, 'bedrock_identifier': identifier,
            'display_name': name.replace('_', ' ').title(),
            'bedrock_options': {'icon': icon, 'creative_category': 'none'},
        })
    write_json(PACK / 'textures/item_texture.json', {
        'resource_pack_name': 'taxsmp_gui_buttons',
        'texture_name': 'atlas.items',
        'texture_data': texture_data,
    })
    mappings = {'format_version': 2, 'items': items}
    write_json(ROOT / 'bedrock/custom_mappings/taxsmp-buttons.json', mappings)
    write_json(DIST / 'taxsmp-buttons-geyser.json', mappings)
    output = DIST / 'TaxSMP-Bedrock-Buttons.mcpack'
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(PACK.rglob('*')):
            if path.is_file():
                info = zipfile.ZipInfo(path.relative_to(PACK).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.create_system = 0
                info.external_attr = 0
                archive.writestr(info, path.read_bytes())
    (DIST / 'TaxSMP-Bedrock-Buttons.mcpack.sha256').write_text(
        hashlib.sha256(output.read_bytes()).hexdigest() + '\n', encoding='utf-8', newline='\n')
    print(f'Built {output.name}: {len(texture_data)} textures, {len(identifiers)} Geyser definitions, {len(SCOREBOARD_ICONS)} scoreboard icons.')


if __name__ == '__main__':
    main()
