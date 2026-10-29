"""Windows desktop adapters. No Tk imports/calls, startup opt-in only.

pystray and Pillow are explicit runtime dependencies; never installed at runtime.
"""
import hashlib
import os
from pathlib import Path
import subprocess
import threading


def create_icon(actions):
    if os.name != 'nt': raise RuntimeError('Windows tepsisi kullanılamıyor.')
    import pystray
    from PIL import Image, ImageDraw
    image = Image.new('RGBA', (64, 64), '#245dc1')
    draw = ImageDraw.Draw(image)
    draw.rectangle((14, 14, 49, 50), outline='white', width=4)
    draw.line((32, 15, 32, 49), fill='white', width=3)
    return pystray.Icon('ManhwaTakipCompanion', image, 'Manhwa Takip', pystray.Menu(
        pystray.MenuItem('Aç', lambda: actions['open'](), default=True),
        pystray.MenuItem('Bağlantı durumu', lambda: actions['status']()),
        pystray.MenuItem('Tamamen çık', lambda: actions['exit']())))


class TrayController:
    def __init__(self, post, on_open, on_status, on_exit, icon_factory=create_icon, ready_timeout=2):
        self.post = post; self.callbacks = dict(open=on_open, status=on_status, exit=on_exit)
        self.icon_factory = icon_factory; self.ready_timeout = ready_timeout
        self.ready = threading.Event(); self.stopped = threading.Event()
        self.icon = None; self.thread = None; self.lock = threading.Lock(); self.stop_sent = False

    @property
    def available(self):
        return self.ready.is_set() and not self.stopped.is_set() and self.thread is not None and self.thread.is_alive()

    def dispatch(self, key):
        if not self.stopped.is_set():
            self.post(lambda: self.callbacks[key]() if not self.stopped.is_set() else None)

    def show(self):
        if self.stopped.is_set(): return False
        if self.thread is None:
            self.thread = threading.Thread(target=self.run, name='manhwa-tray', daemon=True)
            self.thread.start()
        self.ready.wait(self.ready_timeout)
        if not self.available: self.stop()
        return self.available

    def run(self):
        try:
            self.icon = self.icon_factory({key: (lambda k=key: self.dispatch(k)) for key in self.callbacks})
            def setup(icon):
                # A timed-out/aborted close must never create a late inaccessible icon.
                with self.lock:
                    if not self.stopped.is_set():
                        icon.visible = True
                        self.ready.set()
                if self.stopped.is_set(): self.stop_icon()
            self.icon.run(setup=setup)  # Windows backend owns this daemon's message loop.
        except Exception:
            pass  # UI reports a bounded, non-sensitive availability error.
        finally:
            self.ready.clear()

    def stop_icon(self):
        with self.lock:
            if self.icon is None or self.stop_sent: return
            self.stop_sent = True
        self.icon.stop()

    def stop(self):
        self.stopped.set()
        # Before setup, pystray.stop() is a no-op; let late setup issue the stop.
        if self.ready.is_set(): self.stop_icon()
        if self.thread is not None and self.thread is not threading.current_thread():
            self.thread.join(timeout=.5)


def startup_command(executable, script=None):
    return subprocess.list2cmdline([str(executable), *([str(script)] if script is not None else [])])


def application_command():
    import sys
    folder = Path(__file__).resolve().parent
    launcher = folder / 'ManhwaTakip.exe'
    if launcher.is_file(): return startup_command(launcher)
    pythonw = Path(sys.executable).with_name('pythonw.exe')
    return startup_command(pythonw if pythonw.is_file() else sys.executable, folder / 'app.py')


class RunKeyStore:
    path = r'Software\Microsoft\Windows\CurrentVersion\Run'
    name = 'ManhwaTakipCompanion'

    def __init__(self, registry=None):
        if registry is None:
            import winreg
            registry = winreg
        self.registry = registry

    def read(self):
        r = self.registry
        try:
            with r.OpenKey(r.HKEY_CURRENT_USER, self.path) as key:
                return r.QueryValueEx(key, self.name)[0]
        except FileNotFoundError: return None

    def write(self, value):
        r = self.registry
        with r.CreateKeyEx(r.HKEY_CURRENT_USER, self.path, 0, r.KEY_SET_VALUE) as key:
            r.SetValueEx(key, self.name, 0, r.REG_SZ, value)

    def delete(self):
        r = self.registry
        try:
            with r.OpenKey(r.HKEY_CURRENT_USER, self.path, 0, r.KEY_SET_VALUE) as key:
                r.DeleteValue(key, self.name)
        except FileNotFoundError: pass


class WindowsStartup:
    def __init__(self, command=None, store=None):
        self.command = command if command is not None else application_command()
        self.store = store if store is not None else RunKeyStore()

    def value(self): return self.store.read()

    def set_enabled(self, enabled):
        current = self.value()
        if current is not None and current != self.command:
            raise ValueError('Başlangıç kaydı başka bir komuta ait; değiştirilmedi.')
        if enabled: self.store.write(self.command)
        elif current is not None: self.store.delete()

    def restore(self, value):
        if self.value() not in (None, self.command):
            raise OSError('Başlangıç kaydı eşzamanlı değişti; üzerine yazılmadı.')
        if value is None: self.store.delete()
        else: self.store.write(value)


class WindowsEventBackend:
    def __init__(self):
        import ctypes
        from ctypes import wintypes
        self.ctypes = ctypes
        self.kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        for name, args, result in (
            ('CreateEventW', [ctypes.c_void_p, wintypes.BOOL, wintypes.BOOL, wintypes.LPCWSTR], wintypes.HANDLE),
            ('SetEvent', [wintypes.HANDLE], wintypes.BOOL),
            ('WaitForSingleObject', [wintypes.HANDLE, wintypes.DWORD], wintypes.DWORD),
            ('CloseHandle', [wintypes.HANDLE], wintypes.BOOL)):
            function = getattr(self.kernel, name); function.argtypes = args; function.restype = result

    def create(self, name):
        handle = self.kernel.CreateEventW(None, False, False, name)
        error = self.ctypes.get_last_error()
        if not handle: raise self.ctypes.WinError(error)
        return handle, error != 183  # ERROR_ALREADY_EXISTS

    def signal(self, handle):
        if not self.kernel.SetEvent(handle): raise self.ctypes.WinError(self.ctypes.get_last_error())

    def poll(self, handle):
        result = self.kernel.WaitForSingleObject(handle, 0)
        if result == 0xFFFFFFFF: raise self.ctypes.WinError(self.ctypes.get_last_error())
        return result == 0

    def close(self, handle): self.kernel.CloseHandle(handle)


class InstanceGate:
    """Atomic per-user/session event gate, held until owned-resource cleanup ends."""
    def __init__(self, folder, backend=None):
        self.backend = backend if backend is not None else WindowsEventBackend()
        digest = hashlib.sha256(os.path.normcase(str(Path(folder).resolve())).encode()).hexdigest()[:24]
        self.handle, self.primary = self.backend.create('Local\\ManhwaTakipCompanion-' + digest)
        if not self.primary:
            try: self.backend.signal(self.handle)
            finally: self.close()

    def poll(self): return self.handle is not None and self.primary and self.backend.poll(self.handle)

    def close(self):
        if self.handle is not None:
            self.backend.close(self.handle); self.handle = None
