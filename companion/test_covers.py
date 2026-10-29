import base64
import copy
import tempfile
import unittest
import io
import socket
from pathlib import Path
from unittest.mock import patch
from test_companion import snapshot
from covers import CoverSearch, CoverDrafts, allowed_url, source_page, manual_cover, clipboard_image


class CoverTests(unittest.TestCase):
    def test_manual_cover_is_local_and_uses_separate_transfer_payload(self):
        row = snapshot()['library']['records'][0]
        with patch('covers.convert_image', return_value=(b'png', b'jpeg')):
            result = manual_cover(row, b'input')
        self.assertTrue(result['manual']); self.assertTrue(result['replaceExisting'])
        self.assertTrue(result['imageUrl'].startswith('https://manhwa-takip.invalid/local-cover/'))
        with tempfile.TemporaryDirectory() as folder:
            drafts = CoverDrafts(Path(folder) / 'covers.json'); drafts.add([result])
            self.assertEqual(drafts.changes(), [])
            self.assertEqual(set(drafts.manual_changes()[0]), {'id', 'expectedUpdatedAt', 'jpeg'})
        for data in (b'', b'x' * (5 * 1024 * 1024 + 1)):
            with self.assertRaises(ValueError): manual_cover(row, data)

    def test_clipboard_image_reader_uses_sta_and_rejects_missing_image(self):
        import subprocess
        with patch('covers.subprocess.run', return_value=subprocess.CompletedProcess([], 0, base64.b64encode(b'png'), b'')) as run:
            self.assertEqual(clipboard_image(), b'png')
        self.assertIn('-STA', run.call_args.args[0])
        with patch('covers.subprocess.run', return_value=subprocess.CompletedProcess([], 1, b'', b'')):
            with self.assertRaises(ValueError): clipboard_image()

    def test_source_catalog_keeps_real_series_cards_and_serialized_titles_only(self):
        body = b'<a class="wl-card" href="/webtoon/test"><img alt="Test &amp; Seri"></a><a class="series-card" href="https://evil.invalid/x"><img alt="Test"></a>data:{series:[{migrate_id:1,name:"Test Seri",slug:"test"}],totalCount:1}'
        search = CoverSearch()
        with patch.object(search, 'read', return_value=body): candidates = search.source_candidates('Test')
        self.assertIn({'titles': ['Test & Seri'], 'page': 'https://trmanga.com/webtoon/test'}, candidates)
        self.assertIn({'titles': ['Test Seri'], 'page': 'https://eldermanga.com/manga/test'}, candidates)
        self.assertFalse(any('evil.invalid' in item['page'] for item in candidates))

    def test_alternative_skips_previous_source_and_duplicate_image(self):
        search = CoverSearch(); row = snapshot()['library']['records'][0]
        row['url'] = 'https://eldermanga.com/manga/test'
        previous = 'https://eldermanga.com/old.jpg'
        with patch.object(search, 'source_candidates', return_value=[
                {'titles': [row['title']], 'page': row['url']},
                {'titles': [row['title']], 'page': 'https://okutoon.com/test'}]), \
             patch.object(search, 'read', return_value=b'<meta property="og:image" content="https://okutoon.com/new.jpg">') as read, \
             patch('covers.convert_image', return_value=(b'png', b'jpeg')):
            result = search.find(row, exclude={row['url'], previous})
        self.assertEqual(result['imageUrl'], 'https://okutoon.com/new.jpg')
        self.assertNotIn(row['url'], [call.args[0] for call in read.call_args_list])

    def test_replacement_flag_survives_pending_drafts(self):
        with tempfile.TemporaryDirectory() as folder:
            drafts = CoverDrafts(Path(folder) / 'covers.json')
            drafts.add([dict(id='fixture', expectedUpdatedAt=1, imageUrl='https://okutoon.com/new.jpg', jpeg='jpeg', replaceExisting=True)])
            self.assertTrue(drafts.changes()[0]['replaceExisting'])

    def test_url_and_redirect_boundaries(self):
        for url in ('http://golgebahcesi.com/manga/a', 'https://localhost/a',
                    'https://api.mangadex.org.evil.test/manga', 'https://u:p@golgebahcesi.com/a',
                    'https://golgebahcesi.com:8765/a', 'https://127.0.0.1/a'):
            with self.assertRaises(ValueError): allowed_url(url)
        self.assertEqual(source_page('https://eldermanga.com/manga/a/chapter-8?x=1'), 'https://eldermanga.com/manga/a')

    def test_search_uses_real_candidate_not_ai_invented_url(self):
        search = CoverSearch()
        row = snapshot()['library']['records'][0]
        candidate = {'id': row['id'], 'attributes': {'title': {'en': row['title']}, 'altTitles': []},
                     'relationships': [{'type': 'cover_art', 'attributes': {'fileName': 'real.jpg'}}]}
        with patch.object(search, 'json', return_value={'data': [candidate]}), patch.object(search, 'image', return_value=(b'png', b'jpeg')):
            result = search.find(row)
        self.assertEqual(result['imageUrl'], f"https://uploads.mangadex.org/covers/{row['id']}/real.jpg.256.jpg")
        self.assertEqual(result['match'], 'Ad eşleşmesi')

    def test_ai_only_selects_existing_candidate_and_receives_no_private_fields(self):
        search = CoverSearch(ai=True)
        row = snapshot()['library']['records'][0]
        candidates = [{'id': row['id'], 'titles': ['Other name'], 'imageUrl': 'https://uploads.mangadex.org/covers/a/b.jpg'}]
        with patch.object(search, 'json', return_value={'choices': [{'message': {'content': '{"id":"invented"}'}}]}) as call:
            self.assertIsNone(search.choose(row, candidates))
        sent = call.call_args.kwargs['data']
        self.assertNotIn('notes', sent.decode()); self.assertNotIn('Kalsın', sent.decode())

    def test_offline_drafts_survive_conflict_and_clear_only_after_verified_cover(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'covers.json'; drafts = CoverDrafts(path)
            row = snapshot()['library']['records'][0]
            proposal = {'id': row['id'], 'expectedUpdatedAt': row['updatedAt'], 'title': row['title'],
                        'imageUrl': 'https://uploads.mangadex.org/covers/a/b.jpg',
                        'jpeg': base64.b64encode(b'jpeg').decode(), 'png': base64.b64encode(b'png').decode(), 'match': 'Ad eşleşmesi'}
            drafts.add([proposal]); drafts = CoverDrafts(path)
            drafts.reconcile(snapshot()); self.assertEqual(len(drafts.entries), 1)
            current = copy.deepcopy(snapshot()); current['library']['records'][0]['updatedAt'] += 1
            current['library']['records'][0]['local']['coverUrl'] = proposal['imageUrl']
            drafts.reconcile(current); self.assertEqual(len(drafts.entries), 1)
            current['covers'] = [{'seriesId': row['id'], 'jpeg': base64.b64encode(b'verified').decode()}]
            drafts.reconcile(current); self.assertEqual(drafts.entries, {})

    def test_failed_save_leaves_previous_pending_covers(self):
        with tempfile.TemporaryDirectory() as folder:
            drafts = CoverDrafts(Path(folder) / 'covers.json')
            with patch('covers.atomic_write', side_effect=OSError('disk full')):
                with self.assertRaises(OSError): drafts.add([{'id': 'fixture'}])
            self.assertEqual(drafts.entries, {})


class PublicWebTests(unittest.TestCase):
    def client(self, resolve=None, transport=None):
        from covers import PublicWebClient
        return PublicWebClient(resolve=resolve or (lambda host: ['8.8.8.8']), transport=transport or (lambda url, addresses: self.reply()))

    @staticmethod
    def reply(status=200, mime='text/html', body=b'ok', location=None, length=None):
        from covers import PublicResponse
        return PublicResponse(status, mime, length, location, io.BytesIO(body))

    def test_forbidden_mixed_dns_and_urls_never_connect(self):
        forbidden = ['0.0.0.0', '10.0.0.1', '100.64.0.1', '127.0.0.1', '169.254.1.1', '172.16.0.1', '192.168.1.1', '192.0.2.1', '198.18.0.1', '224.0.0.1', '240.0.0.1', '::1', '::', 'fc00::1', 'fe80::1', '2001:db8::1', '::ffff:127.0.0.1', '::ffff:8.8.8.8', '64:ff9b::808:808']
        opened = []
        for address in forbidden:
            with self.subTest(address=address), self.assertRaises(ValueError):
                self.client(resolve=lambda host: ['8.8.8.8', address], transport=lambda *args: opened.append(args)).get('https://public.example/', 100, False)
        self.assertEqual(opened, [])
        for url in ['http://public.example', 'https://u:p@public.example', 'https://public.example:444', 'https://[::ffff:8.8.8.8]/', 'https://127.0.0.1/']:
            with self.subTest(url=url), self.assertRaises(ValueError):
                self.client(resolve=lambda host: self.fail('DNS forbidden'), transport=lambda *args: self.fail('connect forbidden')).get(url, 100, False)

    def test_redirect_rebinding_pins_and_revalidates(self):
        answers = iter([['8.8.8.8'], ['127.0.0.1']]); opened = []
        def transport(url, addresses):
            opened.append(addresses)
            return self.reply(302, location='https://cdn.example/cover')
        with self.assertRaises(ValueError):
            self.client(resolve=lambda host: next(answers), transport=transport).get('https://public.example/', 100, True)
        self.assertEqual(opened, [('8.8.8.8',)])

    def test_mime_stream_length_timeout_and_four_requests(self):
        for reply in [self.reply(mime='text/plain'), self.reply(body=b'xxx'), self.reply(length=3)]:
            with self.assertRaises(ValueError): self.client(transport=lambda *args: reply).get('https://public.example/', 2, False)
        with self.assertRaises(ValueError): self.client().get('https://public.example/', 2, True)
        self.assertEqual(self.client(transport=lambda *args: self.reply(mime='image/avif')).get('https://public.example/', 2, True), b'ok')
        def timeout(*args): raise socket.timeout('fixture')
        with self.assertRaises(socket.timeout): self.client(transport=timeout).get('https://public.example/', 2, False)
        opened = []
        def redirect(*args):
            opened.append(args); return self.reply(302, location='/next')
        with self.assertRaises(ValueError): self.client(transport=redirect).get('https://public.example/', 2, False)
        self.assertEqual(len(opened), 4)

    def test_real_connection_uses_numeric_socket_and_original_tls_hostname(self):
        from covers import PinnedHTTPSConnection
        from unittest.mock import Mock
        raw, tls, context = Mock(), Mock(), Mock()
        context.wrap_socket.return_value = tls
        with patch('covers.ssl.create_default_context', return_value=context), patch('covers.socket.socket', return_value=raw), patch('covers.socket.getaddrinfo', side_effect=AssertionError('second DNS')):
            connection = PinnedHTTPSConnection('public.example', ('8.8.8.8',))
            connection.connect()
        raw.connect.assert_called_once_with(('8.8.8.8', 443))
        raw.settimeout.assert_called_once_with(10)
        context.wrap_socket.assert_called_once_with(raw, server_hostname='public.example')
        tls.settimeout.assert_called_once_with(15)
        # Production creates the verified default context, not an unverified custom context.
        import ssl
        context = ssl.create_default_context()
        self.assertTrue(context.check_hostname); self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)

    def test_ipv6_relative_redirect_closure_limits_and_json(self):
        replies = [self.reply(302, location='/cover'), self.reply(mime='image/webp; q=1')]
        bodies = [reply.body for reply in replies]
        opened = []
        def transport(url, addresses):
            opened.append(url); return replies.pop(0)
        client = self.client(resolve=lambda host: ['2606:4700:4700::1111'], transport=transport)
        self.assertEqual(client.get('https://public.example/start', 5 * 1024 * 1024, True), b'ok')
        self.assertEqual(opened, ['https://public.example/start', 'https://public.example/cover'])
        self.assertTrue(all(body.closed for body in bodies))
        for image, limit in [(False, 2 * 1024 * 1024 + 1), (True, 5 * 1024 * 1024 + 1)]:
            with self.assertRaises(ValueError): self.client().get('https://public.example/', limit, image)
        self.assertEqual(self.client(transport=lambda *args: self.reply(mime='application/json')).get('https://api.mangadex.org/', 100, False), b'ok')

    def test_five_sources_still_use_transport_and_generic_search_stays_closed(self):
        for host in ['golgebahcesi.com', 'eldermanga.com', 'uzaymanga.com', 'okutoon.com', 'trmanga.com']:
            with self.subTest(host=host):
                page = f'https://{host}/' + ('test' if host == 'okutoon.com' else 'webtoon/test' if host == 'trmanga.com' else 'manga/test')
                search = CoverSearch(); search.web = self.client(transport=lambda url, addresses: self.reply(mime='image/jpeg' if url.endswith('.jpg') else 'text/html', body=b"<meta property='og:image' content='/cover.jpg'>"))
                row = snapshot()['library']['records'][0]; row['url'] = page
                with patch('covers.convert_image', return_value=(b'png', b'jpeg')):
                    self.assertEqual(search.find(row)['imageUrl'], f'https://{host}/cover.jpg')
        search = CoverSearch(); opened = []
        search.web = self.client(transport=lambda *args: opened.append(args))
        with self.assertRaises(ValueError): search.read('https://generic.example/manga/test')
        self.assertEqual(opened, [])

    def test_unsafe_redirects_empty_dns_and_same_host_rebinding(self):
        for target in ['http://public.example/a', 'https://127.0.0.1/a', 'https://[::ffff:127.0.0.1]/a', 'https://u:p@public.example/a', 'https://public.example:444/a']:
            opened = []
            def transport(*args):
                opened.append(args); return self.reply(302, location=target)
            with self.subTest(target=target), self.assertRaises(ValueError): self.client(transport=transport).get('https://public.example/', 100, False)
            self.assertEqual(len(opened), 1)
        with self.assertRaises(ValueError):
            self.client(resolve=lambda host: [], transport=lambda *args: self.fail('empty DNS connected')).get('https://public.example/', 100, False)
        answers = iter([['8.8.8.8'], ['127.0.0.1']]); opened = []
        def rebound(*args):
            opened.append(args); return self.reply(302, location='/same-host')
        with self.assertRaises(ValueError): self.client(resolve=lambda host: next(answers), transport=rebound).get('https://public.example/', 100, False)
        self.assertEqual(len(opened), 1)


class MetadataTests(unittest.TestCase):
    def test_coversearch_metadata_public_cdn_redirect(self):
        page = 'https://trmanga.com/webtoon/test'
        for initial in ('https://cdn.trmanga.com/cover.jpg', 'https://other-public.example/cover.jpg'):
            opened = []
            def transport(url, addresses):
                opened.append(url)
                if url == page:
                    return PublicWebTests.reply(body=f'<h1>Mine</h1><meta property="og:image" content="{initial}">'.encode())
                if url == initial: return PublicWebTests.reply(302, location='https://new-public-cdn.example/final.jpg')
                return PublicWebTests.reply(mime='image/jpeg', body=b'fixture')
            search = CoverSearch(); search.web = PublicWebTests().client(transport=transport)
            with patch('covers.convert_image', return_value=(b'png', b'jpeg')) as convert:
                result = search.metadata(page)
            with self.subTest(initial=initial):
                self.assertIsNotNone(result.cover)
                self.assertEqual(opened, [page, initial, 'https://new-public-cdn.example/final.jpg'])
                convert.assert_called_once_with(b'fixture')

    def test_coversearch_metadata_private_redirect_never_connects(self):
        page = 'https://trmanga.com/webtoon/test'
        for target in ('https://127.0.0.1/cover.jpg', 'https://private.example/cover.jpg'):
            opened = []
            def transport(url, addresses):
                opened.append(url)
                if url == page: return PublicWebTests.reply(body=b'<h1>Mine</h1><meta property="og:image" content="https://cdn.trmanga.com/cover.jpg">')
                return PublicWebTests.reply(302, location=target)
            search = CoverSearch(); search.web = PublicWebTests().client(
                resolve=lambda host: ['10.0.0.1'] if host == 'private.example' else ['8.8.8.8'], transport=transport)
            with patch('covers.convert_image') as convert:
                result = search.metadata(page)
            self.assertEqual(result.title, 'Mine'); self.assertIsNone(result.cover)
            self.assertEqual(opened, [page, 'https://cdn.trmanga.com/cover.jpg'])
            convert.assert_not_called()

    def test_webp_normalizes_to_bounded_png_jpeg_and_manual_cover(self):
        from PIL import Image
        from covers import convert_image
        source = io.BytesIO(); Image.new('RGBA', (512, 768), (20, 40, 60, 120)).save(source, 'WEBP')
        png, jpeg = convert_image(source.getvalue())
        for data, fmt in ((png, 'PNG'), (jpeg, 'JPEG')):
            with Image.open(io.BytesIO(data)) as image:
                self.assertEqual(image.format, fmt); self.assertEqual(image.size, (171, 256)); image.load()
            self.assertLess(len(data), 5 * 1024 * 1024)
        result = manual_cover(snapshot()['library']['records'][0], source.getvalue())
        self.assertTrue(result['manual']); self.assertTrue(base64.b64decode(result['jpeg']).startswith(b'\xff\xd8'))

    def test_webp_oversized_and_corrupt_rejected(self):
        from PIL import Image
        from covers import convert_image
        source = io.BytesIO(); Image.new('RGB', (4001, 4000)).save(source, 'WEBP')
        for data in (source.getvalue(), b'RIFF\x20\x00\x00\x00WEBPbroken'):
            with self.subTest(length=len(data)), self.assertRaises(ValueError): convert_image(data)

    def fixture(self, name):
        return (Path(__file__).parent / 'fixtures' / 'metadata' / (name + '.html')).read_text(encoding='utf-8')

    def test_structured_entities_precedence_and_generic_path(self):
        from covers import parse_metadata
        result = parse_metadata(self.fixture('structured'), 'https://public.example/books/not-title')
        self.assertEqual(result.title, 'Kılıç & Gölge İ'); self.assertFalse(result.inferredTitle)
        self.assertEqual(result.images, ['https://cdn.example/cover.jpg'])
        result = parse_metadata('', 'https://public.example/books/a-real-title')
        self.assertEqual(result.seriesUrl, 'https://public.example/books/a-real-title')
        self.assertEqual(result.title, 'a real title'); self.assertTrue(result.inferredTitle)

    def test_parent_heading_lazy_logo_and_block(self):
        from covers import parse_metadata
        result = parse_metadata(self.fixture('chapter'), 'https://public.example/books/kilic/12')
        self.assertEqual(result.seriesUrl, 'https://public.example/books/kilic'); self.assertEqual(result.title, 'Kılıç')
        result = parse_metadata(self.fixture('lazy'), 'https://public.example/books/golge')
        self.assertEqual(result.title, 'Gölge & Işık')
        self.assertEqual(result.images, ['https://public.example/covers/golge.jpg'])
        self.assertEqual(parse_metadata('<h1>Türkçe <span>İsim</span></h1><title>Wrong</title>', 'https://public.example/a').title, 'Türkçe İsim')
        with self.assertRaises(ValueError): parse_metadata(self.fixture('blocked'), 'https://public.example/a')

    def test_fetcher_uses_pinned_client_and_retains_title_on_bad_cover(self):
        from covers import SeriesMetadataFetcher
        opened = []
        def transport(url, addresses):
            opened.append(url)
            return PublicWebTests.reply(mime='image/jpeg' if 'cdn.example' in url else 'text/html',
                body=b'bad' if 'cdn.example' in url else self.fixture('structured').encode())
        client = PublicWebTests().client(transport=transport)
        with patch('covers.convert_image', side_effect=ValueError('decode')):
            result = SeriesMetadataFetcher(client).fetch('https://public.example/books/test')
        self.assertEqual(result.title, 'Kılıç & Gölge İ'); self.assertIsNone(result.cover)
        self.assertEqual(opened, ['https://public.example/books/test', 'https://cdn.example/cover.jpg'])

    def test_named_paths_generic_paths_and_unrelated_thumbnail(self):
        from covers import metadata_start, parse_metadata
        self.assertEqual(metadata_start('https://mangawow.org/manga/my-wife-is-a-demon-queen/bolum-1/'), 'https://mangawow.org/manga/my-wife-is-a-demon-queen/')
        self.assertEqual(metadata_start('https://webtoonhatti.club/webtoon/i-am-the-fated-villain/bolum-284/'), 'https://webtoonhatti.club/webtoon/i-am-the-fated-villain/')
        self.assertEqual(metadata_start('https://other.example/manga/a/bolum-1'), 'https://other.example/manga/a/bolum-1')
        self.assertEqual(parse_metadata("<meta property='og:title' content='My Wife is a Demon Queen - MangaWOW'>", 'https://mangawow.org/manga/a/').title, 'My Wife is a Demon Queen')
        self.assertEqual(parse_metadata("<h1>Mine</h1><meta property='og:image' content='/og-default.png'><img class='thumbnail' src='/other.jpg' alt='Other'>", 'https://public.example/books/mine').images, [])

    def test_redirect_relative_cover_uses_final_page(self):
        from covers import SeriesMetadataFetcher
        opened = []
        def transport(url, addresses):
            opened.append(url)
            if url.endswith('/old'): return PublicWebTests.reply(302, location='/books/new/')
            if url.endswith('/books/new/'): return PublicWebTests.reply(body=b"<h1>Mine</h1><meta property='og:image' content='cover.jpg'>")
            return PublicWebTests.reply(mime='image/jpeg')
        with patch('covers.convert_image', return_value=(b'png', b'jpeg')):
            result = SeriesMetadataFetcher(PublicWebTests().client(transport=transport)).fetch('https://public.example/old')
        self.assertEqual(result.cover['imageUrl'], 'https://public.example/books/new/cover.jpg')
        self.assertEqual(result.seriesUrl, 'https://public.example/books/new/')
        self.assertEqual(len(opened), 3)

    def test_real_decode_byte_pixel_and_broken_image_bounds(self):
        from covers import convert_image
        import struct, zlib
        def chunk(kind, value): return struct.pack('!I', len(value)) + kind + value + struct.pack('!I', zlib.crc32(kind + value))
        def png(w, h):
            return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b'\0\xff\0\0')) + chunk(b'IEND', b'')
        normalized_png, normalized_jpeg = convert_image(png(1, 1))
        self.assertTrue(normalized_png.startswith(b'\x89PNG'))
        self.assertTrue(normalized_jpeg.startswith(b'\xff\xd8'))
        self.assertTrue(convert_image(normalized_jpeg)[1].startswith(b'\xff\xd8'))
        for data in (b'broken', b'x' * (5 * 1024 * 1024 + 1), png(100000, 100000)):
            with self.assertRaises(ValueError): convert_image(data)

    def test_object_cover_recommendation_is_not_series_cover_and_oversized_bytes_never_decode(self):
        from covers import parse_metadata, convert_image
        body = "<h1>Mine</h1><img class='object-cover' src='/other.jpg' alt='Other'><img class='thumbnail' src='/another.jpg' alt='Another'>"
        self.assertEqual(parse_metadata(body, 'https://public.example/books/mine').images, [])
        with patch('covers.subprocess.run') as run:
            for data in (b'', b'x' * (5 * 1024 * 1024 + 1)):
                with self.assertRaises(ValueError): convert_image(data)
            run.assert_not_called()

if __name__ == '__main__': unittest.main()
