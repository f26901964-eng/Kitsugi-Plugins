#!/usr/bin/env python3
"""Wayback uzerinden guncel sayfa yapisini cikarir.

1) CDX API ile son 60 gundeki film sayfasi anlik goruntulerini listeler.
2) En yeni 1-2 film sayfasini indirir.
3) Sayfadaki embed/iframe adreslerini cikarir, bunlarin da arsiv kopyasini arar.
4) FilmMakinesi icin ayni akis.
5) Bulunan gomulu oynatici scriptlerini (dc_ / eval paket) kaydeder.
"""
import json
import os
import re
import time
import urllib.parse

import requests

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out3")
os.makedirs(OUT, exist_ok=True)

UA = "KitsugiProbe/1.0 (compatible; research)"
S = requests.Session()
S.headers.update({"User-Agent": UA})
REPORT = []


def log(*a):
    line = " ".join(str(x) for x in a)
    print(line, flush=True)
    REPORT.append(line)


def save(name, text):
    with open(os.path.join(OUT, name), "w", encoding="utf-8", errors="replace") as fh:
        fh.write(text)


def cdx(url_pattern, frm="20260901", to="20261024", limit=40, extra=""):
    q = (f"http://web.archive.org/cdx/search/cdx?url={urllib.parse.quote(url_pattern, safe='')}"
         f"&output=json&from={frm}&to={to}&limit={limit}&filter=statuscode:200&collapse=urlkey{extra}")
    for attempt in range(4):
        try:
            r = S.get(q, timeout=60)
            if r.status_code == 200:
                rows = r.json()
                if not rows:
                    return []
                head = rows[0]
                return [dict(zip(head, row)) for row in rows[1:]]
            log(f"[cdx] {r.status_code} {url_pattern}")
        except Exception as exc:
            log(f"[cdx] FAIL {exc}")
        time.sleep(5 * (attempt + 1))
    return []


def fetch_snapshot(ts, url, save_as=None, tries=4):
    raw = f"http://web.archive.org/web/{ts}id_/{url}"
    for attempt in range(tries):
        try:
            r = S.get(raw, timeout=90, allow_redirects=True)
            if r.status_code == 200 and len(r.text) > 500:
                log(f"[snap] OK {r.status_code} len={len(r.text)} {url}")
                if save_as:
                    save(save_as, r.text)
                return r.text
            log(f"[snap] {r.status_code} len={len(r.text)} {url}")
        except Exception as exc:
            log(f"[snap] FAIL {exc} {url}")
        time.sleep(6 * (attempt + 1))
    return None


def analyse(tag, html):
    low = html.lower()
    marks = [k for k in ("video-parts", "data-video_url", "data-video-url", "alternative-link",
                         "video/embed", "data-src", "rplayer", "playerr", "closeload",
                         "rapid.", "master.txt", "m3u8", "dc_", "eval(function",
                         "sources:", "player") if k in low]
    log(f"[{tag}] isaretler: {marks}")
    frames = re.findall(r'<iframe[^>]*>', html)
    log(f"[{tag}] iframe ({len(frames)}): {frames[:3]}")
    parts = re.findall(r'data-video[_-]?url="([^"]+)"', html)
    log(f"[{tag}] video-parts ({len(parts)}): {parts[:6]}")
    embeds = re.findall(r'(?:data-src|src)="([^"]*(?:video/embed|rplayer|playerr|closeload|rapid)[^"]*)"', html)
    log(f"[{tag}] embed adaylari: {list(dict.fromkeys(embeds))[:6]}")
    return list(dict.fromkeys(frames)), embeds


def probe_site(tag, base):
    log(f"\n########## {tag} {base} ##########")
    pages = cdx(f"{base}/*", limit=300)
    films = [p for p in pages if re.search(r"/(film|film-izle|dizi)/", p["original"])]
    log(f"[{tag}] toplam snapshot={len(pages)} film-benzeri={len(films)}")
    for p in films[:8]:
        log(f"   {p['timestamp']} {p['original']}")
    if not films:
        recent = sorted(pages, key=lambda x: x["timestamp"], reverse=True)[:8]
        for p in recent:
            log(f" [son]  {p['timestamp']} {p['original']}")
        films = recent
    if not films:
        return
    # en yeni 2 film sayfasi
    films.sort(key=lambda x: x["timestamp"], reverse=True)
    for idx, p in enumerate(films[:2]):
        html = fetch_snapshot(p["timestamp"], p["original"], f"{tag}_film_{idx}.html")
        if not html:
            continue
        _, embeds = analyse(f"{tag}_{idx}", html)
        # embed sayfalarinin arsivini ara
        for e_idx, emb in enumerate(embeds[:3]):
            if emb.startswith("//"):
                emb = "https:" + emb
            if not emb.startswith("http"):
                continue
            snaps = cdx(emb, frm="20250101", limit=5, extra="&filter=mimetype:text/html")
            log(f"[{tag}] embed arsivi {emb} -> {len(snaps)}")
            if snaps:
                snaps.sort(key=lambda x: x["timestamp"], reverse=True)
                body = fetch_snapshot(snaps[0]["timestamp"], snaps[0]["original"], f"{tag}_embed_{idx}_{e_idx}.html")
                if body:
                    analyse(f"{tag}_embed_{idx}_{e_idx}", body)
                    # paketli scripti ayri kaydet
                    for m in re.finditer(r"eval\(function\(p,a,c,k,e,d\).{0,40000}?\(\'[^']*\.split", body, re.S):
                        save(f"{tag}_embed_{idx}_{e_idx}_packed.js", m.group(0))
                        break


def main():
    probe_site("fm", "filmmakinesi.to")
    probe_site("hd", "www.hdfilmcehennemi.nl")
    save("_report.txt", "\n".join(REPORT))


if __name__ == "__main__":
    main()
