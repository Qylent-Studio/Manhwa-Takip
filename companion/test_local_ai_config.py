"""Synthetic, offline tests of the private-only AI HTTP exception."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import covers


class LocalAiConfigTest(unittest.TestCase):
    def test_private_models_url_preserves_exact_base(self):
        with tempfile.TemporaryDirectory() as folder:
            config = Path(folder) / 'connections-private.json'
            self.assertEqual('', covers.local_ai_base(config))
            config.write_text(json.dumps({'ai_url': 'http://192.0.2.10:20128/v1/models'}), encoding='utf-8')
            self.assertEqual('http://192.0.2.10:20128/v1', covers.local_ai_base(config))
            for value in ('http://192.0.2.10/v1', 'http://key@192.0.2.10/v1/models',
                          'http://192.0.2.10:0/v1/models', 'http://192.0.2.10/v1?x=/models', ''):
                config.write_text(json.dumps({'ai_url': value}), encoding='utf-8')
                self.assertEqual('', covers.local_ai_base(config))

    def test_only_exact_configured_endpoint_can_reach_transport(self):
        base = 'http://192.0.2.10:20128/v1'
        search = covers.CoverSearch()
        with patch.object(covers, 'LOCAL_AI', base), patch.object(search.opener, 'open') as transport:
            transport.return_value.__enter__.return_value.read.return_value = b'{}'
            self.assertEqual(b'{}', search.read(base + '/chat/completions', ai=True))
            for url in ('http://192.0.2.11:20128/v1/chat/completions',
                        'http://192.0.2.10:20129/v1/chat/completions',
                        base + '/other', base + '/chat/completions?x=1'):
                with self.assertRaises(ValueError): search.read(url, ai=True)
            self.assertEqual(1, transport.call_count)
        with patch.object(covers, 'LOCAL_AI', ''), patch.object(search.opener, 'open') as transport:
            with self.assertRaises(ValueError): search.read('/chat/completions', ai=True)
            with self.assertRaises(ValueError): search.read(base + '/chat/completions', ai=True)
            transport.assert_not_called()


if __name__ == '__main__':
    unittest.main()
