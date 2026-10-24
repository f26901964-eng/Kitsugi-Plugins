#!/usr/bin/env python3
"""Canli site kesfi: FilmMakinesi + HDFilmCehennemi video zinciri.

Amac: eklentilerin gomulu oynatici (embed) -> rplayer -> m3u8 zincirini
gercek yanitlarla dogrulamak ve Kotlin tarafina tasinacak kalibi cikarmak.
"""
import json
import os
import re
import sys
import time
import traceback

import requests

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
os.makedirs(OUT, exist_ok=True)

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

SESSION = requests.Session()
SESSION.headers.update({
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
})

REPORT = []


def log(*args):
    line = " ".join(str(a) for a in args)
    print(line, flush=True)
    REPORT.append(line)


def save(name, text):
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8", errors="replace") as fh:
        fh.write(text)
    return path


def get(url, referer=None, save_as=None, timeout=30):
    headers = {}
    if referer:
        headers["Referer"] = referer
    try:
        r = SESSION.get(url, headers=headers, timeout=timeout, allow_redirects=True)
        body = r.text
        log(f"[GET] {url} -> {r.status_code} final={r.url} len={len(body)} ct={r.headers.get('Content-Type')}")
        if save_as:
            save(save_as, body)
        return r, body
    except Exception as exc:
        log(f"[GET-FAIL] {url} -> {exc}")
        return None, ""


def markers(name, body, keys):
    low = body.lower()
    hits = [k for k in keys if k.lower() in low]
    log(f"[MARKERS] {name}: {hits}")


MARK_KEYS = [
    "video-parts", "data-video_url", "data-video-url", "alternative-link",
    "rplayer", "rapidrame", "rapidvid", "closeload", "master.txt", "m3u8",
    "eval(function", "function dc_", "jwplayer", "sources:", "iframe",
    "video-container", "aria-label='Player'", 'aria-label="Player"',
    "__NEXT_DATA__", "contentUrl", "player", "embed",
]


# ───────────────────────── FilmMakinesi ─────────────────────────
def probe_filmmakinesi():
    log("\n=========== FILMMAKINESI ===========")
    base = None
    for cand in ["https://filmmakinesi.to", "https://filmmakinesi.film",
                 "https://filmmakinesi.tv", "https://filmmakinesi.pw",
                 "https://filmmakinesi.net", "https://filmmakinesi.de",
                 "https://filmmakinesi.com"]:
        r, body = get(cand + "/", save_as=f"fm_home_{cand.split('//')[1].replace('.', '_')}.html")
        if r is not None and r.status_code == 200 and len(body) > 2000:
            low = body.lower()
            if any(k in low for k in ["film-list", "item-relative", "data-title", "filmler"]):
                base = f"{r.url.split('//')[0]}//{r.url.split('//')[1].split('/')[0]}"
                log(f"[FM] canli taban: {base}")
                save("fm_home.html", body)
                break
    if not base:
        log("[FM] canli domain bulunamadi")
        return

    home = open(os.path.join(OUT, "fm_home.html"), encoding="utf-8", errors="replace").read()
    hrefs = re.findall(r'href="(https?://[^"]*filmmakinesi[^"]*|/[^"]*)"', home)
    film_links = []
    for h in hrefs:
        if re.search(r"/(film|film-izle|dizi)/", h):
            full = h if h.startswith("http") else base + (h if h.startswith("/") else "/" + h)
            if full not in film_links:
                film_links.append(full)
    log(f"[FM] aday icerik linkleri: {film_links[:6]}")
    if not film_links:
        return

    for page in film_links[:2]:
        r, body = get(page, referer=base + "/", save_as="fm_page.html")
        if r is None:
            continue
        markers("fm_page", body, MARK_KEYS)
        parts = re.findall(r'data-video_url="([^"]+)"', body) + \
                re.findall(r'data-video-url="([^"]+)"', body)
        frames = re.findall(r'<iframe[^>]+(?:data-src|src)="([^"]+)"', body)
        log(f"[FM] video-parts: {parts}")
        log(f"[FM] iframes: {frames}")
        embeds = list(dict.fromkeys(parts + frames))
        for idx, emb in enumerate(embeds[:4]):
            if emb.startswith("//"):
                emb = "https:" + emb
            get(emb, referer=page, save_as=f"fm_embed_{idx}.html")
            eb = open(os.path.join(OUT, f"fm_embed_{idx}.html"), encoding="utf-8", errors="replace").read()
            markers(f"fm_embed_{idx}", eb, MARK_KEYS)
            av = re.findall(r"av\(['\"]([^'\"]+)['\"]\)", eb)
            log(f"[FM] embed{idx} av(): {av[:2]}")
            streams = re.findall(r'https?://[^\s"\'<>]+\.(?:m3u8|mp4)[^\s"\'<>]*', eb)
            log(f"[FM] embed{idx} stream adaylari: {streams[:5]}")
            packed = "eval(function(p,a,c,k,e,d)" in eb or "function dc_" in eb
            log(f"[FM] embed{idx} packed={packed}")
            # varsa dogrudan oynat
            for s in streams[:2]:
                probe_stream(s, referer=emb, label=f"fm_embed{idx}")
        # dizi bolum sayfasi da dene
        break


# ───────────────────────── HDFilmCehennemi ─────────────────────────
def probe_hdfilmcehennemi():
    log("\n=========== HDFILMCEHENNEMI ===========")
    base = None
    for cand in ["https://www.hdfilmcehennemi.nl", "https://hdfilmcehennemi.nl",
                 "https://www.hdfilmcehennemi.la", "https://hdfilmcehennemi.la",
                 "https://www.hdfilmcehennemi.com.tr", "https://www.hdfilmcehennemi.com",
                 "https://www.hdfilmcehennemi.biz"]:
        r, body = get(cand + "/", save_as=f"hd_home_{cand.split('//')[1].replace('.', '_')}.html")
        if r is not None and r.status_code == 200 and len(body) > 2000:
            low = body.lower()
            if any(k in low for k in ["images/list/poster", "alternative-link", "load/page", "post-info"]):
                base = f"{r.url.split('//')[0]}//{r.url.split('//')[1].split('/')[0]}"
                log(f"[HD] canli taban: {base}")
                save("hd_home.html", body)
                break
    if not base:
        log("[HD] canli domain bulunamadi")
        return

    home = open(os.path.join(OUT, "hd_home.html"), encoding="utf-8", errors="replace").read()
    markers("hd_home", home, MARK_KEYS)
    hrefs = re.findall(r'href="([^"]+)"', home)
    film_links = []
    for h in hrefs:
        if re.search(r"/film(/|-)", h) or re.search(r"-izle", h):
            full = h if h.startswith("http") else base + (h if h.startswith("/") else "/" + h)
            if full not in film_links:
                film_links.append(full)
    log(f"[HD] aday icerik linkleri: {film_links[:8]}")
    if not film_links:
        return

    page = film_links[0]
    r, body = get(page, referer=base + "/", save_as="hd_page.html")
    if r is None:
        return
    markers("hd_page", body, MARK_KEYS)
    buttons = re.findall(r'<button[^>]*class="[^"]*alternative-link[^"]*"[^>]*>', body)
    log(f"[HD] alternative-link butonlari ({len(buttons)}): {buttons[:6]}")
    ds = re.findall(r'(?:data-video|data-video-id|data-video-url|data-src|data-id)="([^"]+)"', body)
    log(f"[HD] data-* degerleri: {list(dict.fromkeys(ds))[:12]}")
    frames = re.findall(r'<iframe[^>]+>', body)
    log(f"[HD] iframe etiketleri: {frames[:4]}")

    ids = re.findall(r'data-video="([^"]+)"', body)
    if not ids:
        ids = re.findall(r'/video/([A-Za-z0-9_-]+)/', body)
    log(f"[HD] video id adaylari: {list(dict.fromkeys(ids))[:6]}")

    for vid in list(dict.fromkeys(ids))[:2]:
        if "://" in vid:
            continue
        api = f"{base}/video/{vid}/"
        r2, body2 = get(api, referer=page, save_as=f"hd_video_{vid}.json")
        markers(f"hd_video_{vid}", body2, MARK_KEYS)
        log(f"[HD] /video/{vid}/ ilk 600: {body2[:600].replace(chr(10), ' ')}")
        emb = re.findall(r'(?:data-src|src)\\?=\\?"([^"\\]+)', body2)
        emb += re.findall(r'(https?:\\?/\\?/[^"\s<>]+rplayer[^"\s<>]*)', body2)
        log(f"[HD] video {vid} embed adaylari: {emb[:4]}")
        for idx, e in enumerate(emb[:2]):
            e = e.replace("\\/", "/")
            if e.startswith("//"):
                e = "https:" + e
            rp, rpbody = get(e, referer=page, save_as=f"hd_rplayer_{vid}_{idx}.html")
            markers(f"hd_rplayer_{vid}", rpbody, MARK_KEYS)
            av = re.findall(r"av\(['\"]([^'\"]+)['\"]\)", rpbody)
            log(f"[HD] rplayer av(): {av[:2]}")
            packed = re.findall(r"eval\(function\(p,a,c,k,e,d\)", rpbody)
            log(f"[HD] rplayer packed: {len(packed)}")
            streams = re.findall(r'https?://[^\s"\'<>]+\.(?:m3u8|mp4)[^\s"\'<>]*', rpbody)
            log(f"[HD] rplayer stream adaylari: {streams[:5]}")
            for s in streams[:2]:
                probe_stream(s, referer=e, label=f"hd_{vid}")
        break


def probe_stream(url, referer, label):
    """Final m3u8/mp4 adresinin gercekten oynatilabilir oldugunu dogrula."""
    headers = {"Referer": referer} if referer else {}
    try:
        r = SESSION.get(url, headers=headers, timeout=25, allow_redirects=True, stream=True)
        first = next(r.iter_content(4096), b"").decode("utf-8", "replace")
        log(f"[STREAM-{label}] {r.status_code} {r.url} | bas: {first[:160]!r}")
        r.close()
    except Exception as exc:
        log(f"[STREAM-{label}] HATA {url} -> {exc}")


def main():
    for fn in (probe_filmmakinesi, probe_hdfilmcehennemi):
        try:
            fn()
        except Exception:
            log(f"[HATA] {fn.__name__}")
            log(traceback.format_exc())
        time.sleep(1)
    save("_report.txt", "\n".join(REPORT))


if __name__ == "__main__":
    main()

# probe tetik 2026-10-24T12:35:47Z
