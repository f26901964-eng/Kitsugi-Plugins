#!/usr/bin/env python3
"""Canli site kesfi (gecici): FilmMakinesi + HDFilmCehennemi oynatici zinciri.

Her adimda HAM yanit kaydedilir; eklenti kodu tahmine degil bu dosyalara gore
yazilacak.
"""
import json
import os
import re
import sys
import time
import traceback
import urllib.parse

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
os.makedirs(OUT, exist_ok=True)

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
REPORT = []
MANIFEST = {"pages": [], "embeds": [], "streams": []}


def log(*a):
    line = " ".join(str(x) for x in a)
    print(line, flush=True)
    REPORT.append(line)


def save(name, text):
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8", errors="replace") as fh:
        fh.write(text)
    return name


def load_proxies():
    mode = os.environ.get("PROBE_MODE", "proxy")
    if mode == "direct":
        return [None]
    try:
        data = json.load(open(os.path.join(OUT, "proxies.json")))
        return [d["proxy"] for d in data][:25] or [None]
    except Exception:
        return [None]


PROXIES = load_proxies()
CUR = {"i": 0}


def to_requests_proxy(p):
    if not p:
        return None
    if p.startswith("socks5://"):
        p = "socks5h://" + p[len("socks5://"):]
    if p.startswith("socks4://"):
        p = "socks4a://" + p[len("socks4://"):]
    return {"http": p, "https": p}


def fetch(url, headers=None, label="", timeout=30, allow_redirects=True, stream_peek=False):
    """Proxy havuzunu dolasarak istek atar. Basarili yaniti dondurur."""
    hdrs = {"User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"}
    if headers:
        hdrs.update(headers)
    tried = 0
    last_exc = None
    while tried < min(len(PROXIES), 8):
        p = PROXIES[CUR["i"] % len(PROXIES)]
        try:
            t0 = time.time()
            r = requests.get(url, headers=hdrs, proxies=to_requests_proxy(p), timeout=timeout,
                             allow_redirects=allow_redirects, stream=stream_peek)
            if stream_peek:
                raw = b""
                for chunk in r.iter_content(4096):
                    raw += chunk
                    if len(raw) >= 65536:
                        break
                r.close()
                r._content = raw
            body = r.text
            low = body[:4000].lower()
            if r.status_code == 451 or "unavailable in your region" in low:
                raise RuntimeError("geo-blocked via proxy")
            log(f"[GET]{label} {r.status_code} {len(body)}b {time.time()-t0:.1f}s via={p} {url[:150]} -> {r.url[:150]}")
            return r
        except Exception as exc:
            last_exc = exc
            log(f"[GET-FAIL]{label} via={p} {url[:120]} :: {type(exc).__name__}: {str(exc)[:120]}")
            CUR["i"] += 1
            tried += 1
    log(f"[GET-GIVEUP]{label} {url[:150]} :: {last_exc}")
    return None


def absolutize(base, href):
    if not href:
        return None
    href = href.strip().replace("\\/", "/")
    if href.startswith("//"):
        return "https:" + href
    return urllib.parse.urljoin(base, href)


def origin_of(url):
    u = urllib.parse.urlsplit(url)
    return f"{u.scheme}://{u.netloc}"


def record_response(kind, label, r, fname, extra=None):
    entry = {
        "kind": kind, "label": label, "file": fname,
        "url": r.url if r is not None else None,
        "status": r.status_code if r is not None else None,
        "headers": dict(r.headers) if r is not None else None,
        "history": [h.url for h in r.history] if r is not None else None,
    }
    if extra:
        entry.update(extra)
    MANIFEST["pages" if kind == "page" else "embeds"].append(entry)


# ───────────────────────── FilmMakinesi ─────────────────────────
def fm():
    log("\n=================== FILMMAKINESI ===================")
    base = None
    for cand in ["https://filmmakinesi.to", "https://filmmakinesi.film", "https://filmmakinesi.com",
                 "https://filmmakinesi.net", "https://filmmakinesi.tv", "https://filmmakinesi.pw"]:
        r = fetch(cand + "/", label=" fm-domain")
        if r is not None and r.status_code == 200 and "filmmakinesi" in r.text.lower() and len(r.text) > 5000:
            base = origin_of(r.url)
            save("fm_home.html", r.text)
            record_response("page", "fm_home", r, "fm_home.html")
            break
    if not base:
        log("[FM] canli domain yok")
        return
    log(f"[FM] taban: {base}")
    home = open(os.path.join(OUT, "fm_home.html"), encoding="utf-8").read()
    links = []
    for href in re.findall(r'href="([^"]+)"', home):
        full = absolutize(base, href)
        if not full or "filmmakinesi" not in full:
            continue
        if re.search(r"/film/[^/]+/?$", full) or re.search(r"/dizi/[^/]+/?$", full):
            if full not in links:
                links.append(full)
    films = [l for l in links if "/film/" in l][:3]
    series = [l for l in links if "/dizi/" in l][:2]
    log(f"[FM] filmler: {films}")
    log(f"[FM] diziler: {series}")

    targets = list(films[:2])
    # dizi bolumu
    for s in series[:1]:
        r = fetch(s, headers={"Referer": base + "/"}, label=" fm-series")
        if r is None:
            continue
        save("fm_series.html", r.text)
        record_response("page", "fm_series", r, "fm_series.html")
        eps = []
        for href in re.findall(r'href="([^"]+)"', r.text):
            full = absolutize(base, href)
            if full and re.search(r"/(sezon|bolum)-\d+", full) and full not in eps:
                eps.append(full)
        log(f"[FM] bolumler: {eps[:5]}")
        if eps:
            targets.append(eps[0])

    for idx, page in enumerate(targets):
        r = fetch(page, headers={"Referer": base + "/"}, label=f" fm-page{idx}")
        if r is None:
            continue
        fname = save(f"fm_page_{idx}.html", r.text)
        record_response("page", f"fm_page_{idx}", r, fname)
        body = r.text
        embeds = []
        for m in re.finditer(r'data-video_url="([^"]+)"', body):
            embeds.append(m.group(1))
        for m in re.finditer(r'<iframe[^>]+?(?:data-src|src)="([^"]+)"', body):
            embeds.append(m.group(1))
        embeds = [absolutize(page, e) for e in embeds]
        embeds = [e for e in dict.fromkeys(embeds) if e and "youtube" not in e]
        log(f"[FM] page{idx} embed adaylari: {embeds}")
        for e_idx, emb in enumerate(embeds[:4]):
            er = fetch(emb, headers={"Referer": page}, label=f" fm-embed{idx}.{e_idx}")
            if er is None:
                continue
            ename = save(f"fm_embed_{idx}_{e_idx}.html", er.text)
            record_response("embed", f"fm_embed_{idx}_{e_idx}", er, ename,
                            {"site": "fm", "page": page, "embed": emb, "referer": page})


# ───────────────────────── HDFilmCehennemi ─────────────────────────
HD_CANDS = [
    "https://www.hdfilmcehennemi.nl", "https://hdfilmcehennemi.nl",
    "https://www.hdfilmcehennemi.la", "https://www.hdfilmcehennemi.com",
    "https://hdfilmcehennemi.com", "https://www.hdfilmcehennemi.biz",
    "https://www.hdfilmcehennemi.ws", "https://www.hdfilmcehennemi.de",
    "https://www.hdfilmcehennemi.mobi", "https://hdfilmcehennemi.mobi",
]


def hd():
    log("\n=================== HDFILMCEHENNEMI ===================")
    base = None
    for cand in HD_CANDS:
        r = fetch(cand + "/", label=" hd-domain", timeout=25)
        if r is None:
            continue
        low = r.text.lower()
        log(f"[HD] {cand} -> {r.status_code} {r.url} len={len(r.text)} cf={'just a moment' in low}")
        if r.status_code == 200 and "hdfilmcehennemi" in low and len(r.text) > 5000 and base is None:
            base = origin_of(r.url)
            save("hd_home.html", r.text)
            record_response("page", "hd_home", r, "hd_home.html")
            break
        else:
            save(f"hd_domain_{urllib.parse.urlsplit(cand).netloc}.html", r.text)
    if not base:
        log("[HD] canli domain yok")
        return
    log(f"[HD] taban: {base}")

    film_links, series_links = [], []
    for kind, path in (("film", "/load/page/1/home/"), ("series", "/load/page/1/home-series/")):
        r = fetch(base + path, headers={"X-Requested-With": "fetch", "Accept": "*/*", "Referer": base + "/"},
                  label=f" hd-load-{kind}")
        if r is None:
            continue
        save(f"hd_load_{kind}.json", r.text)
        try:
            html = r.json().get("html", "")
        except Exception:
            html = r.text
        for href in re.findall(r'href="([^"]+)"', html.replace('\\"', '"')):
            full = absolutize(base, href)
            if not full or urllib.parse.urlsplit(full).netloc != urllib.parse.urlsplit(base).netloc:
                continue
            (film_links if kind == "film" else series_links).append(full)
    film_links = list(dict.fromkeys(film_links))
    series_links = list(dict.fromkeys(series_links))
    if not film_links:
        home = open(os.path.join(OUT, "hd_home.html"), encoding="utf-8").read()
        for href in re.findall(r'<a[^>]+href="([^"]+)"[^>]*title=', home):
            full = absolutize(base, href)
            if full and full.startswith(base) and full.count("/") >= 4:
                film_links.append(full)
        film_links = list(dict.fromkeys(film_links))
    log(f"[HD] filmler: {film_links[:5]}")
    log(f"[HD] diziler: {series_links[:5]}")

    targets = film_links[:2]
    for s in series_links[:1]:
        r = fetch(s, headers={"Referer": base + "/"}, label=" hd-series")
        if r is None:
            continue
        save("hd_series.html", r.text)
        record_response("page", "hd_series", r, "hd_series.html")
        eps = []
        for href in re.findall(r'href="([^"]+)"', r.text):
            full = absolutize(base, href)
            if full and re.search(r"(sezon|bolum)", full) and full not in eps and full != s:
                eps.append(full)
        log(f"[HD] bolumler: {eps[:5]}")
        if eps:
            targets.append(eps[0])

    for idx, page in enumerate(targets):
        r = fetch(page, headers={"Referer": base + "/"}, label=f" hd-page{idx}")
        if r is None:
            continue
        fname = save(f"hd_page_{idx}.html", r.text)
        record_response("page", f"hd_page_{idx}", r, fname)
        body = r.text
        vids = re.findall(r'data-video="([^"]+)"', body)
        log(f"[HD] page{idx} data-video: {vids}")
        btns = re.findall(r'<button[^>]*alternative-link[^>]*>.*?</button>', body, re.S)
        btn_short = [re.sub(r"\s+", " ", b)[:200] for b in btns[:6]]
        log(f"[HD] page{idx} butonlar: {btn_short}")
        iframes = re.findall(r'<iframe[^>]*>', body)
        log(f"[HD] page{idx} iframe: {iframes[:4]}")
        embeds = []
        for m in re.finditer(r'<iframe[^>]+?(?:data-src|src)="([^"]+)"', body):
            embeds.append(absolutize(page, m.group(1)))
        for v_idx, vid in enumerate(list(dict.fromkeys(vids))[:4]):
            api = f"{base}/video/{vid}/"
            vr = fetch(api, headers={"X-Requested-With": "fetch", "Accept": "*/*", "Referer": page},
                       label=f" hd-api{idx}.{v_idx}")
            if vr is None:
                continue
            save(f"hd_api_{idx}_{v_idx}.json", vr.text)
            text = vr.text.replace('\\"', '"').replace("\\/", "/")
            for m in re.finditer(r'(?:data-src|src)="([^"]+)"', text):
                embeds.append(absolutize(page, m.group(1)))
        embeds = [e for e in dict.fromkeys(embeds) if e and "youtube" not in e]
        log(f"[HD] page{idx} embed adaylari: {embeds}")
        for e_idx, emb in enumerate(embeds[:4]):
            er = fetch(emb, headers={"Referer": page}, label=f" hd-embed{idx}.{e_idx}")
            if er is None:
                continue
            ename = save(f"hd_embed_{idx}_{e_idx}.html", er.text)
            record_response("embed", f"hd_embed_{idx}_{e_idx}", er, ename,
                            {"site": "hd", "page": page, "embed": emb, "referer": page})


def main():
    log(f"[proxy] havuz: {PROXIES[:10]}")
    for fn in (fm, hd):
        try:
            fn()
        except Exception:
            log(f"[HATA] {fn.__name__}\n{traceback.format_exc()}")
    json.dump(MANIFEST, open(os.path.join(OUT, "manifest.json"), "w"), indent=1)
    save("_crawl_report.txt", "\n".join(REPORT))


if __name__ == "__main__":
    main()
