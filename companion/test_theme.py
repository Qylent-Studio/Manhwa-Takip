import unittest
from unittest.mock import patch


def contrast(a, b):
    def luminance(color):
        rgb = [int(color[i:i+2], 16) / 255 for i in (1, 3, 5)]
        rgb = [x / 12.92 if x <= .04045 else ((x + .055) / 1.055) ** 2.4 for x in rgb]
        return sum(x * weight for x, weight in zip(rgb, (.2126, .7152, .0722)))
    values = sorted([luminance(a), luminance(b)])
    return (values[1] + .05) / (values[0] + .05)


class ThemeTests(unittest.TestCase):
    def test_modes_and_existing_color_contract(self):
        import theme
        keys = {'canvas', 'surface', 'text', 'muted', 'border', 'accent', 'hover', 'pressed', 'selection', 'disabled'}
        self.assertTrue(keys <= theme.palette('light', False).keys())
        self.assertEqual(theme.palette('system', True), theme.palette('dark', False))
        self.assertEqual(theme.palette('system', False), theme.palette('light', True))
        self.assertEqual(theme.palette('invalid', True), theme.palette('dark', False))
        colors = theme.palette('dark', False); colors['text'] = 'changed'
        self.assertNotEqual(theme.palette('dark', False)['text'], 'changed')

    def test_readability_for_normal_selected_disabled_and_accent_text(self):
        import theme
        for mode in ('light', 'dark'):
            colors = theme.palette(mode, False)
            for fg, bg in (('text', 'surface'), ('muted', 'surface'), ('text', 'selection'), ('disabled', 'canvas'), ('on_accent', 'accent')):
                with self.subTest(mode=mode, fg=fg):
                    self.assertGreaterEqual(contrast(colors[fg], colors[bg]), 4.5)

    def test_system_detection_fails_safe_without_writing_registry(self):
        import theme
        with patch('winreg.OpenKey', side_effect=OSError('fixture unavailable')):
            self.assertFalse(theme.system_dark())
