"""User-run Windows companion. Python/Tkinter and existing ADB installation only."""
import argparse
import base64
import hashlib
import json
import os
import queue
import re
import subprocess
import sys
import tempfile
import threading
import time
import unicodedata
from contextlib import nullcontext
from datetime import datetime
from decimal import Decimal
from pathlib import Path
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from core import STATUSES, FIELDS, Drafts, Exchange, read_backup, backup_zip, ai_package, zip_files, encode, atomic_write
from covers import CoverSearch, CoverDrafts, LOCAL_AI, manual_cover, clipboard_image
from connections import ConnectionController
from preferences import Preferences
import theme
from tray import TrayController, WindowsStartup, InstanceGate

OPINIONS = {"UNSET": "Belirtilmedi", "LIKED": "Sevdim", "DISLIKED": "Sevmedim"}

SETUP_GUIDANCE = (
    'Çevrimdışı kurulum: USB izleme kapalı. Ayarlar ve yerel yedekler kullanılabilir.\n'
    '1. Resmî Android SDK Platform-Tools paketini kur: '
    'https://developer.android.com/tools/releases/platform-tools → '
    '%LOCALAPPDATA%\\Android\\Sdk\\platform-tools\\adb.exe\n'
    '2. Mevcut eşleştirme anahtarı yoksa ilk anahtar kurulumu gerekir. '
    'Bağlantılar → İlk eşleştirmeyi hazırla düğmesini kendi açık onayınla kullan; '
    'uygulama kendiliğinden anahtar üretmez veya mevcut anahtarı değiştirmez.\n'
    '3. Mevcut eşleştirme anahtarı… ile anahtarı telefondaki MCP ayarına kaydet. '
    'MCP istemci kurulumu… istemci ayarını açar; anahtar oluşturmaz.\n'
    'Kurulumdan sonra bilgisayar uygulamasından Tamamen çık ve yeniden aç. '
    'Telefon paylaşımını ayrıca kendin aç.'
)

def ai_import_job(text):
    """Bounded transport only. The phone owns identity matching, preview and atomic approval."""
    if len(text.encode('utf-8')) > 4 * 1024 * 1024:
        raise ValueError('AI JSON 4 MiB sınırını aşıyor.')
    content = text.strip().lstrip('\ufeff').strip()
    if content.startswith('```'):
        lines = content.splitlines()
        if len(lines) < 3 or lines[0].strip().lower() not in ('```', '```json') or lines[-1].strip() != '```':
            raise ValueError('Tek bir JSON kod bloğu seç.')
        content = '\n'.join(lines[1:-1])
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result: raise ValueError('JSON yinelenen alan içeriyor.')
            result[key] = value
        return result
    def invalid_constant(_): raise ValueError('JSON sayısı geçersiz.')
    try: root = json.loads(content, object_pairs_hook=unique, parse_constant=invalid_constant)
    except (json.JSONDecodeError, RecursionError) as error: raise ValueError('JSON eksik veya geçersiz.') from error
    if isinstance(root, dict) and ('format' in root or 'version' in root):
        if root.get('format') != 'manhwa-chatgpt' or type(root.get('version')) is not int or root['version'] != 1:
            raise ValueError('AI JSON biçimi veya sürümü desteklenmiyor.')
    rows = root.get('series', [root]) if isinstance(root, dict) else root
    if not isinstance(rows, list) or not 1 <= len(rows) <= 10000 or any(not isinstance(row, dict) for row in rows):
        raise ValueError('JSON 1–10.000 seri nesnesi içermeli.')
    return {'action': 'ai_import', 'text': text}

COLORS = theme.palette('light', False)  # Compatibility for consumers; windows own their palette.


def run_adb(adb, args):
    result = subprocess.run([str(adb), *args], capture_output=True, timeout=25, text=True, encoding="utf-8", errors="replace", creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    if result.returncode: raise ValueError("USB işlemi tamamlanamadı. Telefon kilidini ve USB hata ayıklama yetkisini kontrol et.")
    return result.stdout


def usb_devices(text):
    return [parts[0] for line in text.splitlines() if len(parts := line.split()) >= 2 and parts[1] == "device" and not parts[0].startswith("emulator-") and ":" not in parts[0]]


def fold(text): return text.replace("I", "ı").replace("İ", "i").casefold()


class Window:
    def __init__(self, root, folder, exchange=None, adb=None, demo=False, connections=None, tray=None, startup=None, instance=None):
        self.root = root; self.folder = Path(folder); self.exchange = exchange; self.adb = adb
        self.demo = demo; self.instance = instance; self.workers = []
        self.exiting = False; self.shutdown_done = threading.Event(); self.shutdown_thread = None
        self.hidden = False; self.closing_to_tray = False
        self.preferences = Preferences.load(self.folder / 'preferences.json')
        self.colors = theme.palette(self.preferences.theme, theme.system_dark())
        COLORS = self.colors
        self.messages = queue.Queue(); self.closed = threading.Event(); self.busy = False
        self.connections = connections if connections is not None else ConnectionController()
        self.connection_busy = False
        self.snapshot = None; self.serial = None; self.drafts = None; self.selected = None; self.loading = False
        self.reverse_owned = set(); self.last_connected = None
        self.cover_drafts = None; self.cover_results = []; self.cover_cancel = threading.Event()
        self.cover_pictures = {}
        self.cards = {}; self.photo_cache = {}; self.photo_requested = set(); self.record_photo_keys = {}
        self.photo_jobs = queue.Queue()
        self.start_worker(self.cover_worker)
        self.cover_excluded = {}
        root.title("Manhwa Takip — Bilgisayar"); root.geometry("1280x860"); root.minsize(1000, 680)
        theme.apply(root, self.colors)
        self.status = tk.StringVar(value="USB telefon bekleniyor. Program açıkken veriler otomatik alınır.")
        status_label = ttk.Label(root, textvariable=self.status, style="Shell.TLabel", wraplength=1000, padding=(20, 12))
        status_label.pack(side="bottom", fill="x")
        shell = ttk.Frame(root, style="Shell.TFrame"); shell.pack(fill="both", expand=True)
        sidebar = ttk.Frame(shell, style="Shell.TFrame", width=190, padding=(12, 24)); sidebar.pack(side="left", fill="y")
        sidebar.pack_propagate(False)
        ttk.Label(sidebar, text="MT", style="Brand.TLabel").pack(anchor="w", padx=16)
        ttk.Label(sidebar, text="Manhwa Takip", style="Shell.TLabel", font=("Segoe UI", 11, "bold")).pack(anchor="w", padx=16, pady=(4, 24))
        ttk.Label(sidebar, text="Bilgisayar uygulaması\nUSB ile eşitleme", style="Shell.TLabel").pack(side="bottom", anchor="w", padx=16, pady=8)
        workspace = ttk.Frame(shell, style="Shell.TFrame", padding=(8, 20, 20, 0)); workspace.pack(side="left", fill="both", expand=True)
        header = ttk.Frame(workspace, style="Shell.TFrame"); header.pack(fill="x", pady=(0, 16))
        self.page_title = tk.StringVar(value="Okuma alanım")
        ttk.Label(header, textvariable=self.page_title, style="Title.TLabel").pack(side="left")
        actions = ttk.Frame(header, style="Shell.TFrame"); actions.pack(side="right")
        self.buttons = []
        def button(parent, title, callback, primary=False):
            item = ttk.Button(parent, text=title, command=callback, style="Accent.TButton" if primary else "TButton")
            item.pack(side="left", padx=4); self.buttons.append(item)
        button(actions, "Telefondan yenile", self.refresh)
        button(actions, "Telefona aktar", self.transfer, primary=True)
        tabs = ttk.Notebook(workspace, style="Workspace.TNotebook"); tabs.pack(fill="both", expand=True)
        self.tabs = tabs
        library = ttk.Frame(tabs); tabs.add(library, text="Okuma alanım")
        backup = ttk.Frame(tabs, padding=20); tabs.add(backup, text="Yedekler")
        ai_page = ttk.Frame(tabs); tabs.add(ai_page, text="AI paylaşımı")
        ai_canvas = tk.Canvas(ai_page, highlightthickness=0, background=COLORS['surface'])
        ai_scroll = ttk.Scrollbar(ai_page, orient='vertical', command=ai_canvas.yview); ai_scroll.pack(side='right', fill='y')
        ai_canvas.configure(yscrollcommand=ai_scroll.set); ai_canvas.pack(fill='both', expand=True)
        ai = ttk.Frame(ai_canvas, padding=20); ai_item = ai_canvas.create_window((0, 0), window=ai, anchor='nw')
        ai.bind('<Configure>', lambda _: ai_canvas.configure(scrollregion=ai_canvas.bbox('all')))
        ai_canvas.bind('<Configure>', lambda event: ai_canvas.itemconfigure(ai_item, width=event.width))
        self.settings = ttk.Frame(tabs, padding=20); tabs.add(self.settings, text="Ayarlar")
        self.connections_page = ttk.Frame(tabs, padding=20); tabs.add(self.connections_page, text="Bağlantılar")
        ttk.Label(self.connections_page, text="USB ve MCP bağlantıları", style="Section.TLabel").pack(anchor="w")
        ttk.Label(self.connections_page, text="Paylaşımı telefonda kendin aç. AI/MCP değişiklikleri telefon onayında kalır.\nYeniden başlatma ortak MCP belleğini temizler; diğer istemcilerin bağlantısı kesilebilir.",
                  wraplength=650, style="Muted.TLabel").pack(anchor="w", pady=12)
        self.connection_status = tk.StringVar(value="Durum için Yenile düğmesine bas.")
        ttk.Label(self.connections_page, textvariable=self.connection_status, wraplength=650).pack(anchor="w", pady=12)
        self.connection_buttons = []
        for title, action in (("Durumu yenile", "status"), ("İlk eşleştirmeyi hazırla", "prepare_pairing"), ("USB başlat", "start_usb"), ("MCP başlat", "start_mcp"), ("MCP yeniden başlat", "restart_mcp")):
            item = ttk.Button(self.connections_page, text=title, command=lambda a=action: self.connection_action(a))
            item.pack(anchor="w", pady=4); self.connection_buttons.append(item)
        ttk.Button(self.connections_page, text="MCP istemci kurulumu…", command=self.open_connection_setup).pack(anchor="w", pady=12)
        ttk.Button(self.connections_page, text="Mevcut eşleştirme anahtarı…", command=lambda: self.open_connection_setup(key=True)).pack(anchor="w", pady=4)
        self.navigation = tk.IntVar(value=0); self.nav_buttons = []
        for index, title in enumerate(("Okuma alanım", "Yedekler", "AI paylaşımı", "Ayarlar", "Bağlantılar")):
            item = ttk.Radiobutton(sidebar, text=title, variable=self.navigation, value=index,
                                   command=lambda i=index: tabs.select(i), style="Nav.TRadiobutton")
            item.pack(fill="x", pady=3); self.nav_buttons.append(item)
        tabs.bind("<<NotebookTabChanged>>", self.update_navigation)
        self.update_navigation()
        root.bind("<Configure>", lambda event: status_label.configure(wraplength=max(500, event.width - 40)) if event.widget == root else None)
        panes = ttk.Panedwindow(library, orient="horizontal"); panes.pack(fill="both", expand=True); self.panes = panes
        left = ttk.Frame(panes, padding=16); right = ttk.Frame(panes, padding=16, width=350); panes.add(left, weight=3)
        self.editor = right
        self.search = tk.StringVar(); self.filter = tk.StringVar(value="Hepsi"); self.category = tk.StringVar(value="Tüm kategoriler")
        ttk.Label(left, text="Kaldığın yerden devam et", style="Section.TLabel").pack(anchor="w", pady=(0, 4))
        self.reading_info = tk.StringVar(value="Kapaklar, okuma durumun ve son bölümün tek bakışta.")
        ttk.Label(left, textvariable=self.reading_info, style="Muted.TLabel").pack(anchor="w", pady=(0, 16))
        ttk.Label(left, text="Seri veya alternatif ad ara", style="Muted.TLabel").pack(anchor="w", pady=(0, 4))
        self.search_entry = ttk.Entry(left, textvariable=self.search); self.search_entry.pack(fill="x", pady=(0, 12))
        filters = ttk.Frame(left); filters.pack(fill="x", pady=(0, 12)); filters.columnconfigure((0, 1), weight=1, uniform="filters")
        ttk.Label(filters, text="Okuma durumu", style="Muted.TLabel").grid(row=0, column=0, sticky="w", pady=(0, 4))
        ttk.Label(filters, text="Kategori", style="Muted.TLabel").grid(row=0, column=1, sticky="w", padx=(8, 0), pady=(0, 4))
        ttk.Combobox(filters, textvariable=self.filter, values=["Hepsi", *STATUSES.values()], state="readonly", width=12).grid(row=1, column=0, sticky="ew")
        self.categories = ttk.Combobox(filters, textvariable=self.category, state="readonly", values=["Tüm kategoriler"], width=12)
        self.categories.grid(row=1, column=1, sticky="ew", padx=(8, 0))
        self.list_info = tk.StringVar(value="Kütüphaneni görmek için USB telefonu bağla.")
        ttk.Label(left, textvariable=self.list_info, style="Muted.TLabel", wraplength=380).pack(anchor="w", pady=(0, 10))
        for var in (self.search, self.filter, self.category): var.trace_add("write", lambda *_: self.draw_list())
        listing = ttk.Frame(left); listing.pack(fill="both", expand=True)
        # Keep the existing selection/draft model; the visible surface is a card grid.
        self.tree = ttk.Treeview(listing, columns=("status", "chapter", "draft"), selectmode="browse")
        self.tree.bind("<<TreeviewSelect>>", self.select)
        self.card_canvas = tk.Canvas(listing, background=COLORS["surface"], highlightthickness=0)
        list_scroll = ttk.Scrollbar(listing, orient="vertical", command=self.card_canvas.yview); list_scroll.pack(side="right", fill="y")
        self.card_canvas.configure(yscrollcommand=list_scroll.set); self.card_canvas.pack(fill="both", expand=True)
        self.card_grid = ttk.Frame(self.card_canvas)
        self.card_container = self.card_canvas.create_window((0, 0), window=self.card_grid, anchor="nw")
        self.card_grid.bind("<Configure>", lambda _: self.card_canvas.configure(scrollregion=self.card_canvas.bbox("all")))
        self.card_canvas.bind("<Configure>", self.layout_cards)
        self.card_canvas.bind("<MouseWheel>", lambda event: self.card_canvas.yview_scroll(-int(event.delta / 120), "units"))
        self.card_empty = ttk.Label(self.card_grid, text="USB telefonu bağla. Serilerin ve kapakların burada görünecek.", style="Muted.TLabel", padding=24, wraplength=450)
        self.card_empty.grid(row=0, column=0, sticky="w")
        editor_header = ttk.Frame(right); editor_header.pack(fill="x", pady=(0, 6))
        ttk.Label(editor_header, text="Seri ayrıntıları", style="Section.TLabel").pack(side="left")
        self.editor_close = ttk.Button(editor_header, text="Kapat", command=self.close_editor); self.editor_close.pack(side="right")
        ttk.Label(right, text="Değişiklikler taslak olarak saklanır.", style="Muted.TLabel").pack(anchor="w", pady=(0, 12))
        canvas = tk.Canvas(right, highlightthickness=0, background=COLORS["surface"]); scroll = ttk.Scrollbar(right, orient="vertical", command=canvas.yview)
        canvas.configure(yscrollcommand=scroll.set); scroll.pack(side="right", fill="y"); canvas.pack(side="left", fill="both", expand=True)
        form = ttk.Frame(canvas); container = canvas.create_window((0, 0), window=form, anchor="nw")
        self.cover_preview = ttk.Label(form, text="Kapak yok"); self.cover_preview.pack(anchor="w", pady=6)
        self.single_cover = ttk.Button(form, text="Kapak ara", command=self.find_selected_cover)
        self.single_cover.pack(fill="x", pady=4); self.buttons.append(self.single_cover)
        self.other_cover = ttk.Button(form, text="Farklı kaynaktan bul", command=lambda: self.find_selected_cover(True))
        self.other_cover.pack(fill="x", pady=4); self.buttons.append(self.other_cover)
        self.file_cover = ttk.Button(form, text="Dosyadan kapak seç", command=self.load_manual_cover)
        self.file_cover.pack(fill="x", pady=4); self.buttons.append(self.file_cover)
        self.paste_cover = ttk.Button(form, text="Panodan kapak yapıştır", command=lambda: self.load_manual_cover(True))
        self.paste_cover.pack(fill="x", pady=4); self.buttons.append(self.paste_cover)
        ttk.Label(form, text="Elle seçilen kapak USB bağlıysa otomatik aktarılır; bağlı değilse bu telefon için bekler.", wraplength=300, style="Muted.TLabel").pack(fill="x", pady=4)
        form.bind("<Configure>", lambda _: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>", lambda event: canvas.itemconfigure(container, width=event.width))
        self.vars = {}; self.texts = {}
        labels = [("title", "Seri adı"), ("alternativeTitles", "Alternatif adlar — her satır bir ad"), ("url", "Okuma bağlantısı"), ("lastChapter", "Son okunan bölüm"),
                  ("status", "Okuma durumu"), ("opinion", "Beğeni"), ("likedReason", "Sevme nedeni"), ("dislikedReason", "Sevmeme nedeni"),
                  ("dropReason", "Bırakma nedeni"), ("notes", "Notlar"), ("categories", "Kategoriler — her satır bir kategori")]
        for key, label in labels:
            ttk.Label(form, text=label).pack(anchor="w", pady=(8, 2))
            if key in ("notes", "alternativeTitles", "categories"):
                item = tk.Text(form, height=3, wrap="word", undo=True, padx=8, pady=6); item.pack(fill="x"); self.texts[key] = item
                item.bind("<FocusOut>", lambda _: self.stash())
            else:
                var = tk.StringVar(); self.vars[key] = var
                if key in ("status", "opinion"):
                    ttk.Combobox(form, textvariable=var, values=list((STATUSES if key == "status" else OPINIONS).values()), state="readonly").pack(fill="x")
                else: ttk.Entry(form, textvariable=var).pack(fill="x")
        for key, label in (("favorite", "Favori"), ("pinned", "Sabitle")):
            self.vars[key] = tk.BooleanVar(); ttk.Checkbutton(form, text=label, variable=self.vars[key]).pack(anchor="w", pady=6)
        ttk.Button(form, text="Taslağı kaydet", command=self.stash, style="Accent.TButton").pack(fill="x", pady=10)
        ttk.Button(form, text="Taslağı güncel telefon kaydına taşı", command=self.rebase).pack(fill="x", pady=4)
        ttk.Button(form, text="Bu taslağı kaldır", command=self.discard).pack(fill="x", pady=4)
        ttk.Label(form, text="Taslak bilgisayarda saklanır. Telefona aktar düğmesiyle uygulanır.", wraplength=300, style="Muted.TLabel").pack(fill="x", pady=(8, 16))
        for var in self.vars.values(): var.trace_add("write", lambda *_: self.schedule_stash())
        self.stash_timer = None
        ttk.Label(backup, text="Anahtarsız tam yedek", font=("Segoe UI", 18, "bold")).pack(anchor="w")
        ttk.Label(backup, text="Seriler, kategoriler, geçmiş, kapaklar, sohbetler ve genel ayarlar. API/eşleştirme anahtarları içermez.\nHer bağlantıda tarihli yedek otomatik kaydedilir. Dosyalar parolasızdır.", wraplength=650, style="Muted.TLabel").pack(anchor="w", pady=12)
        row = ttk.Frame(backup); row.pack(anchor="w")
        button(row, "JSON kaydet", lambda: self.export_backup(False)); button(row, "ZIP kaydet", lambda: self.export_backup(True)); button(row, "Yedeği telefona geri yükle", self.restore)
        ttk.Button(backup, text="Yedek klasörünü aç", command=lambda: os.startfile(str(self.device_folder() / "backups")) if self.serial else None).pack(anchor="w", pady=18)
        ttk.Label(backup, text="Geri yükleme mevcut verileri korur, eksikleri ekler. Kayıt değişiklikleri için Kütüphane sayfasını kullan.", wraplength=650, style="Muted.TLabel").pack(anchor="w")
        ttk.Label(ai, text="Okuma profilini paylaş", font=("Segoe UI", 18, "bold")).pack(anchor="w")
        ttk.Label(ai, text="Varsayılan yalnızca seri adı. Diğer alanları açıkça seç. Sohbet ve anahtarlar paylaşılmaz.\nSon doğrulanmış telefon verileri kullanılır; gönderilmemiş taslaklar dahil değildir.", wraplength=650, style="Muted.TLabel").pack(anchor="w", pady=8)
        fields = ttk.Frame(ai); fields.pack(fill='x')
        self.ai_name_var = tk.BooleanVar(value=True)
        self.ai_name = ttk.Checkbutton(fields, text='Seri adı (zorunlu)', variable=self.ai_name_var, state='disabled')
        self.ai_name.grid(row=0, column=0, sticky='w')
        self.ai_fields = {}
        for index, (key, label) in enumerate((('STATUS', 'Durum'), ('CHAPTER', 'Son bölüm'), ('OPINION', 'Beğeni'), ('CATEGORIES', 'Kategoriler'), ('NOTES', 'Notlar'), ('REASONS', 'Nedenler'), ('URL', 'Bağlantı'), ('HISTORY', 'Geçmiş'))):
            var = tk.BooleanVar(value=False); self.ai_fields[key] = var
            ttk.Checkbutton(fields, text=label, variable=var).grid(row=1 + index // 4, column=index % 4, sticky='w', padx=(0, 12))
        self.ai_status = tk.StringVar(value="Hepsi"); self.ai_mode = tk.StringVar(value="Okuma profili")
        ttk.Label(ai, text="Paylaşılacak okuma durumu", style="Muted.TLabel").pack(anchor="w", pady=(12, 0))
        ttk.Combobox(ai, textvariable=self.ai_status, values=["Hepsi", *STATUSES.values()], state="readonly").pack(anchor="w", pady=6)
        ttk.Label(ai, text="Paylaşım amacı", style="Muted.TLabel").pack(anchor="w", pady=(8, 0))
        ttk.Combobox(ai, textvariable=self.ai_mode, values=["Okuma profili", "Yeni öneri", "Okunan bölüme kadar özet"], state="readonly", width=35).pack(anchor="w", pady=6)
        self.ai_manual = tk.BooleanVar(value=False)
        ttk.Checkbutton(ai, text='Yalnızca elle seçilen seriler (Ctrl / Shift)', variable=self.ai_manual).pack(anchor='w')
        self.ai_series = ttk.Treeview(ai, show='tree', selectmode='extended', height=3)
        self.ai_series.pack(fill='x', pady=4)
        ttk.Button(ai, text='Önizle', command=self.preview_ai).pack(anchor='w')
        self.ai_preview = tk.Text(ai, height=5, wrap='word', state='disabled')
        self.ai_preview.pack(fill='x', pady=4)
        self.ai_cache_key = None; self.ai_cache = None
        for var in [*self.ai_fields.values(), self.ai_manual, self.ai_status, self.ai_mode]:
            var.trace_add('write', lambda *_: self.clear_ai_preview())
        self.ai_series.bind('<<TreeviewSelect>>', lambda _: self.clear_ai_preview()
                            if self.ai_manual.get() and self.ai_cache_key is not None and self.ai_cache_key[4] != frozenset(self.ai_series.selection()) else None)
        row = ttk.Frame(ai); row.pack(anchor="w", pady=12)
        button(row, "AI JSON kaydet", lambda: self.export_ai(False)); button(row, "AI ZIP kaydet", lambda: self.export_ai(True))
        ttk.Button(ai, text="AI JSON'u panoya kopyala", command=self.copy_ai).pack(anchor="w")
        imports = ttk.Frame(ai); imports.pack(anchor='w', pady=6)
        button(imports, "AI JSON dosyasını içe aktar", self.import_ai_file)
        button(imports, "AI JSON'u panodan al", self.import_ai_clipboard)
        ttk.Label(ai, text="Tek seri için kapak ara", style="Section.TLabel").pack(anchor="w", pady=(24, 8))
        self.ai_cover_series = ttk.Combobox(ai, state="readonly", width=45)
        self.ai_cover_series.pack(anchor="w", pady=4)
        cover_actions = ttk.Frame(ai); cover_actions.pack(anchor="w", pady=8)
        button(cover_actions, "Kapak ara", self.find_ai_cover)
        appearance = ttk.Frame(self.settings); appearance.pack(fill='x', pady=(0, 12))
        ttk.Label(appearance, text='Görünüm', style='Section.TLabel').pack(side='left', padx=(0, 16))
        self.theme_var = tk.StringVar(value=self.preferences.theme)
        self.theme_choice = ttk.Combobox(appearance, state='readonly', values=('system', 'light', 'dark'),
                                         textvariable=self.theme_var, width=12)
        self.theme_choice.pack(side='left')
        self.theme_choice.bind('<<ComboboxSelected>>', lambda _: self.change_theme())
        ttk.Label(appearance, text='system: Sistem · light: Açık · dark: Karanlık', style='Muted.TLabel').pack(side='left', padx=12)
        lifecycle = ttk.Frame(self.settings); lifecycle.pack(fill='x', pady=(0, 10))
        self.close_to_tray_var = tk.BooleanVar(value=self.preferences.close_to_tray)
        self.startup_var = tk.BooleanVar(value=self.preferences.start_with_windows)
        ttk.Checkbutton(lifecycle, text='Kapatınca tepside çalışmayı sürdür', variable=self.close_to_tray_var,
                        command=self.change_close_to_tray).pack(anchor='w')
        self.startup_toggle = ttk.Checkbutton(lifecycle, text='Windows açılışında başlat (yalnız bu kullanıcı)', variable=self.startup_var,
                                              command=self.change_startup)
        self.startup_toggle.pack(anchor='w')
        ttk.Button(lifecycle, text='Tamamen çık', command=self.exit_application).pack(anchor='w')
        ttk.Label(lifecycle, text='Tepside USB izleme sürer. Windows başlangıcı ayrı ve isteğe bağlıdır.',
                  style='Muted.TLabel').pack(anchor='w')
        ttk.Label(self.settings, text="Eksik kapakları bul", font=("Segoe UI", 18, "bold")).pack(anchor="w")
        ttk.Label(self.settings, text="Tek tıkla kapaksız seriler aranır. Önce kayıtlı kaynak, sonra MangaDex kullanılır. Mevcut kapaklar korunur.\nSeri adları ve kaynak bağlantıları arama için kullanılır; notlar ve sohbetler gönderilmez. Son yedekle telefon bağlı olmadan da çalışır.", wraplength=650, style="Muted.TLabel").pack(anchor="w", pady=10)
        self.cover_ai = tk.BooleanVar(value=False)
        prefs = self.folder / "cover-settings.json"
        try:
            if prefs.exists(): self.cover_ai.set(json.loads(prefs.read_text(encoding="utf-8")).get("ai", False) is True)
        except (OSError, ValueError): pass
        ttk.Checkbutton(self.settings, text="Mevcut yerel AI ile ad eşleştirmesine yardım et", variable=self.cover_ai).pack(anchor="w", pady=4)
        ttk.Label(self.settings, text="AI yardımı seri adı ve alternatif adları paylaşır. AI anahtarı telefondan alınmaz. Yerel servis anahtar isterse kaynak araması AI yardımı olmadan devam eder.", wraplength=650, style="Muted.TLabel").pack(anchor="w", pady=4)
        row = ttk.Frame(self.settings); row.pack(anchor="w", pady=12)
        button(row, "Eksik kapakları bul", self.find_covers)
        self.cover_accept = ttk.Button(row, text="Seçilen kapakları onayla", command=self.accept_covers); self.cover_accept.pack(side="left", padx=4)
        ttk.Button(row, text="Aramayı durdur", command=self.cover_cancel.set).pack(side="left", padx=4)
        self.cover_other = ttk.Button(self.settings, text="Seçili seri için farklı kaynaktan bul", command=self.find_other_result)
        self.cover_other.pack(anchor="w", pady=4); self.buttons.append(self.cover_other)
        self.cover_send = ttk.Button(self.settings, text="Kapakları telefona aktar", command=self.transfer_covers, style="Accent.TButton")
        self.cover_send.pack(anchor="w", pady=(0, 8))
        self.cover_info = tk.StringVar(value="Önce telefon bağlantısından kütüphane alınmalı.")
        ttk.Label(self.settings, textvariable=self.cover_info, wraplength=650, style="Muted.TLabel").pack(anchor="w", pady=6)
        self.cover_tree = ttk.Treeview(self.settings, columns=("match",), show="tree headings", selectmode="extended", height=9)
        self.cover_tree.heading("#0", text="Seri — Ctrl / Shift ile çoklu seçim"); self.cover_tree.heading("match", text="Kaynak / sonuç")
        self.cover_tree.column("#0", width=500); self.cover_tree.column("match", width=300); self.cover_tree.pack(fill="both", expand=True)
        self.cover_tree.bind("<<TreeviewSelect>>", self.show_cover_result)
        self.result_preview = ttk.Label(self.settings, text="Kapak önizlemesi için sonuç seç."); self.result_preview.pack(anchor="w", pady=6)
        theme.widgets(root, self.colors)
        self.startup = startup if startup is not None else (None if demo else WindowsStartup())
        self.tray = tray if tray is not None else (None if demo else TrayController(
            self.messages.put, self.restore_window, self.show_connection_status, self.exit_application))
        root.bind_all('<Map>', self.theme_mapped, add='+')
        root.after(1500, self.poll_system_theme)
        root.protocol("WM_DELETE_WINDOW", self.close); root.after(100, self.drain)
        marker = self.folder / "last-device.json"
        if marker.exists():
            try:
                serial = json.loads(marker.read_text(encoding="utf-8"))["serial"]
                snapshot = read_backup((self.device_folder(serial) / "latest.json").read_bytes())
                self.loaded(serial, snapshot, Drafts(self.device_folder(serial) / "drafts.json"), "Bilgisayardaki son yedek açıldı. Güncel veriler için USB telefonu bağla.", "read")
            except (ValueError, OSError, KeyError, TypeError): self.status.set("Son bilgisayar yedeği açılamadı; dosyalar korunuyor. USB telefonu bağla.")
        if not demo:
            if self.exchange is not None and self.adb is not None:
                self.start_worker(self.monitor)
            else:
                self.status.set('Çevrimdışı kurulum — USB izleme kapalı. Kurulum adımları için Bağlantılar ekranını aç.')
                self.connection_status.set(SETUP_GUIDANCE)

    def start_worker(self, target):
        if self.closed.is_set(): return
        worker = threading.Thread(target=target, daemon=True)
        self.workers.append(worker); worker.start()

    def change_close_to_tray(self):
        previous = self.preferences.close_to_tray
        self.preferences.close_to_tray = self.close_to_tray_var.get()
        try: self.preferences.save()
        except (OSError, ValueError):
            self.preferences.close_to_tray = previous; self.close_to_tray_var.set(previous)
            self.status.set('Kapatma ayarı kaydedilemedi; önceki ayar korunuyor.')

    def change_startup(self):
        previous = self.preferences.start_with_windows
        old_value = None; captured = False; attempted = False
        self.startup_toggle.state(['!alternate'])
        try:
            if self.startup is None: raise OSError('Başlangıç bu test oturumunda devre dışı.')
            old_value = self.startup.value(); captured = True
            attempted = True  # Registry calls may mutate successfully and then raise.
            self.startup.set_enabled(self.startup_var.get())
            self.preferences.start_with_windows = self.startup_var.get(); self.preferences.save()
            self.status.set('Windows başlangıç ayarı kaydedildi.')
        except (OSError, ValueError):
            self.preferences.start_with_windows = previous; self.startup_var.set(previous)
            unknown = object(); observed = unknown
            if self.startup is not None:
                try: observed = self.startup.value()
                except (OSError, ValueError): pass
                if attempted and captured and observed is not unknown and observed != old_value:
                    try: self.startup.restore(old_value)  # Adapter refuses observed foreign ownership.
                    except (OSError, ValueError): pass
                    # A rollback can also mutate and then raise; only a read verifies the outcome.
                    try: observed = self.startup.value()
                    except (OSError, ValueError): observed = unknown
            if observed is unknown or (observed is not None and observed != self.startup.command):
                self.startup_toggle.state(['alternate'])
                self.status.set('Windows başlangıç durumu doğrulanamadı veya kayıt başka bir komuta ait. '
                                'Onay kutusu belirsizdir; kayıtlı tercih gerçek durumu garanti etmez. Tekrar dene veya Windows başlangıç ayarını kontrol et.')
                return
            actual = observed is not None
            self.preferences.start_with_windows = actual; self.startup_var.set(actual)
            disk_warning = ''
            if actual != previous:
                try: self.preferences.save()
                except (OSError, ValueError): disk_warning = ' Tercih dosyası güncellenemedi; kayıtlı tercih farklı olabilir.'
            restored = captured and observed == old_value
            self.status.set(('Başlangıç işlemi başarısız; ' if restored or not attempted else 'Başlangıç değişikliği geri alınamadı; ')
                            + 'doğrulanan Windows durumu: ' + ('açık.' if actual else 'kapalı.') + disk_warning)

    def restore_window(self):
        if self.closed.is_set() or self.exiting: return
        self.hidden = False
        self.root.deiconify(); self.root.lift(); self.root.focus_force()

    def show_connection_status(self):
        if self.closed.is_set(): return
        self.restore_window(); self.tabs.select(self.connections_page); self.connection_action('status')

    def check_tray(self):
        if self.hidden and (self.tray is None or not self.tray.available):
            self.restore_window()
            self.status.set('Sistem tepsisi kullanılamıyor; pencere güvenli biçimde açık tutuldu. Ayarlar → Tamamen çık ile kapatabilirsin.')

    def change_theme(self):
        previous = self.preferences.theme
        self.preferences.theme = self.theme_var.get()
        try:
            self.preferences.save()
        except (OSError, ValueError):
            self.preferences.theme = previous; self.theme_var.set(previous)
            self.status.set('Görünüm ayarı kaydedilemedi; önceki ayar korunuyor.')
            return
        self.refresh_system_theme()

    def refresh_system_theme(self):
        colors = theme.palette(self.preferences.theme, theme.system_dark())
        if colors != self.colors:
            self.colors = colors
            theme.apply(self.root, colors)

    def poll_system_theme(self):
        if self.closed.is_set(): return
        if self.preferences.theme == 'system': self.refresh_system_theme()
        self.root.after(1500, self.poll_system_theme)

    def theme_mapped(self, event):
        if isinstance(event.widget, (tk.Tk, tk.Toplevel, tk.Menu)):
            theme.widgets(event.widget, self.colors)

    def update_navigation(self, event=None):
        current = self.tabs.index(self.tabs.select())
        self.navigation.set(current); self.page_title.set(self.tabs.tab(current, "text"))
        for index, item in enumerate(self.nav_buttons):
            item.state(["disabled" if self.tabs.tab(index, "state") == "disabled" else "!disabled"])

    def connection_action(self, action):
        if self.connection_busy or self.closed.is_set(): return
        if action != 'prepare_pairing' and not self.demo and (self.exchange is None or self.adb is None):
            self.connection_status.set(SETUP_GUIDANCE); return
        if action == 'prepare_pairing' and not messagebox.askyesno('İlk eşleştirmeyi hazırla',
                'Bu bilgisayarda yerel eşleştirme anahtarı yoksa oluşturulsun mu? '
                'Mevcut anahtar değiştirilmez. Anahtar gösterilmez veya panoya kopyalanmaz. '
                'Telefon paylaşımı ve MCP başlatılmaz. Sonrasında uygulamadan Tamamen çıkıp yeniden açman gerekir.', parent=self.root): return
        if action == 'restart_mcp' and not messagebox.askyesno('MCP yeniden başlat',
                'Ortak köprü belleği temizlenir ve diğer MCP istemcileri geçici olarak kesilir. Devam edilsin mi?', parent=self.root): return
        self.connection_busy = True
        self.connection_status.set('Bağlantı işlemi sürüyor…')
        for item in self.connection_buttons: item.state(['disabled'])
        def worker():
            try:
                result = getattr(self.connections, action)()
                text = '\n'.join(f'{key}: {value}' for key, value in result.items()) if action == 'status' else result
            except Exception:
                text = 'Bağlantı işlemi tamamlanamadı; işlem kısmen uygulanmış olabilir; durumu yenile.'
            if not self.closed.is_set(): self.messages.put(lambda t=text: self.connection_finished(t))
        self.start_worker(worker)

    def connection_finished(self, text):
        self.connection_busy = False; self.connection_status.set(text)
        for item in self.connection_buttons: item.state(['!disabled'])

    def open_connection_setup(self, key=False):
        from connections import _bridge
        try:
            subprocess.Popen([sys.executable, *(['-I'] if sys.flags.isolated else []), str(_bridge / 'user_setup.py'), *(['--key'] if key else [])],
                             creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        except OSError:
            self.connection_status.set('MCP kurulum penceresi açılamadı; mevcut ayarlar değiştirilmedi.')

    def device_folder(self, serial=None):
        return self.folder / "devices" / hashlib.sha256((serial or self.serial).encode()).hexdigest()[:16]

    def drain(self):
        if self.closed.is_set(): return
        if self.instance is not None and self.instance.poll(): self.messages.put(self.restore_window)
        self.check_tray()
        try:
            while not self.closed.is_set() and not self.messages.empty():
                callback = self.messages.get()
                try: callback()
                except (OSError, ValueError, KeyError, TypeError): self.failed("Sonuç kaydedilemedi. Taslaklar korunuyor; disk alanını kontrol edip yeniden yenile.")
        finally:
            if not self.closed.is_set(): self.root.after(100, self.drain)

    def schedule_stash(self):
        if self.loading or self.closed.is_set() or self.exiting: return
        if self.stash_timer: self.root.after_cancel(self.stash_timer)
        self.stash_timer = self.root.after(500, self.stash)

    def stash(self):
        if hasattr(self, 'closed') and self.closed.is_set(): return True
        self.stash_timer = None
        if self.loading or not self.selected or not self.drafts: return True
        original = next((x for x in self.snapshot["library"]["records"] if x["id"] == self.selected), None)
        if not original: return True
        fields = {key: var.get() for key, var in self.vars.items()}
        for key, widget in self.texts.items():
            value = widget.get("1.0", "end-1c")
            fields[key] = value if key == "notes" else [unicodedata.normalize("NFC", x.strip()) if key == "categories" else x.strip() for x in value.splitlines() if x.strip()]
        for key, choices in (("status", STATUSES), ("opinion", OPINIONS)):
            fields[key] = next((name for name, label in choices.items() if label == fields[key]), original.get(key, next(iter(choices))))
        fields["title"] = fields["title"].strip(); fields["url"] = fields["url"].strip()
        chapter = fields["lastChapter"].strip().replace(",", ".")
        if re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", chapter): chapter = format(Decimal(chapter), "f").rstrip("0").rstrip(".") if "." in chapter else str(int(chapter))
        fields["lastChapter"] = chapter
        try:
            self.drafts.save(original, fields)
            if self.tree.exists(self.selected): self.tree.set(self.selected, "draft", "Var" if self.selected in self.drafts.entries else "")
            card = self.cards.get(self.selected)
            if card:
                card['title'].configure(text=fields['title'])
                card['chapter'].configure(text=f"Bölüm {chapter}")
                card['status'].configure(text=STATUSES.get(fields['status'], ''))
                card['draft'].configure(text="Gönderilmemiş taslak" if self.selected in self.drafts.entries else "")
            return True
        except (ValueError, OSError):
            self.status.set("Taslak kaydedilemedi. Düzenleyicideki metin korunuyor; disk alanını kontrol et.")
            return False

    def draw_list(self):
        if not self.snapshot: return
        self.loading = True
        self.tree.delete(*self.tree.get_children())
        for card in self.cards.values(): card['frame'].destroy()
        self.cards.clear(); self.card_empty.grid_remove(); self.record_photo_keys.clear()
        count = 0
        for row in self.snapshot["library"]["records"]:
            item = {**row, **row.get("local", {}), **self.drafts.entries.get(row["id"], {}).get("fields", {})}
            if fold(self.search.get()) not in fold(" ".join([item["title"], *item.get("alternativeTitles", [])])): continue
            if self.filter.get() != "Hepsi" and STATUSES.get(item["status"]) != self.filter.get(): continue
            if self.category.get() != "Tüm kategoriler" and self.category.get() not in item.get("categories", []): continue
            tags = ("alternate",) if count % 2 else ()
            self.tree.insert("", "end", iid=row["id"], text=item["title"], tags=tags, values=(STATUSES.get(item["status"]), item.get("lastChapter", ""), "Var" if row["id"] in self.drafts.entries else ""))
            self.add_card(row, item)
            count += 1
            self.request_record_photo(row)
        total = len(self.snapshot["library"]["records"])
        self.list_info.set(f"{count} / {total} seri" if count else "Bu arama ve filtrelerde sonuç yok." if total else "Kütüphanede henüz seri yok.")
        reading = sum(x['status'] == 'READING' for x in self.snapshot['library']['records'])
        self.reading_info.set(f"{reading} seri devam ediyor  ·  {total} seri takipte  ·  Kapaklar otomatik gösterilir")
        if not count:
            self.card_empty.configure(text=self.list_info.get()); self.card_empty.grid(row=0, column=0, sticky="w")
        self.layout_cards()
        self.loading = False

    def add_card(self, row, item):
        identity = row['id']
        frame = ttk.Frame(self.card_grid, style="Card.TFrame", padding=12)
        cover = ttk.Label(frame, text="Kapak yok", anchor="center", width=15, style="Muted.TLabel")
        cover.grid(row=0, column=0, rowspan=5, sticky="ns", padx=(0, 12))
        frame.columnconfigure(1, weight=1)
        title = ttk.Label(frame, text=item['title'], style="CardTitle.TLabel", wraplength=180)
        title.grid(row=0, column=1, sticky="nw", pady=(0, 6))
        status = ttk.Label(frame, text=STATUSES.get(item['status'], ''), style="Muted.TLabel")
        status.grid(row=1, column=1, sticky="w")
        chapter = ttk.Label(frame, text=f"Bölüm {item.get('lastChapter', '')}", style="Chapter.TLabel")
        chapter.grid(row=2, column=1, sticky="w", pady=8)
        draft = ttk.Label(frame, text="Gönderilmemiş taslak" if identity in self.drafts.entries else "", style="Muted.TLabel")
        draft.grid(row=3, column=1, sticky="w")
        edit = ttk.Button(frame, text="Ayrıntılar / düzenle", command=lambda: self.open_editor(identity))
        edit.grid(row=4, column=1, sticky="ew", pady=(8, 0))
        edit.bind('<FocusIn>', lambda _: self.card_canvas.yview_moveto(max(0, frame.winfo_y() - 12) / max(1, self.card_grid.winfo_height())))
        for widget in (frame, cover, title, status, chapter, draft, edit):
            widget.bind('<MouseWheel>', lambda event: self.card_canvas.yview_scroll(-int(event.delta / 120), 'units'))
        self.cards[identity] = dict(frame=frame, cover=cover, title=title, status=status, chapter=chapter, draft=draft, edit=edit)

    def layout_cards(self, event=None):
        width = event.width if event else max(1, self.card_canvas.winfo_width())
        self.card_canvas.itemconfigure(self.card_container, width=width)
        columns = max(1, width // 360)
        for column in range(8): self.card_grid.columnconfigure(column, weight=0, uniform='')
        for column in range(columns): self.card_grid.columnconfigure(column, weight=1, uniform='cards')
        for index, card in enumerate(self.cards.values()):
            card['frame'].grid(row=index // columns, column=index % columns, sticky='nsew', padx=6, pady=6)
            card['title'].configure(wraplength=max(120, width // columns - 170))

    def open_editor(self, identity):
        self.tree.selection_set(identity); self.select()

    def close_editor(self):
        if self.stash() is False: return
        if str(self.editor) in self.panes.panes(): self.panes.forget(self.editor)

    def cover_worker(self):
        from covers import convert_image
        while not self.closed.is_set():
            try: key, image = self.photo_jobs.get(timeout=.2)
            except queue.Empty: continue
            try:
                png, _ = convert_image(base64.b64decode(image, validate=True))
                result = base64.b64encode(png).decode()
            except (OSError, ValueError, subprocess.SubprocessError): result = None
            if not self.closed.is_set(): self.messages.put(lambda k=key, p=result: self.photo_ready(k, p))

    def request_record_photo(self, row):
        identity = row['id']
        pending = self.cover_drafts.entries.get(identity) if self.cover_drafts else None
        image = pending.get('png') if pending else next((x['jpeg'] for x in self.snapshot.get('covers', []) if x['seriesId'] == identity), None)
        if not image: return
        key = (self.serial, identity, hashlib.sha256(image.encode()).hexdigest())
        self.record_photo_keys[identity] = key
        if key in self.photo_cache: self.apply_record_photo(identity, self.photo_cache[key]); return
        if key in self.photo_requested: return
        self.photo_requested.add(key)
        if identity in self.cards: self.cards[identity]['cover'].configure(text="Kapak hazırlanıyor…")
        if pending: self.photo_ready(key, image)
        else: self.photo_jobs.put((key, image))

    def photo_ready(self, key, png):
        self.photo_requested.discard(key)
        try: photo = tk.PhotoImage(master=self.root, data=png) if png else None
        except tk.TclError: photo = None
        if photo: self.photo_cache[key] = photo
        else: self.photo_cache.pop(key, None)
        # Bound memory, retaining currently displayed covers.
        if len(self.photo_cache) > 200:
            for old in list(self.photo_cache):
                if old not in self.record_photo_keys.values(): self.photo_cache.pop(old)
                if len(self.photo_cache) <= 200: break
        if self.record_photo_keys.get(key[1]) == key: self.apply_record_photo(key[1], photo)

    def apply_record_photo(self, identity, photo):
        thumb = photo.subsample(max(1, (photo.width() + 109) // 110, (photo.height() + 159) // 160)) if photo else None
        if self.tree.exists(identity):
            self.cover_pictures[identity] = thumb; self.tree.item(identity, image=thumb or '')
        if identity in self.cards:
            self.cards[identity]['cover'].configure(image=thumb or '', text='' if thumb else 'Kapak açılamadı')
        if self.selected == identity:
            self.cover_preview.image = photo
            self.cover_preview.configure(image=photo or '', text='' if photo else 'Kapak açılamadı')

    def select(self, event=None):
        if self.loading or not self.tree.selection(): return
        chosen = self.tree.selection()[0]
        if chosen == self.selected:
            if str(self.editor) not in self.panes.panes(): self.panes.add(self.editor, weight=1)
            return
        if self.stash() is False:
            if self.selected: self.tree.selection_set(self.selected)
            return
        self.selected = chosen
        if str(self.editor) not in self.panes.panes(): self.panes.add(self.editor, weight=1)
        self.bind_form()

    def bind_form(self):
        original = next((x for x in self.snapshot["library"]["records"] if x["id"] == self.selected), None)
        if not original: self.selected = None; return
        row = {**original, **original.get("local", {}), **self.drafts.entries.get(self.selected, {}).get("fields", {})}
        self.loading = True
        for key, var in self.vars.items():
            value = row.get(key, False if key in ("favorite", "pinned") else "")
            if key in ("status", "opinion"): value = (STATUSES if key == "status" else OPINIONS).get(value, "")
            var.set(value)
        for key, widget in self.texts.items():
            value = row.get(key, "" if key == "notes" else [])
            widget.delete("1.0", "end"); widget.insert("1.0", value if key == "notes" else "\n".join(value))
        self.loading = False
        self.show_record_cover(original)

    def show_record_cover(self, original):
        self.cover_preview.configure(image='', text='Kapak yok')
        self.cover_preview.image = None
        self.request_record_photo(original)
        if original['id'] in self.record_photo_keys and self.record_photo_keys[original['id']] not in self.photo_cache:
            self.cover_preview.configure(text='Kapak hazırlanıyor…')

    def set_record_photo(self, selected, png):
        if self.selected != selected: return
        try:
            photo = tk.PhotoImage(data=png) if png else None
            self.cover_preview.image = photo; self.cover_preview.configure(image=photo or "", text="" if photo else "Kapak önizlemesi açılamadı")
            if photo and self.tree.exists(selected):
                thumb = photo.subsample(6); self.cover_pictures[selected] = thumb; self.tree.item(selected, image=thumb)
        except tk.TclError: self.cover_preview.configure(image="", text="Kapak önizlemesi açılamadı")

    def monitor(self):
        while not self.closed.is_set():
            try:
                devices = usb_devices(run_adb(self.adb, ["devices", "-l"]))
                serial = devices[0] if len(devices) == 1 else None
                if not serial:
                    previously_connected = self.last_connected is not None
                    self.last_connected = None
                    if previously_connected: self.messages.put(self.connection_absent)
                elif serial != self.last_connected and not self.busy:
                    self.last_connected = serial
                    self.messages.put(lambda s=serial: self.launch("read", s))
            except (ValueError, OSError, subprocess.TimeoutExpired):
                self.messages.put(lambda: self.status.set("Telefon algılanamadı. USB kablosunu ve Android platform-tools kurulumunu kontrol et."))
            self.closed.wait(3)

    def exchange_job(self, serial, job):
        if self.closed.is_set(): raise TimeoutError('Bilgisayar aktarımı kapatılıyor.')
        mappings = run_adb(self.adb, ["-s", serial, "reverse", "--list"])
        existing = [line.split()[-1] for line in mappings.splitlines() if len(line.split()) >= 3 and line.split()[-2] == "tcp:8767"]
        if existing and existing != ["tcp:8767"]: raise ValueError("USB aktarım portu başka bir bağlantıda kullanılıyor.")
        if not existing:
            if self.closed.is_set(): raise TimeoutError('Bilgisayar aktarımı kapatılıyor.')
            run_adb(self.adb, ["-s", serial, "reverse", "--no-rebind", "tcp:8767", "tcp:8767"]); self.reverse_owned.add(serial)
        if self.closed.is_set(): raise TimeoutError('Bilgisayar aktarımı kapatılıyor.')
        operation = self.exchange.begin(job)
        output = run_adb(self.adb, ["-s", serial, "shell", "am", "start", "-f", "0x18000000", "-n", "com.qylent.manhwatakip/.DesktopTransferActivity", "--es", "operation", operation.id])
        if "Error" in output: raise ValueError("Telefon uygulamasını güncelle; bilgisayar aktarım ekranı açılamadı.")
        deadline = time.monotonic() + 90
        while not operation.done.wait(1):
            if self.closed.is_set() or time.monotonic() > deadline: raise TimeoutError("Aktarım yanıtı alınamadı; telefon verisi yeniden okunmalı.")
        result = operation.result
        if not result["ok"]: raise ValueError(result.get("error", "Telefon aktarımı reddetti."))
        self.exchange_warning = result.get("warning")
        return result["snapshot"]

    def launch(self, action, serial=None, payload=None):
        if self.busy or (hasattr(self, 'closed') and self.closed.is_set()): return
        if self.stash() is False: return
        if not self.demo and (self.exchange is None or self.adb is None):
            self.status.set('USB izleme kapalı. Önce Bağlantılar ekranındaki kurulum adımlarını tamamla.'); return
        serial = serial or self.serial
        if not serial: self.status.set("USB ile tek bir yetkili telefon bağla."); return
        self.busy = True; self.status.set("Telefon aktarımı sürüyor…")
        for item in self.buttons: item.configure(state="disabled")
        self.update_cover_buttons()
        def worker():
            try:
                self.exchange_warning = None
                warning = None
                try: snapshot = self.exchange_job(serial, {"action": action, **(payload or {})})
                except TimeoutError:
                    if action == "read": raise
                    warning = "İşlem yanıtı kayboldu. Güncel veriler yeniden okundu; doğrulanmayan taslaklar korunuyor."
                    snapshot = self.exchange_job(serial, {"action": "read"})
                warning = warning or self.exchange_warning
                folder = self.device_folder(serial)
                drafts = self.drafts if serial == self.serial and self.drafts is not None else Drafts(folder / "drafts.json")
                atomic_write(folder / "latest.json", encode(snapshot))
                stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
                atomic_write(folder / "backups" / f"manhwa-{stamp}.zip", backup_zip(snapshot))
                self.messages.put(lambda: self.loaded(serial, snapshot, drafts, warning, action))
            except Exception as error:
                message = str(error) if isinstance(error, (ValueError, TimeoutError)) else "Aktarım tamamlanamadı. Veriler ve taslaklar korunuyor; USB bağlantısını ve disk alanını kontrol et."
                self.messages.put(lambda m=message: self.failed(m))
        self.start_worker(worker)

    def loaded(self, serial, snapshot, drafts, warning, action):
        if self.stash() is False:
            self.failed("Taslak kaydedilemedi; mevcut ekran korunuyor. Disk alanını kontrol et."); return
        try:
            drafts.reconcile(snapshot); atomic_write(self.folder / "last-device.json", encode({"serial": serial}))
        except OSError:
            warning = "Güncel telefon verileri alındı; taslak/son cihaz bilgisi diske kaydedilemedi. Taslaklar korunuyor; disk alanını kontrol et."
        selected = self.ai_series.selection() if serial == self.serial else ()
        if serial != self.serial: self.ai_manual.set(False)
        self.serial = serial; self.snapshot = snapshot; self.drafts = drafts
        self.ai_cache_key = None; self.clear_ai_preview()
        self.ai_series.delete(*self.ai_series.get_children())
        for row in snapshot['library']['records']:
            self.ai_series.insert('', 'end', iid=row['id'], text=row['title'])
        self.ai_series.selection_set([identity for identity in selected if self.ai_series.exists(identity)])
        if hasattr(self, "cover_drafts"):
            try:
                covers = CoverDrafts(self.device_folder(serial) / "covers.json"); covers.reconcile(snapshot); self.cover_drafts = covers
            except (ValueError, OSError):
                self.cover_drafts = None; warning = "Bekleyen kapaklar açılamadı/kaydedilemedi; dosyalar korunuyor. Disk alanını kontrol et."
            self.cover_results = list(self.cover_drafts.entries.values()) if self.cover_drafts else []
            self.cover_tree.delete(*self.cover_tree.get_children())
            for result in self.cover_results:
                self.cover_tree.insert("", "end", iid=result["id"], text=result["title"], values=("Onaylı — aktarım bekliyor",))
            self.tabs.tab(self.settings, state="normal")
            self.update_navigation()
        self.categories.configure(values=["Tüm kategoriler", *[x["name"] for x in snapshot["library"]["categoryDefinitions"]]])
        self.draw_list(); self.bind_form(); self.busy = False
        for item in self.buttons: item.configure(state="normal")
        count = len(snapshot["library"]["records"])
        self.status.set(warning or f"{count} seri alındı ve tam yedek kaydedildi. {len(drafts.entries)} gönderilmemiş taslak var.")
        if action == "edit" and drafts.entries: self.status.set("Aktarım sonrasında bazı taslaklar doğrulanamadı; taslaklar korundu. Yenileyip çakışmayı incele.")
        if action in ("covers", "manual_covers") and not warning:
            pending = len(self.cover_drafts.entries) if self.cover_drafts else 0
            self.status.set(f"Kapak aktarımı doğrulandı. {pending} kapak aktarım bekliyor.")
        self.update_cover_buttons()
        if action == "read" and not warning: self.root.after(0, self.transfer_manual_covers)

    def failed(self, message):
        self.busy = False; self.status.set(message)
        for item in self.buttons: item.configure(state="normal")
        self.update_cover_buttons()

    def refresh(self): self.launch("read", self.last_connected or self.serial)

    def import_ai_file(self):
        path = filedialog.askopenfilename(filetypes=[('AI JSON', '*.json *.txt'), ('Tüm dosyalar', '*.*')])
        if not path: return
        try:
            with open(path, 'rb') as source: data = source.read(4 * 1024 * 1024 + 1)
            self.send_ai_import(data.decode('utf-8'))
        except (ValueError, OSError): self.status.set('AI dosyası okunamadı veya geçersiz; hiçbir kayıt değiştirilmedi.')

    def import_ai_clipboard(self):
        try: self.send_ai_import(self.root.clipboard_get())
        except (tk.TclError, ValueError): self.status.set('Panoda geçerli AI JSON metni olmalı; hiçbir kayıt değiştirilmedi.')

    def send_ai_import(self, text):
        if self.busy: return
        if not self.snapshot or not self.serial or self.last_connected != self.serial:
            self.status.set('Önce telefonu bağla ve güncel kütüphaneyi al.'); return
        try: job = ai_import_job(text)
        except ValueError as error: self.status.set(str(error)); return
        # Native review keeps the original text visible; the authoritative field diff is on the phone.
        if messagebox.askyesno('AI önerisini telefonda incele',
                'JSON doğrulandı. Telefonda seri eşleşmeleri ve alan değişiklikleri önizlenir; yalnız telefon onayıyla uygulanır. '
                'Eksik okuma bilgileri uydurulmaz, mevcut not ve kapaklar korunur. İsteğe bağlı kapak araması telefonda seçilir.\n\n'
                + text[:5000] + ('\n… (tam önizleme telefonda)' if len(text) > 5000 else '')):
            self.launch(job['action'], payload={'text': job['text']})

    def connection_absent(self):
        if not self.busy: self.status.set("Telefon bağlı değil. Son veriler ve taslaklar bilgisayarda korunuyor; kapak araması kullanılabilir.")
        self.update_cover_buttons()

    def update_cover_buttons(self):
        if not hasattr(self, "cover_accept"): return
        self.ai_cover_series.configure(values=[x['title'] for x in self.snapshot['library']['records']] if self.snapshot else [])
        self.cover_accept.configure(state="normal" if self.cover_results and not self.busy and self.cover_drafts is not None else "disabled")
        self.cover_send.configure(state="normal" if self.cover_drafts and self.cover_drafts.entries and self.last_connected == self.serial and not self.busy else "disabled")
        count = len(self.cover_drafts.entries) if self.cover_drafts else 0
        self.cover_info.set(f"{count} onaylı kapak aktarım bekliyor. Telefon bağlanınca aktarabilirsin.")

    def find_ai_cover(self):
        if not self.snapshot or self.ai_cover_series.current() < 0:
            self.status.set("Önce kapak aranacak seriyi seç."); return
        self.find_selected_cover(row=self.snapshot['library']['records'][self.ai_cover_series.current()])

    def load_manual_cover(self, clipboard=False):
        if self.busy or not self.snapshot or self.cover_drafts is None: return
        row = next((x for x in self.snapshot['library']['records'] if x['id'] == self.selected), None)
        if not row: self.status.set('Önce kütüphaneden bir seri seç.'); return
        if self.stash() is False: return
        path = None if clipboard else filedialog.askopenfilename(title='Kapak görseli seç', filetypes=[('Görseller', '*.png *.jpg *.jpeg *.bmp *.gif *.tif *.tiff'), ('Tüm dosyalar', '*.*')])
        if not clipboard and not path: return
        self.busy = True
        for item in self.buttons: item.configure(state='disabled')
        self.update_cover_buttons(); self.status.set('Kapak hazırlanıyor…')
        def worker():
            try:
                if clipboard: data = clipboard_image()
                else:
                    with open(path, 'rb') as stream: data = stream.read(5 * 1024 * 1024 + 1)
                result = manual_cover(row, data)
                self.messages.put(lambda: self.manual_cover_ready(row, result))
            except (ValueError, OSError, subprocess.SubprocessError) as error:
                message = str(error) if isinstance(error, ValueError) else 'Görsel okunamadı; mevcut kapak korunuyor.'
                self.messages.put(lambda m=message: self.failed(m))
        self.start_worker(worker)

    def manual_cover_ready(self, row, result):
        self.busy = False
        for item in self.buttons: item.configure(state='normal')
        try: self.cover_drafts.add([result])
        except (ValueError, OSError): self.failed('Kapak diske kaydedilemedi; mevcut kapak korunuyor.'); return
        self.cover_results = [x for x in self.cover_results if x['id'] != row['id']] + [result]
        if self.cover_tree.exists(row['id']): self.cover_tree.delete(row['id'])
        self.cover_tree.insert('', 'end', iid=row['id'], text=row['title'], values=('Elle seçildi — aktarım bekliyor',))
        if self.selected == row['id']: self.set_record_photo(row['id'], result['png'])
        self.update_cover_buttons(); self.draw_list()
        self.status.set('Kapak bilgisayara kaydedildi. Aynı telefon USB ile bağlanınca otomatik aktarılacak.')
        self.transfer_manual_covers()

    def transfer_manual_covers(self):
        if self.busy or self.exchange is None or not self.cover_drafts or self.last_connected != self.serial: return
        changes = self.cover_drafts.manual_changes()
        if changes: self.launch('manual_covers', payload={'changes': changes})

    def find_other_result(self):
        ids = self.cover_tree.selection()
        row = next((x for x in self.snapshot['library']['records'] if ids and x['id'] == ids[0]), None) if self.snapshot else None
        if row: self.find_selected_cover(True, row)
        else: self.status.set("Önce bir kapak sonucu seç.")

    def find_selected_cover(self, alternative=False, row=None):
        if self.busy or not self.snapshot: return
        row = row or next((x for x in self.snapshot['library']['records'] if x['id'] == self.selected), None)
        if not row: self.status.set("Önce kütüphaneden bir seri seç."); return
        if self.stash() is False: return
        fields = self.drafts.entries.get(row['id'], {}).get('fields', {})
        row = {**row, **fields, 'local': {**row.get('local', {}), **fields}}
        previous = next((x for x in self.cover_results if x['id'] == row['id']), None) or (self.cover_drafts.entries.get(row['id']) if self.cover_drafts else None)
        excluded = self.cover_excluded.setdefault(row['id'], set()) if alternative else set()
        if alternative:
            excluded.update(value for value in (row.get('url'), row.get('local', {}).get('coverUrl'),
                            (previous or {}).get('imageUrl'), (previous or {}).get('seriesUrl')) if value)
        elif previous or any(x['seriesId'] == row['id'] for x in self.snapshot.get('covers', [])):
            self.status.set("Bu seride kapak var. Değiştirmek için Farklı kaynaktan bul düğmesini kullan."); return
        self.find_covers([row], set(excluded), alternative)

    def find_covers(self, rows=None, excluded=(), replacement=False):
        if self.busy or not self.snapshot: return
        if self.stash() is False: return
        existing = {x["seriesId"] for x in self.snapshot.get("covers", [])}
        if rows is None: rows = [x for x in self.snapshot["library"]["records"] if x["id"] not in existing and x["id"] not in (self.cover_drafts.entries if self.cover_drafts else {})]
        if not rows: self.status.set("Aranacak eksik kapak yok."); return
        try: atomic_write(self.folder / "cover-settings.json", encode({"ai": self.cover_ai.get()}))
        except OSError: self.status.set("Kapak ayarı kaydedilemedi; disk alanını kontrol et."); return
        settings = self.snapshot.get("ai", {})
        model = settings.get("model", "Privite") if settings.get("baseUrl", LOCAL_AI) == LOCAL_AI else "Privite"
        search = CoverSearch(ai=self.cover_ai.get(), model=model)
        self.busy = True; self.cover_cancel.clear(); self.cover_results = []
        self.cover_tree.delete(*self.cover_tree.get_children()); self.result_preview.configure(image="", text="Aranıyor…")
        for item in self.buttons: item.configure(state="disabled")
        self.update_cover_buttons(); self.status.set(f"{len(rows)} eksik kapak aranıyor…")
        self.tabs.select(self.settings)
        def worker():
            for index, row in enumerate(rows):
                if self.closed.is_set() or self.cover_cancel.is_set(): break
                try:
                    result = search.find(row, exclude=excluded); error = None
                    if replacement: result['replaceExisting'] = True
                except Exception as failure:
                    result = None; error = str(failure) if isinstance(failure, ValueError) else "Kaynak/AI bağlantısı kurulamadı; yeniden deneyebilirsin."
                self.messages.put(lambda r=result, e=error, row=row, i=index: self.cover_found(row, r, e, i + 1, len(rows)))
                if self.cover_cancel.wait(.4): break
            self.messages.put(self.cover_finished)
        self.start_worker(worker)

    def cover_found(self, row, result, error, completed, total):
        if result: self.cover_results.append(result)
        self.cover_tree.insert("", "end", iid=row["id"], text=row["title"], values=(result["match"] if result else error,))
        self.status.set(f"Kapak araması: {completed}/{total}; {len(self.cover_results)} kapak bulundu.")

    def cover_finished(self):
        self.busy = False
        for item in self.buttons: item.configure(state="normal")
        self.update_cover_buttons()
        ids = [x["id"] for x in self.cover_results]
        if ids: self.cover_tree.selection_set(ids); self.show_cover_result()
        self.status.set(f"{'Arama durduruldu' if self.cover_cancel.is_set() else 'Arama bitti'}: {len(ids)} kapak bulundu. Önizlemeleri inceleyip seçilenleri onayla.")

    def show_cover_result(self, event=None):
        ids = self.cover_tree.selection()
        result = next((x for x in self.cover_results if ids and x["id"] == ids[0]), None)
        try:
            photo = tk.PhotoImage(data=result["png"]) if result else None
            self.result_preview.image = photo
            self.result_preview.configure(image=photo or "", text=(result['match'] if result.get('manual') else result["imageUrl"]) if result else "Bu seri için kapak bulunamadı.", compound="left", wraplength=750)
        except tk.TclError: self.result_preview.configure(image="", text="Önizleme açılamadı; bu sonucu onaylama.")

    def accept_covers(self):
        if self.busy or self.cover_drafts is None: return
        selected = set(self.cover_tree.selection()); proposals = [x for x in self.cover_results if x["id"] in selected]
        if not proposals: self.status.set("En az bir bulunan kapak seç."); return
        try: self.cover_drafts.add(proposals)
        except (ValueError, OSError): self.status.set("Kapaklar kaydedilemedi; mevcut taslaklar ve sonuçlar korunuyor."); return
        self.update_cover_buttons(); self.draw_list(); self.bind_form(); self.status.set(f"{len(proposals)} kapak onaylandı ve bilgisayara kaydedildi. Telefon bağlıyken kapakları aktar.")

    def transfer_covers(self):
        if self.busy or not self.cover_drafts or not self.cover_drafts.entries: return
        if self.last_connected != self.serial: self.status.set("Kapakları aktarmak için aynı USB telefonu bağla."); return
        if self.cover_drafts.manual_changes(): self.transfer_manual_covers(); return
        self.launch("covers", payload={"changes": self.cover_drafts.changes()})

    def rebase(self):
        if self.busy or not self.selected or not self.drafts: return
        if self.stash() is False: return
        current = next((x for x in self.snapshot["library"]["records"] if x["id"] == self.selected), None)
        if current and messagebox.askyesno("Taslağı güncelle", "Taslağın değişen alanları son alınan telefon kaydına taşınacak. Telefona aktarmadan önce incele. Devam et?"):
            self.drafts.rebase(current); self.bind_form(); self.status.set("Taslak güncel kayıt sürümüne taşındı; inceleyip Telefona aktar seç.")
            if self.cover_drafts and self.selected in self.cover_drafts.entries:
                pending = self.cover_drafts.entries[self.selected]
                if pending["title"] != current["title"]:
                    self.status.set("Seri adı değişmiş; kapak taslağını kaldırıp yeniden ara."); return
                self.cover_drafts.add([{**pending, "expectedUpdatedAt": current["updatedAt"]}])
                self.status.set("Kapak taslağı güncel sürüme taşındı; önizlemeyi inceleyip kapakları aktar.")

    def discard(self):
        if self.busy or not self.selected or not self.drafts: return
        if messagebox.askyesno("Taslağı kaldır", "Seçilen serinin bilgisayar düzenleme ve kapak taslağı kaldırılacak. Telefon kaydı korunacak. Devam et?"):
            self.drafts.entries.pop(self.selected, None); self.drafts.persist(); self.bind_form(); self.draw_list()
            if self.cover_drafts:
                self.cover_drafts.replace({id: draft for id, draft in self.cover_drafts.entries.items() if id != self.selected})
                self.update_cover_buttons(); self.bind_form(); self.draw_list()

    def transfer(self):
        if self.stash() is False: return
        if not self.drafts or not self.drafts.changes(): self.status.set("Aktarılacak taslak yok."); return
        self.launch("edit", payload={"changes": self.drafts.changes()})

    def restore(self):
        if not self.serial: self.status.set("Önce telefonu bağla."); return
        path = filedialog.askopenfilename(filetypes=[("Tam yedek", "*.json *.zip")])
        if not path: return
        try:
            with open(path, "rb") as stream: snapshot = read_backup(stream.read(32 * 1024 * 1024 + 1))
            if messagebox.askyesno("Yedeği geri yükle", "Mevcut telefon verileri korunacak; yalnızca eksikler eklenecek. Seçilen yedeği aktar?"):
                self.launch("restore", payload={"backup": snapshot})
        except (ValueError, OSError) as error: self.status.set(str(error))

    def write_file(self, data, name):
        path = filedialog.asksaveasfilename(initialfile=name, defaultextension=Path(name).suffix)
        if path:
            try: atomic_write(path, data); self.status.set("Dosya kaydedildi.")
            except OSError: self.status.set("Dosya kaydedilemedi; disk alanını ve klasör yetkisini kontrol et.")

    def export_backup(self, zipped):
        if not self.snapshot: self.status.set("Önce telefondan veri al."); return
        self.write_file(backup_zip(self.snapshot) if zipped else encode(self.snapshot), "manhwa-tam." + ("zip" if zipped else "json"))

    def package(self):
        status = next((key for key, label in STATUSES.items() if label == self.ai_status.get()), "ALL")
        mode = {"Okuma profili": "SERIES", "Yeni öneri": "RECOMMEND", "Okunan bölüme kadar özet": "SUMMARIZE"}[self.ai_mode.get()]
        fields = frozenset(key for key, var in self.ai_fields.items() if var.get())
        ids = frozenset(self.ai_series.selection()) if self.ai_manual.get() else None
        key = (id(self.snapshot), status, mode, fields, ids)
        if key != self.ai_cache_key:
            self.ai_cache = ai_package(self.snapshot, status, mode, fields=fields, selected_ids=ids)
            self.ai_cache_key = key
        return self.ai_cache

    def clear_ai_preview(self):
        self.ai_preview.configure(state='normal'); self.ai_preview.delete('1.0', 'end'); self.ai_preview.configure(state='disabled')

    def preview_ai(self):
        if not self.snapshot: self.status.set('Önce telefondan veri al.'); return
        try:
            text = self.package()['context.json'].decode()
            self.ai_preview.configure(state='normal'); self.ai_preview.delete('1.0', 'end')
            self.ai_preview.insert('1.0', text[:12000]); self.ai_preview.configure(state='disabled')
            self.status.set('İlk 12.000 karakter gösteriliyor; dosya çıktısı tam JSON içerir.' if len(text) > 12000 else 'AI çıktısı hazır.')
        except ValueError as error: self.status.set(str(error))

    def export_ai(self, zipped):
        if not self.snapshot: self.status.set("Önce telefondan veri al."); return
        try:
            package = self.package(); self.write_file(zip_files(package) if zipped else package["context.json"], "manhwa-ai." + ("zip" if zipped else "json"))
        except ValueError as error: self.status.set(str(error))

    def copy_ai(self):
        if not self.snapshot: self.status.set("Önce telefondan veri al."); return
        try:
            data = self.package()["context.json"].decode()
            if len(data) > 100000: raise ValueError("Büyük AI paketini dosyaya kaydet.")
            self.root.clipboard_clear(); self.root.clipboard_append(data); self.status.set("AI JSON panoya kopyalandı.")
        except ValueError as error: self.status.set(str(error))

    def close(self):
        if self.closed.is_set() or self.exiting or self.closing_to_tray: return
        if self.demo or not self.preferences.close_to_tray:
            self.exit_application(); return
        self.closing_to_tray = True
        try: self.hide_to_tray()
        finally: self.closing_to_tray = False

    def hide_to_tray(self):
        if self.stash() is False:
            messagebox.showwarning('Taslak kaydedilemedi', 'Pencere açık tutuldu; düzenlemen henüz kaydedilmedi.', parent=self.root); return
        if self.tray is None or not self.tray.show():
            self.restore_window()
            self.status.set('Sistem tepsisi kullanılamıyor; pencere açık tutuldu. Ayarlar → Tamamen çık ile kapatabilirsin.')
            return
        marker = self.folder / 'tray-explained.json'
        try:
            explained = json.loads(marker.read_text(encoding='utf-8')).get('explained') is True if marker.exists() else False
        except (OSError, ValueError, AttributeError): explained = False
        if not explained:
            messagebox.showinfo('Manhwa Takip tepside çalışır',
                'Kapat düğmesi uygulamayı sistem tepsisine gizler. USB izleme ve yedek alma sürer.\n\n'
                'Tepsi simgesinden Aç / Bağlantı durumu / Tamamen çık seçeneklerini kullanabilirsin. '
                'Simge Windows’un gizli simgeler bölümünde olabilir.\n\n'
                'Windows açılışında başlatma ayrı bir ayardır ve varsayılan olarak kapalıdır. '
                'Bu davranışı Ayarlar’dan değiştirebilirsin.', parent=self.root)
            if self.closed.is_set() or self.exiting: return
            try: atomic_write(marker, encode({'explained': True}))
            except OSError:
                self.status.set('Tepsi açıklaması kaydedilemedi; pencere açık tutuldu.'); return
        if self.closed.is_set() or self.exiting: return
        if not self.tray.available:
            self.restore_window(); self.status.set('Sistem tepsisi kullanılamıyor; pencere açık tutuldu.'); return
        self.hidden = True; self.root.withdraw()

    def exit_application(self):
        if self.closed.is_set() or self.exiting: return
        self.exiting = True
        if self.stash_timer:
            self.root.after_cancel(self.stash_timer); self.stash_timer = None
        if self.stash() is False:
            self.exiting = False; self.restore_window()
            messagebox.showwarning('Taslak kaydedilemedi', 'Pencere açık tutuldu; disk alanını kontrol et.', parent=self.root)
            return
        self.closed.set(); self.cover_cancel.set()
        self.root.deiconify(); self.status.set('Kapatılıyor: bu pencerenin işlemleri ve USB eşlemesi temizleniyor…')
        if self.tray is not None: self.tray.stop()
        def finish():
            try:
                for worker in self.workers: worker.join()
                self.cleanup_reverse()
                if self.exchange is not None: self.exchange.close()
            finally: self.shutdown_done.set()
        if self.demo:
            finish(); self.root.destroy()
        else:
            self.shutdown_thread = threading.Thread(target=finish, name='manhwa-cleanup', daemon=True)
            self.shutdown_thread.start(); self.wait_for_shutdown()

    def wait_for_shutdown(self):
        if self.shutdown_done.is_set(): self.root.destroy()
        else: self.root.after(50, self.wait_for_shutdown)

    def cleanup_reverse(self):
        for serial in tuple(self.reverse_owned):
            try:
                mappings = run_adb(self.adb, ["-s", serial, "reverse", "--list"])
                if any(line.split()[-2:] == ["tcp:8767", "tcp:8767"] for line in mappings.splitlines()):
                    run_adb(self.adb, ["-s", serial, "reverse", "--remove", "tcp:8767"])
            except (ValueError, OSError, subprocess.TimeoutExpired): pass
        self.reverse_owned.clear()


def focus_existing_window():
    if os.name != "nt": return False
    import ctypes
    from ctypes import wintypes
    windows = ctypes.windll.user32
    windows.FindWindowW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR]; windows.FindWindowW.restype = wintypes.HWND
    handle = windows.FindWindowW(None, "Manhwa Takip — Bilgisayar")
    if not handle: return False
    windows.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]; windows.ShowWindow.restype = wintypes.BOOL
    windows.SetForegroundWindow.argtypes = [wintypes.HWND]; windows.SetForegroundWindow.restype = wintypes.BOOL
    windows.ShowWindow(handle, 9); windows.SetForegroundWindow(handle)
    return True


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--self-test", action="store_true"); args = parser.parse_args()
    folder = Path(os.environ.get('LOCALAPPDATA', str(Path.home() / '.local/share'))) / 'ManhwaTakipCompanion' / 'data'
    instance = None if args.self_test else InstanceGate(folder)
    if instance is not None and not instance.primary: return
    root = tk.Tk()
    if args.self_test:
        from demo import snapshot
        with tempfile.TemporaryDirectory() as folder:
            app = Window(root, folder, demo=True); data = snapshot(); data["library"]["records"][0].update(opinion="UNSET", url="", likedReason="", dislikedReason="", dropReason="")
            drafts = Drafts(Path(folder) / "drafts.json"); app.loaded("fixture", data, drafts, None, "read")
            id = data["library"]["records"][0]["id"]; app.tree.selection_set(id); root.update(); app.select()
            app.vars["lastChapter"].set("9,0"); app.stash(); assert drafts.changes()[0]["fields"]["lastChapter"] == "9"
            app.search.set("türkçe"); root.update(); assert len(app.tree.get_children()) == 1
            app.filter.set("Okunacak"); root.update(); assert not app.tree.get_children()
            app.close(); print("COMPANION_NATIVE_GUI_OK")
        return
    try:
        sys.path.insert(0, str(Path(__file__).resolve().parent / "bridge"))
        if not (Path(__file__).resolve().parent / "bridge").exists(): sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "bridge"))
        from bridge import key_path, protect
        key = None
        adb = folder.parents[1] / "Android/Sdk/platform-tools/adb.exe"
        # Missing/unreadable prerequisites must not prevent normal offline UI access.
        # Only decrypt an existing key when the USB runtime is also available.
        if adb.is_file():
            try: key = protect(key_path().read_bytes(), True).decode()
            except (OSError, ValueError): pass
        if not key: adb = None
        with (Exchange(key) if key else nullcontext(None)) as exchange:
            app = Window(root, folder, exchange, adb, instance=instance)
            try: root.mainloop()
            finally:
                if not app.closed.is_set():
                    app.closed.set(); app.cover_cancel.set()
                    if app.tray is not None: app.tray.stop()
                    for worker in app.workers: worker.join()
                    app.cleanup_reverse()
    except Exception as error:
        if not (isinstance(error, OSError) and getattr(error, "winerror", None) in (10048, 10013) and focus_existing_window()):
            messagebox.showerror("Manhwa Takip", "Uygulama açılamadı. Mevcut eşleştirmeyi, Android platform-tools kurulumunu ve başka bir bilgisayar uygulaması penceresi açık olup olmadığını kontrol et.")
        root.destroy()
    finally:
        if instance is not None: instance.close()


if __name__ == "__main__": main()
