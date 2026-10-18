#!/usr/bin/env python3
"""FilmMakinesi / HDFilmCehennemi / genel URL teşhis aracı.

Kullanım:
  python3 tani.py fm  "https://filmmakinesi.to/film/..."     # doğrudan sayfa
  python3 tani.py fm  "the mentalist"                        # sitede ara
  python3 tani.py hdf "https://www.hdfilmcehennemi.nl/..."   # doğrudan sayfa
  python3 tani.py hdf "supernatural"                         # sitede ara
  python3 tani.py url "https://ornek.com/sayfa"              # genel teşhis

  --save  HTML'i tani-<site>.html dosyasina yazar.

Not: Bu betik eklentiye dahil degildir; yalnizca "veri gelmiyor" sorununun
kaynagini (HTTP 451/500, Cloudflare, degisen HTML, bos iframe, m3u8 yok)
gormek icin kullanilir. Ciktiyi oldugu gibi paylasabilirsiniz.
"""

import argparse
import base64
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

SITES = {
    "fm": {
        "name": "FilmMakinesi",
        "base": "https://filmmakinesi.to",
        "search": "/arama/?s={q}",
        "result_re": r'href="([^"]*/(?:film|dizi)/[^"]+)"[^>]*>(.*?)</a>',
    },
    "hdf": {
        "name": "HDFilmCehennemi",
        "base": "https://www.hdfilmcehennemi.nl",
        "search": "/search?q={q}",
        "result_re": r'href="([^"]*/(?:film|dizi)/[^"]+)"[^>]*>(.*?)</a>',
    },
}

IFRAME_RE = re.compile(r'<iframe[^>]+src=["\']([^"\']+)["\']', re.I)
STREAM_RE = re.compile(r'(?i)https?://[^\s"\'<>]+?\.(?:m3u8|mp4)(?:\?[^\s"\'<>]*)?')
TITLE_RE = re.compile(r"<title[^>]*>(.*?)</title>", re.I | re.S)
H1_RE = re.compile(r"<h1[^>]*>(.*?)</h1>", re.I | re.S)
OG_TITLE_RE = re.compile(r'<meta[^>]+property=["\']og:title["\'][^>]+content=["\']([^"\']+)', re.I)
B64_RE = re.compile(r"[A-Za-z0-9+/_=-]{24,}")
TAG_RE = re.compile(r"<[^>]+>")


def clean(text):
    return re.sub(r"\s+", " ", TAG_RE.sub(" ", text or "")).strip()


def fetch(url, referer=None, timeout=20):
    headers = {
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
    }
    if referer:
        headers["Referer"] = referer
    req = urllib.request.Request(url, headers=headers)
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read()
            return {
                "ok": True,
                "status": resp.status,
                "final_url": resp.geturl(),
                "content_type": resp.headers.get("content-type", ""),
                "body": body,
                "elapsed": time.time() - started,
            }
    except urllib.error.HTTPError as exc:
        body = exc.read() if exc.fp else b""
        return {
            "ok": False,
            "status": exc.code,
            "final_url": exc.geturl() if hasattr(exc, "geturl") else url,
            "content_type": exc.headers.get("content-type", "") if exc.headers else "",
            "body": body,
            "elapsed": time.time() - started,
            "error": str(exc),
        }
    except Exception as exc:  # URLError, SSL, timeout...
        return {
            "ok": False,
            "status": None,
            "final_url": url,
            "content_type": "",
            "body": b"",
            "elapsed": time.time() - started,
            "error": f"{type(exc).__name__}: {exc}",
        }


def decode_b64_candidates(text):
    found = []
    for match in B64_RE.findall(text):
        try:
            decoded = base64.b64decode(match + "=" * (-len(match) % 4)).decode("utf-8", "ignore")
        except Exception:
            continue
        if decoded.startswith("http") and (".m3u8" in decoded or ".mp4" in decoded):
            found.append(decoded[:200])
    return found


def report(label, result, save_path=None):
    body = result["body"]
    html = body.decode("utf-8", "replace")
    size_kb = len(body) / 1024
    print(f"\n=== {label} ===")
    print(f"HTTP       : {result['status'] or '-'}  ({result['elapsed']:.2f} sn, {size_kb:.1f} KB)")
    print(f"Son URL    : {result['final_url']}")
    print(f"Content-Type: {result['content_type'] or '-'}")
    if result.get("error"):
        print(f"Hata       : {result['error']}")
    if not html:
        print("Gövde      : boş")
        return html

    title = TITLE_RE.search(html)
    h1 = H1_RE.search(html)
    og = OG_TITLE_RE.search(html)
    print(f"<title>    : {clean(title.group(1))[:160] if title else '-'}")
    print(f"<h1>       : {clean(h1.group(1))[:160] if h1 else '-'}")
    print(f"og:title   : {clean(og.group(1))[:160] if og else '-'}")

    lowered = html.lower()
    print(f"Cloudflare : {'EVET (Just a moment)' if 'just a moment' in lowered else 'hayır'}")
    anchor_count = len(re.findall(r"<a\b", html, re.I))
    print(f"<a> sayısı : {anchor_count}")
    print(f"iframe     : {len(IFRAME_RE.findall(html))}")
    for src in IFRAME_RE.findall(html)[:10]:
        print(f"   - {src[:180]}")
    streams = STREAM_RE.findall(html)
    print(f"m3u8/mp4   : {len(streams)}")
    for stream in streams[:5]:
        print(f"   - {stream[:180]}")
    b64 = decode_b64_candidates(html)
    print(f"base64 akış: {len(b64)}")
    for item in b64[:3]:
        print(f"   - {item}")

    if "filmmakinesi" in label.lower():
        for selector in ("div.film-list div.item-relative", "div.item-relative", "a.item",
                         "div.info-description", "div.alternative-links"):
            print(f"seçici {selector!r}: {len(re.findall(re.escape(selector), html))}")
    if "hdfilmcehennemi" in label.lower():
        for marker in ("alternative-links", "alternative-link", "seasons", "seasons-tab-content",
                       "video-player", "play-video"):
            print(f"işaret {marker!r}: {html.count(marker)}")

    if save_path:
        with open(save_path, "w", encoding="utf-8") as handle:
            handle.write(html)
        print(f"HTML kaydedildi: {save_path}")
    return html


def search(site_key, query, save_path=None):
    site = SITES[site_key]
    url = site["base"] + site["search"].format(q=urllib.parse.quote(query))
    print(f"\nArama: {url}")
    result = fetch(url, referer=site["base"] + "/")
    html = report(f"{site['name']} arama", result, save_path)

    seen = set()
    print("\nBulunan içerik bağlantıları:")
    for href, text in re.findall(site["result_re"], html, re.I | re.S):
        if "comment" in href or href in seen:
            continue
        seen.add(href)
        print(f"   - {clean(text)[:80]:80s} {href[:120]}")
        if len(seen) >= 15:
            break
    if not seen:
        print("   (yok — HTML yapısı değişmiş ya da arama engellenmiş olabilir)")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("site", choices=("fm", "hdf", "url"), help="fm | hdf | url")
    parser.add_argument("target", help="sayfa adresi veya arama sorgusu")
    parser.add_argument("--save", metavar="DOSYA", help="HTML çıktısını kaydet")
    args = parser.parse_args()

    if not args.target.lower().startswith(("http://", "https://")):
        if args.site == "url":
            parser.error("'url' modunda tam adres gerekir (http:// veya https://).")
        search(args.site, args.target, args.save)
        return 0

    label = SITES[args.site]["name"] if args.site in SITES else "Genel URL"
    result = fetch(args.target, referer=SITES[args.site]["base"] + "/" if args.site in SITES else None)
    html = report(label, result, args.save)

    if result["status"] in (451, 500, 403, 404):
        print(
            "\nDeğerlendirme: site bu adresten istenen yanıtı vermedi. Eklenti kodu doğru "
            "olsa bile bu durumda içerik gelmez; bölge kilidi / Cloudflare / geçici sunucu "
            "hatası olabilir."
        )
    elif not IFRAME_RE.findall(html) and not STREAM_RE.findall(html):
        print(
            "\nDeğerlendirme: sayfa açıldı ama iframe/m3u8 bulunamadı. Eklentinin "
            "seçicileri bu HTML ile eşleşmiyor olabilir."
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
