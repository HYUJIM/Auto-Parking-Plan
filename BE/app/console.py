"""콘솔 입출력을 UTF-8 로 맞춘다.

윈도우에서 출력이 파이프·파일로 넘어가면 기본 인코딩이 cp1252(영문)·cp949(한글)라서
한글이나 '—', '⑳' 같은 문자를 print 할 때 UnicodeEncodeError 가 난다. 모든 진입점에서 가장 먼저 호출한다.
"""
from __future__ import annotations

import sys


def ensure_utf8_stdio() -> None:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="backslashreplace")
            except (ValueError, OSError):
                pass
