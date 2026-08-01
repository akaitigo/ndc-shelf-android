#!/usr/bin/env python3
"""READMEの操作デモGIFを、DemoRecordingTestが記録したフレームから合成する。

実行手順:

    JAVA_HOME="$HOME/.local/share/mise/installs/java/temurin-17.0.20+8" \
      ./gradlew :app:testDebugUnitTest --tests '*DemoRecordingTest' \
        -Dndcshelf.recordDemo=true -Proborazzi.test.record=true
    python3 tools/build_demo_gif.py

フレームは実アプリのComposeをRobolectricで動かして撮影したもので、匿名
fixtureだけを含む。GIFはリポジトリサイズを抑えるため縮小・減色する。
"""

from __future__ import annotations

import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover - 実行環境の案内
    sys.exit("Pillow が必要です: pip install --user Pillow")

REPO_ROOT = Path(__file__).resolve().parent.parent
FRAME_DIRECTORY = REPO_ROOT / "app" / "build" / "demo-frames"
OUTPUT = REPO_ROOT / "docs" / "images" / "demo.gif"

# 端末幅411dp・420dpiの実寸から、READMEで見やすい幅へ縮小する。
TARGET_WIDTH = 320
# 各フレームの表示時間（ミリ秒）。最終フレームだけ長めに留めて先頭へ戻す。
FRAME_DURATION_MS = 1400
LAST_FRAME_DURATION_MS = 2200


def main() -> None:
    frames = sorted(FRAME_DIRECTORY.glob("frame-*.png"))
    if not frames:
        sys.exit(f"フレームがありません: {FRAME_DIRECTORY}")

    images: list[Image.Image] = []
    for path in frames:
        with Image.open(path) as source:
            image = source.convert("RGB")
            height = round(image.height * TARGET_WIDTH / image.width)
            resized = image.resize((TARGET_WIDTH, height), Image.LANCZOS)
            # 減色でファイルサイズを抑える。UIは平坦色が多く128色で十分。
            images.append(resized.quantize(colors=128, method=Image.MEDIANCUT))

    durations = [FRAME_DURATION_MS] * len(images)
    durations[-1] = LAST_FRAME_DURATION_MS

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    images[0].save(
        OUTPUT,
        save_all=True,
        append_images=images[1:],
        duration=durations,
        loop=0,
        optimize=True,
        disposal=2,
    )
    size_kib = OUTPUT.stat().st_size / 1024
    print(f"{OUTPUT.relative_to(REPO_ROOT)} を生成しました（{len(images)}フレーム・{size_kib:.0f} KiB）")


if __name__ == "__main__":
    main()
