"""User-only local key display and portable stdio setup. Never prints credentials."""
import argparse
import json
import os
from pathlib import Path
import sys
import tempfile


def server_config():
    executable = Path(sys.executable)
    if executable.name.lower() == "pythonw.exe": executable = executable.with_name("python.exe")
    isolated = ["-I"] if (executable.parent / "python313._pth").is_file() else []
    return {"command": str(executable), "args": [*isolated, str(Path(__file__).resolve().with_name("bridge.py"))]}


def merge_config(existing, server):
    if not isinstance(existing, dict) or not isinstance(existing.get("mcpServers", {}), dict):
        raise ValueError("MCP yapilandirmasi gecersiz; dosya degistirilmedi.")
    servers = existing.get("mcpServers", {})
    if "manhwa_takip" in servers and servers["manhwa_takip"] != server:
        raise ValueError("manhwa_takip zaten farkli ayarla var. Mevcut girdiyi once incele; dosya degistirilmedi.")
    return {**existing, "mcpServers": {**servers, "manhwa_takip": server}}


def install_config(target, server):
    target = Path(target)
    old = target.read_bytes() if target.exists() else None
    merged = merge_config(json.loads(old.decode("utf-8-sig")) if old is not None else {}, server)
    data = (json.dumps(merged, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    target.parent.mkdir(parents=True, exist_ok=True)
    # One recoverable snapshot, created exclusively; never overwrite an earlier backup.
    backup = target.with_name(target.name + ".before-manhwa")
    if old is not None and old != data and not backup.exists():
        with backup.open("xb") as output:
            output.write(old)
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as output:
        temporary = Path(output.name)
        output.write(data); output.flush(); os.fsync(output.fileno())
    try:
        # Refuse to overwrite edits made after opening the configuration.
        if (target.read_bytes() if target.exists() else None) != old:
            raise ValueError("Dosya bu sirada degisti; yeniden dene. Dosya degistirilmedi.")
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def antigravity_target():
    current = Path.home() / ".gemini/config/mcp_config.json"
    legacy = Path.home() / ".gemini/antigravity/mcp_config.json"
    return legacy if legacy.exists() and not current.exists() else current


def window(mode):
    import tkinter as tk
    from tkinter import messagebox, filedialog
    root = tk.Tk(); root.title("Manhwa Takip — " + ("Eşleştirme anahtarı" if mode == "key" else "MCP kurulumu"))
    root.geometry("690x430")
    def copy(value):
        root.clipboard_clear(); root.clipboard_append(value); root.update()
        messagebox.showinfo("Manhwa Takip", "Panoya kopyalandı.", parent=root)
    if mode == "key":
        from bridge import key_path, protect
        try:
            key = protect(key_path().read_bytes(), decrypt=True).decode()
        except Exception:
            messagebox.showerror("Manhwa Takip", "Mevcut bilgisayar eşleştirme anahtarı açılamadı. Mevcut yerel kurulumu kontrol et. Anahtar değiştirilmedi.", parent=root)
            root.destroy(); return 1
        tk.Label(root, text="Bu anahtarı yalnızca kendi telefonundaki MCP eşleştirme alanında kullan.\nAI sağlayıcısı anahtarı değildir; YZ sohbetine veya MCP yapılandırmasına yapıştırma.", wraplength=640).pack(pady=22)
        entry = tk.Entry(root, show="•", width=65); entry.insert(0, key); entry.configure(state="readonly"); entry.pack(pady=12)
        visible = tk.BooleanVar()
        tk.Checkbutton(root, text="Anahtarı göster", variable=visible, command=lambda: entry.configure(show="" if visible.get() else "•")).pack()
        tk.Button(root, text="Eşleştirme anahtarını kopyala", command=lambda: copy(key)).pack(pady=16)
    else:
        server = server_config(); payload = json.dumps({"mcpServers": {"manhwa_takip": server}}, ensure_ascii=False, indent=2)
        tk.Label(root, text="Yerel STDIO MCP: aynı USB köprüsü ve telefonda onay.\nAPI/eşleştirme anahtarı içermez. İstemcide bağlantıyı yeniden yükle; telefonda paylaşımı kendin aç.", wraplength=640).pack(pady=12)
        text = tk.Text(root, height=9, wrap="word"); text.insert("1.0", payload); text.configure(state="disabled"); text.pack(fill="x", padx=18)
        tk.Button(root, text="MCP yapılandırmasını kopyala", command=lambda: copy(payload)).pack(pady=8)
        def install(target):
            try:
                install_config(target, server)
                messagebox.showinfo("Manhwa Takip", f"manhwa_takip eklendi. Diğer ayarlar korundu.\n{target}\nİstemcide MCP bağlantısını yeniden yükle.", parent=root)
            except Exception as error:
                messagebox.showerror("Manhwa Takip", str(error) if isinstance(error, ValueError) else "Dosya kaydedilemedi. Mevcut ayarlar korundu.", parent=root)
        tk.Button(root, text="Antigravity'ye ekle", command=lambda: install(antigravity_target())).pack(pady=4)
        def choose():
            target = filedialog.askopenfilename(title="mcpServers kullanan istemcinin mevcut JSON ayar dosyasını seç", filetypes=[("JSON", "*.json")])
            if target: install(target)
        tk.Button(root, text="Başka istemcinin JSON ayarına ekle…", command=choose).pack(pady=4)
    tk.Button(root, text="Kapat", command=root.destroy).pack(pady=8)
    root.mainloop(); return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--key", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        import tkinter
        assert Path(server_config()["args"][0]).is_file()
        print("USER_SETUP_CHECK_OK")
        return 0
    return window("key" if args.key else "mcp")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception:
        # A key display exception must never leak data through console/tracebacks.
        raise SystemExit(1)
