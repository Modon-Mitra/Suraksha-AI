#!/usr/bin/env python3
"""
Run this once to generate placeholder launcher icons so the project compiles.
Replace with your real Suraksha AI icon later.
"""
import os, struct, zlib

def make_png(size, r, g, b):
    """Create a minimal solid-color PNG."""
    def chunk(name, data):
        c = struct.pack('>I', len(data)) + name + data
        return c + struct.pack('>I', zlib.crc32(c[4:]) & 0xFFFFFFFF)

    raw = b''
    for _ in range(size):
        row = b'\x00' + bytes([r, g, b, 255] * size)
        raw += row
    compressed = zlib.compress(raw)

    ihdr = struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0)
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', ihdr)
            + chunk(b'IDAT', compressed)
            + chunk(b'IEND', b''))

sizes = {
    'mipmap-mdpi':    48,
    'mipmap-hdpi':    72,
    'mipmap-xhdpi':   96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi':192,
}

base = os.path.join(os.path.dirname(__file__), 'app', 'src', 'main', 'res')
for folder, px in sizes.items():
    path = os.path.join(base, folder)
    os.makedirs(path, exist_ok=True)
    for name in ('ic_launcher.png', 'ic_launcher_round.png'):
        with open(os.path.join(path, name), 'wb') as f:
            # Deep navy blue icon
            f.write(make_png(px, 5, 13, 26))
    print(f'✓  {folder}/{px}px icons created')

print('\nDone! Replace these placeholder PNGs with your real app icon.')
