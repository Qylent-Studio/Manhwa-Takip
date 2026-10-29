"""Tray, singleton and current-user startup fixtures; no personal registry writes."""
import queue
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, MagicMock, patch


class FakeIcon:
    def __init__(self, actions):
        self.actions = actions; self.visible = False
        self.stopped = threading.Event(); self.stops = 0

    def run(self, setup):
        setup(self)
        self.stopped.wait(3)

    def stop(self):
        self.stops += 1; self.stopped.set()


class TrayTests(unittest.TestCase):
    def test_callbacks_are_queued_and_inert_after_stop(self):
        from tray import TrayController
        pending = queue.Queue(); calls = []; icons = []
        def factory(actions):
            icon = FakeIcon(actions); icons.append(icon); return icon
        controller = TrayController(pending.put, lambda: calls.append('open'), lambda: calls.append('status'),
                                    lambda: calls.append('exit'), icon_factory=factory)
        self.addCleanup(controller.stop)
        self.assertTrue(controller.show()); self.assertTrue(controller.show())
        self.assertEqual(len(icons), 1)
        thread = threading.Thread(target=lambda: [icons[0].actions[key]() for key in ('open', 'status', 'exit')])
        thread.start(); thread.join()
        self.assertEqual(calls, [])
        while not pending.empty(): pending.get()()
        self.assertEqual(calls, ['open', 'status', 'exit'])
        icons[0].actions['open'](); controller.stop(); controller.stop()
        while not pending.empty(): pending.get()()
        self.assertEqual(calls, ['open', 'status', 'exit'])
        self.assertEqual(icons[0].stops, 1)
        self.assertFalse(controller.available)

    def test_missing_backend_and_failed_icon_never_report_accessible_tray(self):
        from tray import TrayController
        for factory in (Mock(side_effect=ImportError('fixture')), lambda actions: Mock(run=Mock(side_effect=OSError('fixture')))):
            controller = TrayController(lambda callback: None, lambda: None, lambda: None, lambda: None,
                                        icon_factory=factory, ready_timeout=.05)
            self.assertFalse(controller.show()); controller.stop()
            self.assertFalse(controller.available)

    def test_stop_racing_with_late_setup_does_not_show_icon(self):
        from tray import TrayController
        release = threading.Event(); icons = []
        class Late(FakeIcon):
            def run(self, setup):
                release.wait(2); setup(self)
        def factory(actions):
            icon = Late(actions); icons.append(icon); return icon
        controller = TrayController(lambda callback: None, lambda: None, lambda: None, lambda: None,
                                    icon_factory=factory, ready_timeout=.02)
        self.assertFalse(controller.show())
        controller.stop(); release.set(); controller.thread.join(2)
        self.assertFalse(icons[0].visible)
        self.assertFalse(controller.thread.is_alive())


class Store:
    def __init__(self): self.entries = {'UnrelatedApplication': 'leave alone'}
    def read(self): return self.entries.get('ManhwaTakipCompanion')
    def write(self, value): self.entries['ManhwaTakipCompanion'] = value
    def delete(self): self.entries.pop('ManhwaTakipCompanion', None)


class StartupTests(unittest.TestCase):
    def test_opt_in_quotes_command_and_only_changes_owned_current_user_value(self):
        from tray import WindowsStartup, startup_command
        store = Store(); command = startup_command(Path(r'C:\Program Files\Manhwa Takip\ManhwaTakip.exe'))
        self.assertEqual(command, '"C:\\Program Files\\Manhwa Takip\\ManhwaTakip.exe"')
        startup = WindowsStartup(command, store=store)
        self.assertIsNone(startup.value()); self.assertEqual(len(store.entries), 1)
        startup.set_enabled(True); self.assertEqual(store.read(), command)
        startup.set_enabled(False); self.assertIsNone(store.read())
        self.assertEqual(store.entries, {'UnrelatedApplication': 'leave alone'})

    def test_foreign_same_name_value_is_not_deleted_or_overwritten(self):
        from tray import WindowsStartup
        store = Store(); store.write('other command')
        startup = WindowsStartup('owned', store=store)
        for enabled in (False, True):
            with self.assertRaises(ValueError): startup.set_enabled(enabled)
        self.assertEqual(store.read(), 'other command')

    def test_rollback_does_not_overwrite_a_concurrent_foreign_startup_change(self):
        from tray import WindowsStartup
        store = Store(); startup = WindowsStartup('owned', store=store)
        startup.set_enabled(True); store.write('concurrent foreign value')
        with self.assertRaises(OSError): startup.restore(None)
        self.assertEqual(store.read(), 'concurrent foreign value')

    def test_registry_adapter_uses_hkcu_run_only_with_fixture(self):
        from tray import RunKeyStore
        registry = MagicMock(); registry.HKEY_CURRENT_USER = 'fixture-HKCU'
        registry.OpenKey.return_value.__enter__.return_value = 'fixture-key'
        registry.CreateKeyEx.return_value.__enter__.return_value = 'fixture-key'
        registry.QueryValueEx.return_value = ('fixture-command', 1)
        store = RunKeyStore(registry=registry)
        self.assertEqual(store.read(), 'fixture-command'); store.write('quoted command'); store.delete()
        self.assertEqual(registry.CreateKeyEx.call_args.args[:2], ('fixture-HKCU', r'Software\Microsoft\Windows\CurrentVersion\Run'))
        registry.SetValueEx.assert_called_once_with('fixture-key', 'ManhwaTakipCompanion', 0, registry.REG_SZ, 'quoted command')
        registry.DeleteValue.assert_called_once_with('fixture-key', 'ManhwaTakipCompanion')

    def test_install_manifest_explicitly_checks_pinned_dependencies_without_pip(self):
        text = (Path(__file__).parent / 'install.ps1').read_text(encoding='utf-8-sig')
        for name in ('preferences.py', 'theme.py', 'tray.py', 'requirements-runtime.txt'):
            self.assertIn("'" + name + "'", text)
        self.assertIn('pystray', text)
        self.assertNotIn('pip install', text)
        self.assertIn("'THIRD_PARTY_NOTICES.md'", text)


class EventBackend:
    def __init__(self): self.handles = {}; self.signalled = set(); self.next = 0
    def create(self, name):
        existed = name in self.handles.values(); self.next += 1
        self.handles[self.next] = name
        return self.next, not existed
    def signal(self, handle): self.signalled.add(self.handles[handle])
    def poll(self, handle):
        name = self.handles[handle]
        if name not in self.signalled: return False
        self.signalled.remove(name); return True
    def close(self, handle): self.handles.pop(handle)


class InstanceTests(unittest.TestCase):
    def test_repeat_launch_signals_same_primary_until_cleanup_releases_gate(self):
        from tray import InstanceGate
        backend = EventBackend()
        first = InstanceGate('fixture-folder', backend=backend)
        second = InstanceGate('fixture-folder', backend=backend)
        self.assertTrue(first.primary); self.assertFalse(second.primary)
        self.assertTrue(first.poll()); self.assertFalse(first.poll())
        second.close()
        third = InstanceGate('fixture-folder', backend=backend)
        self.assertFalse(third.primary); third.close()
        first.close(); first.close()
        last = InstanceGate('fixture-folder', backend=backend)
        self.assertTrue(last.primary); last.close()
        self.assertFalse(backend.handles)


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        import tkinter as tk
        from app import Window
        self.tk = tk; self.temp = tempfile.TemporaryDirectory(); self.root = tk.Tk()
        self.addCleanup(self.temp.cleanup); self.addCleanup(self.cleanup)
        self.tray = Mock(); self.tray.show.return_value = True; self.tray.available = True
        self.store = Store()
        from tray import WindowsStartup
        self.startup = WindowsStartup('fixture-command', store=self.store)
        self.app = Window(self.root, self.temp.name, demo=True, tray=self.tray, startup=self.startup)
        self.root.update(); self.app.demo = False  # lifecycle tests only: no USB monitor or real tray

    def cleanup(self):
        if hasattr(self, 'app'):
            self.app.closed.set(); self.app.cover_cancel.set()
            if getattr(self.app, 'shutdown_thread', None): self.app.shutdown_thread.join(3)
        try:
            for timer in self.root.tk.call('after', 'info'): self.root.after_cancel(timer)
            self.root.destroy()
        except self.tk.TclError: pass

    def pump(self, predicate):
        deadline = time.monotonic() + 4
        while not predicate() and time.monotonic() < deadline:
            self.root.update(); time.sleep(.01)
        self.assertTrue(predicate())

    def test_close_hides_only_with_ready_tray_explains_once_and_restore_keeps_root(self):
        app = self.app; original = self.root
        with patch('app.messagebox.showinfo') as info:
            app.close(); self.root.update()
            self.assertEqual(self.root.state(), 'withdrawn'); self.assertFalse(app.closed.is_set())
            app.restore_window(); self.root.update()
            self.assertEqual(self.root.state(), 'normal'); self.assertIs(app.root, original)
            app.close(); self.assertEqual(info.call_count, 1)
        self.assertTrue((Path(self.temp.name) / 'tray-explained.json').exists())

    def test_tray_failure_or_loss_keeps_or_restores_visible_window(self):
        app = self.app; self.tray.show.return_value = False
        app.close(); self.root.update()
        self.assertEqual(self.root.state(), 'normal'); self.assertFalse(app.closed.is_set())
        self.assertIn('tepsi', app.status.get().lower())
        self.tray.show.return_value = True
        with patch('app.messagebox.showinfo'): app.close()
        self.tray.available = False; app.check_tray(); self.root.update()
        self.assertEqual(self.root.state(), 'normal')

    def test_exit_race_stashes_once_joins_worker_then_cleans_owned_resources_once(self):
        app = self.app; calls = []; gate = threading.Event(); started = threading.Event()
        def worker():
            started.set(); app.closed.wait(3); gate.wait(3); calls.append('worker done')
        app.start_worker(worker); started.wait(1)
        app.stash = Mock(return_value=True)
        app.cleanup_reverse = Mock(side_effect=lambda: calls.append('reverse'))
        app.exchange = Mock(); app.exchange.close.side_effect = lambda: calls.append('exchange')
        app.exit_application(); app.exit_application(); app.restore_window()
        self.assertTrue(app.closed.is_set()); self.assertFalse(app.shutdown_done.is_set())
        app.messages.put(lambda: calls.append('late callback')); app.drain()
        self.assertNotIn('late callback', calls)
        gate.set(); self.pump(app.shutdown_done.is_set)
        self.assertEqual(calls, ['worker done', 'reverse', 'exchange'])
        app.stash.assert_called_once(); self.tray.stop.assert_called_once()
        app.cleanup_reverse.assert_called_once(); app.exchange.close.assert_called_once()

    def test_failed_stash_blocks_exit_and_hide_but_allows_retry(self):
        app = self.app; app.stash = Mock(return_value=False)
        with patch('app.messagebox.showwarning'):
            app.close(); app.exit_application()
        self.assertFalse(app.closed.is_set()); self.assertEqual(self.root.state(), 'normal')
        self.tray.show.assert_not_called()
        app.stash.return_value = True
        app.preferences.close_to_tray = False; app.close()
        self.pump(app.shutdown_done.is_set)

    def test_startup_toggle_is_opt_in_persisted_and_rolled_back_on_save_failure(self):
        app = self.app
        self.assertIsNone(self.store.read()); self.assertFalse(app.preferences.start_with_windows)
        app.startup_var.set(True); app.change_startup()
        self.assertEqual(self.store.read(), 'fixture-command'); self.assertTrue(app.preferences.start_with_windows)
        with patch.object(app.preferences, 'save', side_effect=OSError('fixture')):
            app.startup_var.set(False); app.change_startup()
        self.assertEqual(self.store.read(), 'fixture-command'); self.assertTrue(app.preferences.start_with_windows)
        self.assertTrue(app.startup_var.get())

    def test_startup_mutate_then_raise_restores_prior_state_for_enable_and_disable(self):
        from preferences import Preferences
        app = self.app
        for before in (False, True):
            with self.subTest(before=before):
                if before: self.store.write('fixture-command')
                else: self.store.delete()
                app.preferences.start_with_windows = before; app.preferences.save()
                operation = 'delete' if before else 'write'
                original = getattr(self.store, operation)
                def mutate_then_raise(*args):
                    original(*args); raise OSError('fixture: mutation succeeded but completion failed')
                app.startup_var.set(not before)
                with patch.object(self.store, operation, side_effect=mutate_then_raise): app.change_startup()
                self.assertEqual(self.store.read(), 'fixture-command' if before else None)
                self.assertEqual(app.startup_var.get(), before)
                self.assertEqual(Preferences.load(Path(self.temp.name) / 'preferences.json').start_with_windows, before)
                self.assertNotIn('alternate', app.startup_toggle.state())

    def test_startup_failed_rollback_reconciles_checkbox_and_preference_to_observed_enabled(self):
        from preferences import Preferences
        app = self.app; original = self.store.write
        def mutate_then_raise(value):
            original(value); raise OSError('fixture: write completed then raised')
        app.startup_var.set(True)
        with patch.object(self.store, 'write', side_effect=mutate_then_raise), \
                patch.object(self.store, 'delete', side_effect=OSError('fixture: rollback denied')):
            app.change_startup()
        self.assertEqual(self.store.read(), 'fixture-command')
        self.assertTrue(app.startup_var.get()); self.assertTrue(app.preferences.start_with_windows)
        self.assertTrue(Preferences.load(Path(self.temp.name) / 'preferences.json').start_with_windows)
        self.assertIn('geri alınamadı', app.status.get())
        self.assertIn('açık', app.status.get()); self.assertNotIn('önceki ayar korunuyor', app.status.get())

    def test_startup_unknown_after_partial_failure_uses_indeterminate_checkbox_not_false_guarantee(self):
        app = self.app; original = self.store.write
        def mutate_then_raise(value):
            original(value); raise OSError('fixture: write completed then raised')
        app.startup_var.set(True)
        with patch.object(self.store, 'write', side_effect=mutate_then_raise), \
                patch.object(self.store, 'read', side_effect=[None, None, OSError('fixture unavailable')]):
            app.change_startup()
        self.assertEqual(self.store.read(), 'fixture-command')
        self.assertIn('alternate', app.startup_toggle.state())
        self.assertIn('doğrulanamadı', app.status.get())
        self.assertNotIn('önceki ayar korunuyor', app.status.get())
        app.startup_var.set(False); app.change_startup()
        self.assertNotIn('alternate', app.startup_toggle.state())
        self.assertIsNone(self.store.read())

    def test_startup_rollback_mutates_then_raises_but_final_read_confirms_restored(self):
        app = self.app; write = self.store.write; delete = self.store.delete
        def write_then_raise(value):
            write(value); raise OSError('fixture write completion')
        def delete_then_raise():
            delete(); raise OSError('fixture rollback completion')
        app.startup_var.set(True)
        with patch.object(self.store, 'write', side_effect=write_then_raise), \
                patch.object(self.store, 'delete', side_effect=delete_then_raise): app.change_startup()
        self.assertIsNone(self.store.read()); self.assertFalse(app.startup_var.get())
        self.assertNotIn('alternate', app.startup_toggle.state())
        self.assertIn('doğrulanan Windows durumu: kapalı', app.status.get())

    def test_startup_failed_rollback_and_failed_reconciliation_save_discloses_disk_mismatch(self):
        from preferences import Preferences
        app = self.app; app.preferences.save(); original = self.store.write
        def write_then_raise(value):
            original(value); raise OSError('fixture write completion')
        app.startup_var.set(True)
        with patch.object(self.store, 'write', side_effect=write_then_raise), \
                patch.object(self.store, 'delete', side_effect=OSError('fixture rollback denied')), \
                patch.object(app.preferences, 'save', side_effect=OSError('fixture disk full')):
            app.change_startup()
        self.assertEqual(self.store.read(), 'fixture-command'); self.assertTrue(app.startup_var.get())
        self.assertTrue(app.preferences.start_with_windows)
        self.assertFalse(Preferences.load(Path(self.temp.name) / 'preferences.json').start_with_windows)
        self.assertIn('doğrulanan Windows durumu: açık', app.status.get())
        self.assertIn('Tercih dosyası güncellenemedi', app.status.get())

    def test_startup_concurrent_foreign_value_is_preserved_and_ui_indeterminate(self):
        app = self.app; original = self.store.write
        def foreign_then_raise(value):
            original('concurrent foreign'); raise OSError('fixture concurrent change')
        app.startup_var.set(True)
        with patch.object(self.store, 'write', side_effect=foreign_then_raise): app.change_startup()
        self.assertEqual(self.store.read(), 'concurrent foreign')
        self.assertIn('alternate', app.startup_toggle.state())
        self.assertNotIn('önceki ayar korunuyor', app.status.get())

    def test_demo_close_exits_even_when_close_to_tray_default_is_true(self):
        self.app.demo = True
        self.app.close()
        self.assertTrue(self.app.closed.is_set()); self.assertTrue(self.app.shutdown_done.is_set())
        self.tray.show.assert_not_called()

    def test_reentrant_close_during_first_explanation_does_not_stash_or_explain_twice(self):
        app = self.app; app.stash = Mock(return_value=True)
        with patch('app.messagebox.showinfo', side_effect=lambda *args, **kwargs: app.close()) as info:
            app.close()
        app.stash.assert_called_once(); info.assert_called_once()
        self.assertTrue(app.hidden)

    def test_close_preference_toggle_persists_and_rolls_back_disk_failure(self):
        app = self.app
        app.close_to_tray_var.set(False); app.change_close_to_tray()
        from preferences import Preferences
        self.assertFalse(Preferences.load(Path(self.temp.name) / 'preferences.json').close_to_tray)
        with patch.object(app.preferences, 'save', side_effect=OSError('fixture')):
            app.close_to_tray_var.set(True); app.change_close_to_tray()
        self.assertFalse(app.preferences.close_to_tray); self.assertFalse(app.close_to_tray_var.get())

    def test_closed_exchange_worker_never_creates_a_late_mapping_or_phone_activity(self):
        app = self.app; app.closed.set()
        with patch('app.run_adb') as adb:
            with self.assertRaises(TimeoutError): app.exchange_job('fixture', {'action': 'read'})
            adb.assert_not_called()

    def test_instance_restore_and_status_callbacks_use_existing_root(self):
        app = self.app; app.instance = Mock(); app.instance.poll.return_value = True
        self.root.withdraw(); app.drain(); self.root.update()
        self.assertEqual(self.root.state(), 'normal')
        with patch.object(app, 'connection_action') as action:
            app.show_connection_status()
            self.assertEqual(app.tabs.select(), str(app.connections_page)); action.assert_called_once_with('status')


class ExchangeLifecycleTests(unittest.TestCase):
    def test_close_is_idempotent_and_releases_pending_operations(self):
        from core import Exchange
        exchange = Exchange('synthetic', port=0); exchange.__enter__()
        operation = exchange.begin({'action': 'read'})
        exchange.close(); exchange.close(); exchange.__exit__()
        self.assertTrue(operation.done.is_set()); self.assertFalse(exchange.thread.is_alive())

    def test_second_main_launch_never_creates_root_reads_credentials_or_opens_exchange(self):
        import app, sys
        with patch.object(sys, 'argv', ['app.py']), patch.object(app, 'InstanceGate') as instance, \
                patch.object(app.tk, 'Tk') as root, patch.object(app, 'Exchange') as exchange:
            instance.return_value.primary = False
            app.main()
            root.assert_not_called(); exchange.assert_not_called()


if __name__ == '__main__': unittest.main()
