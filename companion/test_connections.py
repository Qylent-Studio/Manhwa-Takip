"""Isolated connection checks: no real USB, key, SSH or account access."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'bridge'))
import start_connections as launcher


class ConnectionsTests(unittest.TestCase):
    def test_first_pairing_invokes_only_silent_init_and_returns_restart_guidance(self):
        import connections
        with patch('connections.subprocess.run') as run, patch.object(launcher, 'ensure_mcp') as mcp, patch.object(launcher, 'ensure_usb') as usb:
            text = self.controller().prepare_pairing()
            command = run.call_args.args[0]
            self.assertEqual(command[-2:], [str(connections._bridge / 'bridge.py'), '--init'])
            self.assertEqual(run.call_args.kwargs['stdout'], connections.subprocess.DEVNULL)
            self.assertEqual(run.call_args.kwargs['stderr'], connections.subprocess.DEVNULL)
            self.assertTrue(run.call_args.kwargs['check'])
            self.assertIn('Tamamen çık', text)
            self.assertIn('yeniden aç', text)
            mcp.assert_not_called(); usb.assert_not_called()

    def test_first_pairing_failure_is_sanitized(self):
        with patch('connections.subprocess.run', side_effect=RuntimeError('SECRET')):
            with self.assertRaises(RuntimeError) as error: self.controller().prepare_pairing()
            self.assertNotIn('SECRET', str(error.exception))

    def test_existing_init_preserves_existing_key_without_generating(self):
        backend = launcher.backend_module()
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'fixture.dpapi'; path.write_bytes(b'fake-encrypted-existing')
            with patch.object(backend, 'key_path', return_value=path), patch.object(sys, 'argv', ['bridge.py', '--init']), patch.object(backend.secrets, 'token_urlsafe') as generate, patch.object(backend, 'protect') as protect, patch('builtins.print'):
                backend.main()
            self.assertEqual(path.read_bytes(), b'fake-encrypted-existing')
            generate.assert_not_called(); protect.assert_not_called()

    def controller(self, **kwargs):
        import connections
        return connections.ConnectionController(**kwargs)

    def test_start_and_restart_reuse_existing_backend_functions(self):
        with patch.object(launcher, 'ensure_mcp', return_value='ready') as start:
            controller = self.controller()
            self.assertEqual(controller.start_mcp(), 'ready')
            self.assertEqual(controller.restart_mcp(), 'ready')
            self.assertEqual(start.call_args_list[0].kwargs, {'restart': False})
            self.assertEqual(start.call_args_list[1].kwargs, {'restart': True})

    def test_empty_remote_config_performs_zero_ssh_or_network_calls(self):
        with patch.object(launcher, 'load_config', return_value={}), patch.object(launcher, 'run') as run, patch.object(launcher.urllib.request, 'urlopen') as network:
            self.assertIn('yapılandırılmadı', launcher.ensure_ai())
            run.assert_not_called(); network.assert_not_called()

    def test_usb_only_adds_8765_no_rebind_and_never_opens_sharing(self):
        output = ['PHONE device\n', 'Usb tcp:9999 tcp:9999\n', '', 'Usb tcp:8765 tcp:8765\n']
        with patch.object(Path, 'is_file', return_value=True), patch.object(launcher, 'run', side_effect=output) as run:
            self.controller().start_usb()
            self.assertEqual(run.call_args_list[2].args[0][-3:], ['--no-rebind', 'tcp:8765', 'tcp:8765'])
            self.assertEqual(len(run.call_args_list), 4)

    def test_usb_conflict_is_not_changed(self):
        with patch.object(Path, 'is_file', return_value=True), patch.object(launcher, 'run', side_effect=['PHONE device', 'Usb tcp:8765 tcp:9999']) as run:
            with self.assertRaises(RuntimeError): self.controller().start_usb()
            self.assertEqual(run.call_count, 2)

    def test_status_is_allowlisted_and_does_not_start_backend(self):
        backend = Mock()
        backend.RemoteBridge.return_value.health.return_value = {'server': 'manhwa-usb-bridge', 'pid': 123, 'key': 'SECRET', 'library': 'PRIVATE'}
        with patch.object(launcher, 'backend_module', return_value=backend), patch.object(launcher, 'existing_key', return_value='SECRET'), patch.object(launcher, 'usb_status', return_value='hazır'), patch.object(launcher, 'load_config', return_value={}):
            status = self.controller().status()
            self.assertEqual(status, {'usb': 'hazır', 'mcp': 'hazır', 'remote_ai': 'yapılandırılmadı'})
            self.assertNotIn('SECRET', json.dumps(status)); backend.ensure_backend.assert_not_called()

    def test_error_text_does_not_leak_keys(self):
        with patch.object(launcher, 'ensure_mcp', side_effect=ValueError('SECRET')):
            with self.assertRaisesRegex(RuntimeError, 'MCP') as error: self.controller().start_mcp()
            self.assertNotIn('SECRET', str(error.exception))

    def test_partial_usb_mapping_failure_reports_uncertain_state_and_refresh(self):
        outputs = ['PHONE device', '', '', RuntimeError('SECRET verification failure')]
        with patch.object(Path, 'is_file', return_value=True), patch.object(launcher, 'run', side_effect=outputs) as run:
            with self.assertRaises(RuntimeError) as error: self.controller().start_usb()
            self.assertEqual(run.call_args_list[2].args[0][-3:], ['--no-rebind', 'tcp:8765', 'tcp:8765'])
            self.assertEqual(run.call_count, 4)  # Added successfully, verification failed; no rollback.
            self.assertIn('kısmen uygulanmış olabilir', str(error.exception))
            self.assertIn('durumu yenile', str(error.exception))
            self.assertNotIn('SECRET', str(error.exception))

    def test_partial_backend_shutdown_failure_reports_uncertain_state_and_refresh(self):
        backend = launcher.backend_module()
        with patch.object(launcher, 'existing_key', return_value='fixture'), patch.object(backend.RemoteBridge, 'health', side_effect=[{'pid': 123}, ValueError('offline')]), patch.object(backend.RemoteBridge, 'post') as shutdown, patch.object(backend, 'ensure_backend', side_effect=RuntimeError('SECRET restart failure')):
            with self.assertRaises(RuntimeError) as error: self.controller().restart_mcp()
            shutdown.assert_called_once_with('/shutdown', {})
            self.assertIn('kısmen uygulanmış olabilir', str(error.exception))
            self.assertIn('durumu yenile', str(error.exception))
            self.assertNotIn('SECRET', str(error.exception))

    def test_existing_mcp_backend_does_not_spawn_a_second_process(self):
        backend = launcher.backend_module()
        with patch.object(launcher, 'existing_key', return_value='fixture'), patch.object(backend.RemoteBridge, 'health', return_value={'pid': 123}), patch.object(backend.subprocess, 'Popen') as spawn:
            self.controller().start_mcp()
            spawn.assert_not_called()

    def test_existing_reverse_is_not_owned_removed_or_replaced(self):
        with patch.object(Path, 'is_file', return_value=True), patch.object(launcher, 'run', side_effect=['PHONE device', 'Usb tcp:8765 tcp:8765', 'Usb tcp:8765 tcp:8765']) as run:
            self.controller().start_usb()
            self.assertEqual(run.call_count, 3)
            self.assertTrue(all('--list' in call.args[0] or 'devices' in call.args[0] for call in run.call_args_list))

    def test_private_override_is_loaded_and_public_defaults_are_empty(self):
        with tempfile.TemporaryDirectory() as folder:
            config = Path(folder) / 'private.json'
            with patch.object(launcher, 'private_config_path', return_value=config):
                self.assertEqual(launcher.load_config(), {})
                config.write_text(json.dumps({'ai_url': 'http://example.test/models', 'ssh_alias': 'fixture', 'remote_command': 'fixture command', 'token': 'SECRET'}))
                self.assertNotIn('token', launcher.load_config())
                with patch.object(launcher, 'ai_reachable', return_value=False), patch.object(launcher, 'run') as run, patch.object(launcher.time, 'sleep'):
                    with self.assertRaises(RuntimeError): launcher.ensure_ai()
                    self.assertEqual(run.call_args.args[0][-2:], ['fixture', 'fixture command'])

    def test_install_manifest_is_explicit(self):
        text = (Path(__file__).parent / 'install.ps1').read_text(encoding='utf-8-sig')
        self.assertIn("'connections.py'", text)
        self.assertIn("@('bridge.py','start_connections.py','user_setup.py')", text)
        self.assertNotIn('-Recurse', text)

    def test_private_migration_preserves_old_values_without_overwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'old.py'; target = Path(folder) / 'private.json'
            source.write_text('AI_URL = "http://example.test/models"\ndef ensure_ai():\n remote = "existing command"\n run([str(ssh), "-T", "fixture-alias", remote])\n', encoding='utf-8')
            launcher.migrate_private(source, target)
            self.assertEqual(json.loads(target.read_text())['ssh_alias'], 'fixture-alias')
            target.write_text('{}'); launcher.migrate_private(source, target)
            self.assertEqual(target.read_text(), '{}')


class NativeConnectionsTests(unittest.TestCase):
    def test_first_pairing_offline_requires_confirmation_and_uses_worker_queue(self):
        import tkinter as tk
        from app import Window
        controller = Mock(); calls = []; gate = threading.Event()
        main_thread = threading.get_ident()
        def initialize():
            calls.append(threading.get_ident()); gate.wait(2)
            return 'Yerel eşleştirme anahtarı hazır. Tamamen çık ve yeniden aç.'
        controller.prepare_pairing.side_effect = initialize
        root = tk.Tk()
        try:
            with tempfile.TemporaryDirectory() as folder:
                app = Window(root, folder, demo=True, connections=controller)
                app.demo = False; app.exchange = None; app.adb = None
                app.tabs.select(app.connections_page); root.update()
                button = next(b for b in app.connection_buttons if b.cget('text') == 'İlk eşleştirmeyi hazırla')
                with patch('app.messagebox.askyesno', return_value=False) as confirm:
                    button.invoke()
                    confirm.assert_called_once()
                    self.assertIs(confirm.call_args.kwargs['parent'], root)
                self.assertEqual(controller.mock_calls, [])
                with patch('app.messagebox.askyesno', return_value=True): button.invoke()
                root.update(); self.assertTrue(app.connection_busy)
                gate.set()
                deadline = time.monotonic() + 3
                while app.connection_busy and time.monotonic() < deadline: root.update(); time.sleep(.01)
                self.assertFalse(app.connection_busy)
                self.assertIn('yeniden aç', app.connection_status.get())
                self.assertEqual(len(calls), 1); self.assertNotIn(main_thread, calls)
                controller.start_mcp.assert_not_called(); controller.start_usb.assert_not_called()
                for callback in root.tk.call('after', 'info'): root.after_cancel(callback)
                app.close()
        finally:
            gate.set()
            try: root.destroy()
            except tk.TclError: pass

    def test_partial_operation_failure_message_is_truthful_and_status_refresh_available(self):
        import tkinter as tk
        from app import Window
        from connections import ConnectionController
        controller = ConnectionController()
        changed = []
        def partial_failure(name):
            changed.append(name)
            raise RuntimeError('SECRET verification failure')
        root = tk.Tk()
        try:
            with tempfile.TemporaryDirectory() as folder, patch.object(launcher, 'ensure_usb', side_effect=lambda: partial_failure('mapping added')), patch.object(launcher, 'ensure_mcp', side_effect=lambda **kwargs: partial_failure('backend stopped')), patch.object(controller, 'status', return_value={'usb': 'refresh fixture'}), patch('app.messagebox.askyesno', return_value=True):
                app = Window(root, folder, demo=True, connections=controller)
                app.tabs.select(app.connections_page); root.update()
                for action in ('start_usb', 'restart_mcp'):
                    with self.subTest(action=action):
                        app.connection_action(action)
                        deadline = time.monotonic() + 3
                        while app.connection_busy and time.monotonic() < deadline: root.update(); time.sleep(.01)
                        self.assertFalse(app.connection_busy)
                        message = app.connection_status.get()
                        self.assertNotIn('SECRET', message)
                        self.assertNotIn('Ayarlar değiştirilmedi', message)
                        self.assertIn('kısmen uygulanmış olabilir', message)
                        self.assertIn('durumu yenile', message)
                        self.assertTrue(all(not button.instate(['disabled']) for button in app.connection_buttons))
                        app.connection_buttons[0].invoke()
                        deadline = time.monotonic() + 3
                        while app.connection_busy and time.monotonic() < deadline: root.update(); time.sleep(.01)
                        self.assertIn('refresh fixture', app.connection_status.get())
                self.assertEqual(changed, ['mapping added', 'backend stopped'])
                for callback in root.tk.call('after', 'info'): root.after_cancel(callback)
                app.close()
        finally:
            try: root.destroy()
            except tk.TclError: pass

    def test_actions_and_status_run_off_tk_thread_and_errors_recover(self):
        import tkinter as tk
        from app import Window
        main_thread = threading.get_ident(); calls = []; gate = threading.Event()
        controller = Mock()
        def status():
            calls.append(threading.get_ident()); gate.wait(2)
            return {'usb': 'fixture', 'mcp': 'kapalı', 'remote_ai': 'yapılandırılmadı'}
        controller.status.side_effect = status
        controller.start_mcp.side_effect = RuntimeError('SECRET')
        root = tk.Tk()
        try:
            with tempfile.TemporaryDirectory() as folder:
                app = Window(root, folder, demo=True, connections=controller)
                app.tabs.select(app.connections_page); root.update()
                app.connection_action('status'); root.update()
                self.assertTrue(app.connection_busy)
                gate.set()
                deadline = time.monotonic() + 3
                while app.connection_busy and time.monotonic() < deadline: root.update(); time.sleep(.01)
                self.assertIn('fixture', app.connection_status.get())
                self.assertTrue(calls); self.assertNotIn(main_thread, calls)
                app.connection_action('start_mcp')
                deadline = time.monotonic() + 3
                while app.connection_busy and time.monotonic() < deadline: root.update(); time.sleep(.01)
                self.assertFalse(app.connection_busy); self.assertNotIn('SECRET', app.connection_status.get())
                with patch('app.subprocess.Popen') as process:
                    app.open_connection_setup()
                    self.assertTrue(process.call_args.args[0][-1].endswith('user_setup.py'))
                    app.open_connection_setup(key=True)
                    self.assertEqual(process.call_args.args[0][-1], '--key')
                for callback in root.tk.call('after', 'info'): root.after_cancel(callback)
                app.close()
        finally:
            gate.set()
            try: root.destroy()
            except tk.TclError: pass


if __name__ == '__main__': unittest.main()
