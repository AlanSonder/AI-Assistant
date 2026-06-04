#!/usr/bin/env python3
"""验证已安装的 Python 包"""

import sys
print(f"Python: {sys.version}")
print()

packages = [
    ("numpy", "numpy"),
    ("pyyaml", "yaml"),
    ("openai", "openai"),
    ("requests", "requests"),
    ("pystray", "pystray"),
    ("Pillow", "PIL"),
    ("mss", "mss"),
    ("keyboard", "keyboard"),
    ("pywin32", "win32api"),
    ("pyinstaller", "PyInstaller"),
    ("torch", "torch"),
    ("silero-vad", "silero_vad"),
    ("faster-whisper", "faster_whisper"),
    ("paddlepaddle", "paddle"),
    ("paddleocr", "paddleocr"),
    ("pytesseract", "pytesseract"),
    ("openwakeword", "openwakeword"),
    ("sounddevice", "sounddevice"),
    ("soundfile", "soundfile"),
    ("edge-tts", "edge_tts"),
]

ok = 0
fail = 0
for name, module in packages:
    try:
        mod = __import__(module)
        version = getattr(mod, "__version__", "OK")
        print(f"  [OK] {name:25s} {version}")
        ok += 1
    except Exception as e:
        print(f"  [FAIL] {name:25s} {e}")
        fail += 1

print(f"\nResult: {ok} OK, {fail} FAIL")
