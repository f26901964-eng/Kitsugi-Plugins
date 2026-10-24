#!/usr/bin/env python3
"""Embed (oynatici) sayfasi arsiv taramasi.

Gercek oynatici sayfalari (rplayer / video/embed) CDX'te varsa indirir;
yoksa en yakin esdegeri (eski domain) aranir. Amac: paketli JS'in GERCEK
ornegini bulmak ve cozucuyu buna gore dogrulamak.
"""
import json
import os
import re
import time
import urllib.parse

import requests

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out4")
os.makedirs(OUT, exist_ok=True)
UA = "KitsugiProbe/1.0 (research)"
S = requests.Session()
S.headers.update({"User-Agent": UA})
REPORT = []


def log(*a):
    line = " ".join(str(x) for x in a)
    print(line, flush=True)
    REPORT.append(line)


def cdx(pattern, frm="20230101", to="20261024", limit=60, extra="", collapse="urlkey"):
    q = (f"http://web.archive.org/cdx/search/cdx?url={urllib.parse.quote(pattern, safe='')}"
         f"&output=json&from={frm}&to={to}&limit={limit}&collapse={collapse}{extra}")
    for attempt in range(3):
        try:
            r = S.get(q, timeout=90)
            if r.status_code == 200 and r.text.strip():
                rows = r.json()
                if not rows:
                    return []
                head = rows[0]
                return [dict(zip(head, row)) for row in rows[1:]]
            log(f"[cdx] {r.status_code} {pattern} len={len(r.text)}")
        except Exception as exc:
            log(f"[cdx] FAIL {exc}")
        time.sleep(5 * (attempt + 1))
    return []


def snap(ts, url, name, tries=3):
    raw = f"http://web.archive.org/web/{ts}id_/{url}"
    for attempt in range(tries):
        try:
            r = S.get(raw, timeout=90)
            if r.status_code == 200 and len(r.text) > 400:
                open(os.path.join(OUT, name), "w", encoding="utf-8", errors="replace").write(r.text)
                log(f"[snap] OK len={len(r.text)} {name} <- {url}")
                return r.text
            log(f"[snap] {r.status_code} {url}")
        except Exception as exc:
            log(f"[snap] FAIL {exc}")
        time.sleep(5 * (attempt + 1))
    return None


PATTERNS = [
    "hdfilmcehennemi.mobi/*",
    "hdfilmcehennemi.mobi/rplayer/*",
    "hdfilmcehennemi.mobi/video/embed/*",
    "hdfilmcehennemi.nl/rplayer/*",
    "hdfilmcehennemi.nl/video/embed/*",
    "hdfilmcehennemi.com/rplayer/*",
    "hdfilmcehennemi.com/video/embed/*",
    "hdfilmcehennemi.la/rplayer/*",
    "closeload.filmmakinesi.to/*",
    "closeload.filmmakinesi.film/*",
    "rapid.filmmakinesi.to/*",
    "filmmakinesi.to/video/embed/*",
]


def main():
    for pattern in PATTERNS:
        rows = cdx(pattern, limit=80, extra="&filter=statuscode:200")
        useful = [r for r in rows if re.search(r"(rplayer|playerr|video/embed|embed-)", r["original"])]
        log(f"\n== {pattern}: toplam={len(rows)} oynatici={len(useful)}")
        for r in useful[:6]:
            log(f"   {r['timestamp']} {r['original']}")
        for idx, r in enumerate(sorted(useful, key=lambda x: x["timestamp"], reverse=True)[:2]):
            name = re.sub(r"[^a-z0-9]+", "_", pattern.split("/*")[0]).strip("_") + f"_{idx}.html"
            body = snap(r["timestamp"], r["original"], name)
            if body:
                low = body.lower()
                marks = [k for k in ("eval(function", "dc_", "sources:", "atob", "master.txt",
                                     "m3u8", "tracks:", "av(", "jwplayer") if k in low]
                log(f"   [{name}] isaretler={marks} len={len(body)}")
                m = re.search(r"eval\(function\(p,a,c,k,e,d\).{0,60000}?\.split\(", body, re.S)
                if m:
                    open(os.path.join(OUT, name.replace(".html", "_packed.js")), "w").write(m.group(0))
                    log(f"   [{name}] paketli blok kaydedildi ({len(m.group(0))} b)")
    open(os.path.join(OUT, "_report.txt"), "w").write("\n".join(REPORT))


if __name__ == "__main__":
    main()
