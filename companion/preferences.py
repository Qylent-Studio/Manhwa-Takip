"""Small, non-secret per-user desktop preferences; no OS startup side effects."""
from dataclasses import dataclass, field
import json
import os
from pathlib import Path
import tempfile


def default_path():
    base = Path(os.environ['LOCALAPPDATA']) if os.environ.get('LOCALAPPDATA') else Path.home() / '.local/share'
    return base / 'ManhwaTakipCompanion' / 'data' / 'preferences.json'


@dataclass
class Preferences:
    close_to_tray: bool = True
    start_with_windows: bool = False
    theme: str = 'system'
    _path: Path = field(default_factory=default_path, repr=False, compare=False)

    @classmethod
    def load(cls, path=None):
        result = cls(_path=Path(path) if path is not None else default_path())
        try:
            data = json.loads(result._path.read_text(encoding='utf-8'))
            if not isinstance(data, dict): return result
            for key in ('close_to_tray', 'start_with_windows'):
                if type(data.get(key)) is bool: setattr(result, key, data[key])
            if data.get('theme') in ('light', 'dark', 'system'): result.theme = data['theme']
        except (OSError, ValueError, RecursionError): pass
        return result

    def save(self):
        data = dict(close_to_tray=self.close_to_tray, start_with_windows=self.start_with_windows, theme=self.theme)
        if type(self.close_to_tray) is not bool or type(self.start_with_windows) is not bool or self.theme not in ('light', 'dark', 'system'):
            raise ValueError('Geçersiz uygulama ayarı.')
        self._path.parent.mkdir(parents=True, exist_ok=True)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=self._path.parent, prefix='.preferences-', suffix='.tmp', delete=False) as stream:
                temporary = Path(stream.name)
                json.dump(data, stream, ensure_ascii=False)
                stream.flush(); os.fsync(stream.fileno())
            os.replace(temporary, self._path)
        finally:
            if temporary is not None: temporary.unlink(missing_ok=True)
