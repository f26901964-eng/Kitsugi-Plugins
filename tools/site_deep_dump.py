#!/usr/bin/env python3
"""Anizm + TrAnimeIzle **tam HTML / tam zincir dökümü** (salt-okunur, canlı site).

Bu araç eklentilerin dayandığı HER ŞEYİ ham hâliyle diske yazar:

* ana sayfa, arama sayfası, anime detay sayfası, bölüm sayfası HTML'i,
* sayfadaki tüm `<script src>` dosyaları (oynatıcı/AJAX uçlarını bulmak için),
* bölüm sayfasındaki çevirmen/fansub butonları ve onların JSON yanıtları,
* her video kimliği için oynatıcı JSON'u, `/player/<id>` yönlendirmesi,
* anizmplayer.com `getVideo` yanıtı (iki farklı referer varyantıyla),
* TrAnimeIzle `fansubSources` / `sourcePlayer` / `preVideoAd` yanıtları.

Çıktı `site-dump/` klasörüne yazılır ve CI'da artifact olarak yüklenir; böylece
site HTML'i tahmin etmeden, satır satır incelenebilir.

Kullanım:
    python3 tools/site_deep_dump.py                 # ikisi
    python3 tools/site_deep_dump.py anizm
    python3 tools/site_deep_dump.py tranime
"""
from __future__ import annotations

import gzip
import html as html_mod
import json
import os
import re
import ssl
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
)
BASE_HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
    "Accept-Encoding": "gzip",
}
SSL_CTX = ssl.create_default_context()
MAX_BYTES = 6 * 1024 * 1024
OUT = os.environ.get("SITE_DUMP_DIR", "site-dump")

LOG: list[str] = []


def log(line: str = "") -> None:
    print(line, flush=True)
    LOG.append(line)


class Fetched:
    def __init__(self, url, status, text, headers, final_url, error=""):
        self.url = url
        self.status = status
        self.text = text
        self.headers = headers
        self.final_url = final_url
        self.error = error

    @property
    def ok(self):
        return 200 <= self.status < 300 and not self.error

    def header(self, name):
        for k, v in self.headers.items():
            if k.lower() == name.lower():
                return v
        return ""


def _decompress(raw: bytes, encoding: str) -> str:
    enc = (encoding or "").lower()
    if "gzip" in enc:
        try:
            raw = gzip.GzipFile(fileobj=__import__("io").BytesIO(raw)).read()
        except Exception:  # noqa: BLE001
            pass
    return raw.decode("utf-8", "replace")


def _curl(url, referer, data, headers_extra):
    cmd = [
        "curl", "-sS", "-L", "--compressed", "--max-time", "25",
        "-A", UA, "-w", "\n__CURL_STATUS__%{http_code}",
    ]
    if referer:
        cmd += ["-e", referer]
    for k, v in headers_extra.items():
        cmd += ["-H", f"{k}: {v}"]
    if data is not None:
        cmd += ["--data-raw", data]
    cmd.append(url)
    try:
        out = subprocess.run(cmd, capture_output=True, timeout=40).stdout.decode("utf-8", "replace")
    except Exception as exc:  # noqa: BLE001
        return 0, "", str(exc)
    m = re.search(r"\n__CURL_STATUS__(\d+)$", out)
    status = int(m.group(1)) if m else 0
    return status, out[: m.start()] if m else out, ""


def fetch(url, referer="", data=None, xhr=False, follow=True, extra=None, cookie=""):
    headers = dict(BASE_HEADERS)
    if referer:
        headers["Referer"] = referer
    if data is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        headers["Accept"] = "application/json, text/plain, */*"
    if xhr:
        headers["X-Requested-With"] = "XMLHttpRequest"
    if cookie:
        headers["Cookie"] = cookie
    if extra:
        headers.update(extra)

    # 1) önce curl (gerçek tarayıcı TLS parmak izine daha yakın), sonra urllib
    order = ("curl", "urllib")
    result = None
    for kind in order:
        if kind == "curl":
            status, text, err = _curl(url, referer, data, headers)
            hdrs = {}
            if follow is False:
                # curl -L kullanıldı; yönlendirmesiz istek için ayrı çağrı
                status, text, err = _curl_nofollow(url, referer, data, headers)
            result = Fetched(url, status, text, hdrs, url, err)
        else:
            body = data.encode() if isinstance(data, str) else data
            req = urllib.request.Request(url, data=body, headers=headers)
            opener = urllib.request.build_opener()
            if not follow:
                class NoRedirect(urllib.request.HTTPRedirectHandler):
                    def redirect_request(self, *a, **kw):  # noqa: D401
                        return None

                opener = urllib.request.build_opener(NoRedirect)
            try:
                with opener.open(req, timeout=25) as resp:
                    raw = resp.read(MAX_BYTES)
                    result = Fetched(
                        url,
                        resp.status,
                        _decompress(raw, resp.headers.get("Content-Encoding", "")),
                        dict(resp.headers),
                        resp.geturl(),
                    )
            except urllib.error.HTTPError as exc:
                raw = exc.read(MAX_BYTES)
                result = Fetched(
                    url,
                    exc.code,
                    _decompress(raw, exc.headers.get("Content-Encoding", "") if exc.headers else ""),
                    dict(exc.headers or {}),
                    exc.geturl(),
                )
            except Exception as exc:  # noqa: BLE001
                if result is None:
                    result = Fetched(url, 0, "", {}, url, str(exc))
        if result is not None and result.ok and len(result.text) > 400:
            return result
    return result


def _curl_nofollow(url, referer, data, headers_extra):
    cmd = ["curl", "-sS", "--compressed", "--max-time", "25", "-A", UA,
           "-D", "-", "-o", "/dev/null", "-w", "%{http_code}"]
    if referer:
        cmd += ["-e", referer]
    for k, v in (headers_extra or {}).items():
        if k in ("Accept-Encoding", "User-Agent"):
            continue
        cmd += ["-H", f"{k}: {v}"]
    if data is not None:
        cmd += ["--data-raw", data]
    cmd.append(url)
    try:
        out = subprocess.run(cmd, capture_output=True, timeout=40).stdout.decode("utf-8", "replace")
    except Exception as exc:  # noqa: BLE001
        return 0, "", str(exc)
    status = 0
    m = re.search(r"(\d{3})\s*$", out.strip())
    if m:
        status = int(m.group(1))
    return status, out, ""


def save(name: str, text: str) -> str:
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    try:
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text or "")
    except Exception as exc:  # noqa: BLE001
        log(f"  ! yazılamadı {path}: {exc}")
    return path


def save_resp(name: str, r: Fetched) -> None:
    head = (
        f"# {r.url}\n# status={r.status} final={r.final_url} error={r.error}\n"
        f"# content-type={r.header('Content-Type')} location={r.header('Location')}\n"
        f"# bytes={len(r.text)}\n\n"
    )
    save(name, head + (r.text or ""))
    log(f"  → {name}: status={r.status} bytes={len(r.text)} loc={r.header('Location')[:120]}")


def uniq(seq):
    seen, out = set(), []
    for x in seq:
        if x and x not in seen:
            seen.add(x)
            out.append(x)
    return out


def scripts_of(html: str, base: str) -> list[str]:
    urls = re.findall(r"""<script[^>]+src=["']([^"']+)["']""", html, re.I)
    out = []
    for u in urls:
        out.append(urllib.parse.urljoin(base, html_mod.unescape(u)))
    return uniq(out)


def looks_blocked(text: str) -> bool:
    low = (text or "").lower()
    return ("just a moment" in low and "challenge-platform" in low) or "cf-browser-verification" in low


# ══════════════════════════════════════════════════════════════════════════
# ANIZM
# ══════════════════════════════════════════════════════════════════════════
ANIZM_DOMAINS = [
    "https://anizm.net",
    "https://anizm.com.tr",
    "https://anizm.org",
    "https://anizm.tv",
]


def dump_anizm() -> None:
    log("\n## ANIZM")
    base = ""
    home = None
    for dom in ANIZM_DOMAINS:
        r = fetch(dom + "/")
        tag = dom.split("//")[1].replace(".", "_")
        save_resp(f"anizm__home__{tag}.html", r)
        log(f"- {dom}/ → status={r.status} bytes={len(r.text)} ctype={r.header('Content-Type')}")
        if r.ok and not looks_blocked(r.text):
            base, home = dom, r
            break
    if not home:
        log("- !! Anizm ana sayfasına erişilemedi (tüm alan adları).")
        return

    html = home.text
    for sel in [
        "v3-catalog-card", "v3-series-card", "v3-episode-card", "v3-catalog-poster",
        "data-v3-deferred-src", "v3-card-copy", "v3-poster-rail", "arama", "searchAnime",
        "episodeListTabContent", "bolumKutucugu", "data-anime-episode-grid",
    ]:
        log(f"  · '{sel}' geçiş sayısı: {html.count(sel)}")

    # ---- arama uçları ----
    queries = ["naruto", "attack on titan", "shingeki no kyojin"]
    search_tried = []
    for q in queries:
        for path in ["/arama?arama={q}", "/search?q={q}", "/arama/{q}", "/searchAnime?q={q}"]:
            url = base + path.format(q=urllib.parse.quote(q))
            if url in search_tried:
                continue
            search_tried.append(url)
            r = fetch(url, referer=base + "/")
            ctype = r.header("Content-Type")
            name = "anizm__search__" + re.sub(r"[^a-z0-9]+", "_", path.format(q=q).lower())[:60] + ".txt"
            save_resp(name, r)
            hits = len(re.findall(r'href="[^"]*(?:/anime/|/seri/|/[a-z0-9-]+-bolum)', r.text or ""))
            log(f"  · arama {url} → {r.status} ctype={ctype} linkSayısı={hits}")

    # ---- anime detay sayfası ----
    detail_candidates = uniq(
        re.findall(r"""href=["'](https?://[^"']*anizm[^"']*/(?:anime|seri|dizi)/[^"'#?]+)["']""", html, re.I)
        + [urllib.parse.urljoin(base, h) for h in re.findall(
            r"""href=["'](/[^"'#?]*(?:anime|seri)/[^"'#?]+)["']""", html, re.I)]
    )
    if not detail_candidates:
        detail_candidates = uniq(urllib.parse.urljoin(base, h) for h in re.findall(r"""href=["'](/[a-z0-9-]{3,})["']""", html))[:12]
    log(f"- detay adayı: {len(detail_candidates)} → {detail_candidates[:3]}")
    detail_url = detail_candidates[0] if detail_candidates else ""
    detail = None
    for cand in detail_candidates[:6]:
        r = fetch(cand, referer=base + "/")
        if r.ok:
            detail, detail_url = r, cand
            break
    if detail:
        save_resp("anizm__detail.html", detail)
        d = detail.text
        for sel in ["v3-anime-detail-cover", "v3-anime-detail-meta", "v3-anime-detail-tags",
                    "v3-anime-summary-content", "data-anime-episode-grid", "data-episode-id",
                    "bolumKutucugu", "episodeListTabContent", "data-progress", "data-url=", "cevirmen"]:
            log(f"  · detay '{sel}': {d.count(sel)}")
        for s in scripts_of(d, base)[:10]:
            sr = fetch(s, referer=detail_url)
            save_resp("anizm__js__" + re.sub(r"[^A-Za-z0-9]+", "_", s.split("//")[-1])[:80], sr)

        ep_links = uniq(
            re.findall(r"""href=["'](https?://[^"']*anizm[^"']*/[^"'#?]*bolum[^"'#?]*)["']""", d, re.I)
            + [urllib.parse.urljoin(base, h) for h in re.findall(
                r"""href=["'](/[^"'#?]*bolum[^"'#?]*)["']""", d, re.I)]
        )
        log(f"  · bölüm bağlantısı: {len(ep_links)} → {ep_links[:3]}")
        if ep_links:
            dump_anizm_episode(ep_links[0], base)
    else:
        log("- !! detay sayfası açılamadı")


def dump_anizm_episode(ep_url: str, base: str) -> None:
    r = fetch(ep_url, referer=base + "/")
    save_resp("anizm__episode.html", r)
    if not r.ok:
        return
    page = r.text
    for sel in ["data-sources", "cevirmen", "translator", "videoPlayerButtons", "data-episode-id",
                "data-anime-episode-grid", "player/", "bolum/"]:
        log(f"  · bölüm '{sel}': {page.count(sel)}")

    ep_id = ""
    m = (re.search(r"/bolum/(\d+)", ep_url)
         or re.search(r'data-url=["\'][^"\']*/bolum/(\d+)', page)
         or re.search(r'data-episode-id=["\'](\d+)', page)
         or re.search(r'data-progress=["\'][^"\']*/bolum/(\d+)', page))
    if m:
        ep_id = m.group(1)
    log(f"  · epId={ep_id}")

    for s in scripts_of(page, base)[:10]:
        sr = fetch(s, referer=ep_url)
        save_resp("anizm__epjs__" + re.sub(r"[^A-Za-z0-9]+", "_", s.split("//")[-1])[:80], sr)

    # çevirmenler: hem data-sources hem translator= hem /cevirmen/
    trs = uniq(
        re.findall(r'data-sources=["\']([^"\']+)["\']', page)
        + re.findall(r'translator=["\']([^"\']+)["\']', page)
        + re.findall(r'(/[^"\'\s]*cevirmen/\d+[^"\'\s]*)', page)
        + re.findall(r'(https?://[^"\'\s]*/episode/\d+/translator/\d+)', page)
    )
    log(f"  · çevirmen kaynağı: {len(trs)} → {trs[:4]}")

    videos: list[str] = []
    for i, tr in enumerate(trs[:8]):
        turl = urllib.parse.urljoin(base, html_mod.unescape(tr))
        trr = fetch(turl, referer=ep_url, xhr=True)
        save_resp(f"anizm__translator_{i}.json", trr)
        body = trr.text or ""
        try:
            payload = json.loads(body)
            if isinstance(payload, dict) and isinstance(payload.get("data"), str):
                body = payload["data"]
        except Exception:  # noqa: BLE001
            pass
        videos += re.findall(r'video=["\']([^"\']+)["\']', body)
        videos += re.findall(r'/bolum/(\d+)/video/(\d+)', body) and [
            f"/bolum/{a}/video/{b}" for a, b in re.findall(r"/bolum/(\d+)/video/(\d+)", body)
        ] or []
        videos += re.findall(r"/video/(\d+)", body) and [
            f"/video/{v}" for v in re.findall(r"/video/(\d+)", body)
        ] or []
        log(f"    · çevirmen[{i}] {turl} → {trr.status} videoAttr={len(re.findall(chr(34)+'video=', body))}")

    videos = uniq(videos)
    log(f"  · video kimliği: {len(videos)} → {videos[:6]}")

    hashes = set()
    for i, v in enumerate(videos[:14]):
        vurl = urllib.parse.urljoin(ep_url, html_mod.unescape(v))
        if not re.search(r"/\d+", vurl):
            continue
        vr = fetch(vurl, referer=ep_url, xhr=True)
        save_resp(f"anizm__video_{i}.json", vr)
        try:
            payload = json.loads(vr.text or "{}")
        except Exception:  # noqa: BLE001
            payload = {}
        log(f"    · video[{i}] {vurl} → {vr.status} keys={list(payload)[:6] if isinstance(payload, dict) else type(payload).__name__}")
        if isinstance(payload, dict):
            for val in (payload.get("url"), payload.get("playerOrigin"), payload.get("videoSource"),
                        payload.get("securedLink")):
                if isinstance(val, str):
                    hashes |= set(re.findall(r"\b[a-f0-9]{32}\b", val))

        # /player/<id> yönlendirmesi (eski sözleşme)
        pid = re.search(r"/video/(\d+)", vurl)
        if pid:
            purl = f"{base}/player/{pid.group(1)}"
            pr = fetch(purl, referer=ep_url, follow=False)
            save_resp(f"anizm__player_{i}_head.txt", pr)
            if pr.header("Location"):
                target = pr.header("Location")
                log(f"      · /player yönlendirme → {target[:160]}")
                hashes |= set(re.findall(r"\b[a-f0-9]{32}\b", target))

    # anizmplayer getVideo (iki referer varyantı)
    for h in sorted(hashes)[:6]:
        for variant, rref in (("site", base + "/"), ("player", f"https://anizmplayer.com/video/{h}")):
            body = urllib.parse.urlencode({"hash": h, "r": rref})
            r2 = fetch(
                f"https://anizmplayer.com/player/index.php?data={h}&do=getVideo",
                referer="https://anizmplayer.com/",
                data=body,
                xhr=True,
                extra={"Origin": "https://anizmplayer.com"},
            )
            save_resp(f"anizm__getVideo_{h[:8]}_{variant}.json", r2)
            log(f"  · getVideo {h[:8]} ({variant}) → {r2.status} bytes={len(r2.text)}")


# ══════════════════════════════════════════════════════════════════════════
# TRANIMEIZLE
# ══════════════════════════════════════════════════════════════════════════
TIZLE_DOMAINS = [
    "https://www.tranimeizle.live",
    "https://www.tranimeizle.io",
    "https://www.tranimeizle.top",
]


def dump_tranimeizle() -> None:
    log("\n## TRANIMEIZLE")
    base, home = "", None
    for dom in TIZLE_DOMAINS:
        r = fetch(dom + "/")
        save_resp("tizle__home__" + dom.split("//")[1].replace(".", "_") + ".html", r)
        log(f"- {dom}/ → status={r.status} bytes={len(r.text)} ctype={r.header('Content-Type')}")
        if r.ok and not looks_blocked(r.text):
            base, home = dom, r
            break
    if not home:
        log("- !! TrAnimeIzle ana sayfasına erişilemedi.")
        return
    html = home.text
    for sel in ["fansubSelector", "sourceBtn", "data-fid", "data-fad", "animeDetail",
                "/api/fansubSources", "/api/sourcePlayer"]:
        log(f"  · '{sel}': {html.count(sel)}")

    for q in ["naruto", "attack on titan"]:
        for path in ["/arama?q={q}", "/search?q={q}", "/arama/{q}"]:
            url = base + path.format(q=urllib.parse.quote(q))
            r = fetch(url, referer=base + "/")
            save_resp("tizle__search__" + re.sub(r"[^a-z0-9]+", "_", path.format(q=q).lower())[:60] + ".txt", r)
            log(f"  · arama {url} → {r.status} ctype={r.header('Content-Type')}")

    links = uniq([urllib.parse.urljoin(base, h) for h in re.findall(
        r"""href=["'](/[^"'#?]*(?:anime|seri)[^"'#?]*)["']""", html, re.I)])
    log(f"- detay adayı: {len(links)} → {links[:3]}")
    for cand in links[:4]:
        d = fetch(cand, referer=base + "/")
        if not d.ok:
            continue
        save_resp("tizle__detail.html", d)
        body = d.text
        for sel in ["fansubSelector", "data-fid", "data-fad", "bolum", "episode"]:
            log(f"  · detay '{sel}': {body.count(sel)}")
        for s in scripts_of(body, base)[:10]:
            sr = fetch(s, referer=cand)
            save_resp("tizle__js__" + re.sub(r"[^A-Za-z0-9]+", "_", s.split("//")[-1])[:80], sr)
        eps = uniq([urllib.parse.urljoin(base, h) for h in re.findall(
            r"""href=["'](/[^"'#?]*-[^"'#?]*bolum[^"'#?]*)["']""", body, re.I)])
        log(f"  · bölüm: {len(eps)} → {eps[:3]}")
        if eps:
            dump_tizle_episode(eps[0], base)
            break


def dump_tizle_episode(ep_url: str, base: str) -> None:
    r = fetch(ep_url, referer=base + "/")
    save_resp("tizle__episode.html", r)
    if not r.ok:
        return
    page = r.text
    for sel in ["fansubSelector", "data-fid", "data-fad", "sourceBtn", "data-id", "player"]:
        log(f"  · bölüm '{sel}': {page.count(sel)}")
    for s in scripts_of(page, base)[:10]:
        sr = fetch(s, referer=ep_url)
        save_resp("tizle__epjs__" + re.sub(r"[^A-Za-z0-9]+", "_", s.split("//")[-1])[:80], sr)

    fansubs = uniq(re.findall(r'data-fid=["\'](\d+)["\'][^>]*?data-fad=["\'](\d+)["\']', page))
    if not fansubs:
        fansubs = uniq([(a, b) for b, a in re.findall(r'data-fad=["\'](\d+)["\'][^>]*?data-fid=["\'](\d+)["\']', page)])
    log(f"  · fansub (fid,fad): {len(fansubs)} → {fansubs[:4]}")

    ids: list[str] = []
    for fid, fad in fansubs[:6]:
        payload = json.dumps({"fansub_id": fid, "fansub_adi": "", "anime_id": fad, "anime_adi": ""})
        for variant, body in (
            ("named", payload),
            ("raw", json.dumps({"fid": fid, "fad": fad})),
        ):
            sr = fetch(base + "/api/fansubSources", referer=ep_url, data=body, xhr=True,
                       extra={"Content-Type": "application/json; charset=UTF-8"})
            save_resp(f"tizle__fansubSources_{fid}_{fad}_{variant}.json", sr)
            ids += re.findall(r'data-id=["\'](\d+)["\']', sr.text or "")
            ids += re.findall(r'"id"\s*:\s*"?(\d+)"?', sr.text or "")
            log(f"    · fansubSources({fid},{fad},{variant}) → {sr.status} ids={len(ids)}")

    ids = uniq(ids)
    log(f"  · kaynak id: {len(ids)} → {ids[:6]}")
    for i, sid in enumerate(ids[:16]):
        for path in (f"/api/sourcePlayer/{sid}", f"/api/preVideoAd/{sid}"):
            sr = fetch(base + path, referer=ep_url, xhr=True,
                       extra={"Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"})
            save_resp("tizle__" + path.strip("/").replace("/", "_") + ".json", sr)
            iframe = re.search(r'<iframe[^>]+src=["\']([^"\']+)', sr.text or "")
            log(f"    · {path} → {sr.status} iframe={(iframe.group(1)[:110] if iframe else '-')}")


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    which = args[0].lower() if args else "all"
    os.makedirs(OUT, exist_ok=True)
    if which in ("all", "anizm"):
        try:
            dump_anizm()
        except Exception as exc:  # noqa: BLE001
            log(f"- !! Anizm dökümü çöktü: {exc!r}")
    if which in ("all", "tranime", "tranimeizle"):
        try:
            dump_tranimeizle()
        except Exception as exc:  # noqa: BLE001
            log(f"- !! TrAnimeIzle dökümü çöktü: {exc!r}")
    save("SUMMARY.md", "\n".join(LOG) + "\n")
    print("\n".join(LOG[-40:]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
