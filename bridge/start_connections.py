"""User-invoked launch shortcuts; no credentials or automatic phone sharing."""
import argparse
import ast
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request

def private_config_path():
    return Path(os.environ['LOCALAPPDATA']) / 'ManhwaTakip' / 'connections-private.json'


def migrate_private(source, target=None):
    """Extract only legacy launch constants; never execute the old file or inspect credentials."""
    target = Path(target) if target is not None else private_config_path()
    if target.exists() or not Path(source).is_file(): return
    tree = ast.parse(Path(source).read_text(encoding='utf-8-sig'))
    config = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(x, ast.Name) and x.id == 'AI_URL' for x in node.targets):
            if isinstance(node.value, ast.Constant): config['ai_url'] = node.value.value
        if isinstance(node, ast.FunctionDef) and node.name == 'ensure_ai':
            for child in ast.walk(node):
                if isinstance(child, ast.Assign) and any(isinstance(x, ast.Name) and x.id == 'remote' for x in child.targets) and isinstance(child.value, ast.Constant):
                    config['remote_command'] = child.value.value
                if isinstance(child, ast.List) and len(child.elts) >= 2 and isinstance(child.elts[-1], ast.Name) and child.elts[-1].id == 'remote' and isinstance(child.elts[-2], ast.Constant):
                    config['ssh_alias'] = child.elts[-2].value
    if not config: return
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open('x', encoding='utf-8') as output: json.dump(config, output, ensure_ascii=False, indent=2)


def load_config():
    path = private_config_path()
    if not path.exists(): return {}
    value = json.loads(path.read_text(encoding='utf-8-sig'))
    if not isinstance(value, dict): raise RuntimeError('Özel bağlantı ayarı geçersiz.')
    return {name: value[name] for name in ('ai_url', 'ssh_alias', 'remote_command')
            if isinstance(value.get(name), str) and value[name].strip()}


def backend_module():
    import bridge
    return bridge


def existing_key():
    backend = backend_module()
    return backend.protect(backend.key_path().read_bytes(), True).decode()


def adb_path():
    return Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platform-tools/adb.exe'


def usb_status():
    if not adb_path().is_file(): return 'ADB bulunamadı'
    serial = usb_target(run([str(adb_path()), 'devices']))
    return 'hazır' if not needs_reverse(run([str(adb_path()), '-s', serial, 'reverse', '--list'])) else 'yönlendirme bekleniyor'


def run(command, timeout=15):
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout,
                            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    if result.returncode:
        raise RuntimeError("Komut tamamlanamadi; mevcut SSH/USB yetkisini kontrol et.")
    return result.stdout


def usb_target(devices):
    physical = [parts[0] for line in devices.splitlines() if len(parts := line.split()) == 2
                and parts[1] == "device" and not parts[0].startswith("emulator-") and ":" not in parts[0]]
    if len(physical) != 1:
        raise RuntimeError("USB ile tam bir yetkili fiziksel telefon bagla.")
    return physical[0]


def needs_reverse(mappings):
    existing = [parts[-1] for line in mappings.splitlines() if len(parts := line.split()) >= 3 and parts[-2] == "tcp:8765"]
    if existing and existing != ["tcp:8765"]:
        raise RuntimeError("8765 baska yonlendirmede; degistirilmedi.")
    return not existing


def ensure_usb():
    adb = adb_path()
    if not adb.is_file():
        raise RuntimeError("Android platform-tools/ADB bulunamadi.")
    serial = usb_target(run([str(adb), "devices"]))
    command = [str(adb), "-s", serial, "reverse"]
    if needs_reverse(run(command + ["--list"])):
        run(command + ["--no-rebind", "tcp:8765", "tcp:8765"])
    if needs_reverse(run(command + ["--list"])):
        raise RuntimeError("USB yonlendirmesi dogrulanamadi.")
    return "USB koprusu hazir. Telefonda Paylas ve esitle yap."


def ai_reachable(url):
    try:
        with urllib.request.urlopen(url, timeout=4):
            return True
    except urllib.error.HTTPError:
        return True  # HTTP rejection still proves a running service; never launch a duplicate.
    except (urllib.error.URLError, TimeoutError, OSError):
        return False


def ensure_ai():
    config = load_config()
    if not config.get('ai_url'): return 'Uzak AI yapılandırılmadı; uzak başlatma devre dışı.'
    if not ai_reachable(config['ai_url']):
        if not all(config.get(name) for name in ('ssh_alias', 'remote_command')):
            return 'Uzak AI başlatma yapılandırılmadı; ayarlar değiştirilmedi.'
        ssh = Path(os.environ["SYSTEMROOT"]) / "System32/OpenSSH/ssh.exe"
        run([str(ssh), "-T", "-o", "BatchMode=yes", "-o", "ConnectTimeout=5", "-o", "StrictHostKeyChecking=yes", config['ssh_alias'], config['remote_command']], timeout=15)
        for _ in range(10):
            if ai_reachable(config['ai_url']):
                break
            time.sleep(1)
        else:
            raise RuntimeError("AI sunucusu yanit vermiyor; mevcut SSH ve ag erisimini kontrol et.")
    return "AI sunucusu hazir."


def ensure_mcp(restart=False):
    backend = backend_module()
    (backend.restart_backend if restart else backend.ensure_backend)(existing_key())
    return "MCP koprusu yeniden baslatildi." if restart else "MCP koprusu hazir."


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["all", "ai", "usb", "restart"], default="all")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        print("CONNECTION_LAUNCHER_CHECK_OK")
        return
    failed = False
    actions = [ensure_ai] if args.mode == "ai" else [ensure_usb] if args.mode == "usb" else [ensure_usb, ensure_ai, lambda: ensure_mcp(args.mode == "restart")]
    for action in actions:
        try:
            print(action())
        except Exception as error:
            failed = True
            print(str(error) if isinstance(error, RuntimeError) else "Baglanti hazirlanamadi; ag/USB yetkisini kontrol et.")
    raise SystemExit(1 if failed else 0)


if __name__ == "__main__":
    main()
