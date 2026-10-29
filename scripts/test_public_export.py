"""Synthetic-only exporter acceptance tests; no user settings or devices."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

try:
    import public_export as subject
except ImportError:
    subject = None


class PublicExportTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(subject, 'public_export implementation is missing')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / 'source'
        self.root.mkdir()
        self.dest = self.base / 'public'
        self.put('README.md', '# Safe fixture')
        self.put('app/src/main/java/example/Safe.kt', 'class Safe')

    def put(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf-8')
        return target

    def export_fixture(self, root, destination, **kwargs):
        return subject.export(root, destination, minimal_fixture=True, **kwargs)

    @unittest.skipUnless(os.name == 'nt', 'Windows executable-extension stat heuristic')
    def test_windows_batch_file_snapshot_preserves_identity(self):
        for name in ['example.bat', 'gradlew.bat']:
            with self.subTest(name=name):
                path = self.put(name, '@echo off\necho synthetic fixture\n')
                expected = path.read_bytes()
                with path.open('rb') as stream:
                    named = path.lstat()
                    opened = os.fstat(stream.fileno())
                    self.assertEqual(subject.identity(named), subject.identity(opened))
                    self.assertEqual(subject.identity(named)[:2], (named.st_dev, named.st_ino))
                    self.assertEqual(subject.identity(named)[3:],
                                     (named.st_size, named.st_mtime_ns, named.st_birthtime_ns,
                                      named.st_file_attributes))
                self.assertEqual(expected, subject.read_snapshot(path))

    def test_posix_identity_retains_full_mode(self):
        path = self.put('mode-fixture', 'safe')
        info = path.lstat()
        with mock.patch.object(subject.os, 'name', 'posix'):
            self.assertEqual(info.st_mode, subject.identity(info)[2])

    def test_safe_sources_and_example_enter_but_private_paths_do_not(self):
        self.put('bridge/chatgpt-local-mcp.toml.example', 'command = "python"')
        excluded = ['.env', '.env.production', 'bridge/.env.local', 'companion/settings-private.json',
                    'app/src/main/AGENTS.md', 'app/src/main/key.jks', 'local.properties',
                    'memory-bank/brain.md', 'artifacts/shot.png', 'companion/chat.json',
                    'companion/library.zip', 'companion/backup/data.py', 'companion/__pycache__/data.py',
                    'companion/credentials.py', 'bridge/connections-private.json',
                    'docs/superpowers/notes.md', 'companion/user.env', 'companion/.env-example']
        for path in excluded:
            self.put(path, 'PRIVATE_FIXTURE')
        self.export_fixture(self.root, self.dest)
        actual = {p.relative_to(self.dest).as_posix() for p in self.dest.rglob('*') if p.is_file()}
        self.assertEqual({'README.md', 'app/src/main/java/example/Safe.kt',
                          'bridge/chatgpt-local-mcp.toml.example'}, actual)

    def test_existing_destination_is_never_modified(self):
        self.dest.mkdir()
        marker = self.dest / 'keep.txt'
        marker.write_text('keep')
        with self.assertRaises(subject.ExportError):
            self.export_fixture(self.root, self.dest)
        self.assertEqual('keep', marker.read_text())
        self.assertEqual([marker], list(self.dest.iterdir()))

    def test_backup_source_names_are_not_private_data(self):
        names = ['app/src/main/java/example/BackupCodec.kt',
                 'app/src/main/java/example/Backup.kt',
                 'app/src/main/res/xml/backup.xml',
                 'app/src/main/java/example/BackupsActivity.kt',
                 'app/src/test/java/example/BackupCodecTest.kt']
        for name in names:
            self.put(name, 'class Fixture')
        selected = self.export_fixture(self.root, self.dest)
        self.assertTrue(set(names).issubset(selected))
        self.assertFalse(any('Hidden.kt' in name for name in selected))
        for name in ['backup.json', 'backups/data.json', 'backup.zip', 'data.json']:
            self.assertTrue(subject.private_path(name), name)

    def test_private_directory_cannot_silently_hide_required_source(self):
        for name in ['app/src/main/java/example/backups/Hidden.kt',
                     'app/src/main/res/data/hidden.xml']:
            path = self.put(name, 'PRIVATE_FIXTURE')
            with self.subTest(name=name), self.assertRaisesRegex(subject.ExportError, 'required-source'):
                self.export_fixture(self.root, self.dest)
            self.assertFalse(self.dest.exists())
            path.unlink()

    def test_default_api_requires_real_project_inputs(self):
        with self.assertRaisesRegex(subject.ExportError, 'required-source'):
            subject.export(self.root, self.dest)
        self.assertFalse(self.dest.exists())

    def test_each_essential_input_is_required(self):
        for name in subject.REQUIRED_FILES:
            self.put(name, 'safe')
        self.put('app/src/main/res/values/strings.xml', '<resources/>')
        self.assertTrue(subject.REQUIRED_FILES.issubset(subject.select_files(self.root)))
        for name in subject.REQUIRED_FILES:
            path = self.root / name
            path.unlink()
            with self.subTest(name=name), self.assertRaisesRegex(subject.ExportError, 'required-source'):
                subject.select_files(self.root)
            self.put(name, 'safe')

    def test_independent_inventory_detects_selection_omission(self):
        with mock.patch.dict(subject.TREE_TYPES, {'app/src/main/java': set()}):
            with self.assertRaisesRegex(subject.ExportError, 'required-source'):
                self.export_fixture(self.root, self.dest)

    def test_strict_export_contains_exact_windows_packaging_sources(self):
        inputs = {
            'companion/PortableLauncher.cs', 'companion/demo.py', 'companion/setup.iss',
            'scripts/build-windows.ps1', 'scripts/release_manifest.py',
            'scripts/check_windows_payload.py', 'scripts/test_release_manifest.py',
            'companion/test_startup.py', 'companion/test_local_ai_config.py',
            'companion/image_decode.py', 'companion/requirements-runtime.txt',
            'companion/licenses/Pillow-LICENSE.txt', 'companion/licenses/pystray-COPYING.txt',
            'companion/licenses/pystray-COPYING.LGPL.txt', 'companion/licenses/six-LICENSE.txt',
            'docs/images/android-library.png',
        }
        for name in subject.REQUIRED_FILES | inputs:
            self.put(name, 'safe synthetic source')
        self.put('app/src/main/res/values/strings.xml', '<resources/>')
        with zipfile.ZipFile(self.root / 'gradle/wrapper/gradle-wrapper.jar', 'w') as archive:
            archive.writestr('safe.txt', 'safe synthetic wrapper')
        excluded = {'companion/connections-private.json', 'companion/runtime/python.exe',
                    'companion/Setup.exe', 'artifacts/Setup.exe'}
        for name in excluded:
            self.put(name, 'not a public source input')
        selected = set(subject.export(self.root, self.dest))  # Strict default, not fixture mode.
        self.assertTrue(inputs.issubset(selected))
        self.assertTrue(all((self.dest / name).is_file() for name in inputs))
        self.assertTrue(selected.isdisjoint(excluded))
        self.assertNotIn('docs/images/windows-library.png', selected)  # Optional until created.
        self.put('docs/images/windows-library.png', 'safe synthetic image')
        self.assertIn('docs/images/windows-library.png', subject.select_files(self.root))

    def test_source_walk_errors_are_not_silently_ignored(self):
        walk = subject.os.walk
        for folder in ['app/src/main', 'app/src/main/java']:
            def fail_walk(path, **kwargs):
                if path == self.root / folder:
                    kwargs['onerror'](PermissionError('synthetic walk failure'))
                    return iter(())
                return walk(path, **kwargs)
            with self.subTest(folder=folder), mock.patch.object(subject.os, 'walk', side_effect=fail_walk):
                with self.assertRaisesRegex(subject.ExportError, 'source-enumeration'):
                    self.export_fixture(self.root, self.dest)
        self.assertFalse(self.dest.exists())

    def test_unselected_main_source_tree_fails_explicitly(self):
        self.put('app/src/main/kotlin/example/Extra.kt', 'class Extra')
        with self.assertRaisesRegex(subject.ExportError, 'required-source'):
            self.export_fixture(self.root, self.dest)
        self.assertFalse(self.dest.exists())

    def test_cli_has_no_implicit_minimal_fixture_bypass(self):
        self.put('scripts/public-reviewed-fixtures.json', '[]')
        output = io.StringIO()
        with mock.patch('sys.argv', ['public_export.py', '--source-root', str(self.root),
                                    '--destination', str(self.dest)]):
            with contextlib.redirect_stdout(output):
                self.assertEqual(1, subject.main())
        self.assertIn('required-source', output.getvalue())
        self.assertFalse(self.dest.exists())

    def test_reviewed_synthetic_exception_is_exact_and_opt_in(self):
        label = 'companion/test_connections.py'
        # Explicit synthetic fixture review, not a production endpoint exemption.
        data = ('fixture = "http://' + '192.168.' + '77.66:1234"').encode()
        self.put(label, data.decode())
        review = subject.ReviewedSyntheticException(
            label, 'private-network', hashlib.sha256(data).hexdigest(),
            'Reviewed synthetic fixture; never a default or live endpoint.')
        self.assertTrue(subject.scan_bytes(label, data))
        self.assertFalse(subject.scan_bytes(label, data, (review,)))
        self.assertTrue(subject.scan_bytes('companion/core.py', data, (review,)))
        self.assertTrue(subject.scan_bytes(label, data + b' ', (review,)))
        with self.assertRaises(subject.ExportError):
            self.export_fixture(self.root, self.dest)
        self.export_fixture(self.root, self.dest, reviewed_exceptions=(review,))
        self.assertEqual(data, (self.dest / label).read_bytes())

    def test_ssh_directives_block_but_code_operators_do_not(self):
        for directive in ['Host fixture', '  HostName example.invalid', 'IdentityFile ~/.ssh/fixture']:
            self.assertIn(('fixture', 'ssh-config'), subject.scan_bytes('fixture', directive.encode()))
        for code in ['host = uri.hostname', 'host == "example.invalid"', 'host : str',
                     'HostName\t= value', 'IdentityFile : value']:
            self.assertNotIn(('fixture', 'ssh-config'), subject.scan_bytes('fixture', code.encode()))

    def test_sentence_ending_private_address_is_blocked_but_longer_version_is_not(self):
        # A trailing sentence period must not hide a private address.
        self.assertIn(('fixture', 'private-network'),
                      subject.scan_bytes('fixture', ('adres: ' + '192.168.' + '3.21.').encode()))
        self.assertIn(('fixture', 'private-network'),
                      subject.scan_bytes('fixture', ('"' + '172.' + '16.0.1."').encode()))
        # A longer dotted number stays a version-like string, not an address.
        self.assertNotIn(('fixture', 'private-network'),
                         subject.scan_bytes('fixture', ('sdk ' + '10.' + '0.0.1.2').encode()))

    def test_manifest_strict_schema_known_paths_and_exact_digest(self):
        label = 'companion/test_covers.py'
        data = ('fixture = "http://' + '10.' + '0.0.1"').encode()
        record = dict(file=label, category='private-network', sha256=hashlib.sha256(data).hexdigest(),
                      rationale='Reviewed synthetic SSRF rejection fixture, not a live endpoint.')
        path = self.put('scripts/public-reviewed-fixtures.json', json.dumps([record]))
        reviews = subject.load_reviewed_manifest(self.root)
        self.assertFalse(subject.scan_bytes(label, data, reviews))
        self.assertTrue(subject.scan_bytes(label, data + b' ', reviews))
        for invalid in [{**record, 'file': 'companion/core.py'}, {**record, 'category': 'credential'},
                        {**record, 'sha256': 'bad'}, {**record, 'rationale': ''},
                        {**record, 'extra': True}, {**record, 'rationale': 1}]:
            path.write_text(json.dumps([invalid]), encoding='utf-8')
            with self.subTest(invalid=invalid), self.assertRaises(subject.ExportError):
                subject.load_reviewed_manifest(self.root)
        for invalid in [{}, [record, record]]:
            path.write_text(json.dumps(invalid), encoding='utf-8')
            with self.assertRaises(subject.ExportError): subject.load_reviewed_manifest(self.root)

    def test_cli_loads_known_manifest_without_fixture_bypass(self):
        with mock.patch('sys.argv', ['public_export.py', '--destination', str(self.dest)]), \
                mock.patch.object(subject, 'load_reviewed_manifest', return_value=('review',)) as load, \
                mock.patch.object(subject, 'export') as export:
            self.assertEqual(0, subject.main())
        load.assert_called_once()
        self.assertEqual(('review',), export.call_args.kwargs['reviewed_exceptions'])
        self.assertNotIn('minimal_fixture', export.call_args.kwargs)

    def test_review_cannot_suppress_other_categories_or_have_no_rationale(self):
        for category, rationale in [('private-key', 'Synthetic review'), ('private-network', '')]:
            review = subject.ReviewedSyntheticException('README.md', category, '0' * 64, rationale)
            with self.subTest(category=category), self.assertRaises(subject.ExportError):
                subject.scan_bytes('README.md', b'safe', (review,))

    def test_unselected_staging_entries_are_rejected(self):
        verify = subject.verify_staging
        for extra in ['private.json', 'empty-directory']:
            with self.subTest(extra=extra):
                destination = self.base / extra.replace('.', '-')
                def inject(stage, *args, **kwargs):
                    target = stage / extra
                    target.mkdir() if extra == 'empty-directory' else target.write_text('SAFE')
                    return verify(stage, *args, **kwargs)
                with mock.patch.object(subject, 'verify_staging', side_effect=inject):
                    with self.assertRaises(subject.ExportError):
                        self.export_fixture(self.root, destination)

    def test_staging_file_replacement_during_scan_is_rejected(self):
        scan = subject.scan_content
        verify = subject.verify_staging
        def check(stage, *args, **kwargs):
            def replace(label, data, *rest):
                result = scan(label, data, *rest)
                if label == 'README.md':
                    (stage / label).write_bytes(b'changed clean content')
                return result
            with mock.patch.object(subject, 'scan_content', side_effect=replace):
                return verify(stage, *args, **kwargs)
        with mock.patch.object(subject, 'verify_staging', side_effect=check):
            with self.assertRaises(subject.ExportError):
                self.export_fixture(self.root, self.dest)

    def test_staging_addition_during_scan_is_rejected(self):
        scan = subject.scan_content
        verify = subject.verify_staging
        def check(stage, *args, **kwargs):
            def add(label, data, *rest):
                result = scan(label, data, *rest)
                (stage / 'unexpected-private.json').write_text('SAFE')
                return result
            with mock.patch.object(subject, 'scan_content', side_effect=add):
                return verify(stage, *args, **kwargs)
        with mock.patch.object(subject, 'verify_staging', side_effect=check):
            with self.assertRaises(subject.ExportError):
                self.export_fixture(self.root, self.dest)

    def test_clean_staging_replacement_before_verification_is_rejected(self):
        verify = subject.verify_staging
        def replace(stage, *args, **kwargs):
            (stage / 'README.md').write_text('different but scanner-clean')
            return verify(stage, *args, **kwargs)
        with mock.patch.object(subject, 'verify_staging', side_effect=replace):
            with self.assertRaises(subject.ExportError):
                self.export_fixture(self.root, self.dest)

    def test_staging_directory_replaced_with_junction_is_rejected(self):
        verify = subject.verify_staging
        def replace(stage, *args, **kwargs):
            folder = stage / 'app'
            outside = self.base / 'moved-app'
            folder.rename(outside)
            self.make_directory_link(folder, outside)
            self.addCleanup(self.remove_directory_link, folder)
            return verify(stage, *args, **kwargs)
        with mock.patch.object(subject, 'verify_staging', side_effect=replace):
            with self.assertRaises(subject.ExportError):
                self.export_fixture(self.root, self.dest)

    def make_directory_link(self, link, outside):
        if os.name == 'nt':
            result = subprocess.run(['cmd', '/c', 'mklink', '/J', str(link), str(outside)], capture_output=True)
            self.assertEqual(0, result.returncode)
        else:
            link.symlink_to(outside, target_is_directory=True)

    def remove_directory_link(self, link):
        os.rmdir(link) if os.name == 'nt' else link.unlink()

    def test_open_time_source_replacement_is_rejected(self):
        source = self.root / 'README.md'
        original_open = subject.os.open
        def replace(path, flags, *args, **kwargs):
            if Path(path) == source:
                other = self.root / 'replacement'
                other.write_bytes(b'clean replacement')
                os.replace(other, source)
            return original_open(path, flags, *args, **kwargs)
        with mock.patch.object(subject.os, 'open', side_effect=replace):
            with self.assertRaises(subject.ExportError):
                subject.read_snapshot(source)

    def test_archive_scan_uses_captured_bytes_not_replaced_path(self):
        jar = self.root / 'gradle/wrapper/gradle-wrapper.jar'
        jar.parent.mkdir(parents=True)
        with zipfile.ZipFile(jar, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('fixture.txt', '-----BEGIN ' + 'PRIVATE KEY-----')
        captured = jar.read_bytes()
        read = subject.read_snapshot
        def replace(path):
            data = read(path)
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('safe.txt', 'SAFE')
            return data
        with mock.patch.object(subject, 'read_snapshot', side_effect=replace):
            self.assertIn(('wrapper.jar!fixture.txt', 'private-key'), subject.scan_file(jar, 'wrapper.jar'))
        self.assertNotEqual(captured, jar.read_bytes())

    def test_copy_scans_exact_archive_snapshot_after_preflight(self):
        jar = self.root / 'gradle/wrapper/gradle-wrapper.jar'
        jar.parent.mkdir(parents=True)
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('safe.txt', 'SAFE')
        clean = jar.read_bytes()
        unsafe = io.BytesIO()
        with zipfile.ZipFile(unsafe, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('fixture.txt', '-----BEGIN ' + 'PRIVATE KEY-----')
        read = subject.read_snapshot
        reads = 0
        def replace(path):
            nonlocal reads
            if path == jar:
                reads += 1
                if reads == 2:
                    # Copy captured unsafe bytes, but any path reread sees clean.
                    jar.write_bytes(clean)
                    return unsafe.getvalue()
            return read(path)
        with mock.patch.object(subject, 'read_snapshot', side_effect=replace):
            with self.assertRaisesRegex(subject.ExportError, 'source-changed'):
                self.export_fixture(self.root, self.dest)
        self.assertFalse((self.dest / 'gradle/wrapper/gradle-wrapper.jar').exists())

    def test_destination_inside_source_or_source_parent_is_rejected(self):
        for target in (self.root / 'public', self.root, self.base):
            with self.subTest(target=target), self.assertRaises(subject.ExportError):
                self.export_fixture(self.root, target)
        self.assertFalse((self.root / 'public').exists())

    def test_secrets_private_hosts_ssh_paths_and_devices_block_without_values(self):
        fixtures = {
            'private-key': '-----BEGIN ' + 'PRIVATE KEY-----\nSYNTHETIC',
            'credential': 'api_key = "' + 'sk-' + 'SyntheticOnlyToken12345678901234567890' + '"',
            'private-network': 'http://' + '192.168.' + '77.66:1234/v1',
            'ssh-config': 'ssh_alias = "' + 'fixture-remote' + '"',
            'personal-path': 'C:/' + 'Users/' + 'FixturePerson/private',
            'device-id': 'device_serial = "' + 'FixtureDevice123456' + '"',
        }
        for category, value in fixtures.items():
            with self.subTest(category=category):
                path = self.put('companion/core.py', value)
                output = io.StringIO()
                with contextlib.redirect_stdout(output), self.assertRaises(subject.ExportError):
                    self.export_fixture(self.root, self.dest)
                self.assertIn(category, output.getvalue())
                self.assertNotIn(value, output.getvalue())
                self.assertFalse(self.dest.exists())
                path.unlink()

    def test_allowed_archive_is_inspected_and_nested_archives_are_rejected(self):
        jar = self.root / 'gradle/wrapper/gradle-wrapper.jar'
        jar.parent.mkdir(parents=True)
        for name, value, category in [('secret.txt', '-----BEGIN ' + 'PRIVATE KEY-----', 'private-key'),
                                      ('.env.production', 'SAFE', 'private-file'),
                                      ('nested.zip', 'SAFE', 'nested-archive'),
                                      ('../escape.txt', 'SAFE', 'archive-path')]:
            with self.subTest(name=name):
                with zipfile.ZipFile(jar, 'w') as archive:
                    archive.writestr(name, value)
                findings = subject.scan_file(jar, 'gradle/wrapper/gradle-wrapper.jar')
                self.assertIn(category, {finding[1] for finding in findings})
                with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(subject.ExportError):
                    self.export_fixture(self.root, self.dest)
                self.assertFalse(self.dest.exists())

    def test_binary_content_cannot_hide_a_secret(self):
        path = self.put('companion/core.py', 'safe')
        path.write_bytes(b'\x00\xff' + b'-----BEGIN ' + b'PRIVATE KEY-----')
        self.assertIn('private-key', {x[1] for x in subject.scan_file(path, 'companion/core.py')})

    def test_directory_junction_escape_is_rejected(self):
        outside = self.base / 'outside'
        outside.mkdir()
        (outside / 'escape.py').write_text('SHOULD_NOT_COPY')
        link = self.root / 'companion'
        if os.name == 'nt':
            result = subprocess.run(['cmd', '/c', 'mklink', '/J', str(link), str(outside)], capture_output=True)
            self.assertEqual(0, result.returncode)
        else:
            link.symlink_to(outside, target_is_directory=True)
        try:
            with self.assertRaises(subject.ExportError):
                self.export_fixture(self.root, self.dest)
            self.assertFalse(self.dest.exists())
        finally:
            if os.name == 'nt':
                os.rmdir(link)
            else:
                link.unlink()

    def test_file_symlink_is_rejected_when_supported(self):
        outside = self.base / 'outside.txt'
        outside.write_text('SHOULD_NOT_COPY')
        link = self.root / 'companion/core.py'
        link.parent.mkdir()
        try:
            link.symlink_to(outside)
        except OSError as error:
            self.skipTest('File symlink requires OS permission: ' + str(error.winerror))
        with self.assertRaises(subject.ExportError):
            self.export_fixture(self.root, self.dest)
        self.assertFalse(self.dest.exists())

    def test_symlinked_destination_ancestor_is_rejected(self):
        outside = self.base / 'outside'
        outside.mkdir()
        link = self.base / 'redirect'
        if os.name == 'nt':
            result = subprocess.run(['cmd', '/c', 'mklink', '/J', str(link), str(outside)], capture_output=True)
            self.assertEqual(0, result.returncode)
        else:
            link.symlink_to(outside, target_is_directory=True)
        try:
            with self.assertRaises(subject.ExportError):
                self.export_fixture(self.root, link / 'public')
            self.assertFalse((outside / 'public').exists())
        finally:
            if os.name == 'nt':
                os.rmdir(link)
            else:
                link.unlink()


if __name__ == '__main__':
    unittest.main()
