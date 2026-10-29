"""Connection operations, called by the native UI's background worker only."""
from pathlib import Path
import os
import subprocess
import sys
import threading

_bridge = Path(__file__).resolve().parent / 'bridge'
if not _bridge.is_dir(): _bridge = Path(__file__).resolve().parents[1] / 'bridge'
if str(_bridge) not in sys.path: sys.path.insert(0, str(_bridge))
import start_connections as launcher


class ConnectionController:
    def __init__(self):
        self._lock = threading.Lock()

    def status(self) -> dict:
        result = {'usb': 'bağlı değil', 'mcp': 'kapalı / eşleştirme gerekli', 'remote_ai': 'yapılandırılmadı'}
        try: result['usb'] = launcher.usb_status()
        except Exception: pass
        try:
            launcher.backend_module().RemoteBridge(launcher.existing_key()).health()
            result['mcp'] = 'hazır'
        except Exception: pass
        try:
            if launcher.load_config().get('ai_url'): result['remote_ai'] = 'özel ayar mevcut'
        except Exception: result['remote_ai'] = 'özel ayar okunamadı'
        return result

    def _run(self, action, label):
        with self._lock:
            try: return action()
            except Exception:
                raise RuntimeError(label + ' tamamlanamadı; işlem kısmen uygulanmış olabilir; durumu yenile.') from None

    def start_usb(self) -> str:
        return self._run(launcher.ensure_usb, 'USB')

    def prepare_pairing(self) -> str:
        def initialize():
            subprocess.run([sys.executable, *(['-I'] if sys.flags.isolated else []), str(_bridge / 'bridge.py'), '--init'],
                           stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                           check=True, timeout=30,
                           creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            return ('Yerel eşleştirme anahtarı hazır; varsa mevcut anahtar korundu. '
                    'Bilgisayar uygulamasından Tamamen çık ve yeniden aç. '
                    'Ardından Mevcut eşleştirme anahtarı… ile telefona kendin kaydet. '
                    'Telefon paylaşımı ve MCP başlatılmadı.')
        return self._run(initialize, 'İlk eşleştirme hazırlığı')

    def start_mcp(self) -> str:
        return self._run(lambda: launcher.ensure_mcp(restart=False), 'MCP')

    def restart_mcp(self) -> str:
        return self._run(lambda: launcher.ensure_mcp(restart=True), 'MCP yeniden başlatma')
