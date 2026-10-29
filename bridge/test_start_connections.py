import unittest
from unittest.mock import patch
import urllib.error
from start_connections import usb_target, needs_reverse, ensure_ai


class ShortcutSafetyTests(unittest.TestCase):
    def test_only_one_authorized_physical_usb_device_is_selected(self):
        self.assertEqual("PHONE", usb_target("List of devices attached\nemulator-5554\tdevice\nPHONE\tdevice\n"))
        # TEST-NET-2 synthetic network-device serial, never a live phone.
        for devices in ("PHONE\tunauthorized", "A\tdevice\nB\tdevice", "198.51.100.4:5555\tdevice"):
            with self.assertRaises(RuntimeError):
                usb_target(devices)

    def test_existing_usb_mapping_is_preserved_and_conflict_rejected(self):
        self.assertTrue(needs_reverse("UsbFfs tcp:8766 tcp:8766\n"))
        self.assertFalse(needs_reverse("UsbFfs tcp:8765 tcp:8765\n"))
        with self.assertRaises(RuntimeError):
            needs_reverse("UsbFfs tcp:8765 tcp:9999\n")

    def test_running_auth_required_ai_never_starts_second_server(self):
        with patch('start_connections.load_config', return_value={'ai_url': 'http://example.test/models'}), patch("start_connections.urllib.request.urlopen", side_effect=urllib.error.HTTPError("test", 401, "auth", {}, None)), patch("start_connections.run") as command:
            self.assertIn("hazir", ensure_ai())
            command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
