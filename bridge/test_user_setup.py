import json
from pathlib import Path
import tempfile
import unittest
import os
from unittest.mock import patch
from user_setup import merge_config, install_config, server_config
from bridge import BridgeState, tool_definitions
from test_bridge import record, snapshot


class UserSetupTests(unittest.TestCase):
    @unittest.skipUnless(os.environ.get("MANHWA_GUI_TEST") == "1", "Native Windows GUI acceptance is opt-in")
    def test_native_key_and_mcp_windows_use_only_disposable_data(self):
        import tkinter as tk
        import user_setup
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "mcp_config.json"
            target.write_text('{"mcpServers":{"existing":{"command":"keep"}}}', encoding="utf-8")
            fixture = Path(directory) / "fixture.key"; fixture.write_bytes(b"not-a-real-key")
            for mode in ("key", "mcp"):
                copied = []
                def inspect(root):
                    root.update()
                    self.assertGreater(root.winfo_width(), 600)
                    widgets = root.winfo_children()
                    buttons = {w.cget("text"): w for w in widgets if isinstance(w, tk.Button)}
                    with patch.object(root, "clipboard_clear"), patch.object(root, "clipboard_append", side_effect=copied.append), patch("tkinter.messagebox.showinfo"):
                        if mode == "key":
                            entry = next(w for w in widgets if isinstance(w, tk.Entry))
                            self.assertTrue(entry.cget("show")); self.assertEqual("fixture-pairing-only", entry.get())
                            buttons["Eşleştirme anahtarını kopyala"].invoke()
                            self.assertEqual(["fixture-pairing-only"], copied)
                        else:
                            buttons["Antigravity'ye ekle"].invoke()
                            servers = json.loads(target.read_text(encoding="utf-8"))["mcpServers"]
                            self.assertIn("manhwa_takip", servers); self.assertIn("existing", servers)
                    buttons["Kapat"].invoke()
                with patch.object(tk.Tk, "mainloop", inspect), patch("user_setup.antigravity_target", return_value=target), patch("bridge.key_path", return_value=fixture), patch("bridge.protect", return_value=b"fixture-pairing-only"):
                    self.assertEqual(0, user_setup.window(mode))
    def test_gui_pythonw_launch_exports_console_python_for_stdio(self):
        with patch("user_setup.sys.executable", str(Path("runtime") / "pythonw.exe")):
            self.assertEqual(str(Path("runtime") / "python.exe"), server_config()["command"])
    def test_config_preserves_other_servers_and_settings_and_is_idempotent(self):
        server = server_config()
        original = {"mcpServers": {"other": {"command": "existing"}}, "setting": True}
        result = merge_config(original, server)
        self.assertEqual(original["mcpServers"]["other"], result["mcpServers"]["other"])
        self.assertTrue(result["setting"])
        self.assertEqual(result, merge_config(result, server))
        self.assertNotIn("pairing", json.dumps(result))
        self.assertNotIn("key", server)

    def test_conflicting_or_malformed_config_is_never_overwritten(self):
        for original in [{"mcpServers": []}, {"mcpServers": {"manhwa_takip": {"command": "other"}}}, []]:
            with self.assertRaises(ValueError): merge_config(original, server_config())
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mcp_config.json"
            path.write_text('{"mcpServers":{"other":{"command":"keep"}}}', encoding="utf-8")
            old = path.read_bytes()
            install_config(path, server_config())
            self.assertEqual(old, path.with_name(path.name + ".before-manhwa").read_bytes())
            first = path.read_bytes(); install_config(path, server_config())
            self.assertEqual(first, path.read_bytes())
            self.assertIn("other", json.loads(first)["mcpServers"])

    def test_mcp_categories_are_multi_select_and_remain_phone_proposals(self):
        state = BridgeState("fixture-only")
        state.sync({"library": snapshot([record()]), "decisions": {}})
        result = state.call("propose_change", {"seriesId": record()["id"], "expectedUpdatedAt": 2, "fields": {"categories": ["Harem", "Macera"]}})
        self.assertEqual([], state.records[0]["categories"])
        self.assertEqual(["Harem", "Macera"], state.proposals[result["token"]]["after"]["categories"])
        for labels in [["IŞIK", "ışık"], [3], "Harem"]:
            with self.assertRaises(ValueError): state.call("propose_change", {"seriesId": record()["id"], "expectedUpdatedAt": 2, "fields": {"categories": labels}})
        self.assertEqual("array", tool_definitions()[1]["inputSchema"]["properties"]["fields"]["properties"]["categories"]["type"])
