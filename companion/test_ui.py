"""Native UI checks with synthetic data; no phone or personal files."""
import copy
import base64
import tempfile
import time
import tkinter as tk
from tkinter import font, ttk
import unittest
from pathlib import Path
from unittest.mock import patch
from app import Window
from core import Drafts
from test_companion import snapshot


class DesktopUiTests(unittest.TestCase):
    def test_theme_change_preserves_unsaved_editor_cards_and_selection(self):
        self.load(); app = self.window
        identity = app.snapshot['library']['records'][0]['id']
        app.open_editor(identity); self.root.update()
        app.vars['lastChapter'].set('not yet valid')
        app.texts['notes'].insert('end', '\nunsaved draft')
        card = app.cards[identity]['frame']; editor = app.editor
        content = app.texts['notes'].get('1.0', 'end-1c')
        app.theme_var.set('dark'); app.change_theme(); self.root.update()
        self.assertEqual(app.vars['lastChapter'].get(), 'not yet valid')
        self.assertEqual(app.texts['notes'].get('1.0', 'end-1c'), content)
        self.assertIs(app.cards[identity]['frame'], card)
        self.assertIs(app.editor, editor)
        self.assertEqual(app.selected, identity)
        self.assertEqual(app.preferences.theme, 'dark')
        from preferences import Preferences
        self.assertEqual(Preferences.load(Path(self.folder.name) / 'preferences.json').theme, 'dark')

    def test_theme_styles_classic_widgets_popups_and_control_states(self):
        import theme
        app = self.window
        popup = tk.Toplevel(self.root); menu = tk.Menu(popup, tearoff=False)
        entry = ttk.Combobox(popup, state='readonly'); entry.pack()
        text = tk.Text(popup); text.pack()
        app.theme_var.set('dark'); app.change_theme(); self.root.update()
        colors = theme.palette('dark', False); style = ttk.Style(self.root)
        self.assertEqual(text.cget('background'), colors['surface'])
        self.assertEqual(text.cget('insertbackground'), colors['text'])
        self.assertEqual(str(menu.cget('background')), colors['surface'])
        self.assertEqual(popup.cget('background'), colors['canvas'])
        self.assertEqual(style.lookup('TCombobox', 'foreground', ('readonly',)), colors['text'])
        self.assertEqual(style.lookup('TButton', 'foreground', ('disabled',)), colors['disabled'])
        popup.destroy()

    def test_theme_updates_already_created_combobox_dropdown_and_future_popup(self):
        import theme
        app = self.window
        dropdown = self.root.tk.call('ttk::combobox::PopdownWindow', app.theme_choice)
        app.theme_var.set('dark'); app.change_theme()
        colors = theme.palette('dark', False)
        self.assertEqual(str(self.root.tk.call(dropdown + '.f.l', 'cget', '-background')), colors['surface'])
        popup = tk.Toplevel(self.root); text = tk.Text(popup); text.pack()
        self.root.update()
        self.assertEqual(text.cget('foreground'), colors['text'])
        self.assertEqual(text.cget('background'), colors['surface'])
        self.assertEqual(popup.cget('background'), colors['canvas'])
        popup.destroy()

    def test_theme_save_failure_keeps_previous_palette_preference_and_draft(self):
        app = self.window; before = app.colors.copy(); previous = app.preferences.theme
        with patch.object(app.preferences, 'save', side_effect=OSError('fixture disk full')):
            app.theme_var.set('dark'); app.change_theme()
        self.assertEqual(app.colors, before)
        self.assertEqual(app.preferences.theme, previous)
        self.assertEqual(app.theme_var.get(), previous)
        self.assertIn('kaydedilemedi', app.status.get())

    def test_system_theme_refresh_does_not_rebuild_window(self):
        app = self.window; editor = app.editor
        app.theme_var.set('system')
        with patch('theme.system_dark', return_value=True): app.change_theme()
        self.assertEqual(self.root.cget('background'), '#171b22')
        with patch('theme.system_dark', return_value=False): app.refresh_system_theme()
        self.assertEqual(self.root.cget('background'), '#f4f6fa')
        self.assertIs(app.editor, editor)

    def test_ai_selection_preview_copy_and_files_use_one_projection(self):
        import json, zipfile, io
        self.load(); app = self.window
        self.assertTrue(app.ai_name.instate(['disabled']))
        self.assertTrue(app.ai_name_var.get())
        self.assertEqual(json.loads(app.package()['context.json'])['series'],
                         [{'title': 'Türkçe 雪'}, {'title': 'Başka seri'}])
        app.ai_fields['REASONS'].set(True)
        app.ai_fields['CHAPTER'].set(True)
        app.ai_series.selection_set(app.snapshot['library']['records'][0]['id'])
        app.ai_manual.set(True); app.ai_mode.set('Yeni öneri'); app.ai_status.set('Okunuyor')
        app.preview_ai(); self.root.update()
        expected = app.package()['context.json']
        self.assertEqual(app.ai_preview.get('1.0', 'end-1c'), expected.decode())
        app.copy_ai(); self.assertEqual(self.root.clipboard_get(), expected.decode())
        row = json.loads(expected)['series'][0]
        self.assertEqual(row['lastChapter'], '8'); self.assertNotIn('notes', row)
        with patch.object(app, 'write_file') as write:
            app.export_ai(False); self.assertEqual(write.call_args.args[0], expected)
            app.export_ai(True)
            with zipfile.ZipFile(io.BytesIO(write.call_args.args[0])) as archive:
                self.assertEqual(archive.read('context.json'), expected)
                for name in archive.namelist(): self.assertNotIn('Kalsın', archive.read(name).decode())
        app.nav_buttons[0].invoke(); app.nav_buttons[2].invoke(); self.root.update()
        self.assertEqual(app.package()['context.json'], expected)
        app.ai_status.set('Okunacak')
        self.assertEqual(json.loads(app.package()['context.json'])['series'], [])

    def test_editor_resize_keeps_card_actions_inside_canvas(self):
        self.load(); app = self.window
        self.root.geometry('1280x860'); self.root.update()
        identity = app.snapshot['library']['records'][0]['id']
        app.cards[identity]['edit'].invoke(); self.root.update()
        self.root.geometry('1000x680'); self.root.update()
        for card in app.cards.values():
            frame = card['frame']; button = card['edit']
            self.assertGreaterEqual(frame.winfo_width(), frame.winfo_reqwidth())
            self.assertLessEqual(button.winfo_rootx() + button.winfo_width(), app.card_canvas.winfo_rootx() + app.card_canvas.winfo_width())

    def test_failed_cover_decode_can_recover_on_refresh(self):
        self.load(); app = self.window; identity = app.snapshot['library']['records'][0]['id']
        image = tk.PhotoImage(width=20, height=30); image.put('#245dc1', to=(0, 0, 20, 30))
        path = Path(self.folder.name) / 'retry.png'; image.write(str(path))
        png = base64.b64encode(path.read_bytes()).decode()
        app.cover_drafts.entries[identity] = dict(png=png)
        key = (app.serial, identity, __import__('hashlib').sha256(png.encode()).hexdigest())
        app.photo_ready(key, None)
        app.draw_list(); self.root.update()
        self.assertTrue(app.cards[identity]['cover'].cget('image'), 'A previous decoding failure must not suppress refresh forever')

    def test_saved_covers_are_visible_without_selecting_and_survive_filtering(self):
        from covers import convert_image
        png = tk.PhotoImage(width=40, height=60)
        png.put('#005fb8', to=(0, 0, 40, 60))
        source = Path(self.folder.name) / 'fixture.png'; png.write(str(source))
        _, jpeg = convert_image(source.read_bytes())
        self.load(); app = self.window
        identities = [row['id'] for row in app.snapshot['library']['records']]
        app.snapshot['covers'] = [{'seriesId': identity, 'jpeg': base64.b64encode(jpeg).decode()} for identity in identities]
        app.draw_list()
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            self.root.update(); time.sleep(.02)
            if all(app.tree.item(identity, 'image') for identity in identities): break
        self.assertIsNone(app.selected)
        self.assertTrue(all(app.tree.item(identity, 'image') for identity in identities), 'All saved covers must load without clicking')
        self.assertEqual(set(app.cards), set(identities))
        self.assertTrue(all(card['cover'].cget('image') for card in app.cards.values()))
        app.search.set('TÜRKÇE'); self.root.update()
        self.assertEqual(set(app.cards), {identities[0]})
        self.assertTrue(app.cards[identities[0]]['cover'].cget('image'))
        app.search.set(''); self.root.update()
        self.assertEqual(len(app.cards), 2)

    def test_card_editor_opens_only_on_request_and_preserves_draft_on_close(self):
        drafts = self.load(); app = self.window
        identity = app.snapshot['library']['records'][0]['id']
        self.assertIsNone(app.selected)
        self.assertFalse(app.editor.winfo_ismapped())
        app.cards[identity]['edit'].invoke(); self.root.update()
        self.assertEqual(app.selected, identity)
        self.assertTrue(app.editor.winfo_ismapped())
        app.vars['lastChapter'].set('12')
        app.editor_close.invoke(); self.root.update()
        self.assertFalse(app.editor.winfo_ismapped())
        self.assertEqual(drafts.changes()[0]['fields']['lastChapter'], '12')
        self.assertIn('12', app.cards[identity]['chapter'].cget('text'))

    def test_manual_cover_queues_offline_then_automatically_uses_manual_route(self):
        self.load(); app = self.window; row = app.snapshot['library']['records'][0]
        result = dict(id=row['id'], title=row['title'], expectedUpdatedAt=row['updatedAt'],
                      imageUrl='https://manhwa-takip.invalid/local-cover/fixture.jpg', png='', jpeg='new', match='Elle seçilen kapak', manual=True, replaceExisting=True)
        with patch.object(app, 'launch') as launch:
            app.manual_cover_ready(row, result)
            launch.assert_not_called()
            self.assertIn(row['id'], app.cover_drafts.entries)
            app.last_connected = app.serial; app.exchange = object()
            app.transfer_manual_covers()
            self.assertEqual(launch.call_args.args[0], 'manual_covers')
            self.assertNotIn('imageUrl', launch.call_args.kwargs['payload']['changes'][0])

    def test_single_alternative_previews_without_replacing_saved_cover_and_ai_selection_works(self):
        self.load(); app = self.window
        row = app.snapshot['library']['records'][0]
        app.tree.selection_set(row['id']); self.root.update(); app.select()
        row['local']['coverUrl'] = 'https://uploads.mangadex.org/covers/old.jpg'
        result = dict(id=row['id'], title=row['title'], expectedUpdatedAt=row['updatedAt'],
                      imageUrl='https://okutoon.com/new.jpg', seriesUrl='https://okutoon.com/test', png='', jpeg='new', match='Türkçe kaynak')
        with patch('app.CoverSearch.find', return_value=result) as find:
            app.other_cover.invoke()
            deadline = time.monotonic() + 5
            while app.busy and time.monotonic() < deadline: self.root.update(); time.sleep(.02)
        self.assertFalse(app.busy)
        self.assertIn(row['local']['coverUrl'], find.call_args.kwargs['exclude'])
        self.assertTrue(app.cover_results[0]['replaceExisting'])
        self.assertFalse(app.cover_drafts.entries, 'Preview must not replace or approve anything')
        self.assertEqual(row['local']['coverUrl'], 'https://uploads.mangadex.org/covers/old.jpg')
        app.ai_cover_series.current(1)
        with patch.object(app, 'find_selected_cover') as select:
            app.find_ai_cover()
        self.assertEqual(select.call_args.kwargs['row']['id'], app.snapshot['library']['records'][1]['id'])

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        # Deterministic start: tests must exercise a real light -> dark transition,
        # regardless of the developer's personal Windows theme.
        (Path(self.folder.name) / 'preferences.json').write_text('{"theme":"light"}', encoding='utf-8')
        self.root = tk.Tk()
        self.addCleanup(self.folder.cleanup)
        self.addCleanup(self.close)
        self.window = Window(self.root, self.folder.name, demo=True)
        self.root.update()

    def close(self):
        self.window.closed.set()
        for timer in self.root.tk.call('after', 'info'): self.root.after_cancel(timer)
        self.root.destroy()

    def load(self):
        data = snapshot()
        second = copy.deepcopy(data['library']['records'][0])
        second.update(id='22222222-2222-4222-8222-222222222222', title='Başka seri', status='PLANNED')
        data['library']['records'].append(second)
        drafts = Drafts(Path(self.folder.name) / 'drafts.json')
        self.window.loaded('fixture', data, drafts, None, 'read')
        self.root.update()
        return drafts

    def test_sidebar_respects_unavailable_page_and_keeps_selection_in_sync(self):
        app = self.window
        self.assertEqual(len(app.nav_buttons), 5)
        self.assertEqual(app.tabs.tab(app.connections_page, 'text'), 'Bağlantılar')
        self.assertFalse(app.nav_buttons[3].instate(['disabled']))
        app.nav_buttons[3].invoke()
        self.assertEqual(app.tabs.index(app.tabs.select()), 3)
        self.load()
        for index, button in enumerate(app.nav_buttons):
            button.invoke(); self.root.update()
            self.assertEqual(app.tabs.index(app.tabs.select()), index)
            self.assertEqual(app.navigation.get(), index)
            self.assertTrue(button.instate(['selected']))
        app.tabs.select(0); self.root.update()
        self.assertEqual(app.navigation.get(), 0)

    def test_navigation_preserves_editor_and_filters_report_empty_results(self):
        drafts = self.load(); app = self.window
        identity = app.snapshot['library']['records'][0]['id']
        app.tree.selection_set(identity); self.root.update(); app.select()
        app.vars['lastChapter'].set('9,0')
        app.nav_buttons[1].invoke(); self.root.update()
        app.nav_buttons[0].invoke(); self.root.update()
        self.assertEqual(app.vars['lastChapter'].get(), '9,0')
        app.stash()
        self.assertEqual(drafts.changes()[0]['fields']['lastChapter'], '9')
        app.search.set('TÜRKÇE'); self.root.update()
        self.assertEqual(len(app.tree.get_children()), 1)
        self.assertIn('1 / 2', app.list_info.get())
        app.search.set('eşleşmeyen ad'); self.root.update()
        self.assertFalse(app.tree.get_children())
        self.assertIn('sonuç yok', app.list_info.get().lower())
        self.assertEqual(app.vars['lastChapter'].get(), '9,0')

    def test_minimum_window_keeps_actions_visible_and_navigation_keyboard_operable(self):
        self.load(); app = self.window
        self.root.geometry('1000x680'); self.root.update()
        app.nav_buttons[1].focus_force(); self.root.update()
        app.nav_buttons[1].event_generate('<KeyPress-space>'); self.root.update()
        self.assertEqual(app.navigation.get(), 1)
        def controls(widget):
            for child in widget.winfo_children():
                if child.winfo_class() == 'Canvas': continue
                yield child
                yield from controls(child)
        for button in app.nav_buttons:
            button.invoke(); self.root.update()
            for widget in controls(self.root):
                if not widget.winfo_ismapped() or widget.winfo_class() not in ('TButton', 'TRadiobutton', 'TCheckbutton'): continue
                self.assertGreaterEqual(widget.winfo_width(), widget.winfo_reqwidth(), widget.cget('text'))
                self.assertLessEqual(widget.winfo_rootx() + widget.winfo_width(), self.root.winfo_rootx() + self.root.winfo_width())
                self.assertLessEqual(widget.winfo_rooty() + widget.winfo_height(), self.root.winfo_rooty() + self.root.winfo_height())

    def test_page_heading_renders_larger_than_body_font(self):
        def descendants(widget):
            for child in widget.winfo_children():
                yield child
                yield from descendants(child)
        heading = next(widget for widget in descendants(self.root)
                       if isinstance(widget, ttk.Label) and widget.cget('style') == 'Title.TLabel')
        actual = heading.cget('font') or ttk.Style(self.root).lookup('Title.TLabel', 'font')
        self.assertGreaterEqual(font.Font(self.root, font=actual).cget('size'), 22)


if __name__ == '__main__': unittest.main()
