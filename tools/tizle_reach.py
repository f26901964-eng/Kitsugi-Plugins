#!/usr/bin/env python3
"""TrAnimeIzle **erişim yolu matrisi** — site Cloudflare ile IP engelliyorsa
hangi okuma katmanından açıldığını ölçer.

Neden var?
    Site veri merkezi ASN'lerine **Cloudflare Error 1005** döndürüyor
    (depodaki `site-dump-data` dalı bunu belgeliyor). Bu modül sırayla şu
    katmanları dener ve **hangisinin gerçek HTML döndürdüğünü** söyler:

      1. `direct`     — düz HTTPS isteği
      2. `jina`       — r.jina.ai (x-respond-with: html)
      3. `allorigins` — api.allorigins.win/raw
      4. `corsproxy`  — corsproxy.io
      5. `codetabs`   — api.codetabs.com/v1/proxy
      6. `corssh`     — proxy.cors.sh
      7. `wayback`    — web.archive.org (CDX + `id_` ham snapshot)

Kullanım (kütüphane):
    from tools import tizle_reach
    result = tizle_reach.fetch(url)                 # otomatik: sırayla dener
    result = tizle_reach.fetch(url, via="wayback")  # tek katman
    print(result.status, result.via, len(result.text), result.note)

Kullanım (komut satırı):
    python3 tools/tizle_reach.py                     # matris raporu
    python3 tools/tizle_reach.py --url <episode_url>
"""

from __future__ import annotations

import argparse
import gzip
import json
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Iterable

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) "
    "Gecko/20100101 Firefox/133.0"
)
BASE_HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
    "Accept-Encoding": "gzip",
    "Upgrade-Insecure-Requests": "1",
}
MAX_BYTES = 8 * 1024 * 1024
TIMEOUT = 30
#: Arşiv uçları için tekrar ayarları (`--fast` bunları küçültür).
RETRY_ATTEMPTS = 4
RETRY_FIRST_DELAY = 8

SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE

#: Sayfa gerçekten site mi? (Cloudflare engel sayfası değil mi?)
CONTENT_MARKERS = ("flx-block", "data-href", "animeDetail-items", "fansubSelector", "playlist-title")
BLOCK_MARKERS = (
    "you have been blocked",
    "error 1005",
    "error code: 1005",
    "attention required",
    "just a moment",
    "access denied",
    "cf-error-details",
)


@dataclass
class Result:
    url: str
    status: int = 0
    text: str = ""
    via: str = ""
    note: str = ""
    final_url: str = ""
    headers: dict = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return self.status == 200 and len(self.text) > 0 and not self.blocked

    @property
    def blocked(self) -> bool:
        low = (self.text or "")[:6000].lower()
        return any(marker in low for marker in BLOCK_MARKERS)

    @property
    def has_site_content(self) -> bool:
        return any(marker in (self.text or "") for marker in CONTENT_MARKERS)

    def summary(self) -> str:
        flags = []
        if self.blocked:
            flags.append("ENGELLİ")
        if self.has_site_content:
            flags.append("SITE-ICERIK")
        return (
            f"{self.via or '?'}: HTTP {self.status}, {len(self.text)}B"
            + (f", {', '.join(flags)}" if flags else "")
            + (f" — {self.note}" if self.note else "")
        )


def _raw_request(
    url: str,
    method: str = "GET",
    headers: dict | None = None,
    data: bytes | None = None,
    timeout: int = TIMEOUT,
) -> tuple[int, str, str]:
    request_headers = dict(BASE_HEADERS)
    request_headers.update(headers or {})
    request = urllib.request.Request(url, data=data, headers=request_headers)
    if method.upper() != "GET":
        request.get_method = lambda: method.upper()  # type: ignore[assignment]
    try:
        with urllib.request.urlopen(request, timeout=timeout, context=SSL_CTX) as response:
            raw = response.read(MAX_BYTES)
            if response.headers.get("Content-Encoding") == "gzip":
                try:
                    raw = gzip.decompress(raw)
                except Exception:
                    pass
            return response.status, raw.decode("utf-8", "replace"), response.geturl()
    except urllib.error.HTTPError as error:
        try:
            raw = error.read(MAX_BYTES)
        except Exception:
            raw = b""
        return error.code, raw.decode("utf-8", "replace"), url
    except Exception:
        return 0, "", url


# ---------------------------------------------------------------------------
# Katmanlar
# ---------------------------------------------------------------------------

def fetch_direct(url: str, method: str = "GET", headers: dict | None = None,
                 data: bytes | None = None) -> Result:
    status, body, final = _raw_request(url, method, headers, data)
    return Result(url=url, status=status, text=body, via="direct", final_url=final)


def fetch_jina(url: str, method: str = "GET", headers: dict | None = None,
               data: bytes | None = None) -> Result:
    if method.upper() != "GET":
        return Result(url=url, via="jina", note="POST desteklenmiyor")
    status, body, final = _raw_request(
        "https://r.jina.ai/" + url, "GET", {"x-respond-with": "html"}
    )
    return Result(url=url, status=status, text=body, via="jina", final_url=final)


def _proxy(prefix: str, via: str, url: str, method: str = "GET",
           headers: dict | None = None, data: bytes | None = None) -> Result:
    target = prefix + urllib.parse.quote(url, safe="")
    status, body, final = _raw_request(target, method, headers, data)
    return Result(url=url, status=status, text=body, via=via, final_url=final)


def fetch_allorigins(url: str, method: str = "GET", headers: dict | None = None,
                     data: bytes | None = None) -> Result:
    if method.upper() != "GET":
        return Result(url=url, via="allorigins", note="POST desteklenmiyor")
    return _proxy("https://api.allorigins.win/raw?url=", "allorigins", url, method, headers, data)


def fetch_corsproxy(url: str, method: str = "GET", headers: dict | None = None,
                    data: bytes | None = None) -> Result:
    return _proxy("https://corsproxy.io/?", "corsproxy", url, method, headers, data)


def fetch_codetabs(url: str, method: str = "GET", headers: dict | None = None,
                   data: bytes | None = None) -> Result:
    return _proxy("https://api.codetabs.com/v1/proxy?quest=", "codetabs", url, method, headers, data)


def fetch_corssh(url: str, method: str = "GET", headers: dict | None = None,
                 data: bytes | None = None) -> Result:
    merged = dict(headers or {})
    merged.setdefault("x-cors-api-key", "temp_" + "0" * 32)
    return _proxy("https://proxy.cors.sh/", "corssh", url, method, merged, data)


def _retry(url: str, attempts: int | None = None, first_delay: int | None = None,
           timeout: int = 45) -> tuple[int, str, str]:
    """429/5xx'te artan bekleme ile tekrar dener (arşiv uçları çok sık 429 verir)."""
    import time as _time
    attempts = RETRY_ATTEMPTS if attempts is None else attempts
    first_delay = RETRY_FIRST_DELAY if first_delay is None else first_delay
    delay = first_delay
    status, body, final = 0, "", url
    for attempt in range(attempts):
        status, body, final = _raw_request(url, timeout=timeout)
        if status in (200, 404):
            return status, body, final
        if attempt < attempts - 1:
            _time.sleep(delay)
            delay = min(delay * 2, 60)
    return status, body, final


def wayback_snapshot(url: str) -> str | None:
    """En yeni 200 snapshot'ın zaman damgası (önce availability API, sonra CDX)."""
    availability = (
        "https://archive.org/wayback/available?url=" + urllib.parse.quote(url, safe="")
    )
    status, body, _ = _retry(availability, timeout=30)
    if status == 200 and body.strip().startswith("{"):
        try:
            snap = json.loads(body).get("archived_snapshots", {}).get("closest", {})
            stamp = str(snap.get("timestamp") or "")
            if snap.get("available") and len(stamp) >= 8:
                return stamp
        except Exception:
            pass
    query = (
        "https://web.archive.org/cdx/search/cdx?output=json&filter=statuscode:200"
        "&collapse=digest&limit=-3&url=" + urllib.parse.quote(url, safe="")
    )
    status, body, _ = _retry(query, timeout=30)
    if status != 200 or not body.strip().startswith("["):
        return None
    try:
        rows = json.loads(body)
    except Exception:
        return None
    if len(rows) < 2:
        return None
    for row in reversed(rows[1:]):
        if len(row) >= 2 and row[1]:
            return row[1]
    return None


def fetch_wayback(url: str, method: str = "GET", headers: dict | None = None,
                  data: bytes | None = None) -> Result:
    if method.upper() != "GET":
        return Result(url=url, via="wayback", note="POST desteklenmiyor")
    stamp = wayback_snapshot(url)
    if not stamp:
        return Result(url=url, via="wayback", note="snapshot yok")
    target = f"https://web.archive.org/web/{stamp}id_/{url}"
    status, body, final = _retry(target, timeout=40)
    return Result(url=url, status=status, text=body, via="wayback", final_url=final,
                  note=f"snapshot {stamp}")


def fetch_translate(url: str, method: str = "GET", headers: dict | None = None,
                    data: bytes | None = None) -> Result:
    """Google Translate vekili (`<host>.translate.goog`) — Google'ın kendi
    çıkış IP'siyle getirir; sitedeki ASN engeli bu IP'yi kapsamayabilir."""
    if method.upper() != "GET":
        return Result(url=url, via="translate", note="POST desteklenmiyor")
    parts = urllib.parse.urlsplit(url)
    host = parts.hostname or ""
    if "." not in host:
        return Result(url=url, via="translate", note="geçersiz host")
    proxy_host = "https://" + host.replace(".", "-") + ".translate.goog"
    query = dict(urllib.parse.parse_qsl(parts.query, keep_blank_values=True))
    query.update({"_x_tr_sl": "auto", "_x_tr_tl": "en", "_x_tr_hl": "en"})
    target = proxy_host + (parts.path or "/") + "?" + urllib.parse.urlencode(query)
    status, body, final = _raw_request(target, timeout=45)
    return Result(url=url, status=status, text=body, via="translate", final_url=final)


TECHNIQUES: dict[str, callable] = {
    "direct": fetch_direct,
    "translate": fetch_translate,
    "jina": fetch_jina,
    "allorigins": fetch_allorigins,
    "corsproxy": fetch_corsproxy,
    "codetabs": fetch_codetabs,
    "corssh": fetch_corssh,
    "wayback": fetch_wayback,
}

#: Otomatik sıralama: en güvenilirden en yedekle.
AUTO_ORDER = (
    "direct", "translate", "jina", "codetabs", "corsproxy", "allorigins", "corssh", "wayback",
)


def fetch(
    url: str,
    method: str = "GET",
    headers: dict | None = None,
    data: bytes | None = None,
    via: str | None = None,
    prefer_content: bool = True,
) -> Result:
    """Tek katman (`via`) veya otomatik sıra ile getirir.

    `prefer_content=True` iken 200 dönen ama **site içeriği taşımayan**
    (ör. Cloudflare engel sayfası) cevaplar atlanır, sıradaki katman denenir.
    """
    attempts = (via,) if via else AUTO_ORDER
    best: Result | None = None
    for name in attempts:
        function = TECHNIQUES.get(name)
        if function is None:
            continue
        result = function(url, method, headers, data)  # type: ignore[operator]
        if result.ok and (result.has_site_content or not prefer_content):
            return result
        if best is None or (result.status == 200 and not result.blocked
                            and len(result.text) > len(best.text)):
            if not result.note or result.status == 200:
                best = result
    return best or Result(url=url, note="hiçbir katman cevap vermedi")


def reachability_matrix(url: str) -> list[Result]:
    return [TECHNIQUES[name](url) for name in AUTO_ORDER]  # type: ignore[misc]


def main(argv: Iterable[str] | None = None) -> int:
    global RETRY_ATTEMPTS, RETRY_FIRST_DELAY
    parser = argparse.ArgumentParser(description="TrAnimeIzle erişim yolu matrisi")
    parser.add_argument("--url", default="https://www.tranimeizle.io/")
    parser.add_argument("--json", action="store_true", help="JSON çıktı")
    parser.add_argument("--fast", action="store_true", help="tek tekrar, kısa bekleme (CI için)")
    parser.add_argument("--winner-out", default="", help="çalışan katmanın adını bu dosyaya yaz")
    args = parser.parse_args(list(argv) if argv is not None else None)
    if args.fast:
        RETRY_ATTEMPTS = 2
        RETRY_FIRST_DELAY = 3

    rows = []
    for result in reachability_matrix(args.url):
        rows.append({
            "via": result.via,
            "status": result.status,
            "bytes": len(result.text),
            "blocked": result.blocked,
            "site_content": result.has_site_content,
            "note": result.note,
        })
        print(f"- {result.via:<11} {result.summary()}", flush=True)

    working = [row["via"] for row in rows if row["status"] == 200 and row["site_content"]]

    if args.json:
        print(json.dumps(rows, ensure_ascii=False, indent=2))
    print("\nÇalışan katman(lar): " + (", ".join(working) if working else "YOK"), file=sys.stderr)
    if args.winner_out:
        winner = working[0] if working else ""
        with open(args.winner_out, "w", encoding="utf-8") as handle:
            handle.write(winner)
        print(f"WINNER={winner or 'yok'} → {args.winner_out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
