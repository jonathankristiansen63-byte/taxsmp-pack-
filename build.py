"""Build dist/TaxSMP-Pack.zip from the pack/ folder.

- Copies the lang overrides in pack/assets/minecraft/lang/en_us.json into every
  Minecraft language, so players who don't play in English get them too.
- Checks that every font glyph is valid (<= 256x256 px, ascent <= height).
- Writes a byte-for-byte reproducible zip, plus its SHA-1 next to it.

Usage: python build.py
"""
import hashlib
import json
import os
import struct
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
LONG_PATH = "\\\\?\\"  # the Windows long path prefix \\?\
if os.name == "nt" and not str(ROOT).startswith(LONG_PATH):
    # Windows skips files past 260 characters without this prefix.
    ROOT = Path(LONG_PATH + str(ROOT))
PACK = ROOT / "pack"
DIST = ROOT / "dist"
ZIP_PATH = DIST / "TaxSMP-Pack.zip"

# Every language the vanilla Java client ships with.
LANGUAGES = """
af_za ar_sa ast_es az_az ba_ru bar be_by be_latn bg_bg br_fr brb bs_ba ca_es
cs_cz cy_gb da_dk de_at de_ch de_de el_gr en_au en_ca en_gb en_nz en_pt en_ud
en_us enp enws eo_uy es_ar es_cl es_ec es_es es_mx es_uy es_ve esan et_ee eu_es
fa_ir fi_fi fil_ph fo_fo fr_ca fr_fr fra_de fur_it fy_nl ga_ie gd_gb gl_es
haw_us he_il hi_in hn_no hr_hr hu_hu hy_am id_id ig_ng io_en is_is isv it_it
ja_jp jbo_en ka_ge kk_kz kn_in ko_kr ksh kw_gb ky_kg la_la lb_lu li_li lmo lo_la
lol_us lt_lt lv_lv lzh mk_mk mn_mn ms_my mt_mt nah nb_no nds_de nl_be nl_nl
nn_no no_no oc_fr ovd pl_pl pls pt_br pt_pt qya_aa ro_ro rpr ru_ru ry_ua sah_sah
se_no sk_sk sl_si so_so sq_al sr_cs sr_sp sv_se sxu szl ta_in th_th tl_ph tlh_aa
tok tr_tr tt_ru tzo_mx uk_ua val_es vec_it vi_vn vp_vl yi_de yo_ng zh_cn zh_hk
zh_tw zlm_arab
""".split()

TEXT_SUFFIXES = {".json", ".mcmeta", ".txt", ".properties", ".fsh", ".vsh", ".glsl"}


def fail(msg):
    sys.exit(f"BUILD FAILED: {msg}")


def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        fail(f"{path} is not a PNG")
    return struct.unpack(">II", head[16:24])


def load_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        fail(f"{path.relative_to(ROOT)} is not valid JSON: {e}")


def check_fonts():
    for font in PACK.glob("assets/*/font/*.json"):
        for p in load_json(font).get("providers", []):
            if p.get("type") != "bitmap":
                continue
            ns, _, rel = p["file"].partition(":")
            if not rel:
                ns, rel = "minecraft", ns
            tex = PACK / "assets" / ns / "textures" / rel
            if not tex.exists():
                fail(f"{font.name}: texture {p['file']} not found at {tex.relative_to(ROOT)}")
            w, h = png_size(tex)
            rows = p["chars"]
            cell_w, cell_h = w // max(len(r) for r in rows), h // len(rows)
            if cell_w > 256 or cell_h > 256:
                fail(f"{p['file']}: glyphs are {cell_w}x{cell_h}, Minecraft's limit is 256x256")
            if p.get("ascent", 0) > p.get("height", 8):
                fail(f"{font.name}: ascent {p['ascent']} is higher than height {p.get('height', 8)}")


def collect_files():
    files = {}
    for path in sorted(PACK.rglob("*")):
        if path.is_file():
            data = path.read_bytes()
            if path.suffix in TEXT_SUFFIXES:
                if path.suffix in {".json", ".mcmeta"}:
                    load_json(path)
                data = data.replace(b"\r\n", b"\n")
            files[path.relative_to(PACK).as_posix()] = data

    base_path = PACK / "assets/minecraft/lang/en_us.json"
    if base_path.exists():
        base = load_json(base_path)
        for lang in LANGUAGES:
            name = f"assets/minecraft/lang/{lang}.json"
            own = load_json(PACK / name) if (PACK / name).exists() else {}
            merged = {**base, **own}
            files[name] = (json.dumps(merged, indent=2, sort_keys=True) + "\n").encode()
    return files


def write_zip(files):
    DIST.mkdir(exist_ok=True)
    with zipfile.ZipFile(ZIP_PATH, "w", zipfile.ZIP_STORED) as z:
        for name in sorted(files):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 0
            info.external_attr = 0
            z.writestr(info, files[name])


def main():
    if not (PACK / "pack.mcmeta").exists():
        fail("pack/pack.mcmeta is missing")
    check_fonts()
    files = collect_files()
    write_zip(files)
    sha1 = hashlib.sha1(ZIP_PATH.read_bytes()).hexdigest()
    (DIST / "TaxSMP-Pack.zip.sha1").write_bytes((sha1 + "\n").encode())
    print(f"Built {ZIP_PATH.relative_to(ROOT)} ({ZIP_PATH.stat().st_size // 1024} KB, {len(files)} files)")
    print(f"SHA-1: {sha1}")


if __name__ == "__main__":
    main()
