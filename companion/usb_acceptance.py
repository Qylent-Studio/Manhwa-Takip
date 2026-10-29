"""Real signed USB activity acceptance. Writes only one generated, temporary fixture."""
import argparse
import os
import subprocess
import sys
import uuid
import struct
import zlib
from pathlib import Path
from core import Exchange
from app import run_adb
from covers import manual_cover


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--serial", required=True); parser.add_argument('--manual-cover', action='store_true'); args = parser.parse_args()
    adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
    emulator = args.serial.startswith("emulator-")
    if args.manual_cover and not emulator: raise ValueError('Manuel kapak testi yalnızca test emülatöründe çalışır.')
    if emulator: key = "companion-emulator-fixture-key-1234567890"
    else:
        sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "bridge"))
        from bridge import key_path, protect
        key = protect(key_path().read_bytes(), True).decode()
    fixture = str(uuid.uuid4())
    mappings = run_adb(adb, ["-s", args.serial, "reverse", "--list"])
    existing = any(line.split()[-2:] == ["tcp:8767", "tcp:8767"] for line in mappings.splitlines())
    if not existing: run_adb(adb, ["-s", args.serial, "reverse", "--no-rebind", "tcp:8767", "tcp:8767"])
    try:
        with Exchange(key) as exchange:
            job = {"action": "edit", "changes": [{"id": fixture, "expectedUpdatedAt": 1, "fields": {"lastChapter": "9", "notes": "USB kabul", "favorite": True}}]}
            if args.manual_cover:
                def chunk(tag, data): return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data))
                png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b'\0\xff\0\0')) + chunk(b'IEND', b'')
                cover = manual_cover({'id': fixture, 'title': 'USB kapak', 'updatedAt': 1}, png)
                job = {'action': 'manual_covers', 'changes': [{key: cover[key] for key in ('id', 'expectedUpdatedAt', 'jpeg')}]}
            operation = exchange.begin(job)
            test = 'com.qylent.manhwatakip.DesktopUsbAcceptanceTest#' + ('temporaryManualCoverAppliesWithoutAiApproval' if args.manual_cover else 'temporaryUsbEditKeepsUserLibraryAndAiApproval')
            command = [str(adb), "-s", args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", test, "-e", "companionUsbAcceptance", "true", '-e', 'manualCoverAcceptance', str(args.manual_cover).lower(), "-e", "fixtureId", fixture, "-e", "operationId", operation.id]
            if emulator: command += ["-e", "fixtureKey", key]
            command += ["com.qylent.manhwatakip.test/androidx.test.runner.AndroidJUnitRunner"]
            completed = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=100, creationflags=subprocess.CREATE_NO_WINDOW)
            print(completed.stdout)
            if completed.returncode or "OK (1 test)" not in completed.stdout or not operation.done.wait(5): raise RuntimeError("USB acceptance incomplete")
            result = operation.result
            assert result["ok"] and result["applied"] == 1
            assert not result["snapshot"].get("keys")
            record = next(x for x in result["snapshot"]["library"]["records"] if x["id"] == fixture)
            if args.manual_cover:
                assert record['local']['coverUrl'] == cover['imageUrl'] and record['lastChapter'] == '1'
                assert any(x['seriesId'] == fixture for x in result['snapshot'].get('covers', []))
            else: assert record["lastChapter"] == "9" and record["notes"] == "USB kabul"
            print("REAL_SIGNED_USB_ROUNDTRIP_OK; AUTH_AND_PERSONAL_DATA_PROTECTION_VERIFIED")
    finally:
        if not existing: run_adb(adb, ["-s", args.serial, "reverse", "--remove", "tcp:8767"])


if __name__ == "__main__": main()
