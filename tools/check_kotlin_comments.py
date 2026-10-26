#!/usr/bin/env python3
"""Kotlin kaynaklarında **iç içe yorum** hatasını yakalar.

Kotlin blok yorumları iç içe geçebilir: bir KDoc içinde `/*` dizisi (ör.
`/api/v1/*`) ikinci bir yorum açar, KDoc'un `*/` kapanışı yalnızca içtekini
kapatır ve dosyanın geri kalanı yorum olur. Derleyici bunu "Unclosed comment"
diye bildirir; ilk hata satırları ise kafa karıştırıcı olur
("Unresolved reference …", "'x' overrides nothing").

Bu betik metin dizgilerini (ham/çift/tek tırnak) ayıkladıktan sonra blok yorum
derinliğini izler; sonda kapanmamış yorum varsa hata verir.

Kullanım: `python3 tools/check_kotlin_comments.py` (repo kökünden).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

RAW_STRING = re.compile(r'"""(?:.|\n)*?"""')
STRING = re.compile(r'"(?:[^"\\\n]|\\.)*"')
CHAR = re.compile(r"'(?:[^'\\\n]|\\.)*'")


def strip_literals(text: str) -> str:
    text = RAW_STRING.sub('""', text)
    text = STRING.sub('""', text)
    text = CHAR.sub("''", text)
    return text


def scan(path: Path) -> list[str]:
    raw = path.read_text(encoding="utf-8")
    text = strip_literals(raw)
    depth = 0
    starts: list[int] = []
    line_no = 1
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "\n":
            line_no += 1
            i += 1
            continue
        if depth == 0 and ch == "/" and nxt == "/":
            j = text.find("\n", i)
            if j < 0:
                break
            i = j
            continue
        if ch == "/" and nxt == "*":
            depth += 1
            if depth == 1:
                starts = [line_no]
            i += 2
            continue
        if ch == "*" and nxt == "/":
            depth -= 1
            if depth < 0:
                return [f"{path.relative_to(ROOT)}:{line_no}: fazladan '*/'"]
            if depth == 0:
                starts = []
            i += 2
            continue
        i += 1

    if depth != 0:
        where = starts[0] if starts else "?"
        return [f"{path.relative_to(ROOT)}: kapanmamış blok yorum (açılış satırı: {where})"]
    return []


def main() -> int:
    problems: list[str] = []
    for path in sorted(ROOT.rglob("*.kt")):
        if any(part in {"build", ".git"} for part in path.parts):
            continue
        problems.extend(scan(path))
    if problems:
        print("Kotlin yorum denetimi BAŞARISIZ:")
        for problem in problems:
            print("  -", problem)
        return 1
    print("Kotlin yorum denetimi temiz.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
