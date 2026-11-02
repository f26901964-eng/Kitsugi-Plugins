#!/usr/bin/env python3
"""Dizipal **oynatıcı zinciri** probu (bölüm sayfası → şifre çözme → m3u8).

Ölçülen sözleşme (03.11.2026):

1. Bölüm sayfası (`/bolum/<slug>-<s>x<e>`) RSC akışında `player` nesnesi taşır:

   ```json
   "player": {"kind": "episode", "contentId": 400473,
              "sources": [{"label": "PUB+ · Altyazısız", "quality": "1080P", ...}],
              "enc": {"ciphertext": "<b64>", "iv": "<hex32>", "salt": "<hex512>"},
              "k": "<b64 200>"}
   ```

2. Şifre çözme (site tarafındaki `2vttktfk_mjfx.js` ile birebir):
   * `password = k` (UTF-8), `salt = bytes.fromhex(enc.salt)`
   * `PBKDF2-HMAC-SHA512(password, salt, iterations=999, dkLen=32)`
   * `AES-256-CBC`, `iv = bytes.fromhex(enc.iv)`, gövde = base64(ciphertext)
   * düz metin bir **oynatıcı adresi** (`//sn.dplayer82.site/iframe.php?v=...`)

3. Oynatıcı sayfası `Referer: https://dizipal1593.com/` ile açılır; m3u8 oradan
   çıkarılır (bu betik hangi biçimde çıktığını ölçer).

Kullanım:
    python3 tools/dizipal_player_probe.py                 # gassal 4x1
    python3 tools/dizipal_player_probe.py --path /bolum/gassal-1x1-c09
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.parse
from typing import Iterable

try:
    from tools import tizle_reach
except ImportError:  # pragma: no cover
    import tizle_reach  # type: ignore[no-redef]

OUT = os.environ.get("SITE_DUMP_DIR", "site-dump") + "/dizipal/player"
BASE = "https://dizipal1593.com"
HEADERS = {
    "Referer": BASE + "/",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "tr-TR,tr;q=0.9",
}

LOG: list[str] = []


def log(line: str = "") -> None:
    print(line, flush=True)
    LOG.append(line)


def save(name: str, data: str) -> str:
    path = os.path.join(OUT, name)
    os.makedirs(os.path.dirname(path) or OUT, exist_ok=True)
    with open(path, "w", encoding="utf-8", errors="replace") as handle:
        handle.write(data or "")
    return path


def aes_cbc_decrypt(key: bytes, iv: bytes, data: bytes) -> bytes:
    """openssl yoksa `cryptography`, o da yoksa saf Python ile AES-CBC çözer."""
    try:
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

        decryptor = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
        return decryptor.update(data) + decryptor.finalize()
    except Exception:
        pass
    with open("/tmp/dz-ct.bin", "wb") as handle:
        handle.write(data)
    result = subprocess.run(
        ["openssl", "enc", "-d", "-aes-256-cbc", "-K", key.hex(), "-iv", iv.hex(),
         "-in", "/tmp/dz-ct.bin"],
        capture_output=True,
    )
    return result.stdout


def unescape_flight(html: str) -> str:
    """RSC akışındaki kaçış dizilerini çözer; JSON parçacıklarını aranabilir yapar."""
    text = html.replace('\\"', '"').replace("\\\\/", "/").replace("\\/", "/")
    return text.replace("\\n", "\n").replace("\\\\", "\\")


def player_payload(html: str) -> dict | None:
    """Bölüm sayfasındaki `player` nesnesini çıkarır."""
    plain = unescape_flight(html)
    start = plain.find('"player":{')
    if start < 0:
        return None
    start += len('"player":')
    depth = 0
    for index in range(start, min(len(plain), start + 20000)):
        char = plain[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                blob = plain[start:index + 1]
                try:
                    return json.loads(blob)
                except Exception:
                    return None
    return None


def decrypt_player(player: dict) -> str | None:
    enc = player.get("enc") or {}
    password = player.get("k")
    if not (enc.get("ciphertext") and enc.get("iv") and enc.get("salt") and password):
        return None
    key = hashlib.pbkdf2_hmac(
        "sha512", password.encode(), bytes.fromhex(enc["salt"]), 999, 32
    )
    raw = base64.b64decode(enc["ciphertext"].replace("\\/", "/"))
    plain = aes_cbc_decrypt(key, bytes.fromhex(enc["iv"]), raw)
    try:
        return plain.decode("utf-8").strip()
    except UnicodeDecodeError:
        return None


def media_hits(html: str) -> list[str]:
    """Bir HTML'de geçen akış/oynatıcı izleri."""
    hits: list[str] = []
    patterns = (
        r'["\'](?:file|src|source|url)["\']\s*:\s*["\']([^"\']+\.m3u8[^"\']*)["\']',
        r'["\'](?:file|src|source|url)["\']\s*:\s*["\']([^"\']+\.mp4[^"\']*)["\']',
        r'https?://[^\s"\'<>\\]+\.m3u8[^\s"\'<>\\]*',
        r'<iframe[^>]+src=["\']([^"\']+)["\']',
        r'<source[^>]+src=["\']([^"\']+)["\']',
        r'atob\(\s*["\']([A-Za-z0-9+/=]{40,})["\']',
    )
    for pattern in patterns:
        for match in re.finditer(pattern, html or ""):
            value = match.group(1) if match.groups() else match.group(0)
            if value not in hits:
                hits.append(value)
    return hits


def probe_episode(path: str, via: str | None = None) -> None:
    url = BASE + path
    log(f"## {url}")
    page = tizle_reach.fetch(url, via=via, headers=HEADERS)
    log(f"  sayfa: via={page.via} HTTP {page.status} {len(page.text)}B")
    save(("episode" + path.replace("/", "_") + ".html")[:80], page.text)

    player = player_payload(page.text)
    if not player:
        log("  ! player nesnesi bulunamadı")
        return
    save("player-" + path.strip("/").replace("/", "_") + ".json",
         json.dumps(player, ensure_ascii=False, indent=2))
    log(f"  player: kind={player.get('kind')} contentId={player.get('contentId')} "
        f"dil={player.get('language')} kaynak={[s.get('label') for s in player.get('sources') or []]}")

    target = decrypt_player(player)
    log(f"  çözülen adres: {target!r}")
    if not target:
        return
    if target.startswith("//"):
        target = "https:" + target
    save("decrypted-url.txt", target + "\n")

    frame = tizle_reach.fetch(target, via=via, headers=HEADERS)
    log(f"  oynatıcı: via={frame.via} HTTP {frame.status} {len(frame.text)}B")
    save("frame.html", frame.text)
    hits = media_hits(frame.text)
    log(f"  oynatıcı izleri: {len(hits)}")
    for hit in hits[:25]:
        log(f"    * {hit}")

    # iç içe iframe varsa bir seviye daha in
    for hit in hits:
        if ".m3u8" in hit:
            continue
        nested = hit if hit.startswith("http") else urllib.parse.urljoin(target, hit)
        body = tizle_reach.fetch(nested, via=via, headers=HEADERS)
        log(f"    → iç çerçeve {nested}: via={body.via} HTTP {body.status} {len(body.text)}B")
        save("nested-" + re.sub(r"[^A-Za-z0-9]+", "-", nested)[-60:] + ".html", body.text)
        for inner in media_hits(body.text)[:10]:
            log(f"       * {inner}")


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Dizipal oynatıcı zinciri probu")
    parser.add_argument("--path", action="append",
                        default=None,
                        help="Bölüm/film yolu (tekrarlanabilir)")
    args = parser.parse_args(list(argv) if argv is not None else None)

    os.makedirs(OUT, exist_ok=True)
    paths = args.path or [
        "/bolum/gassal-4x1",
        "/bolum/gassal-1x1-c09",
        "/bolum/the-simpsons-38x5",
    ]
    for path in paths:
        probe_episode(path)
        log()
    save("SUMMARY.md", "\n".join(["# Oynatıcı probu", "", "```", *LOG, "```", ""]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
