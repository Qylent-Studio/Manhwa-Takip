"""Clean startup fixtures: no real credentials, ADB, registry writes or phone access."""
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

import app
import bridge


class StartupTests(unittest.TestCase):
    def run_startup(self, key_available, adb_available, unreadable=False):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            key = base / 'fixture.dpapi'
            if key_available: key.write_bytes(b'encrypted-fixture')
            adb = base / 'Android/Sdk/platform-tools/adb.exe'
            if adb_available:
                adb.parent.mkdir(parents=True); adb.touch()
            root = Mock()
            window = Mock(closed=threading.Event(), cover_cancel=threading.Event(), workers=[], tray=None)
            exchange = Mock()
            exchange.__enter__ = Mock(return_value=exchange)
            exchange.__exit__ = Mock(return_value=False)
            with patch.dict(os.environ, {'LOCALAPPDATA': folder}), patch.object(sys, 'argv', ['app.py']), \
                    patch.object(bridge, 'key_path', return_value=key), \
                    patch.object(bridge, 'protect', side_effect=ValueError('PRIVATE') if unreadable else None, return_value=b'fixture-key') as decrypt, \
                    patch.object(app.tk, 'Tk', return_value=root), patch.object(app, 'InstanceGate') as gate, \
                    patch.object(app, 'Window', return_value=window) as create_window, \
                    patch.object(app, 'Exchange', return_value=exchange) as create_exchange, \
                    patch.object(app.messagebox, 'showerror') as error:
                app.main()
                self.assertEqual(create_window.call_count, 1, 'Normal window must open before setup')
                self.assertFalse(create_window.call_args.kwargs.get('demo', False))
                root.mainloop.assert_called_once(); error.assert_not_called()
                if key_available and adb_available and not unreadable:
                    create_exchange.assert_called_once_with('fixture-key')
                    self.assertIs(create_window.call_args.args[2], exchange)
                    self.assertEqual(create_window.call_args.args[3], adb)
                    exchange.__enter__.assert_called_once(); exchange.__exit__.assert_called_once()
                    window.cleanup_reverse.assert_called_once()
                else:
                    create_exchange.assert_not_called()
                    self.assertIsNone(create_window.call_args.args[2])
                    self.assertIsNone(create_window.call_args.args[3])
                if not key_available: decrypt.assert_not_called()
                if key_available: self.assertEqual(key.read_bytes(), b'encrypted-fixture')
                gate.return_value.close.assert_called_once()

    def test_no_key_or_adb_opens_normal_offline_window(self): self.run_startup(False, False)
    def test_no_key_with_adb_opens_offline_without_decrypt(self): self.run_startup(False, True)
    def test_no_adb_with_key_opens_offline_without_exchange(self): self.run_startup(True, False)
    def test_unreadable_key_is_not_printed_or_replaced(self): self.run_startup(True, True, unreadable=True)
    def test_existing_prerequisites_keep_exchange_lifecycle(self): self.run_startup(True, True)

    @unittest.skipUnless(os.environ.get('MANHWA_STARTUP_NATIVE') == '1', 'Explicit native Windows proof')
    def test_native_clean_main_reaches_settings_and_connections_without_usb(self):
        real_window = app.Window
        checks = []
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            def create_window(*args, **kwargs):
                window = real_window(*args, **kwargs)
                def check():
                    try:
                        self.assertFalse(window.demo)
                        self.assertEqual(window.root.state(), 'normal')
                        self.assertTrue(window.root.winfo_viewable())
                        self.assertIsNone(window.exchange); self.assertIsNone(window.adb)
                        self.assertFalse(any(w._target == window.monitor for w in window.workers))
                        for page in (window.settings, window.connections_page):
                            window.tabs.select(page); window.root.update_idletasks()
                            self.assertEqual(window.tabs.select(), str(page))
                        self.assertIn('platform-tools', window.connection_status.get())
                        with patch.object(app.subprocess, 'Popen') as setup:
                            window.open_connection_setup()
                            self.assertTrue(setup.call_args.args[0][-1].endswith('user_setup.py'))
                            window.open_connection_setup(key=True)
                            self.assertEqual(setup.call_args.args[0][-1], '--key')
                        window.connection_action('start_usb')
                        window.connection_action('status')
                        window.launch('read', 'fixture')
                        self.assertIn('Bağlantılar', window.status.get())
                        checks.append(True)
                    finally:
                        for callback in window.root.tk.call('after', 'info'):
                            window.root.after_cancel(callback)
                        window.exit_application()
                window.root.after(300, check)
                # Tk normally swallows callback failures; surface them to unittest.
                window.root.report_callback_exception = lambda typ, value, tb: checks.append(value)
                return window
            with patch.dict(os.environ, {'LOCALAPPDATA': folder}), patch.object(sys, 'argv', ['app.py']), \
                    patch.object(bridge, 'key_path', return_value=base / 'missing.dpapi'), \
                    patch.object(bridge, 'protect', side_effect=AssertionError('No credential access')) as decrypt, \
                    patch.object(app, 'Window', side_effect=create_window), patch.object(app, 'InstanceGate'), \
                    patch.object(app, 'run_adb') as adb, patch.object(app, 'Exchange') as exchange, \
                    patch.object(app.ConnectionController, 'start_usb') as usb, \
                    patch.object(app.ConnectionController, 'status') as status, \
                    patch.object(app.messagebox, 'showerror') as error:
                app.main()
                self.assertEqual(checks, [True])
                decrypt.assert_not_called(); adb.assert_not_called(); exchange.assert_not_called()
                usb.assert_not_called(); status.assert_not_called(); error.assert_not_called()
            self.assertFalse((base / 'missing.dpapi').exists())
            self.assertFalse((base / 'ManhwaTakip').exists())


if __name__ == '__main__': unittest.main()
