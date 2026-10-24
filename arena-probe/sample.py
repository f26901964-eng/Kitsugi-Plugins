#!/usr/bin/env python3
"""Taze oynatici ornekleri toplar (gecici).

Her oynatici sayfasi K kez indirilir: sablon (degisken adlari, olu dallar,
host adi adimi) her istekte rastgele degistigi icin Kotlin cozucusunun
bircok farkli varyant uzerinde dogrulanmasi amaclanir.
Cikti: out2/manifest.json + out2/*.html
"""
import json
import os
import re
import urllib.parse

import crawl
from crawl import fetch, log, absolutize

OUT2 = os.path.join(crawl.HERE, "out2")
os.makedirs(OUT2, exist_ok=True)
MAN = {"pages": [], "embeds": []}
K = int(os.environ.get("SAMPLE_REPEAT", "3"))


def save2(name, text):
    with open(os.path.join(OUT2, name), "w", encoding="utf-8", errors="replace") as fh:
        fh.write(text)
    return name


def dump():
    json.dump(MAN, open(os.path.join(OUT2, "manifest.json"), "w"), indent=1)


def fetch_embeds(site, idx, page, embeds):
    for e_idx, emb in enumerate(embeds[:4]):
        for k in range(K):
            er = fetch(emb, headers={"Referer": page}, label=f" {site}-embed{idx}.{e_idx}.{k}", rounds=2)
            if er is None or er.status_code != 200 or len(er.text) < 2000:
                continue
            label = f"{site}_{idx}_{e_idx}_{k}"
            fname = save2(label + ".html", er.text)
            MAN["embeds"].append({"label": label, "file": fname, "url": er.url, "embed": emb,
                                  "page": page, "referer": page, "site": site, "status": er.status_code})
            dump()


def fm():
    base = "https://filmmakinesi.to"
    r = fetch(base + "/", label=" fm-home", rounds=3)
    if r is None:
        log("[S-FM] ana sayfa yok")
        return
    links = []
    for href in re.findall(r'href="([^"]+)"', r.text):
        full = absolutize(base, href)
        if full and (re.search(r"/film/[^/]+/?$", full) or re.search(r"/dizi/[^/]+/?$", full)) and full not in links:
            links.append(full)
    films = [l for l in links if "/film/" in l][2:6]
    series = [l for l in links if "/dizi/" in l][:2]
    pages = list(films)
    for s in series[:1]:
        sr = fetch(s, headers={"Referer": base + "/"}, label=" fm-series")
        if sr is None:
            continue
        eps = []
        for href in re.findall(r'href="([^"]+)"', sr.text):
            full = absolutize(base, href)
            if full and re.search(r"/sezon-\d+/bolum-\d+", full) and full not in eps:
                eps.append(full)
        if eps:
            pages.append(eps[-1])
    log(f"[S-FM] sayfalar: {pages}")
    for idx, page in enumerate(pages):
        pr = fetch(page, headers={"Referer": base + "/"}, label=f" fm-page{idx}")
        if pr is None:
            continue
        MAN["pages"].append({"site": "fm", "page": page, "status": pr.status_code})
        embeds = [m.group(1) for m in re.finditer(r'data-video_url="([^"]+)"', pr.text)]
        embeds += [m.group(1) for m in re.finditer(r'<iframe[^>]+?data-src="([^"]+)"', pr.text)]
        embeds = [absolutize(page, e) for e in embeds]
        embeds = [e for e in dict.fromkeys(embeds) if e and "youtube" not in e]
        log(f"[S-FM] page{idx} {page} -> {embeds}")
        fetch_embeds("fm", idx, page, embeds)


def hd():
    base = "https://www.hdfilmcehennemi.nl"
    film_links, series_links = [], []
    for kind, path in (("film", "/load/page/2/home/"), ("series", "/load/page/1/home-series/")):
        r = fetch(base + path, headers={"X-Requested-With": "fetch", "Accept": "*/*", "Referer": base + "/"},
                  label=f" hd-load-{kind}", rounds=3)
        if r is None:
            continue
        try:
            html = r.json().get("html", "")
        except Exception:
            html = r.text
        for href in re.findall(r'href="([^"]+)"', html.replace('\\"', '"')):
            full = absolutize(base, href)
            if full and urllib.parse.urlsplit(full).netloc == urllib.parse.urlsplit(base).netloc:
                (film_links if kind == "film" else series_links).append(full)
    film_links = list(dict.fromkeys(film_links))
    series_links = list(dict.fromkeys(series_links))
    pages = film_links[:3]
    for s in series_links[:1]:
        sr = fetch(s, headers={"Referer": base + "/"}, label=" hd-series")
        if sr is None:
            continue
        eps = []
        for href in re.findall(r'href="([^"]+)"', sr.text):
            full = absolutize(base, href)
            if full and re.search(r"(sezon-\d+|bolum-\d+)", full) and full not in eps and full != s:
                eps.append(full)
        save2("hd_series_page.html", sr.text)
        if eps:
            pages.append(eps[0])
    log(f"[S-HD] sayfalar: {pages}")
    for idx, page in enumerate(pages):
        pr = fetch(page, headers={"Referer": base + "/"}, label=f" hd-page{idx}")
        if pr is None:
            continue
        MAN["pages"].append({"site": "hd", "page": page, "status": pr.status_code})
        if idx == len(pages) - 1:
            save2("hd_last_page.html", pr.text)
        vids = list(dict.fromkeys(re.findall(r'data-video="([^"]+)"', pr.text)))
        embeds = [absolutize(page, m.group(1)) for m in re.finditer(r'<iframe[^>]+?data-src="([^"]+)"', pr.text)]
        for v_idx, vid in enumerate(vids[:4]):
            vr = fetch(f"{base}/video/{vid}/", headers={"X-Requested-With": "fetch", "Accept": "*/*", "Referer": page},
                       label=f" hd-api{idx}.{v_idx}")
            if vr is None:
                continue
            text = vr.text.replace('\\"', '"').replace("\\/", "/")
            for m in re.finditer(r'(?:data-src|src)="([^"]+)"', text):
                embeds.append(absolutize(page, m.group(1)))
        embeds = [e for e in dict.fromkeys(embeds) if e and "youtube" not in e]
        log(f"[S-HD] page{idx} {page} vids={vids} -> {embeds}")
        fetch_embeds("hd", idx, page, embeds)


if __name__ == "__main__":
    for fn in (fm, hd):
        try:
            fn()
        except Exception as exc:
            log(f"[S] {fn.__name__} hata: {exc}")
    dump()
    with open(os.path.join(OUT2, "_sample_report.txt"), "w") as fh:
        fh.write("\n".join(crawl.REPORT))
    log(f"[S] toplam oynatici ornegi: {len(MAN['embeds'])}")
