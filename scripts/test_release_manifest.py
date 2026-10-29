"""Focused Windows payload gates; synthetic fixtures only."""
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))


class ReleaseManifestTests(unittest.TestCase):
    def test_recipe_and_launcher_are_portable_and_preserve_data(self):
        root = Path(__file__).resolve().parents[1]
        recipe = root / 'companion/setup.iss'
        self.assertTrue(recipe.is_file(), 'user-only installer recipe missing')
        text = recipe.read_text()
        self.assertIn('PrivilegesRequired=lowest', text)
        self.assertIn('{localappdata}\\Programs\\ManhwaTakip', text)
        self.assertNotIn('[UninstallDelete]', text)
        self.assertNotIn('[Registry]', text)
        launcher = (root / 'companion/PortableLauncher.cs').read_text()
        self.assertIn('AppDomain.CurrentDomain.BaseDirectory', launcher)
        self.assertNotIn('__PYTHON', launcher)
        self.assertNotIn('EnvironmentVariables["LOCALAPPDATA"]', launcher)
        self.assertIn('-I -B', launcher)

    def test_embedded_stdio_config_disables_user_site(self):
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'bridge'))
        from unittest.mock import patch
        import user_setup
        with tempfile.TemporaryDirectory() as temporary:
            executable = Path(temporary) / 'pythonw.exe'
            (executable.parent / 'python313._pth').write_text('import site\n')
            with patch.object(user_setup.sys, 'executable', str(executable)):
                server = user_setup.server_config()
            self.assertEqual(server['args'][0], '-I')

    def test_manifest_rejects_missing_helpers_and_sensitive_bytes(self):
        import release_manifest as manifest
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for relative in manifest.REQUIRED:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'synthetic')
            with zipfile.ZipFile(root / 'runtime/python313.zip', 'w') as archive:
                archive.writestr('synthetic.pyc', b'synthetic')
            manifest.validate(root)
            helper = root / 'bridge/user_setup.py'
            helper.unlink()
            with self.assertRaisesRegex(ValueError, 'missing'): manifest.validate(root)
            helper.write_bytes(b'synthetic')
            for name, value in [('runtime/.env', b'x'), ('runtime/leak.bin', b'-----BEGIN ' + b'PRIVATE KEY-----'),
                                ('runtime/leak.bin', b'C:\\Users\\SyntheticBuild\\Python\\python.exe'),
                                ('bridge/local-config.json', b'{}')]:
                path = root / name
                path.write_bytes(value)
                with self.subTest(name=name, value=value), self.assertRaises(ValueError): manifest.validate(root)
                path.unlink()

    def test_demo_has_no_private_data_and_selftest_needs_no_tests(self):
        root = Path(__file__).resolve().parents[1]
        sys.path.insert(0, str(root / 'companion'))
        import demo
        data = demo.snapshot()
        self.assertEqual(data['chats'], [])
        self.assertNotIn('keys', data)
        self.assertEqual(data['library']['records'][0]['lastChapter'], '8')
        self.assertNotIn('from test_companion import', (root / 'companion/app.py').read_text(encoding='utf-8'))


if __name__ == '__main__': unittest.main()
