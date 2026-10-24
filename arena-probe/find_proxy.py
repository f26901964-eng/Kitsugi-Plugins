#!/usr/bin/env python3
"""Turkiye cikisli calisan proxy bul (gecici arastirma betigi).

FilmMakinesi / HDFilmCehennemi TR disini 451 ile engelliyor. GitHub runner'i
ABD'de oldugu icin canli HTML'e ancak TR cikisli bir proxy uzerinden ulasilir.
"""
import concurrent.futures as cf
import json
import os
import re
import time

import requests

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
os.makedirs(OUT, exist_ok=True)

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")

SOURCES_TEXT = [
    ("proxifly", "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/countries/TR/data.txt", None),
    ("proxyscrape-http", "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&country=tr&protocol=http&proxy_format=protocolipport&format=text&timeout=20000", None),
    ("proxyscrape-s4", "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&country=tr&protocol=socks4&proxy_format=protocolipport&format=text&timeout=20000", None),
    ("proxyscrape-s5", "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&country=tr&protocol=socks5&proxy_format=protocolipport&format=text&timeout=20000", None),
    ("pld-http", "https://www.proxy-list.download/api/v1/get?type=http&country=TR", "http"),
    ("pld-https", "https://www.proxy-list.download/api/v1/get?type=https&country=TR", "http"),
    ("pld-s4", "https://www.proxy-list.download/api/v1/get?type=socks4&country=TR", "socks4"),
    ("pld-s5", "https://www.proxy-list.download/api/v1/get?type=socks5&country=TR", "socks5"),
]
# Ulke bilgisi satir icinde olan listeler (filtrelenir)
SOURCES_TAGGED = [
    ("spys", "https://spys.me/proxy.txt", "http", r"^(\d+\.\d+\.\d+\.\d+:\d+)\s+TR-"),
    ("spys-s", "https://spys.me/socks.txt", "socks5", r"^(\d+\.\d+\.\d+\.\d+:\d+)\s+TR-"),
    ("rooster-http", "https://raw.githubusercontent.com/roosterkid/openproxylist/main/HTTPS.txt", "http", r"(\d+\.\d+\.\d+\.\d+:\d+).*\bTR\b"),
    ("rooster-s4", "https://raw.githubusercontent.com/roosterkid/openproxylist/main/SOCKS4.txt", "socks4", r"(\d+\.\d+\.\d+\.\d+:\d+).*\bTR\b"),
    ("rooster-s5", "https://raw.githubusercontent.com/roosterkid/openproxylist/main/SOCKS5.txt", "socks5", r"(\d+\.\d+\.\d+\.\d+:\d+).*\bTR\b"),
]


def log(*a):
    print(*a, flush=True)


def collect():
    cands = {}
    for name, url, default_scheme in SOURCES_TEXT:
        try:
            r = requests.get(url, timeout=30, headers={"User-Agent": UA})
            n = 0
            for line in r.text.splitlines():
                line = line.strip()
                if not line:
                    continue
                if "://" not in line:
                    if not re.match(r"^\d+\.\d+\.\d+\.\d+:\d+$", line):
                        continue
                    line = f"{default_scheme or 'http'}://{line}"
                line = line.replace("https://", "http://")
                cands[line] = name
                n += 1
            log(f"[src] {name}: {n}")
        except Exception as exc:
            log(f"[src] {name}: FAIL {exc}")
    for name, url, scheme, pattern in SOURCES_TAGGED:
        try:
            r = requests.get(url, timeout=30, headers={"User-Agent": UA})
            n = 0
            for line in r.text.splitlines():
                m = re.search(pattern, line)
                if m:
                    cands[f"{scheme}://{m.group(1)}"] = name
                    n += 1
            log(f"[src] {name}: {n}")
        except Exception as exc:
            log(f"[src] {name}: FAIL {exc}")
    # geonode JSON
    try:
        r = requests.get("https://proxylist.geonode.com/api/proxy-list?country=TR&limit=500&page=1"
                         "&sort_by=lastChecked&sort_type=desc", timeout=30, headers={"User-Agent": UA})
        n = 0
        for item in r.json().get("data", []):
            for proto in item.get("protocols", []):
                proto = "http" if proto in ("http", "https") else proto
                cands[f"{proto}://{item['ip']}:{item['port']}"] = "geonode"
                n += 1
        log(f"[src] geonode: {n}")
    except Exception as exc:
        log(f"[src] geonode: FAIL {exc}")
    return cands


def to_requests_proxy(p):
    if p.startswith("socks5://"):
        p = "socks5h://" + p[len("socks5://"):]
    if p.startswith("socks4://"):
        p = "socks4a://" + p[len("socks4://"):]
    return {"http": p, "https": p}


TEST_URL = "https://filmmakinesi.to/"


def test(p):
    t0 = time.time()
    try:
        r = requests.get(TEST_URL, proxies=to_requests_proxy(p), timeout=15,
                         headers={"User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9"})
        body = r.text
        dt = time.time() - t0
        low = body.lower()
        geo_blocked = "unavailable in your region" in low or r.status_code == 451
        ok = (r.status_code == 200 and len(body) > 5000 and "filmmakinesi" in low and not geo_blocked)
        return p, ok, dt, r.status_code, len(body), geo_blocked
    except Exception as exc:
        return p, False, time.time() - t0, None, 0, str(type(exc).__name__)


def main():
    cands = collect()
    log(f"[total] {len(cands)} aday")
    good = []
    with cf.ThreadPoolExecutor(max_workers=96) as ex:
        for p, ok, dt, code, ln, geo in ex.map(test, list(cands.keys())):
            if ok:
                log(f"[OK] {p} {dt:.1f}s code={code} len={ln} (kaynak={cands[p]})")
                good.append({"proxy": p, "latency": dt, "source": cands[p]})
            elif code is not None:
                log(f"[--] {p} code={code} len={ln} geo={geo}")
    good.sort(key=lambda x: x["latency"])
    with open(os.path.join(OUT, "proxies.json"), "w") as fh:
        json.dump(good, fh, indent=1)
    log(f"[done] calisan TR proxy: {len(good)}")


if __name__ == "__main__":
    main()
