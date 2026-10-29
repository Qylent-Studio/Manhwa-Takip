"""Isolated, bounded WebP decoder. Dependencies are installed at setup, never here."""
import sys
import warnings
from pathlib import Path


def decode(source, png, jpeg):
    from PIL import Image
    Image.MAX_IMAGE_PIXELS = 16_000_000
    warnings.simplefilter('error', Image.DecompressionBombWarning)
    if not 0 < Path(source).stat().st_size <= 5 * 1024 * 1024:
        raise ValueError('Image bytes exceed limit')
    with Image.open(source, formats=('WEBP',)) as image:
        if image.width * image.height > 16_000_000:
            raise ValueError('Image pixels exceed limit')
        image.load()
        image.thumbnail((256, 256), Image.Resampling.LANCZOS)
        with image.convert('RGB') as normalized:
            normalized.save(png, 'PNG')
            normalized.save(jpeg, 'JPEG')


if __name__ == '__main__':
    try:
        decode(*sys.argv[1:])
    except Exception:
        sys.exit(1)
