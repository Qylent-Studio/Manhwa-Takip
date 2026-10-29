import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


class PreferencesTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'nested' / 'preferences.json'

    def load(self):
        import preferences
        return preferences.Preferences.load(self.path)

    def test_defaults_and_missing_file_do_not_write(self):
        prefs = self.load()
        self.assertEqual((prefs.close_to_tray, prefs.start_with_windows, prefs.theme), (True, False, 'system'))
        self.assertFalse(self.path.exists())

    def test_malformed_or_non_object_is_safe(self):
        self.path.parent.mkdir()
        for raw in (b'{broken', b'[]', b'null', b'\xff'):
            self.path.write_bytes(raw)
            self.assertEqual(self.load().theme, 'system')
            self.assertTrue(self.load().close_to_tray)

    def test_invalid_fields_fall_back_independently(self):
        self.path.parent.mkdir()
        self.path.write_text(json.dumps(dict(close_to_tray='false', start_with_windows=1, theme='unknown')))
        prefs = self.load()
        self.assertEqual((prefs.close_to_tray, prefs.start_with_windows, prefs.theme), (True, False, 'system'))

    def test_roundtrip(self):
        prefs = self.load()
        prefs.close_to_tray = False; prefs.start_with_windows = True; prefs.theme = 'dark'
        prefs.save()
        again = self.load()
        self.assertEqual((again.close_to_tray, again.start_with_windows, again.theme), (False, True, 'dark'))

    def test_replace_failure_preserves_previous_file_and_removes_temp(self):
        prefs = self.load(); prefs.save()
        before = self.path.read_bytes(); prefs.theme = 'dark'
        with patch('preferences.os.replace', side_effect=OSError('fixture disk failure')):
            with self.assertRaises(OSError): prefs.save()
        self.assertEqual(self.path.read_bytes(), before)
        self.assertEqual(list(self.path.parent.iterdir()), [self.path])

    def test_default_path_is_per_user_not_bundle(self):
        import preferences
        with patch.dict(os.environ, {'LOCALAPPDATA': self.temp.name}):
            self.assertEqual(preferences.default_path(), Path(self.temp.name) / 'ManhwaTakipCompanion/data/preferences.json')
