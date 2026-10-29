"""Fail-closed source export. No account, network, build, or credential access.

Only FILES and TREE_TYPES below are eligible; exclusions are a second boundary.
The scanner is a heuristic review gate, not proof that arbitrary content is safe.
"""
import argparse
from dataclasses import dataclass
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import zipfile


FILES = {
    '.gitignore', 'README.md', 'LICENSE', 'THIRD_PARTY_NOTICES.md',
    'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'build.ps1', 'gradlew', 'gradlew.bat',
    'app/build.gradle.kts', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
    'docs/privacy.md', 'docs/troubleshooting.md',
    'docs/images/android-library.png', 'docs/images/windows-library.png',
    'scripts/prepare-public.ps1', 'scripts/public_export.py', 'scripts/test_public_export.py',
    'scripts/public-reviewed-fixtures.json',
    'scripts/build-windows.ps1', 'scripts/release_manifest.py',
    'scripts/test_release_manifest.py', 'scripts/check_windows_payload.py',
    'bridge/README.md', 'bridge/bridge.py', 'bridge/start_connections.py', 'bridge/user_setup.py',
    'bridge/chatgpt-local-mcp.toml.example', 'bridge/local_acceptance.py', 'bridge/usb_acceptance.py',
    'bridge/pairing_setup.py', 'bridge/test_bridge.py', 'bridge/test_pairing_setup.py',
    'bridge/test_shared_backend.py', 'bridge/test_start_connections.py', 'bridge/test_user_setup.py',
    'companion/README.md', 'companion/app.py', 'companion/core.py', 'companion/connections.py',
    'companion/covers.py', 'companion/image_decode.py', 'companion/preferences.py',
    'companion/theme.py', 'companion/tray.py', 'companion/Launcher.cs',
    'companion/demo.py', 'companion/PortableLauncher.cs', 'companion/setup.iss',
    'companion/install.ps1', 'companion/build-launcher.ps1', 'companion/requirements-runtime.txt',
    'companion/gui_acceptance.py', 'companion/usb_acceptance.py',
    'companion/test_companion.py', 'companion/test_connections.py', 'companion/test_covers.py',
    'companion/test_launcher.py', 'companion/test_preferences.py', 'companion/test_theme.py',
    'companion/test_startup.py', 'companion/test_local_ai_config.py',
    'companion/test_tray.py', 'companion/test_ui.py',
    'companion/licenses/Pillow-LICENSE.txt', 'companion/licenses/pystray-COPYING.txt',
    'companion/licenses/pystray-COPYING.LGPL.txt', 'companion/licenses/six-LICENSE.txt',
    'app/src/test/resources/ai-share/contract.json',
}
TREE_TYPES = {
    'app/src/main/java': {'.kt'}, 'app/src/main/res': {'.xml'},
    'app/src/test/java': {'.kt'}, 'app/src/androidTest/java': {'.kt'},
    'app/src/test/resources/metadata': {'.html'}, 'companion/fixtures': {'.html'},
}
FILES.add('app/src/main/AndroidManifest.xml')
REQUIRED_FILES = {
    'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
    'gradlew', 'gradlew.bat', 'app/build.gradle.kts',
    'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/com/qylent/manhwatakip/MainActivity.kt',
    'app/src/main/java/com/qylent/manhwatakip/LibraryApplication.kt',
}
MAX_FILE = 32 * 1024 * 1024
MAX_ARCHIVE = 64 * 1024 * 1024
ARCHIVES = {'.zip', '.jar', '.apk', '.aar', '.tar', '.gz', '.7z', '.rar', '.whl'}
PRIVATE_PART = re.compile(r'(?i)(^\.env|\.env(?:\.|$)|^agents\.md$|^local\.properties$|'
                          r'private|credential|keystore|^backups?(?:[._-]|$)|^chat(?:[._-]|$)|^library(?:[._-]|$)|'
                          r'^memory-bank$|^artifacts$|^__pycache__$|^\.git$|^build$|^data(?:[._-]|$)|^cache)')
PATTERNS = {
    'private-key': r'-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----',
    'credential': r'(?i)(?:\b(?:api[_-]?key|token|password|secret)\s*[=:]\s*[\"\x27][A-Za-z0-9_./+\-=]{20,}|\b(?:sk-|gh[pousr]_)[A-Za-z0-9_-]{20,}|\bAKIA[A-Z0-9]{16}\b)',
    # (?!\d)(?!\.\d) still rejects a longer dotted number, but a private address
    # followed by a sentence period must be caught; (?![\d.]) silently missed that.
    'private-network': r'(?<![\d.])(?:10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2}|169\.254(?:\.\d{1,3}){2})(?!\d)(?!\.\d)|(?i:https?://\[(?:fc|fd|fe80:)[0-9a-f:]+\])',
    'ssh-config': r'(?im)(?:^[ \t]*(?:Host|HostName|IdentityFile)[ \t]+[^\s=:]\S*|(?:ssh_alias|remote_command)\s*[=:]\s*[\"\x27][^\"\x27]+[\"\x27])',
    'personal-path': r'(?i)(?:[A-Z]:[\\/]Users[\\/](?!<|%|\$)[\w.-]+|/home/[\w.-]+/|/Users/[\w.-]+/)',
    'device-id': r'(?i)(?:device[_-]?(?:serial|id)|android[_-]?id)\s*[=:]\s*[\"\x27][A-Za-z0-9_-]{8,}[\"\x27]',
}


class ExportError(Exception):
    pass


@dataclass(frozen=True)
class ReviewedSyntheticException:
    """Human-reviewed synthetic network fixture ONLY; never approve live defaults.

    The CLI loads the narrow repository manifest; API callers must explicitly
    provide reviewed complete-file hashes and rationales to opt in.
    """
    file: str
    category: str
    sha256: str
    rationale: str


def identity(info):
    # Windows stat exposes creation time as ctime, but fstat may expose change
    # time (Python 3.13). Compare the unambiguous birth time there instead.
    timestamp = getattr(info, 'st_birthtime_ns', info.st_ctime_ns) if os.name == 'nt' else info.st_ctime_ns
    # Windows pathname stat synthesizes executable bits from extensions (.bat,
    # etc.), unlike handle fstat. Only file type is meaningful in that field;
    # keep full POSIX permissions and all other identity/reparse fields intact.
    mode = stat.S_IFMT(info.st_mode) if os.name == 'nt' else info.st_mode
    return (info.st_dev, info.st_ino, mode, info.st_size,
            info.st_mtime_ns, timestamp, getattr(info, 'st_file_attributes', 0))


def ancestry_identity(path):
    check_ancestors(path)
    # Ancestor directory timestamps also change for unrelated sibling writes.
    return ((path, identity(path.lstat())),) + tuple(
        (part, identity(part.lstat())[:3]) for part in path.parents)


def read_snapshot(path):
    """Bounded read; reject links and identity changes around open/read.

    O_NOFOLLOW is used where available; Windows reparse points and ancestry
    are checked before and after opening as well as against the open handle.
    This is not a filesystem lock against writers after verification returns.
    """
    path = Path(path)
    before = ancestry_identity(path)
    expected = path.lstat()
    if not stat.S_ISREG(expected.st_mode) or expected.st_size > MAX_FILE:
        raise ExportError('size-or-type-limit: file rejected')
    flags = os.O_RDONLY | getattr(os, 'O_BINARY', 0) | getattr(os, 'O_NOFOLLOW', 0)
    fd = os.open(path, flags)
    with os.fdopen(fd, 'rb') as stream:
        if identity(os.fstat(stream.fileno())) != identity(expected):
            raise ExportError('source-changed: file identity')
        data = stream.read(MAX_FILE + 1)
        if identity(os.fstat(stream.fileno())) != identity(expected):
            raise ExportError('source-changed: open file')
    if len(data) > MAX_FILE or ancestry_identity(path) != before:
        raise ExportError('source-changed: ancestry or content')
    return data


def is_link(path):
    info = path.lstat()
    return stat.S_ISLNK(info.st_mode) or bool(getattr(info, 'st_file_attributes', 0) & 0x400)


def check_ancestors(path):
    for part in (path, *path.parents):
        if os.path.lexists(part) and is_link(part):
            raise ExportError('reparse-point: source/destination ancestry rejected')


def private_path(relative, *, source_file=False):
    path = PurePosixPath(relative)
    # Eligible source basenames (Backup.kt, backup.xml, etc.) are not data
    # filenames. Directory privacy rules still apply; omissions fail below.
    parts = path.parts[:-1] if source_file else path.parts
    return any(PRIVATE_PART.search(part) for part in parts) or path.suffix.lower() in {
        '.env', '.jks', '.keystore', '.pem', '.p12', '.pfx', '.key', '.db', '.sqlite', '.sqlite3', '.pyc'
    }


def source_walk_error(error):
    raise ExportError('source-enumeration: unreadable source directory') from error


def required_sources(root, *, minimal_fixture=False):
    """Independent, unpruned inventory: actual main inputs cannot disappear.

    Minimal fixture mode only waives the fixed project skeleton, not inventory
    completeness or enumeration errors. It is intentionally unavailable in CLI.
    """
    required = set() if minimal_fixture else set(REQUIRED_FILES)
    base = root / 'app/src/main'
    check_ancestors(base)
    for current, dirs, files in os.walk(base, followlinks=False, onerror=source_walk_error):
        parent = Path(current)
        for name in dirs + files:
            check_ancestors(parent / name)
        for name in files:
            path = parent / name
            if path.suffix.lower() in {'.kt', '.xml'}:
                required.add(path.relative_to(root).as_posix())
    if not minimal_fixture:
        for folder in ('app/src/main/java', 'app/src/main/res'):
            if not (root / folder).is_dir():
                raise ExportError('required-source-missing: ' + folder)
    return required


def select_files(root, *, minimal_fixture=False):
    required = required_sources(root, minimal_fixture=minimal_fixture)
    selected = set()
    for name in FILES:
        path = root / name
        check_ancestors(path)
        if path.is_file() and not private_path(name):
            selected.add(name)
    for folder, extensions in TREE_TYPES.items():
        base = root / folder
        check_ancestors(base)
        if not base.is_dir():
            continue
        for current, dirs, files in os.walk(base, followlinks=False, onerror=source_walk_error):
            parent = Path(current)
            for child in dirs + files:
                if is_link(parent / child):
                    raise ExportError('reparse-point: ' + (parent / child).relative_to(root).as_posix())
            dirs[:] = [name for name in dirs if not private_path(name)]
            for name in files:
                path = parent / name
                relative = path.relative_to(root).as_posix()
                if path.suffix.lower() in extensions and not private_path(relative, source_file=True):
                    selected.add(relative)
    missing = required - selected
    if missing:
        raise ExportError('required-source-missing-or-excluded: ' + ', '.join(sorted(missing)))
    return sorted(selected)


def scan_bytes(label, data, reviewed_exceptions=()):
    for review in reviewed_exceptions:
        if (not isinstance(review, ReviewedSyntheticException)
                or review.category != 'private-network' or not review.rationale.strip()
                or not re.fullmatch(r'[0-9a-f]{64}', review.sha256)
                or not review.file or '\\' in review.file
                or review.file.startswith('/') or '..' in PurePosixPath(review.file).parts):
            raise ExportError('invalid-synthetic-review: explicit narrow review required')
    approved = {(review.file, review.category, review.sha256) for review in reviewed_exceptions}
    digest = hashlib.sha256(data).hexdigest()
    # Latin-1 is lossless for bytes; UTF-16 source is scanned too, including archives.
    texts = [data.decode('latin-1'), data.decode('utf-16-le', errors='ignore'),
             data.decode('utf-16-be', errors='ignore')]
    return [(label, category) for category, pattern in PATTERNS.items()
            if (label, category, digest) not in approved
            and any(re.search(pattern, text) for text in texts)]


def scan_content(label, data, reviewed_exceptions=()):
    if len(data) > MAX_FILE:
        return [(label, 'size-limit')]
    findings = scan_bytes(label, data, reviewed_exceptions)
    archive_expected = PurePosixPath(label).suffix.lower() in ARCHIVES
    if zipfile.is_zipfile(io.BytesIO(data)):
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                entries = archive.infolist()
                if len(entries) > 5000 or sum(x.file_size for x in entries) > MAX_ARCHIVE:
                    return findings + [(label, 'archive-limit')]
                for entry in entries:
                    member = PurePosixPath(entry.filename.replace('\\', '/'))
                    location = label + '!' + member.as_posix()
                    if member.is_absolute() or '..' in member.parts or ':' in entry.filename:
                        findings.append((label, 'archive-path'))
                    if private_path(member.as_posix()):
                        findings.append((location, 'private-file'))
                    if stat.S_ISLNK(entry.external_attr >> 16):
                        findings.append((location, 'archive-link'))
                    if entry.is_dir():
                        continue
                    if entry.file_size > MAX_FILE or entry.flag_bits & 1:
                        findings.append((location, 'archive-limit'))
                        continue
                    content = archive.read(entry)
                    if member.suffix.lower() in ARCHIVES or zipfile.is_zipfile(io.BytesIO(content)):
                        findings.append((location, 'nested-archive'))
                    findings.extend(scan_bytes(location, content, reviewed_exceptions))
        except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, NotImplementedError):
            findings.append((label, 'unreadable-archive'))
    elif archive_expected:
        findings.append((label, 'unreadable-archive'))
    return sorted(set(findings))


def scan_file(path, label, reviewed_exceptions=()):
    return scan_content(label, read_snapshot(path), reviewed_exceptions)


def staging_inventory(destination):
    check_ancestors(destination)
    inventory = {'.': identity(destination.lstat())}
    def walk_error(error):
        raise ExportError('staging-inventory: unreadable directory') from error
    for current, dirs, files in os.walk(destination, followlinks=False, onerror=walk_error):
        check_ancestors(Path(current))
        for name in dirs + files:
            path = Path(current) / name
            check_ancestors(path)
            info = path.lstat()
            if not (stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode)):
                raise ExportError('staging-inventory: special file rejected')
            inventory[path.relative_to(destination).as_posix()] = identity(info)
    return inventory


def verify_staging(destination, captured_hashes, reviewed_exceptions=()):
    expected = {'.', *captured_hashes}
    for name in captured_hashes:
        expected.update(parent.as_posix() for parent in PurePosixPath(name).parents if str(parent) != '.')
    before = staging_inventory(destination)
    if set(before) != expected:
        raise ExportError('staging-inventory: exact membership mismatch')
    for name, digest in captured_hashes.items():
        data = read_snapshot(destination / name)
        if hashlib.sha256(data).hexdigest() != digest or scan_content(name, data, reviewed_exceptions):
            raise ExportError('staging-scan: ' + name)
    # Re-enumerate after scanning: catch added files, replacements and junctions.
    if staging_inventory(destination) != before:
        raise ExportError('staging-changed: inventory or identity mismatch')


def export(root, destination, *, reviewed_exceptions=(), minimal_fixture=False):
    root = Path(os.path.abspath(root))
    destination = Path(os.path.abspath(destination))
    check_ancestors(root)
    check_ancestors(destination)
    if not root.is_dir() or root == destination or root in destination.parents or destination in root.parents:
        raise ExportError('unsafe-destination: use a new external sibling staging directory')
    if os.path.lexists(destination):
        raise ExportError('destination-exists: no overwrite or deletion performed')
    if not destination.parent.is_dir():
        raise ExportError('destination-parent-missing: create an external parent first')
    selected = select_files(root, minimal_fixture=minimal_fixture)
    findings = []
    for name in selected:
        findings.extend(scan_file(root / name, name, reviewed_exceptions))
    print('SCAN files=' + str(len(selected)) + ' findings=' + str(len(findings)))
    for name, category in findings:
        print(category + ': ' + name)
    if findings:
        raise ExportError('privacy-gate: export blocked; destination not created')
    # Exclusive creation prevents accidental overwrite. Nothing recursively copies a developer folder.
    destination.mkdir()
    try:
        captured_hashes = {}
        for name in selected:
            source = root / name
            check_ancestors(source)
            data = read_snapshot(source)
            if scan_content(name, data, reviewed_exceptions):
                raise ExportError('source-changed: ' + name)
            target = destination / name
            check_ancestors(target)
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open('xb') as output:
                output.write(data)
            captured_hashes[name] = hashlib.sha256(data).hexdigest()
        verify_staging(destination, captured_hashes, reviewed_exceptions)
    except BaseException:
        # Never clean up automatically: no destructive overwrite or recursive deletion.
        print('INCOMPLETE staging: do not publish; inspect destination manually')
        raise
    print('EXPORT_OK files=' + str(len(selected)) + ' staging_findings=0')
    return selected


REVIEWED_MANIFEST = 'scripts/public-reviewed-fixtures.json'
REVIEWED_PATHS = {
    'app/src/test/java/com/qylent/manhwatakip/PublicWebClientTest.kt',
    'companion/covers.py', 'companion/test_covers.py',
}


def load_reviewed_manifest(root):
    """Only known complete files and private-network reviews; no CLI bypass."""
    try:
        records = json.loads(read_snapshot(Path(root) / REVIEWED_MANIFEST).decode('utf-8'))
        if not isinstance(records, list):
            raise ValueError()
        reviews = []
        for record in records:
            if (not isinstance(record, dict)
                    or set(record) != {'file', 'category', 'sha256', 'rationale'}
                    or not all(isinstance(value, str) for value in record.values())
                    or record['file'] not in REVIEWED_PATHS
                    or any(review.file == record['file'] for review in reviews)):
                raise ValueError()
            review = ReviewedSyntheticException(**record)
            scan_bytes('manifest-validation', b'', (review,))
            reviews.append(review)
        return tuple(reviews)
    except (OSError, ValueError, UnicodeError):
        raise ExportError('invalid-synthetic-review-manifest: strict known-path schema required') from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', required=True)
    parser.add_argument('--source-root', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    try:
        reviews = load_reviewed_manifest(args.source_root)
        export(args.source_root, args.destination, reviewed_exceptions=reviews)
    except ExportError as error:
        print(str(error))
        return 1
    except (OSError, ValueError, zipfile.BadZipFile):
        print('export-io-error: export not verified; no content or exception values printed')
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
