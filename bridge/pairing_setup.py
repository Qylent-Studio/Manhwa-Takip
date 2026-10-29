"""Explicit USB setup of the existing PC key; no key/nonce output or library writes."""
import argparse
import json
import secrets
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from bridge import key_path, protect


def make_fixture(key, nonce, port=8766):
    credential = {"key": key}

    class Fixture(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            host = f"127.0.0.1:{self.server.server_address[1]}"
            authorized = (self.headers.get("Host") == host and self.headers.get("Origin") is None
                          and secrets.compare_digest(self.headers.get("Authorization", ""), "Bearer " + nonce))
            if self.path != "/key" or not authorized:
                self.send_error(403, "Setup session rejected")
                return
            with lock:
                value = credential.pop("key", None)
            if value is None:
                self.send_error(409, "Setup session already consumed")
                return
            payload = json.dumps({"pairingKey": value}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(payload)

    lock = threading.Lock()
    server = ThreadingHTTPServer(("127.0.0.1", port), Fixture)
    return server, credential


def main():
    parser = argparse.ArgumentParser(description="Mevcut PC anahtarını yalnızca seçilen USB telefona kaydet")
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    if args.serial.startswith("emulator-"):
        raise RuntimeError("Bu kurulum için açıkça seçilmiş fiziksel telefon gerekir.")
    adb = [args.adb, "-s", args.serial]
    subprocess.run(adb + ["get-state"], check=True, capture_output=True, timeout=10)
    mapping = subprocess.run(adb + ["reverse", "--list"], check=True, capture_output=True, text=True, timeout=10)
    if any(line.split()[1:2] == ["tcp:8766"] for line in mapping.stdout.splitlines()):
        raise RuntimeError("8766 yönlendirmesi kullanımda; mevcut yönlendirme değiştirilmedi.")
    key = protect(key_path().read_bytes(), True).decode()
    nonce = secrets.token_urlsafe(32)
    server, credential = make_fixture(key, nonce)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    reverse_created = False
    try:
        subprocess.run(adb + ["reverse", "--no-rebind", "tcp:8766", "tcp:8766"], check=True, capture_output=True, timeout=10)
        reverse_created = True
        result = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-e", "class",
            "com.qylent.manhwatakip.BridgePairingSetupTest", "-e", "bridgePairingSetup", "true",
            "-e", "fixtureToken", nonce,
            "com.qylent.manhwatakip.test/androidx.test.runner.AndroidJUnitRunner"],
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=90)
        output = (result.stdout + result.stderr).replace(key, "[REDACTED_PAIRING_KEY]").replace(nonce, "[REDACTED_SETUP_NONCE]")
        print(output)
        if result.returncode != 0 or "PAIRING_SAVED_ENCRYPTED_OK" not in output or "FAILURES!!!" in output:
            raise RuntimeError("USB eşleştirme kurulumu doğrulanamadı; kayıtlar değiştirilmedi.")
        print("PHYSICAL_PAIRING_SETUP_OK")
    finally:
        cleanup_ok = True
        try:
            if reverse_created:
                cleanup = subprocess.run(adb + ["reverse", "--remove", "tcp:8766"], capture_output=True, timeout=10)
                cleanup_ok = cleanup.returncode == 0
        except Exception:
            cleanup_ok = False
        finally:
            credential.clear(); server.shutdown(); server.server_close()
            key = None; nonce = None
        if not cleanup_ok:
            raise RuntimeError("Geçici USB yönlendirmesi kaldırılmadı; 8766 ayrıca kontrol edilmeli.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Exceptions may contain the instrumentation nonce; never print commands or raw messages.
        print("PAIRING_SETUP_FAILED: " + type(error).__name__)
        sys.exit(1)
