"""Private stdio MCP bridge. USB sync listens only on loopback; no database writes."""
import argparse
import copy
import ctypes
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import threading
import time
import unicodedata
import uuid
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
import urllib.error
import urllib.request

TEXT_FIELDS = ["title", "url", "lastChapter", "status", "opinion", "likedReason", "dislikedReason", "dropReason", "notes"]
FIELDS = TEXT_FIELDS + ["categories"]
STATUSES = ["PLANNED", "READING", "PAUSED", "DROPPED", "COMPLETED"]
OPINIONS = ["UNSET", "LIKED", "DISLIKED"]
MAX_BYTES = 8 * 1024 * 1024

def utf16_length(text): return len(text.encode("utf-16-le")) // 2


def validate_record(value):
    if not isinstance(value, dict) or set(value) - set(FIELDS + ["id", "createdAt", "updatedAt"]) or not set(TEXT_FIELDS + ["id", "createdAt", "updatedAt"]).issubset(value):
        raise ValueError("Kayıt alanları geçersiz.")
    if any(not isinstance(value[f], str) for f in TEXT_FIELDS + ["id"]):
        raise ValueError("Alan türü geçersiz.")
    item = copy.deepcopy(value)
    categories = item.get("categories", [])
    if not isinstance(categories, list) or any(not isinstance(c, str) or not c.strip() or utf16_length(c.strip()) > 80 or any(unicodedata.category(ch) == "Cc" for ch in c.strip()) for c in categories):
        raise ValueError("Özel kategori listesi geçersiz.")
    item["categories"] = [unicodedata.normalize("NFC", c.strip()) for c in categories]
    keys = [c.replace("I", "ı").replace("İ", "i").lower() for c in item["categories"]]
    if len(set(keys)) != len(keys): raise ValueError("Mükerrer özel kategori var.")
    if not re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", item["id"]):
        raise ValueError("Kayıt kimliği geçersiz.")
    item["id"] = item["id"].lower()
    item["title"] = item["title"].strip()
    if not item["title"] or utf16_length(item["title"]) > 200 or any(unicodedata.category(c) == "Cc" for c in item["title"]):
        raise ValueError("Seri adı geçersiz.")
    item["url"] = item["url"].strip()
    if utf16_length(item["url"]) > 2048:
        raise ValueError("Bağlantı çok uzun.")
    if item["url"]:
        uri = urlsplit(item["url"])
        if uri.scheme not in ["http", "https"] or not uri.hostname or uri.username is not None or uri.password is not None or re.search(r'[\s<>"{}|\\^`\x00-\x1f\x7f]', item["url"]) or re.search(r"%(?![0-9a-fA-F]{2})", item["url"]):
            raise ValueError("Bağlantı geçersiz.")
        host = uri.hostname
        if ":" not in host and (not host.isascii() or not re.fullmatch(r"[A-Za-z0-9.-]+", host) or any(not part or part.startswith("-") or part.endswith("-") for part in host.rstrip(".").split("."))):
            raise ValueError("Bağlantı alan adı geçersiz.")
        if ":" not in host:
            labels = host.rstrip(".").split(".")
            ipv4 = len(labels) == 4 and all(label.isdigit() and 0 <= int(label) <= 255 for label in labels)
            if len(labels) > 1 and labels[-1][0].isdigit() and not ipv4:
                raise ValueError("Bağlantı alan adı geçersiz.")
        if "[" in uri.path or "]" in uri.path: raise ValueError("Bağlantı yolu geçersiz.")
        if uri.port is not None and not 0 <= uri.port <= 65535: raise ValueError("Bağlantı portu geçersiz.")
    chapter = item["lastChapter"].strip().replace(",", ".")
    if chapter and (len(chapter) > 20 or not re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", chapter)):
        raise ValueError("Bölüm geçersiz.")
    item["lastChapter"] = format(Decimal(chapter).normalize(), "f") if chapter else ""
    if item["status"] not in STATUSES or item["opinion"] not in OPINIONS:
        raise ValueError("Durum/beğeni geçersiz.")
    for name in ["likedReason", "dislikedReason", "dropReason", "notes"]:
        if utf16_length(item[name]) > (4000 if name == "notes" else 2000) or "\0" in item[name]:
            raise ValueError("Not/neden alanı geçersiz.")
    if any(type(item[k]) is not int for k in ["createdAt", "updatedAt"]) or not 0 <= item["createdAt"] <= item["updatedAt"] <= 9223372036854775807:
        raise ValueError("Kayıt tarihleri geçersiz.")
    return item


def duplicate_key(item):
    title = unicodedata.normalize("NFC", re.sub(r"\s+", " ", item["title"]))
    return title.replace("I", "ı").replace("İ", "i").lower(), item["url"]


def library_records(library):
    if not isinstance(library, dict) or set(library) != {"format", "version", "exportedAt", "records"} or library["format"] != "manhwa-takip" or type(library["version"]) is not int or library["version"] not in (1, 2):
        raise ValueError("Kütüphane biçimi geçersiz.")
    if type(library["exportedAt"]) is not int or library["exportedAt"] < 0 or not isinstance(library["records"], list) or len(library["records"]) > 10000:
        raise ValueError("Kütüphane sınırı geçersiz.")
    records = [validate_record(r) for r in library["records"]]
    if len({r["id"] for r in records}) != len(records) or len({duplicate_key(r) for r in records}) != len(records):
        raise ValueError("Mükerrer kayıt var.")
    return records


class BridgeState:
    def __init__(self, key):
        self.key = key
        self.lock = threading.RLock()
        self.records = []
        self.proposals = {}
        self.last_sync = 0
        self.expires = 0

    def sync(self, payload):
        if not isinstance(payload, dict) or set(payload) != {"library", "decisions"}:
            raise ValueError("Eşitleme biçimi geçersiz.")
        records = library_records(payload["library"])
        decisions = payload["decisions"]
        if not isinstance(decisions, dict) or len(decisions) > 200 or any(not isinstance(k, str) or v not in ["approved", "rejected"] for k, v in decisions.items()):
            raise ValueError("Karar listesi geçersiz.")
        with self.lock:
            for token, status in decisions.items():
                if token in self.proposals:
                    previous = self.proposals[token]["status"]
                    if previous != "pending_phone_approval" and previous != status:
                        raise ValueError("Önceki karar değiştirilemez.")
            self.records = records
            for token, status in decisions.items():
                if token in self.proposals: self.proposals[token]["status"] = status
            self.last_sync = int(time.time() * 1000)
            self.expires = time.monotonic() + 15 * 60
            pending = [{k:copy.deepcopy(v) for k, v in p.items() if k != "status"} for p in self.proposals.values() if p["status"] == "pending_phone_approval"]
            return dict(proposals=pending, lastSync=self.last_sync)

    def disconnect(self):
        with self.lock:
            self.records = []; self.proposals = {}; self.last_sync = 0; self.expires = 0

    def expire(self):
        with self.lock:
            if self.expires and time.monotonic() >= self.expires: self.disconnect()

    def call(self, name, args):
        with self.lock:
            self.expire()
            if time.monotonic() >= self.expires:
                raise ValueError("Telefon bağlantısı kapalı veya süresi doldu. Telefonda bağlantıyı açıp eşitle.")
            if not isinstance(args, dict): raise ValueError("Araç girdisi geçersiz.")
            if name == "list_library":
                if args: raise ValueError("Bu araç parametre almaz.")
                return dict(records=copy.deepcopy(self.records), lastSync=self.last_sync, source="Son kullanıcı eşitlemesi; telefon güncellemeleri yeniden eşitlenmelidir.")
            if name == "proposal_status":
                if set(args) != {"token"} or not isinstance(args["token"], str) or args["token"] not in self.proposals:
                    raise ValueError("Öneri bulunamadı.")
                return dict(token=args["token"], status=self.proposals[args["token"]]["status"])
            if name != "propose_change": raise ValueError("Araç desteklenmiyor.")
            if set(args) - {"seriesId", "expectedUpdatedAt", "fields"} or "seriesId" not in args or "fields" not in args:
                raise ValueError("Öneri girdisi geçersiz.")
            fields = args["fields"]
            if not isinstance(fields, dict) or not fields or set(fields) - set(FIELDS) or any(not isinstance(v, list if k == "categories" else str) for k, v in fields.items()):
                raise ValueError("Öneri alanları geçersiz.")
            before = None
            if args["seriesId"] is not None:
                if not isinstance(args["seriesId"], str): raise ValueError("Seri kimliği geçersiz.")
                before = next((r for r in self.records if r["id"] == args["seriesId"]), None)
                if before is None or type(args.get("expectedUpdatedAt")) is not int or before["updatedAt"] != args["expectedUpdatedAt"]:
                    raise ValueError("Kayıt bulunamadı veya sürümü değişti; kütüphaneyi yeniden oku.")
            elif "expectedUpdatedAt" in args:
                raise ValueError("Yeni kayıtta eski sürüm kullanılamaz.")
            now = int(time.time() * 1000)
            base = copy.deepcopy(before) if before else dict(id=str(uuid.uuid4()), title="", url="", lastChapter="", status="PLANNED", opinion="UNSET", likedReason="", dislikedReason="", dropReason="", notes="", createdAt=now, updatedAt=now)
            base.update(fields); after = validate_record(base)
            if any(r["id"] != after["id"] and duplicate_key(r) == duplicate_key(after) for r in self.records):
                raise ValueError("Seri aynı bağlantıyla zaten kayıtlı.")
            if len(self.proposals) >= 200:
                raise ValueError("Öneri sınırına ulaşıldı. Bağlantıyı kapatıp yeniden aç.")
            token = str(uuid.uuid4())
            self.proposals[token] = dict(token=token, before=copy.deepcopy(before), after=after, status="pending_phone_approval")
            return dict(token=token, status="pending_phone_approval", message="Henüz kayıt değiştirilmedi. Telefonda eşitle, eski/yeni değerleri incele ve onayla veya reddet.")


def tool_definitions():
    fields = dict(type="object", additionalProperties=False, minProperties=1, properties={name:dict(type="string", **({"enum":STATUSES} if name == "status" else {"enum":OPINIONS} if name == "opinion" else {})) for name in FIELDS})
    fields["properties"]["categories"] = dict(type="array", items=dict(type="string", minLength=1, maxLength=80), uniqueItems=True)
    return [
        dict(name="list_library", description="Son telefon eşitlemesindeki kütüphaneyi ve not/nedenleri oku; snapshot tarihine dikkat et.", inputSchema=dict(type="object", properties={}, additionalProperties=False), annotations=dict(readOnlyHint=True, openWorldHint=False)),
        dict(name="propose_change", description="Tek bir yeni kayıt veya değişiklik öner. Yazmaz; yalnızca telefonda kullanıcı onayı sonrası uygulanır. Mevcut kayıtta expectedUpdatedAt zorunlu.", inputSchema=dict(type="object", required=["seriesId", "fields"], additionalProperties=False, properties=dict(seriesId=dict(type=["string", "null"]), expectedUpdatedAt=dict(type="integer", minimum=0), fields=fields)), annotations=dict(readOnlyHint=False, destructiveHint=False, idempotentHint=False, openWorldHint=False)),
        dict(name="proposal_status", description="Telefonda onay/red bilgisini son eşitlemeden oku.", inputSchema=dict(type="object", required=["token"], additionalProperties=False, properties=dict(token=dict(type="string"))), annotations=dict(readOnlyHint=True, openWorldHint=False))
    ]


class RpcSession:
    def __init__(self, state): self.state = state; self.initialized = False; self.ready = False
    def handle(self, request):
        id = request.get("id") if isinstance(request, dict) else None
        def error(code, message): return dict(jsonrpc="2.0", id=id, error=dict(code=code, message=message))
        if not isinstance(request, dict) or request.get("jsonrpc") != "2.0" or not isinstance(request.get("method"), str):
            return error(-32600, "Geçersiz istek.")
        method = request["method"]; params = request.get("params", {})
        if not isinstance(params, dict): return error(-32602, "Geçersiz parametreler.")
        if "id" not in request:
            if method == "notifications/initialized" and self.initialized: self.ready = True
            return None
        if method == "initialize":
            if self.initialized: return error(-32600, "Zaten başlatıldı.")
            if not isinstance(params.get("protocolVersion"), str) or not isinstance(params.get("clientInfo"), dict) or not isinstance(params.get("capabilities"), dict):
                return error(-32602, "Başlatma parametreleri geçersiz.")
            version = params["protocolVersion"]
            if version not in ["2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"]: version = "2025-11-25"
            self.initialized = True
            result = dict(protocolVersion=version, capabilities=dict(tools=dict(listChanged=False)), serverInfo=dict(name="manhwa-usb-bridge", version="0.2.0"))
        elif method == "ping": result = {}
        elif not self.ready: return error(-32002, "Önce MCP başlatılmalı.")
        elif method == "tools/list": result = dict(tools=tool_definitions())
        elif method == "tools/call":
            try:
                data = self.state.call(params.get("name"), params.get("arguments", {}))
                result = dict(content=[dict(type="text", text=json.dumps(data, ensure_ascii=False))], structuredContent=data, isError=False)
            except ValueError as exc:
                result = dict(content=[dict(type="text", text=str(exc))], isError=True)
        else: return error(-32601, "Yöntem desteklenmiyor.")
        return dict(jsonrpc="2.0", id=id, result=result)


def make_server(state, port=8765):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def reply(self, status, data):
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self.send_response(status); self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body))); self.send_header("Cache-Control", "no-store"); self.end_headers(); self.wfile.write(body)
        def do_POST(self):
            self.connection.settimeout(15)
            if self.headers.get("Origin") is not None or self.headers.get("Host") not in [f"127.0.0.1:{self.server.server_port}", f"localhost:{self.server.server_port}"]:
                return self.reply(403, dict(error="Erisim reddedildi."))
            if not hmac.compare_digest(self.headers.get("Authorization", "").encode("utf-8"), ("Bearer " + state.key).encode("utf-8")):
                return self.reply(401, dict(error="Eslestirme gerekli."))
            if self.path not in ["/sync", "/disconnect", "/tool", "/health", "/shutdown"]:
                return self.reply(404, dict(error="Bulunamadi."))
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= MAX_BYTES or self.headers.get("Transfer-Encoding") is not None: raise ValueError()
                payload = json.loads(self.rfile.read(length).decode("utf-8"))
                if self.path in ["/disconnect", "/health", "/shutdown"]:
                    if payload != {}: raise ValueError()
                    if self.path == "/health": result = dict(server="manhwa-usb-bridge", pid=os.getpid())
                    elif self.path == "/disconnect":
                        state.disconnect(); result = dict(disconnected=True)
                    else:
                        state.disconnect(); result = dict(stopped=True)
                elif self.path == "/tool":
                    if not isinstance(payload, dict) or set(payload) != {"name", "args"}: raise ValueError()
                    try: result = state.call(payload["name"], payload["args"])
                    except ValueError as error: return self.reply(400, dict(error=str(error)))
                else: result = state.sync(payload)
                self.reply(200, result)
                if self.path == "/shutdown": threading.Thread(target=self.server.shutdown, daemon=True).start()
            except Exception: self.reply(400, dict(error="Eslestirme girdisi gecersiz; telefon kayitlari degismedi."))
    class Server(ThreadingHTTPServer):
        allow_reuse_address = False
        def server_bind(self):
            if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            super().server_bind()
        def service_actions(self): state.expire()
    return Server(("127.0.0.1", port), Handler)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url): return None


class RemoteBridge:
    def __init__(self, key, port=8765): self.key = key; self.port = port
    def post(self, path, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        if len(body) > MAX_BYTES: raise ValueError("Kopru istek siniri asildi.")
        request = urllib.request.Request(f"http://127.0.0.1:{self.port}" + path, body,
            {"Authorization":"Bearer " + self.key, "Content-Type":"application/json"})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        try:
            with opener.open(request, timeout=15) as response:
                data = response.read(MAX_BYTES + 1)
        except urllib.error.HTTPError as error:
            try: data = error.read(MAX_BYTES + 1)
            finally: error.close()
            if error.code == 400 and path == "/tool" and len(data) <= MAX_BYTES:
                raise ValueError(json.loads(data).get("error", "Kopru istegi reddedildi.")) from None
            raise ValueError("Yerel kopru erisimi reddedildi; eslestirmeyi kontrol et.") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise ValueError("Yerel kopruye ulasilamadi; baslatma kisayolunu calistir.") from None
        if len(data) > MAX_BYTES: raise ValueError("Kopru yanit siniri asildi.")
        return json.loads(data)
    def call(self, name, args): return self.post("/tool", dict(name=name, args=args))
    def health(self):
        data = self.post("/health", {})
        if data.get("server") != "manhwa-usb-bridge" or type(data.get("pid")) is not int:
            raise ValueError("USB portunda beklenen kopru yok.")
        return data


def ensure_backend(key, port=8765):
    remote = RemoteBridge(key, port)
    try:
        remote.health(); return remote
    except ValueError:
        pass
    if port != 8765: raise RuntimeError("Isolated test backend is unavailable.")
    options = dict(stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if os.name == "nt": options["creationflags"] = subprocess.DETACHED_PROCESS | subprocess.CREATE_NO_WINDOW
    else: options["start_new_session"] = True
    subprocess.Popen([sys.executable, *(['-I'] if sys.flags.isolated else []), str(Path(__file__).resolve()), "--serve"], **options)
    for _ in range(50):
        try:
            remote.health(); return remote
        except ValueError:
            time.sleep(0.1)  # Another MCP session may win the exclusive bind during simultaneous startup.
    raise RuntimeError("Tek yerel kopru baslatilamadi; USB portunu ve Windows erisimini kontrol et.")


def restart_backend(key):
    remote = RemoteBridge(key)
    try: previous = remote.health()["pid"]
    except ValueError: return ensure_backend(key)
    remote.post("/shutdown", {})
    for _ in range(50):
        try:
            if remote.health()["pid"] != previous: return remote
        except ValueError:
            return ensure_backend(key)
        time.sleep(0.1)
    raise RuntimeError("Kopru kapanisi dogrulanamadi; ikinci kopru acilmadi.")


def protect(data, decrypt=False):
    if os.name != "nt": raise RuntimeError("Bu köprü Windows DPAPI gerektirir.")
    from ctypes import wintypes
    class Blob(ctypes.Structure): _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_byte))]
    buffer = ctypes.create_string_buffer(data)
    source = Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte))); target = Blob()
    crypt = ctypes.windll.crypt32
    operation = crypt.CryptUnprotectData if decrypt else crypt.CryptProtectData
    operation.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    operation.restype = wintypes.BOOL
    if not operation(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise RuntimeError("Yerel bağlantı anahtarı şifrelenemedi/açılamadı.")
    try: return ctypes.string_at(target.data, target.size)
    finally:
        ctypes.windll.kernel32.LocalFree.argtypes = [ctypes.c_void_p]
        ctypes.windll.kernel32.LocalFree(ctypes.cast(target.data, ctypes.c_void_p))


def key_path(): return Path(os.environ["LOCALAPPDATA"]) / "ManhwaTakip" / "bridge-key.dpapi"


def main(port=8765):
    parser = argparse.ArgumentParser(description="Özel Manhwa USB/MCP köprüsü")
    parser.add_argument("--init", action="store_true"); parser.add_argument("--show-pairing-key", action="store_true")
    parser.add_argument("--serve", action="store_true"); parser.add_argument("--restart", action="store_true")
    args = parser.parse_args(); path = key_path()
    if args.init:
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists():
            key = secrets.token_urlsafe(32)
            encrypted = protect(key.encode())
            with path.open("xb") as output: output.write(encrypted)
        print("Yerel eşleştirme anahtarı hazır. Anahtarı yalnızca kendi terminalinde --show-pairing-key ile göster.")
        return
    key = protect(path.read_bytes(), decrypt=True).decode()
    if args.show_pairing_key:
        print(key); return  # Explicit end-user display command; never invoked by automated verification.
    if args.restart:
        restart_backend(key); print("MCP_BACKEND_RESTART_OK"); return
    if args.serve:
        state = BridgeState(key); server = make_server(state, port)
        try: server.serve_forever(poll_interval=0.1)
        finally: state.disconnect(); server.server_close()
        return
    rpc = RpcSession(ensure_backend(key, port))
    while True:
        line = sys.stdin.buffer.readline(MAX_BYTES + 1)
        if not line: break
        if len(line) > MAX_BYTES: raise RuntimeError("MCP istek siniri asildi.")
        try: response = rpc.handle(json.loads(line.decode("utf-8")))
        except Exception: response = dict(jsonrpc="2.0", id=None, error=dict(code=-32700, message="JSON istegi gecersiz."))
        if response is not None:
            sys.stdout.buffer.write((json.dumps(response, ensure_ascii=False) + "\n").encode("utf-8")); sys.stdout.buffer.flush()


if __name__ == "__main__":
    try: main()
    except Exception:
        print("Köprü başlatılamadı. --init, port ve Windows kullanıcı erişimini kontrol et.", file=sys.stderr)
        sys.exit(1)
