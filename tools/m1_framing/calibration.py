"""Generate a stationary control pair with known 1% insets on all four edges."""
import argparse
from pathlib import Path

import cv2
import numpy as np


def chart():
    # Unequal marks throughout the image, including within a few pixels of each edge.
    rng = np.random.default_rng(1611)
    img = np.full((800, 600), 208, np.uint8)
    for y in range(6, 800, 25):
        for x in range(7, 600, 24):
            dx, dy, r = rng.integers(-6, 7), rng.integers(-5, 6), rng.integers(2, 8)
            cv2.circle(img, (x + dx, y + dy), r, int(rng.integers(15, 130)), -1, cv2.LINE_AA)
            cv2.line(img, (x, y), (x + int(rng.integers(-10, 11)), y + 9), 76, 2, cv2.LINE_AA)
    return img


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="生成 600×800 校准图和四边各裁 1% 的对照图")
    ap.add_argument("directory", type=Path, help="用于保存两张 PNG 的新目录")
    output = ap.parse_args().directory
    try:
        output.mkdir(parents=True, exist_ok=False)
        img = chart()
        for name, pixels in (("preview.png", img), ("crop-1pct.png", img[8:-8, 6:-6])):
            if not cv2.imwrite(str(output / name), pixels):
                raise OSError(f"写入失败：{name}")
    except (OSError, cv2.error) as exc:
        ap.error(str(exc))
