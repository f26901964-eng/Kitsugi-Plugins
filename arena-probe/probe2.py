#!/usr/bin/env python3
"""Rota kesfi: TR disindan hangi yollarla gercek sayfaya ulasilabiliyor?

Test edilenler: dogrudan, birkac CORS proxy, r.jina.ai okuyucusu,
Wayback Machine anlik goruntusu ve embed/CDN host'lari.
"""
import json
import os
import re
import urllib.parse

import requests

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out2")
os.makedirs(OUT, exist_ok=True)

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
S = requests.Session()
S.headers.update({"User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9"})

REPORT = []


def log(*a):
    line = " ".join(str(x) for x in a)
    print(line, flush=True)
    REPORT.append(line)


def try_url(label, url, save=None, timeout=25, headers=None, expect=None):
    try:
        r = S.get(url, timeout=timeout, headers=headers or {}, allow_redirects=True)
        body = r.text
        ok = r.status_code == 200 and len(body) > 1500
        marker = ""
        low = body.lower()
        for k in ("images/list/poster", "alternative-link", "video-parts", "data-video_url",
                  "item-relative", "hdfilmcehennemi", "filmmakinesi", "rplayer", "video/embed"):
            if k in low:
                marker += f" <{k}>"
        log(f"[{label}] {r.status_code} len={len(body)} final={r.url}{marker}")
        if save and len(body) > 200:
            open(os.path.join(OUT, save), "w", encoding="utf-8", errors="replace").write(body)
        return body if ok else None
    except Exception as exc:
        log(f"[{label}] FAIL {type(exc).__name__}: {str(exc)[:160]}")
        return None


def proxy_routes(target, tag):
    q = urllib.parse.quote(target, safe="")
    routes = [
        ("allorigins", f"https://api.allorigins.win/raw?url={q}"),
        ("codetabs", f"https://api.codetabs.com/v1/proxy?quest={q}"),
        ("corsproxy", f"https://corsproxy.io/?url={q}"),
        ("jina", f"https://r.jina.ai/{target}"),
        ("thingproxy", f"https://thingproxy.freeboard.io/fetch/{target}"),
        ("12ft", f"https://12ft.io/{target}"),
        ("textance", f"https://api.scraperapi.com/?url={q}"),
    ]
    for name, url in routes:
        try_url(f"{tag}:{name}", url, save=f"{tag}_{name}.html", timeout=30)


def wayback(target, tag):
    try:
        api = f"http://archive.org/wayback/available?url={urllib.parse.quote(target, safe='')}"
        r = S.get(api, timeout=25)
        log(f"[{tag}:wayback-api] {r.status_code} {r.text[:400]}")
        data = r.json()
        snap = data.get("archived_snapshots", {}).get("closest")
        if snap and snap.get("url"):
            raw = snap["url"]
            raw_id = re.sub(r"/web/(\d+)/", r"/web/\1id_/", raw)
            try_url(f"{tag}:wayback-raw", raw_id, save=f"{tag}_wayback.html", timeout=45)
            try_url(f"{tag}:wayback-cdx", f"http://web.archive.org/cdx/search/cdx?url={urllib.parse.quote(target, safe='')}&output=json&limit=5&from=2026", save=None, timeout=30)
    except Exception as exc:
        log(f"[{tag}:wayback] FAIL {exc}")


def main():
    targets = [
        ("hd", "https://www.hdfilmcehennemi.nl/"),
        ("fm", "https://filmmakinesi.to/"),
    ]
    for tag, target in targets:
        log(f"\n===== {tag} {target} =====")
        try_url(f"{tag}:direct", target, save=f"{tag}_direct.html")
        wayback(target, tag)
        proxy_routes(target, tag)

    log("\n===== embed hostlari (dogrudan) =====")
    for host in [
        "https://hdfilmcehennemi.mobi/",
        "https://www.hdfilmcehennemi.mobi/",
        "https://closeload.filmmakinesi.to/",
        "https://closeload.filmmakinesi.film/",
        "https://rapid.filmmakinesi.to/",
    ]:
        try_url(host, host, save="host_" + re.sub(r"[^a-z0-9]+", "_", host.split("//")[1]) + ".html", timeout=15)

    open(os.path.join(OUT, "_report.txt"), "w").write("\n".join(REPORT))


if __name__ == "__main__":
    main()
