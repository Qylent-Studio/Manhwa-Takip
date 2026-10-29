"""Test-only Android↔PC fixture; no real library/credentials or external account."""
import argparse
import subprocess
import threading
import time
from bridge import BridgeState, make_server

def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", default="emulator-5554"); args = parser.parse_args()
    previous = subprocess.run([args.adb, "-s", args.serial, "reverse", "--list"], check=True, capture_output=True, text=True).stdout
    mapping = next((line.split()[2] for line in previous.splitlines() if len(line.split()) == 3 and line.split()[1] == "tcp:8765"), None)
    state = BridgeState("test-usb-only-not-a-real-pairing-key-1234567890")
    server = make_server(state)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    done = threading.Event(); proposed = []
    def prepare():
        while not done.wait(0.05):
            with state.lock:
                match = next((r for r in state.records if r["id"] == "00000000-0000-0000-0000-000000000042"), None)
                if match and not proposed:
                    proposed.append(state.call("propose_change", dict(seriesId=match["id"], expectedUpdatedAt=match["updatedAt"], fields=dict(lastChapter="20", categories=["Harem", "Macera"])))["token"])
    worker = threading.Thread(target=prepare, daemon=True); worker.start()
    try:
        subprocess.run([args.adb, "-s", args.serial, "reverse", "tcp:8765", "tcp:8765"], check=True)
        result = subprocess.run([args.adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-e", "usbAcceptance", "true", "-e", "class", "com.qylent.manhwatakip.BridgeRoundTripTest", "com.qylent.manhwatakip.test/androidx.test.runner.AndroidJUnitRunner"], capture_output=True, text=True, encoding="utf-8", timeout=180)
        print(result.stdout)
        if result.returncode != 0 or "OK (1 test)" not in result.stdout or not proposed:
            raise RuntimeError("USB kabul testi başarısız.")
        if state.records or state.proposals or state.expires:
            raise RuntimeError("Bağlantıyı kapatma snapshot'ı temizlemedi.")
        print("USB_ACCEPTANCE_OK — gerçek Android/PC kategori aktarımı, önizleme, onay, stale tekrar reddi ve bağlantıyı kapatma.")
    finally:
        done.set(); server.shutdown(); server.server_close(); thread.join(); worker.join()
        if mapping: subprocess.run([args.adb, "-s", args.serial, "reverse", "tcp:8765", mapping], check=True)
        else: subprocess.run([args.adb, "-s", args.serial, "reverse", "--remove", "tcp:8765"], check=False)

if __name__ == "__main__": main()
