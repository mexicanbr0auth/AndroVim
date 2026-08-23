#!/usr/bin/env python3
"""Generate legacy launcher icons for AndroVim without external dependencies.

Draws a green "V" chevron with a small red cursor block on a dark background
and writes PNGs at all standard mipmap densities.
"""

import os
import struct
import zlib

BG = (16, 20, 26)
FG = (127, 191, 127)  # #7FBF7F
CURSOR = (224, 108, 117)  # #E06C75

SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")


def dist_point_to_segment(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    t = max(0.0, min(1.0, (vx * wx + vy * wy) / (vx * vx + vy * vy + 1e-9)))
    dx, dy = px - (ax + t * vx), py - (ay + t * vy)
    return (dx * dx + dy * dy) ** 0.5


def render(size):
    img = [[BG] * size for _ in range(size)]
    half = size / 2.0
    thickness = size * 0.085
    # Chevron "V"
    segs = [((0.30, 0.36), (0.50, 0.66)), ((0.70, 0.36), (0.50, 0.66))]
    # Cursor block top-left
    cx0, cy0, cx1, cy1 = 0.27, 0.27, 0.37, 0.37
    scale = size
    for y in range(size):
        for x in range(size):
            nx, ny = (x + 0.5) / scale, (y + 0.5) / scale
            for (ax, ay), (bx, by) in segs:
                if dist_point_to_segment(nx, ny, ax, ay, bx, by) <= thickness:
                    img[y][x] = FG
            if cx0 <= nx <= cx1 and cy0 <= ny <= cy1:
                img[y][x] = CURSOR
    return img


def write_png(path, img):
    size = len(img)
    raw = b"".join(
        b"\x00" + bytes(v for pixel in row for v in pixel) for row in img
    )

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(png)


def main():
    for folder, px in SIZES.items():
        write_png(os.path.join(ROOT, folder, "ic_launcher.png"), render(px))
        write_png(os.path.join(ROOT, folder, "ic_launcher_round.png"), render(px))
        print(f"{folder}: {px}px ok")


if __name__ == "__main__":
    main()
