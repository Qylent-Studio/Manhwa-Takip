"""In-place native Tk styling. System preference is read-only and best effort."""
import os
import tkinter as tk
from tkinter import ttk

LIGHT = dict(canvas='#f4f6fa', surface='#ffffff', text='#192536', muted='#536174',
             border='#ccd4df', accent='#245dc1', hover='#1b4da5', pressed='#173e83',
             selection='#e7eefc', disabled='#656565', on_accent='#ffffff')
DARK = dict(canvas='#171b22', surface='#222833', text='#f0f3f8', muted='#b7c2d2',
            border='#566176', accent='#9cbfff', hover='#b2cdff', pressed='#86aff5',
            selection='#34486a', disabled='#a5afbf', on_accent='#142035')


def palette(mode, system_dark):
    return dict(DARK if mode == 'dark' or (mode not in ('light', 'dark') and system_dark) else LIGHT)


def system_dark():
    if os.name != 'nt': return False
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Software\Microsoft\Windows\CurrentVersion\Themes\Personalize') as key:
            return winreg.QueryValueEx(key, 'AppsUseLightTheme')[0] == 0
    except OSError: return False


def titlebar(window, dark):
    """Unsupported Windows versions simply keep their native titlebar."""
    if os.name != 'nt': return
    try:
        import ctypes
        from ctypes import wintypes
        user32 = ctypes.windll.user32
        user32.GetParent.argtypes = [wintypes.HWND]; user32.GetParent.restype = wintypes.HWND
        hwnd = user32.GetParent(window.winfo_id())
        value = ctypes.c_int(bool(dark))
        setter = ctypes.windll.dwmapi.DwmSetWindowAttribute
        setter.argtypes = [wintypes.HWND, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD]
        setter.restype = ctypes.c_long
        if setter(hwnd, 20, ctypes.byref(value), ctypes.sizeof(value)) != 0:
            setter(hwnd, 19, ctypes.byref(value), ctypes.sizeof(value))
    except (AttributeError, OSError, tk.TclError): pass


def style(root, colors):
    c = colors; s = ttk.Style(root)
    if s.theme_use() != 'clam': s.theme_use('clam')
    s.configure('.', font=('Segoe UI', 10), background=c['surface'], foreground=c['text'])
    s.map('.', foreground=[('disabled', c['disabled'])], background=[('disabled', c['canvas'])])
    for name, options in {
        'Shell.TFrame': dict(background=c['canvas']),
        'Shell.TLabel': dict(background=c['canvas'], foreground=c['muted']),
        'Brand.TLabel': dict(background=c['canvas'], foreground=c['accent'], font=('Segoe UI', 20, 'bold')),
        'Title.TLabel': dict(background=c['canvas'], font=('Segoe UI', 24, 'bold')),
        'Section.TLabel': dict(font=('Segoe UI', 13, 'bold')),
        'Muted.TLabel': dict(foreground=c['muted']),
        'Card.TFrame': dict(background=c['surface'], relief='solid', borderwidth=1),
        'CardTitle.TLabel': dict(font=('Segoe UI', 12, 'bold')),
        'Chapter.TLabel': dict(foreground=c['accent'], font=('Segoe UI', 16, 'bold')),
    }.items(): s.configure(name, **options)
    s.configure('TButton', padding=(12, 8), borderwidth=1, relief='solid', bordercolor=c['border'],
                background=c['surface'], lightcolor=c['surface'], darkcolor=c['surface'], focuscolor=c['accent'])
    s.map('TButton', background=[('disabled', c['canvas']), ('pressed', c['selection']), ('active', c['selection'])],
          foreground=[('disabled', c['disabled']), ('!disabled', c['text'])], bordercolor=[('focus', c['accent'])])
    s.configure('Accent.TButton', background=c['accent'], foreground=c['on_accent'], bordercolor=c['accent'],
                lightcolor=c['accent'], darkcolor=c['accent'], focuscolor=c['on_accent'])
    s.map('Accent.TButton', background=[('disabled', c['canvas']), ('pressed', c['pressed']), ('active', c['hover']), ('!disabled', c['accent'])],
          foreground=[('disabled', c['disabled']), ('!disabled', c['on_accent'])])
    s.layout('Nav.TRadiobutton', s.layout('TButton'))
    s.configure('Nav.TRadiobutton', anchor='w', padding=(16, 12), background=c['canvas'], borderwidth=1,
                bordercolor=c['canvas'], lightcolor=c['canvas'], darkcolor=c['canvas'], focuscolor=c['accent'])
    s.map('Nav.TRadiobutton', background=[('selected', c['selection']), ('active', c['selection']), ('!selected', c['canvas'])],
          foreground=[('disabled', c['disabled']), ('selected', c['accent']), ('!selected', c['text'])],
          bordercolor=[('focus', c['accent']), ('selected', c['selection'])])
    for control in ('TEntry', 'TCombobox', 'TSpinbox'):
        s.configure(control, padding=7, fieldbackground=c['surface'], foreground=c['text'], insertcolor=c['text'],
                    bordercolor=c['border'], lightcolor=c['surface'], darkcolor=c['surface'], arrowcolor=c['muted'])
        s.map(control, fieldbackground=[('disabled', c['canvas']), ('readonly', c['surface'])],
              foreground=[('disabled', c['disabled']), ('readonly', c['text'])], bordercolor=[('focus', c['accent'])],
              selectbackground=[('!disabled', c['accent'])], selectforeground=[('!disabled', c['on_accent'])])
    for control in ('TCheckbutton', 'TRadiobutton'):
        s.configure(control, padding=(0, 4), indicatorbackground=c['surface'], indicatorforeground=c['text'])
        s.map(control, background=[('active', c['selection'])], foreground=[('disabled', c['disabled']), ('!disabled', c['text'])],
              indicatorbackground=[('disabled', c['canvas']), ('selected', c['accent'])])
    s.configure('Treeview', rowheight=42, background=c['surface'], fieldbackground=c['surface'], borderwidth=0)
    s.configure('Treeview.Heading', font=('Segoe UI', 10, 'bold'), background=c['canvas'], padding=(10, 10), relief='flat')
    s.map('Treeview', background=[('selected', c['selection'])], foreground=[('selected', c['text'])])
    s.map('Treeview.Heading', background=[('active', c['selection'])])
    s.configure('TScrollbar', background=c['border'], troughcolor=c['canvas'], arrowcolor=c['text'])
    s.map('TScrollbar', background=[('active', c['muted'])])
    s.configure('Workspace.TNotebook', borderwidth=0, tabmargins=0, bordercolor=c['border'], lightcolor=c['surface'], darkcolor=c['surface'])
    s.layout('Workspace.TNotebook.Tab', [])
    for widget in ('Text', 'Listbox', 'Menu'):
        for key, value in dict(background=c['surface'], foreground=c['text'], selectBackground=c['accent'],
                               selectForeground=c['on_accent'], font='{Segoe UI} 10').items():
            root.option_add(f'*{widget}.{key}', value)
    root.option_add('*Text.insertBackground', c['text'])
    root.option_add('*Text.highlightColor', c['accent']); root.option_add('*Text.highlightBackground', c['border'])
    root.option_add('*Text.highlightThickness', 1); root.option_add('*Text.borderWidth', 0)
    root.option_add('*Menu.activeBackground', c['selection']); root.option_add('*Menu.activeForeground', c['text'])
    root.option_add('*Menu.disabledForeground', c['disabled'])
    # ttk combobox dropdown is a classic Tk listbox created lazily.
    root.option_add('*TCombobox*Listbox.background', c['surface'])
    root.option_add('*TCombobox*Listbox.foreground', c['text'])
    root.option_add('*TCombobox*Listbox.selectBackground', c['accent'])
    root.option_add('*TCombobox*Listbox.selectForeground', c['on_accent'])


def widgets(widget, colors):
    """Configure existing controls only: no replacement, content/state/focus edits."""
    c = colors
    if isinstance(widget, (tk.Tk, tk.Toplevel)):
        widget.configure(background=c['canvas']); titlebar(widget, c == DARK)
    elif isinstance(widget, tk.Text):
        widget.configure(background=c['surface'], foreground=c['text'], insertbackground=c['text'],
                         selectbackground=c['accent'], selectforeground=c['on_accent'],
                         highlightbackground=c['border'], highlightcolor=c['accent'])
    elif isinstance(widget, tk.Canvas): widget.configure(background=c['surface'])
    elif isinstance(widget, tk.Listbox):
        widget.configure(background=c['surface'], foreground=c['text'], selectbackground=c['accent'],
                         selectforeground=c['on_accent'], disabledforeground=c['disabled'])
    elif isinstance(widget, tk.Menu):
        widget.configure(background=c['surface'], foreground=c['text'], activebackground=c['selection'],
                         activeforeground=c['text'], disabledforeground=c['disabled'])
    elif isinstance(widget, ttk.Combobox):
        # Tcl owns this lazily-created widget, so it is absent from winfo_children().
        dropdown = str(widget) + '.popdown.f.l'
        if widget.tk.call('winfo', 'exists', dropdown):
            widget.tk.call(dropdown, 'configure', '-background', c['surface'], '-foreground', c['text'],
                           '-selectbackground', c['accent'], '-selectforeground', c['on_accent'])
    for child in widget.winfo_children(): widgets(child, colors)


def apply(root, colors):
    style(root, colors)
    widgets(root, colors)
