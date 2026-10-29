"""Read-only native window/physical USB acceptance; no personal data in output."""
import os
import sys
import tempfile
import time
import tkinter as tk
from pathlib import Path
from app import Window
from core import Exchange, read_backup


def main():
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "bridge"))
    from bridge import key_path, protect
    key = protect(key_path().read_bytes(), True).decode()
    adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
    root = tk.Tk(); outcome = []
    with tempfile.TemporaryDirectory() as folder, Exchange(key) as exchange:
        window = Window(root, folder, exchange, adb)
        deadline = time.monotonic() + 100; reads = []
        def check():
            if window.snapshot is not None and not window.busy:
                try:
                    current = read_backup((window.device_folder() / "latest.json").read_bytes())
                    assert current == window.snapshot and not window.serial.startswith("emulator-")
                    backups = sorted((window.device_folder() / "backups").glob("*.zip"))
                    assert len(backups) == len(reads) + 1 and read_backup(backups[-1].read_bytes()) == current
                    assert all(read_backup(file.read_bytes())["library"]["records"] == current["library"]["records"] for file in backups)
                    assert len(window.tree.get_children()) == len(current["library"]["records"])
                    assert all(button.instate(["!disabled"]) for button in window.buttons)
                    reads.append(True)
                except BaseException:
                    window.close(); raise
                if len(reads) == 2:
                    outcome.append(True); window.close()
                else:
                    window.refresh(); root.after(200, check)
            elif time.monotonic() > deadline:
                window.close()
            else: root.after(200, check)
        root.after(200, check)
        try: root.mainloop()
        finally: window.closed.set(); window.cleanup_reverse()
    assert outcome == [True], "Native USB read/backup acceptance incomplete"
    print("NATIVE_WINDOW_AUTO_USB_READ_REPEATED_REFRESH_AND_DATED_BACKUP_OK")


if __name__ == "__main__": main()
