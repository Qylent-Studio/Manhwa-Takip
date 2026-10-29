"""Explicit product inventory and fail-closed payload privacy checks."""
import hashlib
import json
from pathlib import Path
import re
import zipfile
from public_export import scan_content, load_reviewed_manifest

MODULES = ('app.py', 'core.py', 'connections.py', 'covers.py', 'image_decode.py',
           'preferences.py', 'theme.py', 'tray.py', 'demo.py')
BRIDGE = ('bridge.py', 'start_connections.py', 'user_setup.py')
REQUIRED = {*MODULES, *('bridge/' + name for name in BRIDGE), 'Manhwa Takip.exe',
            'runtime/python.exe', 'runtime/pythonw.exe', 'runtime/python313.zip',
            'runtime/python313._pth', 'runtime/_tkinter.pyd', 'runtime/tcl86t.dll',
            'runtime/tk86t.dll', 'runtime/zlib1.dll', 'runtime/Lib/tkinter/__init__.py',
            'runtime/Lib/site-packages/PIL/Image.py', 'runtime/Lib/site-packages/pystray/__init__.py',
            'runtime/Lib/site-packages/six.py', 'LICENSE', 'THIRD_PARTY_NOTICES.md'}
DENIED = re.compile(r'(?i)(^test_|^tests?$|^__pycache__$|^pip(?:[.-]|$)|^setuptools|^\.env|'
                    r'local[-_]config|connections-private|bridge-key|credential|^data$|^backups?$|'
                    r'^last-device|^drafts|^latest\.json$|\.pyc$|\.(?:key|pem|jks|pfx|db|sqlite)$)')
LEAK = re.compile(rb'(?i:-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----|[A-Z]:[\\/]Users[\\/][A-Za-z0-9_.-]+)|'
                  rb'(?<![A-Za-z0-9_])(?:sk-|gh[pousr]_)[A-Za-z0-9_-]{20,}')


def validate(root):
    root = Path(root)
    actual = {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()}
    missing = REQUIRED - actual
    if missing: raise ValueError('missing: ' + ', '.join(sorted(missing)))
    for name in sorted(actual):
        path = root / name
        if path.is_symlink() or any(DENIED.search(part) for part in Path(name).parts):
            raise ValueError('private/unwanted file: ' + name)
        data = path.read_bytes()
        if LEAK.search(data) or LEAK.search(data.replace(b'\x00', b'')):
            raise ValueError('sensitive/build-path bytes: ' + name)
        if name.endswith('.zip'):
            with zipfile.ZipFile(path) as archive:
                for member in archive.infolist():
                    if '..' in Path(member.filename).parts or member.filename.startswith('/'):
                        raise ValueError('unsafe archive path')
                    if LEAK.search(archive.read(member)):
                        raise ValueError('sensitive archive bytes: ' + name)
    return {name: hashlib.sha256((root / name).read_bytes()).hexdigest()
            for name in sorted(actual) if name != 'release-manifest.json'}


def stage_sources(source, destination):
    source, destination = Path(source), Path(destination)
    reviews = load_reviewed_manifest(source)
    for name in (*MODULES, *('bridge/' + x for x in BRIDGE), 'LICENSE', 'THIRD_PARTY_NOTICES.md'):
        original = name if name.startswith('bridge/') or '/' not in name and name in ('LICENSE', 'THIRD_PARTY_NOTICES.md') else 'companion/' + name
        data = (source / original).read_bytes()
        findings = scan_content(original, data, reviews)
        if findings: raise ValueError('source privacy findings: ' + repr(findings))
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('payload', type=Path)
    parser.add_argument('--source', type=Path)
    args = parser.parse_args()
    if args.source: stage_sources(args.source, args.payload)
    else:
        hashes = validate(args.payload)
        (args.payload / 'release-manifest.json').write_text(json.dumps({'version': '0.10.0', 'sha256': hashes}, indent=2), encoding='utf-8')
        print('PAYLOAD_MANIFEST_OK', len(hashes))
