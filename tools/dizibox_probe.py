#!/usr/bin/env python3
"""DiziBox canlı ölçüm probu (salt-okunur).

Eklentinin kullandığı istekleri (ana sayfa, kategori, arama, dwls_search GET/POST,
bölüm ve dizi sayfası) GitHub runner'dan gönderir ve her biri için:
  * HTTP durum kodu, yönlendirme, sunucu/Cloudflare başlıkları,
  * gövde uzunluğu ve Cloudflare doğrulama işaretleri,
  * tam HTML'i research/dizibox-probe/ altına.
Sonuç research/dizibox-probe/rapor.md dosyasına yazılır.
"""
from __future__ import annotations

import gzip
import os
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://www.dizibox.live"
OUT = os.path.join("research", "dizibox-probe")
UA = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
)
HEADER_KEYS = ["server", "cf-ray", "cf-mitigated", "content-type", "location", "set-cookie", "retry-after"]
CF_MARKERS = [
    "challenge-platform", "cf-chl", "Just a moment", "cf-browser-verification",
    "cf_chl_opt", "Enable JavaScript and cookies to continue", "cloudflare",
]
CASES = [
    ("anasayfa", "GET", BASE + "/", None),
    ("tum-bolumler", "GET", BASE + "/tum-bolumler/", None),
    ("tum-bolumler-p2", "GET", BASE + "/tum-bolumler/page/2/", None),
    ("tur-dram", "GET", BASE + "/tur/dram/", None),
    ("tur-dram-p2", "GET", BASE + "/tur/dram/page/2/", None),
    ("arama-sayfa", "GET", BASE + "/?s=" + urllib.parse.quote("the mentalist"), None),
    ("dwls-get", "GET", BASE + "/wp-admin/admin-ajax.php?action=dwls_search&s=" + urllib.parse.quote("the mentalist"), None),
    ("dwls-post", "POST", BASE + "/wp-admin/admin-ajax.php", "action=dwls_search&s=" + urllib.parse.quote("the mentalist")),
    ("dizi", "GET", BASE + "/diziler/visionquest/", None),
    ("bolum", "GET", BASE + "/visionquest-1-sezon-1-bolum-izle/", None),
]

#: Eklentinin sayfalarda aradığı yapılar. Sayı 0 ise seçici ölmüş demektir.
SELECTORS = [
    r'class="[^"]*movie-box',            # liste kartı
    r'class="[^"]*episode-card-title',   # bölüm kartı başlığı
    r'class="[^"]*poster-title',
    r'class="[^"]*box-image',            # arka plan görseli
    r'class="[^"]*label-imdb',
    r'class="[^"]*release',
    r'class="[^"]*pag\w*',              # sayfalama
    r'img\.afis',
    r'<article',
]
#: Oynatıcı zinciri işaretleri.
PLAYER_HINTS = [
    "iframe", "data-src", "data-player", "data-id", "<video", ".m3u8", ".mp4",
    "dbx", "rapidvid", "sibnet", "vidmoly", "sources:", "file:", "atob(",
    "decrypt", "AES", "CryptoJS",
]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def fetch(method: str, url: str, data: str | None):
    body = data.encode() if data else None
    headers = {
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language": "tr-TR,tr;q=0.9",
        "Accept-Encoding": "gzip",
        "Referer": BASE + "/",
    }
    if method == "POST":
        headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        headers["X-Requested-With"] = "XMLHttpRequest"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    opener = urllib.request.build_opener(NoRedirect, urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    try:
        resp = opener.open(req, timeout=30)
        status, hdrs, raw = resp.status, resp.headers, resp.read()
    except urllib.error.HTTPError as e:
        status, hdrs, raw = e.code, e.headers, e.read()
    except Exception as e:  # ağ hatası
        return {"status": f"HATA {type(e).__name__}: {e}", "headers": {}, "body": b""}
    if hdrs.get("Content-Encoding", "").lower() == "gzip":
        try:
            raw = gzip.decompress(raw)
        except Exception:
            pass
    return {
        "status": status,
        "headers": {k: hdrs.get(k) for k in HEADER_KEYS if hdrs.get(k)},
        "body": raw,
    }


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    lines = ["# DiziBox canlı probu (GitHub runner)", ""]
    for name, method, url, data in CASES:
        r = fetch(method, url, data)
        body = r["body"] or b""
        text = body.decode("utf-8", "replace")
        markers = [m for m in CF_MARKERS if m.lower() in text.lower()]
        title = re.search(r"<title>(.*?)</title>", text, re.S | re.I)
        with open(os.path.join(OUT, name + ".html"), "wb") as f:
            f.write(body[:600000])
        lines += [
            f"## {name}  `{method} {url}`",
            f"- durum: **{r['status']}**",
            f"- başlıklar: `{r['headers']}`",
            f"- gövde: {len(body)} bayt, başlık: {title.group(1).strip()[:120] if title else '-'}",
            f"- Cloudflare işaretleri: {markers or 'yok'}",
            f"- JSON/dwls ilk 200 karakter: `{text[:200].replace(chr(10), ' ')}`" if "dwls" in name else "",
            "",
        ]
        print(lines[-6] if False else f"{name}: {r['status']} {len(body)}B {markers}", flush=True)
    # ── Seçici sağlığı + oynatıcı zinciri ──────────────────────────────────
    lines += ["# Yapı denetimi (seçiciler)", ""]
    for name, method, url, data in CASES:
        path = os.path.join(OUT, name + ".html")
        if not os.path.exists(path):
            continue
        text = open(path, "rb").read().decode("utf-8", "replace")
        counts = {sel: len(re.findall(sel, text, re.I)) for sel in SELECTORS}
        non_zero = {k: v for k, v in counts.items() if v}
        hints = [h for h in PLAYER_HINTS if h.lower() in text.lower()]
        lines += [
            f"## {name}",
            f"- seçiciler: `{non_zero or 'HİÇBİRİ EŞLEŞMEDİ'}`",
            f"- oynatıcı işaretleri: `{hints or 'yok'}`",
            "",
        ]
        print(f"secici {name}: {non_zero} | oynatici: {hints}", flush=True)

    # Bölüm sayfasından çıkan çerçeveleri ayrıca aç (oynatıcı zinciri).
    episode_path = os.path.join(OUT, "bolum.html")
    if os.path.exists(episode_path):
        episode = open(episode_path, "rb").read().decode("utf-8", "replace")
        frames = re.findall(r'<iframe[^>]+src="([^"]+)"', episode, re.I)
        frames += re.findall(r'data-(?:src|player|url|iframe)="(https?://[^"]+)"', episode, re.I)
        frames = list(dict.fromkeys(f for f in frames if f.startswith("http")))
        lines += ["# Bölüm sayfasındaki çerçeveler", f"- bulunan: {len(frames)}", ""]
        print(f"cerceve: {frames}", flush=True)
        for index, frame in enumerate(frames[:4]):
            r = fetch("GET", frame, None)
            body = (r["body"] or b"").decode("utf-8", "replace")
            found = {
                "m3u8": re.findall(r'https?://[^"\\\s]+\.m3u8[^"\\\s]*', body)[:3],
                "mp4": re.findall(r'https?://[^"\\\s]+\.mp4[^"\\\s]*', body)[:2],
                "atob": "atob(" in body,
                "sources": "sources" in body,
                "paket": re.findall(r'([a-z0-9\-]+\.(?:site|com|net|xyz|top|io))/', body)[:5],
            }
            lines += [
                f"## cerceve-{index} `{frame[:120]}`",
                f"- durum: **{r['status']}**, {len(body)} bayt",
                f"- bulgular: `{found}`",
                "",
            ]
            print(f"cerceve-{index}: {r['status']} {len(body)}B {found}", flush=True)

    with open(os.path.join(OUT, "rapor.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(l for l in lines if l is not None))
    return 0


if __name__ == "__main__":
    sys.exit(main())
