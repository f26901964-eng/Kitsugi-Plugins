#!/usr/bin/env python3
"""TrAnimeIzle **arşiv kazıcısı** — site canlı erişimi engellediğinde gerçek HTML'i
Wayback Machine snapshot'larından çıkarır.

Neden ayrı araç?
    `tools/tizle_dump.py` canlı siteye POST atar (fansubSources/sourcePlayer).
    Site veri merkezi IP'lerine Cloudflare **Error 1005** döndürdüğü için o
    istekler yalnızca ev/cihaz bağlantısından çalışır. Bu araç ise **arşivden**
    okuyabildiğimiz her şeyi toplar:

      · ana sayfa + liste + arama snapshot'ları
      · bölüm sayfası snapshot'ı  → `input#EpisodeId`, `div.fansubSelector[data-fid]`
      · sayfanın yüklediği **JS paketleri** (sitenin kendi sözleşmesi: hangi uçlar,
        hangi oynatıcı sarmalları)
      · oynatıcı hostlarının snapshot'ları (varsa)

Kullanım:
    python3 tools/tizle_archive.py --out site-dump/arsiv
    python3 tools/tizle_archive.py --out ... --url <bölüm-url> --sleep 4

Notlar:
    · Wayback çok sık **429** verir: istekler arasında `--sleep` kadar beklenir
      (varsayılan 4 sn) ve 429'da artan beklemeyle 3 kez tekrar denenir.
    · Snapshot zaman damgaları `--window` yıl geriye kadar taranır; en yeniden
      eskiye doğru denenir, ilk **içerikli** snapshot kullanılır.
"""

from __future__ import annotations

import argparse
import gzip
import json
import re
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Iterable

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) "
    "Gecko/20100101 Firefox/133.0"
)
SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE
MAX_BYTES = 16 * 1024 * 1024
SITE_MARKERS = ("flx-block", "data-href", "fansubSelector", "animeDetail-items", "playlist-title")
BLOCK_MARKERS = ("you have been blocked", "error 1005", "attention required", "just a moment")

DOMAINS = [
    "https://www.tranimeizle.io",
    "https://www.tranimeizle.live",
    "https://www.tranimeizle.top",
]


def log(line: str = "") -> None:
    print(line, flush=True)


def _get(url: str, timeout: int = 45) -> tuple[int, str]:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Encoding": "gzip",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout, context=SSL_CTX) as response:
            raw = response.read(MAX_BYTES)
            if response.headers.get("Content-Encoding") == "gzip":
                try:
                    raw = gzip.decompress(raw)
                except Exception:
                    pass
            return response.status, raw.decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        try:
            raw = error.read(MAX_BYTES)
        except Exception:
            raw = b""
        return error.code, raw.decode("utf-8", "replace")
    except Exception:
        return 0, ""


def _get_with_backoff(url: str, attempts: int = 3, base_delay: int = 20) -> tuple[int, str]:
    delay = base_delay
    status, body = 0, ""
    for attempt in range(attempts):
        status, body = _get(url)
        if status in (200, 404):
            return status, body
        if attempt < attempts - 1:
            log(f"      … HTTP {status}, {delay}s bekleyip tekrar")
            time.sleep(delay)
            delay = min(delay * 2, 90)
    return status, body


def has_content(html: str) -> bool:
    low = (html or "")[:8000].lower()
    if any(marker in low for marker in BLOCK_MARKERS):
        return False
    return any(marker in (html or "") for marker in SITE_MARKERS)


def cdx_snapshots(url: str, limit: int = 6, from_year: str = "2025") -> list[str]:
    """Zaman damgaları — en yeniden eskiye."""
    query = (
        "https://web.archive.org/cdx/search/cdx?output=json&filter=statuscode:200"
        f"&limit=-{limit}&from={from_year}&url=" + urllib.parse.quote(url, safe="")
    )
    status, body = _get_with_backoff(query, attempts=3, base_delay=15)
    if status != 200 or not body.strip().startswith("["):
        return []
    try:
        rows = json.loads(body)
    except Exception:
        return []
    stamps = []
    for row in rows[1:]:
        if len(row) >= 2 and row[1]:
            stamps.append(str(row[1]))
    return list(reversed(stamps))


def fetch_snapshot(url: str, outdir: str, name: str, sleep: float) -> tuple[str, str]:
    """URL'in en yeni **içerikli** snapshot'ını indirir. (timestamp, html) döner."""
    stamps = cdx_snapshots(url)
    if not stamps:
        log(f"    · snapshot yok: {url}")
        return "", ""
    for stamp in stamps[:3]:
        time.sleep(sleep)
        status, body = _get_with_backoff(f"https://web.archive.org/web/{stamp}id_/{url}")
        time.sleep(sleep)
        if status == 200 and body:
            content = has_content(body)
            log(f"    · {name} @{stamp} → HTTP 200, {len(body)}B"
                + (" ✔ site içeriği" if content else " (içerik işareti yok)"))
            path = f"{outdir}/{name}__{stamp}.html"
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(body)
            if content:
                return stamp, body
        else:
            log(f"    · {name} @{stamp} → HTTP {status}")
    return "", ""


def episode_and_fansub_ids(html: str) -> tuple[int | None, list[int]]:
    episode_id = None
    for pattern in (
        r'id="EpisodeId"[^>]*value="(\d+)"',
        r"animeWatch\s*\.\s*initialize\s*\(\s*\d+\s*,\s*(\d+)",
        r'EpisodeId"?\s*[:=]\s*"?(\d{2,7})',
    ):
        match = re.search(pattern, html)
        if match:
            episode_id = int(match.group(1))
            break
    fansubs = sorted({int(m.group(1)) for m in re.finditer(r'data-fid="(\d+)"', html)})
    return episode_id, fansubs


def script_urls(html: str, base: str) -> list[str]:
    out = []
    for match in re.finditer(r'<script[^>]+src="([^"]+)"', html, re.I):
        raw = match.group(1)
        if raw.startswith("//"):
            raw = "https:" + raw
        elif raw.startswith("/"):
            raw = base + raw
        if raw.startswith("http") and "tranimeizle" in raw and raw not in out:
            out.append(raw)
    return out


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="TrAnimeIzle Wayback kazıcısı")
    parser.add_argument("--out", default="site-dump/arsiv")
    parser.add_argument("--domain", default="", help="öncelikli site kökü")
    parser.add_argument("--url", default="", help="bölüm sayfası (boşsa keşifle bulunur)")
    parser.add_argument("--sleep", type=float, default=4.0, help="arşiv istekleri arası bekleme")
    parser.add_argument("--query", default="naruto")
    args = parser.parse_args(list(argv) if argv is not None else None)

    import os
    os.makedirs(args.out, exist_ok=True)

    domains = ([args.domain.rstrip("/")] if args.domain else []) + [
        d for d in DOMAINS if d != (args.domain or "").rstrip("/")
    ]

    log("── 1) Ana sayfa + liste + arama")
    home_html, home_domain = "", ""
    for domain in domains:
        stamp, html = fetch_snapshot(domain + "/", args.out, "home", args.sleep)
        if html and has_content(html):
            home_html, home_domain = html, domain
            log(f"  ✔ ana sayfa kaynağı: {domain} @{stamp}")
            break
    if not home_html:
        log("  !! hiçbir domain'in arşiv kopyası yok")

    cards = []
    for match in re.finditer(r'data-href="([^"]+)"', home_html):
        href = match.group(1)
        if href.startswith("/"):
            href = home_domain + href
        if href.startswith("http") and href not in cards:
            cards.append(href)
    log(f"  · ana sayfada {len(cards)} kart bağlantısı")

    if home_domain:
        for path in ("/arama/" + urllib.parse.quote(args.query), "/listeler/populer/sayfa-1"):
            fetch_snapshot(home_domain + path, args.out, "liste" + ("_arama" if "arama" in path else "_populer"), args.sleep)

    log("── 2) Bölüm sayfası")
    episode_url = args.url
    if not episode_url and cards:
        # Bölüm sayfası olma olasılığı yüksek ilk kart
        episode_url = next((c for c in cards if "bolum" in c), cards[0])
        log(f"  · aday bölüm: {episode_url}")
    episode_html = ""
    if episode_url:
        _, episode_html = fetch_snapshot(episode_url, args.out, "bolum", args.sleep)

    if episode_html:
        episode_id, fansubs = episode_and_fansub_ids(episode_html)
        log(f"  · EpisodeId={episode_id}, fansub={fansubs}")
    else:
        log("  !! bölüm sayfası snapshot'ı alınamadı")

    log("── 3) Sayfanın JS paketleri (sitenin kendi sözleşmesi)")
    base = home_domain or DOMAINS[0]
    scripts: list[str] = []
    for html in (home_html, episode_html):
        for url in script_urls(html, base):
            if url not in scripts:
                scripts.append(url)
    log(f"  · {len(scripts)} script")
    for index, url in enumerate(scripts[:8]):
        _, body = fetch_snapshot(url, args.out, f"js{index}", args.sleep)
        if body:
            keys = {
                "/api/fansubSources": body.count("fansubSources"),
                "/api/sourcePlayer": body.count("sourcePlayer"),
                "/api/preVideoAd": body.count("preVideoAd"),
                "embed2": body.count("embed2"),
                "optraco": body.lower().count("optraco"),
                "downloadru": body.lower().count("downloadru"),
            }
            log(f"    · {url.split('/')[-1][:52]:<52} {keys}")

    log("── 4) Özet")
    log(f"  · ana sayfa: {len(home_html)}B · bölüm: {len(episode_html)}B")
    log(f"  · dosyalar: {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
