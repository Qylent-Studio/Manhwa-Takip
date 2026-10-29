import io
import json
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile
from pathlib import Path
from core import read_backup, backup_zip, ai_package, Drafts, Exchange


def snapshot():
    return {"format": "manhwa-full", "version": 1, "exportedAt": 1,
            "library": {"format": "manhwa-takip", "version": 4, "exportedAt": 1,
                        "records": [{"id": "11111111-1111-4111-8111-111111111111", "title": "Türkçe 雪", "lastChapter": "8", "updatedAt": 2,
                                     "status": "READING", "notes": "Kalsın", "categories": [], "local": {"favorite": False}}], "categoryDefinitions": []},
            "history": [], "chats": [{"draft": "Özel sohbet"}], "preferences": {}}


class CompanionTests(unittest.TestCase):
    def test_ai_import_uses_separate_phone_approval_job_not_manual_edits_or_covers(self):
        from app import ai_import_job
        job = ai_import_job('{"format":"manhwa-chatgpt","version":1,"series":[{"title":"New"}]}')
        self.assertEqual(job['action'], 'ai_import')
        self.assertEqual(set(job), {'action', 'text'})
        self.assertEqual(json.loads(job['text'])['series'], [{'title': 'New'}])
        import urllib.request
        import hmac
        import hashlib
        with Exchange('synthetic-fixture', port=0) as exchange:
            operation = exchange.begin(job)
            path = f'/job/{operation.id}'
            request = urllib.request.Request(f'http://127.0.0.1:{exchange.port}{path}', headers={'Authorization': exchange.proof('GET', path)})
            with urllib.request.urlopen(request) as response: envelope = json.load(response)
            self.assertEqual(envelope['signature'], hmac.new(b'synthetic-fixture', ('response\n' + envelope['payload']).encode(), hashlib.sha256).hexdigest())
            sent = json.loads(envelope['payload'])
            self.assertEqual(sent, dict(job, id=operation.id))
            self.assertFalse(operation.done.is_set())  # No PC-side apply or success before phone decision.

    def test_ai_import_rejects_bad_oversize_unknown_version_and_duplicates(self):
        from app import ai_import_job
        for text in ('{bad', ' ' * (4 * 1024 * 1024 + 1), '{"version":99,"series":[{"title":"New"}]}',
                     '{"title":"New","title":"Other"}', '{"series":[]}'):
            with self.subTest(text=text[:50]), self.assertRaises(ValueError): ai_import_job(text)

    def test_ai_import_user_cancel_sends_nothing_and_confirmation_only_sends_ai_job(self):
        from app import Window
        window = Mock(busy=False, snapshot=snapshot(), serial='fixture', last_connected='fixture')
        text = '{"title":"New"}'
        with patch('app.messagebox.askyesno', return_value=False): Window.send_ai_import(window, text)
        window.launch.assert_not_called()
        with patch('app.messagebox.askyesno', return_value=True): Window.send_ai_import(window, text)
        window.launch.assert_called_once_with('ai_import', payload={'text': text})
    def test_actual_zip_members_keep_notes_reasons_independent_and_size_limit(self):
        from core import zip_files
        fixture, original = self.share_fixture()
        for fields in (set(), {'REASONS'}, {'NOTES'}):
            package = ai_package(original, fields=fields, selected_ids={fixture['records'][0]['id']})
            with zipfile.ZipFile(io.BytesIO(zip_files(package))) as archive:
                self.assertEqual(set(archive.namelist()), {'context.json', 'profile.md', 'instructions.txt'})
                for name in archive.namelist():
                    text = archive.read(name).decode()
                    for secret in ('OTHER_NOTE', 'HISTORY_SENTINEL', 'Durum:', 'Okunuyor', 'CHAT_SENTINEL', 'KEY_SENTINEL'):
                        self.assertNotIn(secret, text)
                    if 'NOTES' not in fields: self.assertNotIn(fixture['records'][0]['notes'], text)
                    if 'REASONS' not in fields: self.assertNotIn(fixture['records'][0]['likedReason'], text)
        original['library']['records'] = [dict(fixture['records'][0], id=str(i), notes='N' * 4000) for i in range(1100)]
        with self.assertRaisesRegex(ValueError, '4 MB'): ai_package(original, fields={'NOTES'})
        self.assertLess(len(ai_package(original, fields=set())['context.json']), 4 * 1024 * 1024)

    def share_fixture(self):
        fixture = json.loads((Path(__file__).resolve().parents[1] / "app/src/test/resources/ai-share/contract.json").read_text(encoding="utf-8"))
        return fixture, {"library": {"records": fixture["records"], "categoryDefinitions": fixture["categoryDefinitions"]},
                         "history": fixture["history"], "chats": [{"draft": "CHAT_SENTINEL"}], "keys": {"api": "KEY_SENTINEL"}}

    def test_ai_share_shared_field_cases_project_exact_keys(self):
        fixture, original = self.share_fixture()
        for case in fixture["cases"]:
            with self.subTest(fields=case["fields"]):
                output = json.loads(ai_package(original, fields=set(case["fields"]), selected_ids={fixture["records"][0]["id"]})["context.json"])
                self.assertEqual(output["series"], [case["expected"]])
                self.assertEqual((output["format"], output["version"]), ("manhwa-chatgpt", 1))

    def test_ai_share_title_only_archive_has_no_deselected_values(self):
        _, original = self.share_fixture()
        for mode in ("SERIES", "RECOMMEND", "SUMMARIZE"):
            package = ai_package(original, mode=mode, fields=set())
            output = json.loads(package["context.json"])
            for key in ("profile", "readingHistory", "categoryDefinitions"): self.assertNotIn(key, output)
            text = b"\n".join(package.values()).decode()
            for secret in ("SENTINEL", "OTHER_NOTE", original["library"]["records"][0]["id"], "https://example.org/read", "Kayıtlı bölüm değişikliği", "Durum:", "son bölüm:"):
                self.assertNotIn(secret, text)
            self.assertEqual(package["profile.md"].decode(), "# Okuma profilim\n\n## Sentetik 雪\n\n## Diğer sentetik\n\n")

    def test_ai_share_empty_ids_and_filter_intersection(self):
        fixture, original = self.share_fixture()
        for status, ids, count in (("ALL", None, 2), ("ALL", set(), 0), ("READING", {fixture["records"][1]["id"]}, 0), ("READING", {fixture["records"][0]["id"]}, 1)):
            output = json.loads(ai_package(original, status, fields=set(), selected_ids=ids)["context.json"])
            self.assertEqual(len(output["series"]), count)

    def test_ai_share_history_categories_and_counts_are_opt_in_and_scoped(self):
        fixture, original = self.share_fixture()
        output = json.loads(ai_package(original, fields={"HISTORY", "CATEGORIES", "STATUS"}, selected_ids={fixture["records"][0]["id"]})["context.json"])
        self.assertEqual(output["readingHistory"], fixture["history"][:1])
        self.assertEqual(output["categoryDefinitions"], fixture["categoryDefinitions"][:1])
        self.assertEqual(output["profile"]["Okunuyor"], 1); self.assertEqual(output["profile"]["Okunacak"], 0)
        output = json.loads(ai_package(original, fields={"HISTORY"})["context.json"])
        self.assertNotIn("profile", output); self.assertNotIn("categoryDefinitions", output)

    def test_ai_share_legacy_retains_identity_and_local_metadata(self):
        fixture, original = self.share_fixture()
        output = json.loads(ai_package(original)["context.json"])
        self.assertEqual(output["series"][0]["id"], fixture["records"][0]["id"])
        self.assertTrue(output["series"][0]["favorite"]); self.assertIn("alternativeTitles", output["series"][0])
        self.assertIn("profile", output); self.assertEqual(len(output["readingHistory"]), 2)

    def test_ai_share_recommendation_example_is_unread(self):
        _, original = self.share_fixture()
        output = json.loads(ai_package(original, mode="RECOMMEND", fields=set())["context.json"])
        example = output["responseFormat"]
        self.assertEqual((example["format"], example["version"]), ("manhwa-chatgpt", 1))
        self.assertEqual(example["series"][0]["status"], "PLANNED"); self.assertEqual(example["series"][0]["lastChapter"], "")

    def test_exchange_refuses_second_listener_on_same_windows_port(self):
        with Exchange("synthetic-fixture", port=0) as first:
            with self.assertRaises(OSError):
                with Exchange("synthetic-fixture", port=first.port): pass

    def test_second_launch_restores_existing_window_without_error_dialog(self):
        import app, types, sys
        bridge = types.ModuleType("bridge"); bridge.key_path = Mock(); bridge.key_path.return_value.read_bytes.return_value = b"fixture"
        bridge.protect = Mock(return_value=b"synthetic-fixture")
        root = Mock(); error = OSError("address already in use"); error.winerror = 10048
        with patch.dict(sys.modules, {"bridge": bridge}), patch.object(sys, "argv", ["app.py"]), \
                patch.object(app.tk, "Tk", return_value=root), patch.object(Path, "is_file", return_value=True), \
                patch.object(app, "Exchange", side_effect=error), patch.object(app.messagebox, "showerror") as dialog, \
                patch.object(app, "focus_existing_window", create=True, return_value=True) as focus, \
                patch.object(app, 'InstanceGate') as instance:
            instance.return_value.primary = True  # Do not touch/signal the user's real instance gate.
            app.main()
            focus.assert_called_once(); dialog.assert_not_called(); root.destroy.assert_called_once()

    def test_unsaved_disk_failure_blocks_selection_and_transfer(self):
        from app import Window
        window = Window.__new__(Window); window.loading = False; window.selected = "original"
        window.tree = Mock(); window.tree.selection.return_value = ["other"]
        window.stash = Mock(return_value=False); window.bind_form = Mock()
        window.select()
        self.assertEqual(window.selected, "original"); window.bind_form.assert_not_called()
        window.drafts = Mock(); window.status = Mock(); window.launch = Mock()
        window.transfer(); window.drafts.changes.assert_not_called(); window.launch.assert_not_called()
        del window.launch
        window.busy = False; window.serial = "fixture"; window.exchange_job = Mock()
        window.launch("read"); self.assertFalse(window.busy); window.exchange_job.assert_not_called()

    def test_every_usb_job_starts_a_fresh_transfer_activity(self):
        import threading
        from app import Window
        window = Window.__new__(Window); window.adb = Path("fixture-adb"); window.reverse_owned = set()
        window.closed = threading.Event(); window.exchange = Mock()
        operation = Mock(); operation.id = "fixture-operation"; operation.done = threading.Event(); operation.done.set()
        operation.result = {"ok": True, "snapshot": snapshot()}; window.exchange.begin.return_value = operation
        with patch("app.run_adb", side_effect=["UsbFfs tcp:8767 tcp:8767", "Starting: Intent"] ) as run:
            self.assertEqual(window.exchange_job("fixture", {"action": "read"}), snapshot())
            args = run.call_args_list[1].args[1]
            self.assertIn("-f", args)
            self.assertEqual(int(args[args.index("-f") + 1], 16), 0x18000000)
            self.assertNotIn("-W", args)

    def test_pc_ai_import_keeps_phone_cover_scheduling_outcome_for_ui(self):
        import threading
        from app import Window
        window = Window.__new__(Window); window.adb = Path('fixture-adb'); window.reverse_owned = set()
        window.closed = threading.Event(); window.exchange = Mock()
        operation = Mock(); operation.id = 'fixture-operation'; operation.done = threading.Event(); operation.done.set()
        message = 'Yeni serilerin kapak araması sıraya alındı. İlerleme/Durdur: Kütüphane.'
        operation.result = {'ok': True, 'snapshot': snapshot(), 'warning': message}
        window.exchange.begin.return_value = operation
        with patch('app.run_adb', side_effect=['UsbFfs tcp:8767 tcp:8767', 'Starting: Intent']):
            self.assertEqual(window.exchange_job('fixture', {'action': 'ai_import', 'text': '{"title":"New"}'}), snapshot())
        self.assertEqual(window.exchange_warning, message)  # launch/loaded surfaces this as the status, not generic success.

    def test_large_raw_and_compressed_backup_are_rejected(self):
        from core import MAX_BYTES
        oversized = b" " * (MAX_BYTES + 1)
        with self.assertRaises(ValueError): read_backup(oversized)
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("backup.json", oversized)
        self.assertLess(len(out.getvalue()), MAX_BYTES)
        with self.assertRaises(ValueError): read_backup(out.getvalue())
        with self.assertRaises(ValueError): read_backup(b'PKbroken')

    def test_lost_reply_reads_phone_and_retains_unverified_drafts(self):
        import queue, threading, time
        from app import Window
        with tempfile.TemporaryDirectory() as folder:
            window = Window.__new__(Window); window.folder = Path(folder); window.serial = "fixture"
            window.demo = False; window.exchange = Mock(); window.adb = Path('fixture-adb')
            window.closed = threading.Event(); window.workers = []
            window.drafts = Drafts(window.device_folder() / "drafts.json")
            original = snapshot()["library"]["records"][0]
            window.drafts.save(original, {"lastChapter": "9"})
            window.busy = False; window.stash = Mock(return_value=True); window.status = Mock(); window.buttons = []
            window.messages = queue.Queue(); window.failed = Mock()
            verified = snapshot(); verified["library"]["records"][0].update(lastChapter="9", updatedAt=3)
            for authoritative, expected in ((snapshot(), 1), (verified, 0)):
                window.exchange_job = Mock(side_effect=[TimeoutError("lost response"), authoritative])
                def loaded(serial, current, drafts, warning, action):
                    drafts.reconcile(current); window.busy = False
                    self.assertIn("yanıtı kayboldu", warning)
                window.loaded = loaded
                window.launch("edit", payload={"changes": window.drafts.changes()})
                deadline = time.monotonic() + 5
                while window.messages.empty() and time.monotonic() < deadline: time.sleep(.01)
                self.assertFalse(window.messages.empty()); window.messages.get()()
                self.assertEqual(window.exchange_job.call_args_list[1].args[1], {"action": "read"})
                self.assertEqual(len(window.drafts.entries), expected)
                window.failed.assert_not_called()

    def test_backup_zip_and_secrets_and_paths(self):
        original = snapshot()
        self.assertEqual(read_backup(backup_zip(original)), original)
        original["keys"] = {"api": "never-export"}
        with self.assertRaises(ValueError): read_backup(json.dumps(original).encode())
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w") as z: z.writestr("../backup.json", "{}")
        with self.assertRaises(ValueError): read_backup(out.getvalue())

    def test_drafts_survive_refresh_and_only_verified_results_clear(self):
        with tempfile.TemporaryDirectory() as folder:
            drafts = Drafts(Path(folder) / "drafts.json")
            entry = snapshot()["library"]["records"][0]
            drafts.save(entry, {"lastChapter": "9"})
            drafts = Drafts(drafts.path)
            self.assertEqual(drafts.changes()[0]["expectedUpdatedAt"], 2)
            drafts.reconcile(snapshot())
            self.assertEqual(len(drafts.changes()), 1)
            changed = snapshot(); changed["library"]["records"][0].update(lastChapter="9", updatedAt=3)
            drafts.reconcile(changed)
            self.assertEqual(drafts.changes(), [])

    def test_ai_is_filtered_and_never_contains_chats(self):
        package = ai_package(snapshot(), "READING", "SERIES")
        data = json.loads(package["context.json"])
        self.assertEqual(len(data["series"]), 1)
        self.assertNotIn("Özel sohbet", str(package))
        self.assertEqual(json.loads(ai_package(snapshot(), "PLANNED", "SERIES")["context.json"])["series"], [])

    def test_invalid_backup_shapes_and_unselected_categories(self):
        for value in ([], "invalid", None):
            broken = snapshot(); broken["library"] = value
            with self.assertRaises(ValueError): read_backup(json.dumps(broken).encode())
        broken = snapshot(); broken["library"]["records"][0]["id"] = {"bad": True}
        with self.assertRaises(ValueError): read_backup(json.dumps(broken).encode())
        broken = snapshot(); broken["ai"] = {"baseUrl": "https://example.com", "model": "m", "apiKey": "bad-secret"}
        with self.assertRaises(ValueError): read_backup(json.dumps(broken).encode())
        original = snapshot(); original["library"]["categoryDefinitions"] = [{"name": "Private other category"}]
        self.assertEqual(json.loads(ai_package(original)["context.json"])["categoryDefinitions"], [])

    def test_rebase_is_explicit_and_keeps_desired_fields(self):
        with tempfile.TemporaryDirectory() as folder:
            drafts = Drafts(Path(folder) / "drafts.json")
            before = snapshot()["library"]["records"][0]
            drafts.save(before, {"lastChapter": "9"})
            updated = {**before, "notes": "Telefonda yeni not", "updatedAt": 3}
            drafts.rebase(updated)
            self.assertEqual(drafts.changes()[0]["expectedUpdatedAt"], 3)
            self.assertEqual(drafts.changes()[0]["fields"], {"lastChapter": "9"})

    def test_exchange_auth_and_single_claim(self):
        import urllib.request
        import urllib.error
        with Exchange("synthetic-test-key", port=0) as exchange:
            operation = exchange.begin({"action": "read"})
            url = f"http://127.0.0.1:{exchange.port}/job/{operation.id}"
            with self.assertRaises(urllib.error.HTTPError): urllib.request.urlopen(url)
            with self.assertRaises(urllib.error.HTTPError): urllib.request.urlopen(urllib.request.Request(url, headers={"Authorization": "Bearer synthetic-test-key"}))
            req = urllib.request.Request(url, headers={"Authorization": exchange.proof("GET", f"/job/{operation.id}")})
            with urllib.request.urlopen(req) as response: body = json.load(response)
            self.assertIn("signature", body)
            with self.assertRaises(urllib.error.HTTPError): urllib.request.urlopen(req)
            data = json.dumps({"ok": True, "snapshot": snapshot()}).encode()
            result_url = f"http://127.0.0.1:{exchange.port}/result/{operation.id}"
            wrong = urllib.request.Request(result_url, data=data, headers={"Authorization": req.headers["Authorization"]})
            with self.assertRaises(urllib.error.HTTPError): urllib.request.urlopen(wrong)
            self.assertFalse(operation.done.is_set())
            valid = urllib.request.Request(result_url, data=data, headers={"Authorization": exchange.proof("POST", f"/result/{operation.id}", data)})
            with urllib.request.urlopen(valid) as response: self.assertTrue(json.load(response)["received"])
            self.assertTrue(operation.done.is_set())
            with self.assertRaises(urllib.error.HTTPError): urllib.request.urlopen(valid)

    def test_request_proof_never_sends_key_and_is_bound_to_body(self):
        with Exchange("synthetic-test-key", port=0) as exchange:
            path = "/job/11111111-1111-4111-8111-111111111111"
            proof = exchange.proof("GET", path, b"")
            self.assertNotIn("synthetic-test-key", proof)
            self.assertNotEqual(proof, exchange.proof("POST", path, b"{}"))
            self.assertNotEqual(proof, exchange.proof("GET", path + "x", b""))

    def test_disk_failure_retains_drafts_and_ui_polling(self):
        with tempfile.TemporaryDirectory() as folder:
            drafts = Drafts(Path(folder) / "drafts.json"); original = snapshot()["library"]["records"][0]
            drafts.save(original, {"lastChapter": "9"})
            changed = snapshot(); changed["library"]["records"][0].update(lastChapter="9", updatedAt=3)
            with patch("core.atomic_write", side_effect=OSError("disk full")):
                with self.assertRaises(OSError): drafts.reconcile(changed)
            self.assertEqual(len(drafts.changes()), 1)
        from app import Window
        import queue, threading
        window = Window.__new__(Window); window.closed = threading.Event(); window.messages = queue.Queue(); window.root = Mock()
        window.instance = None; window.hidden = False; window.tray = None
        window.failed = Mock(); window.messages.put(lambda: (_ for _ in ()).throw(OSError("disk full")))
        window.drain()
        window.failed.assert_called_once(); window.root.after.assert_called_once()
        with tempfile.TemporaryDirectory() as folder:
            window = Window.__new__(Window); window.folder = Path(folder); window.stash = Mock(return_value=True)
            window.categories = Mock(); window.draw_list = Mock(); window.bind_form = Mock(); window.buttons = []; window.status = Mock()
            window.serial = None; window.ai_series = Mock(); window.ai_manual = Mock(); window.clear_ai_preview = Mock()
            window.ai_series.get_children.return_value = []
            drafts = Drafts(Path(folder) / "drafts.json"); drafts.save(snapshot()["library"]["records"][0], {"lastChapter": "9"})
            changed = snapshot(); changed["library"]["records"][0].update(lastChapter="9", updatedAt=3)
            with patch("core.atomic_write", side_effect=OSError("disk full")):
                window.loaded("fixture", changed, drafts, None, "read")
            self.assertFalse(window.busy); self.assertEqual(len(drafts.entries), 1)
            self.assertIn("kaydedilemedi", window.status.set.call_args.args[0])

    def test_cleanup_removes_only_owned_unchanged_reverse(self):
        from app import Window
        window = Window.__new__(Window); window.adb = Path("fixture-adb"); window.reverse_owned = {"owned", "changed"}
        def reply(adb, args):
            if args[-1] == "--list": return "UsbFfs tcp:8767 tcp:" + ("8767" if args[1] == "owned" else "9999")
            return ""
        with patch("app.run_adb", side_effect=reply) as run:
            window.cleanup_reverse()
            removed = [call.args[1] for call in run.call_args_list if "--remove" in call.args[1]]
            self.assertEqual(removed, [["-s", "owned", "reverse", "--remove", "tcp:8767"]])


if __name__ == "__main__": unittest.main()
