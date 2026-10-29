import json
import threading
import unittest
import urllib.error
import urllib.request

from pairing_setup import make_fixture


class PairingSetupTests(unittest.TestCase):
    def test_loopback_host_origin_authentication_and_single_use(self):
        server, credential = make_fixture("fixture-only-not-a-real-pairing-key", "fixture-session", 0)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        url = f"http://127.0.0.1:{server.server_address[1]}/key"
        def request(headers):
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=3) as response:
                return json.load(response)
        try:
            for headers in ({}, {"Authorization": "Bearer wrong-session"},
                            {"Authorization": "Bearer fixture-session", "Origin": "https://example.invalid"},
                            {"Authorization": "Bearer fixture-session", "Host": "example.invalid"}):
                with self.assertRaises(urllib.error.HTTPError) as error:
                    request(headers)
                self.assertEqual(403, error.exception.code)
                self.assertIn("key", credential)
            self.assertEqual({"pairingKey": "fixture-only-not-a-real-pairing-key"}, request({"Authorization": "Bearer fixture-session"}))
            self.assertFalse(credential)
            with self.assertRaises(urllib.error.HTTPError) as error:
                request({"Authorization": "Bearer fixture-session"})
            self.assertEqual(409, error.exception.code)
        finally:
            credential.clear(); server.shutdown(); server.server_close(); thread.join(timeout=3)


if __name__ == "__main__":
    unittest.main()
