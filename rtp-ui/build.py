"""Add artwork menus to an original FoliaRTP jar without changing its teleport core.

python build.py ORIGINAL.jar --classpath-file CLASSPATH.txt --asm-jar ASM.jar --asm-tree-jar ASM-TREE.jar
The original jar is a local build input and is never copied to the public repository.
"""
import argparse
import hashlib
import re
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def run_java(command, args, argfile):
    def quote(value):
        return '"' + str(value).replace('\\', '/').replace('"', '\\"') + '"'
    argfile.write_text('\n'.join(quote(a) for a in args) + '\n', encoding='utf-8')
    subprocess.run([command, '@' + str(argfile)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('original', type=Path)
    parser.add_argument('--classpath-file', type=Path, required=True)
    parser.add_argument('--asm-jar', type=Path, required=True)
    parser.add_argument('--asm-tree-jar', type=Path, required=True)
    args = parser.parse_args()
    build = ROOT / 'build'
    base = build / 'base'
    classes = build / 'classes'
    patcher = build / 'patcher'
    for folder in (base, classes, patcher):
        folder.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.original) as archive:
        original = {name: archive.read(name) for name in archive.namelist() if not name.endswith('/')}
    for name, data in original.items():
        if not name.endswith('.class'):
            continue
        path = base / name
        assert path.resolve().is_relative_to(base.resolve())
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    compile_cp = str(args.original.resolve()) + ';' + args.classpath_file.read_text().strip()
    sources = sorted((ROOT / 'src').rglob('*.java'))
    run_java('javac', ['--release', '21', '-encoding', 'UTF-8', '-cp', compile_cp, '-d', classes, *sources], build / 'compile.args')
    asm_cp = str(args.asm_jar.resolve()) + ';' + str(args.asm_tree_jar.resolve())
    run_java('javac', ['-encoding', 'UTF-8', '-cp', asm_cp, '-d', patcher, ROOT / 'PatchRtp.java'], build / 'patch-compile.args')
    run_java('java', ['-cp', str(patcher) + ';' + asm_cp, 'PatchRtp', base], build / 'patch.args')
    config = original['config.yml'].decode('utf-8').replace('\r', '')
    start = config.index('redis:\n')
    section = config[start:]
    private_values = [m.group(1).strip().strip('"\'') for m in re.finditer(r'(?m)^  (?:host|password):\s*(.+)$', section)]
    defaults = {'enabled': 'false', 'host': '"localhost"', 'port': '6379', 'password': '""'}
    section = re.sub(r'(?m)^  (enabled|host|port|password):.*$', lambda m: '  ' + m[1] + ': ' + defaults[m[1]], section)
    files = dict(original)
    files['config.yml'] = (config[:start] + section).encode('utf-8')
    files['plugin.yml'] = re.sub(r'(?m)^version:.*$', 'version: 1.0.0-taxsmp-ui.5', original['plugin.yml'].decode('utf-8').replace('\r', '')).encode('utf-8')
    # listeners/ArrivalListener is rebuilt from src/ to spawn cross-server RTP arrivals at their location
    # through AsyncPlayerSpawnLocationEvent (Paper no longer fires the old PlayerSpawnLocationEvent).
    modified = {'dev/foliartp/' + name + '.class' for name in ('FoliaRTP', 'gui/RTPGui', 'gui/RegionGui', 'gui/NetherRegionGui', 'listeners/ArrivalListener', 'rtp/TeleportHandler', 'rtp/TeleportHandler$AsyncLocationFinder')}
    for name in modified:
        files[name] = (base / name).read_bytes()
    for path in classes.rglob('*.class'):
        files[path.relative_to(classes).as_posix()] = path.read_bytes()
    for name, data in files.items():
        for secret in private_values:
            if len(secret) > 5 and secret.encode() in data:
                raise RuntimeError('Private configuration value remains in ' + name)
    for name in original:
        if name not in modified | {'config.yml', 'plugin.yml'}:
            assert original[name] == files[name], 'Unexpected core change: ' + name
    destination = ROOT.parent / 'dist/FoliaRTP.jar'
    with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            if name.upper().endswith(('.SF', '.RSA', '.DSA')):
                continue
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    destination.with_suffix('.jar.sha256').write_text(hashlib.sha256(destination.read_bytes()).hexdigest() + '\n')
    print('Built FoliaRTP.jar with artwork menus, the arrival spawn fix and sanitized default config. Teleport, Redis and queue classes are byte-identical to input.')


if __name__ == '__main__':
    main()
