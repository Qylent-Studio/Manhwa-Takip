"""Exercise the real Windows launcher using a disposable app, without USB/data."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest


@unittest.skipUnless(os.name == 'nt', 'Windows executable')
class LauncherTests(unittest.TestCase):
    def test_startup_failure_reports_cause_without_hanging_self_test(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary); app = folder / 'ManhwaTakipCompanion'; app.mkdir()
            exe = folder / 'launcher.exe'
            subprocess.run(['powershell', '-NoProfile', '-File', str(Path(__file__).with_name('build-launcher.ps1')),
                            '-OutputPath', str(exe), '-Pythonw', sys.executable, '-ApplicationFolder', str(app)],
                           check=True, capture_output=True)
            self.assertEqual(subprocess.run([str(exe), '--self-test'], timeout=3).returncode, 1)
            self.assertIn('FileNotFoundException', (app / 'launcher-error.txt').read_text(encoding='utf-8-sig'))

    def test_native_exe_handles_unicode_paths_and_forwards_self_test_exit_code(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'Türkçe açılış'; folder.mkdir()
            app = folder / 'ManhwaTakipCompanion'; app.mkdir()
            marker = app / 'launched.json'
            (app / 'app.py').write_text(
                'import json, os, pathlib, sys\n'
                'pathlib.Path("launched.json").write_text(json.dumps({"args":sys.argv[1:],"cwd":os.getcwd(),"local":os.environ["LOCALAPPDATA"]}))\n'
                'sys.exit(37 if "--self-test" in sys.argv else 0)\n', encoding='utf-8')
            exe = folder / 'Manhwa Takip Bilgisayar.exe'
            build = Path(__file__).with_name('build-launcher.ps1')
            subprocess.run(['powershell', '-NoProfile', '-File', str(build), '-OutputPath', str(exe),
                            '-Pythonw', sys.executable, '-ApplicationFolder', str(app)], check=True, capture_output=True)
            environment = {**os.environ, 'LOCALAPPDATA': str(folder / 'different-environment')}
            self.assertEqual(subprocess.run([str(exe)], env=environment, timeout=10).returncode, 0)
            deadline = time.monotonic() + 10
            while not marker.exists() and time.monotonic() < deadline: time.sleep(.05)
            self.assertTrue(marker.exists(), 'EXE must actually start the app')
            result = json.loads(marker.read_text())
            self.assertEqual(result['args'], [])
            self.assertEqual(Path(result['cwd']), app)
            self.assertEqual(Path(result['local']), folder)
            self.assertEqual(subprocess.run([str(exe), '--self-test'], env=environment, timeout=10).returncode, 37)
            self.assertEqual(json.loads(marker.read_text())['args'], ['--self-test'])


if __name__ == '__main__': unittest.main()
