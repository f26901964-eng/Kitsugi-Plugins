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


def wayback_snapshot(url: str) -> str | None:
    """CDX'ten en yeni 200 snapshot'ın zaman damgasını döndürür."""
    query = (
        "https://web.archive.org/cdx/search/cdx?output=json&filter=statuscode:200"
        "&collapse=digest&limit=-3&url=" + urllib.parse.quote(url, safe="")
    )
    status, body, _ = _raw_request(query)
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
    status, body, final = _raw_request(target)
    return Result(url=url, status=status, text=body, via="wayback", final_url=final,
                  note=f"snapshot {stamp}")


TECHNIQUES: dict[str, callable] = {
    "direct": fetch_direct,
    "jina": fetch_jina,
    "allorigins": fetch_allorigins,
    "corsproxy": fetch_corsproxy,
    "codetabs": fetch_codetabs,
    "corssh": fetch_corssh,
    "wayback": fetch_wayback,
}

#: Otomatik sıralama: en güvenilirden en yedekle.
AUTO_ORDER = ("direct", "jina", "codetabs", "corsproxy", "allorigins", "corssh", "wayback")


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
    parser = argparse.ArgumentParser(description="TrAnimeIzle erişim yolu matrisi")
    parser.add_argument("--url", default="https://www.tranimeizle.io/")
    parser.add_argument("--json", action="store_true", help="JSON çıktı")
    args = parser.parse_args(list(argv) if argv is not None else None)

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

    if args.json:
        print(json.dumps(rows, ensure_ascii=False, indent=2))
    else:
        working = [row["via"] for row in rows if row["status"] == 200 and row["site_content"]]
        print("\nÇalışan katman(lar): " + (", ".join(working) if working else "YOK"), file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
