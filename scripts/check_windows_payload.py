"""Bounded offline acceptance; only a disposable LOCALAPPDATA is used."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def check(payload):
    payload = Path(payload).resolve()
    python = payload / 'runtime/python.exe'
    with tempfile.TemporaryDirectory(prefix='manhwa-payload-check-') as temporary:
        env = {**os.environ, 'LOCALAPPDATA': temporary, 'APPDATA': temporary,
               'PATH': str(Path(os.environ['WINDIR']) / 'System32'),
               'PYTHONHOME': temporary, 'PYTHONPATH': temporary}
        code = '''
import sys, pathlib, subprocess, tempfile
import tkinter, PIL, pystray, six, bridge, start_connections, user_setup, image_decode
from PIL import Image, features
assert sys.version_info[:3] == (3,13,15)
assert sys.flags.isolated and sys.flags.no_user_site
runtime = pathlib.Path(sys.executable).parent
assert all(pathlib.Path(p).is_relative_to(runtime.parent) for p in sys.path)
assert features.check('webp')
root = tkinter.Tk(); root.update(); root.destroy()
with tempfile.TemporaryDirectory() as temp:
    folder = pathlib.Path(temp)
    Image.new('RGB',(8,8),'blue').save(folder/'sample.webp','WEBP')
    subprocess.run([sys.executable,'-I','-B',str(runtime.parent/'image_decode.py'),str(folder/'sample.webp'),str(folder/'out.png'),str(folder/'out.jpg')],check=True,timeout=20)
    assert Image.open(folder/'out.jpg').size == (8,8)
subprocess.run([sys.executable,'-I','-B',str(runtime.parent/'bridge/bridge.py'),'--help'],check=True,capture_output=True,timeout=10)
assert not bridge.key_path().exists()
assert pathlib.Path(user_setup.server_config()['command']).samefile(sys.executable)
print('EMBEDDED_RUNTIME_TK_WEBP_HELPERS_OK')
'''
        for args in ([str(python), '-I', '-B', '-c', code],
                     [str(python), '-I', '-B', str(payload / 'app.py'), '--self-test'],
                     [str(payload / 'Manhwa Takip.exe'), '--self-test']):
            result = subprocess.run(args, cwd=temporary, env=env, capture_output=True, text=True, timeout=60)
            print(result.stdout, end='')
            if result.returncode:
                raise RuntimeError(f'Acceptance exit {result.returncode}: {result.stderr}')
        assert not list(Path(temporary).rglob('*.dpapi')), 'Self-test created credentials'
        print('PORTABLE_EXE_SELFTEST_OK isolated LOCALAPPDATA/PATH; no Python/SDK lookup')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('payload')
    check(parser.parse_args().payload)
