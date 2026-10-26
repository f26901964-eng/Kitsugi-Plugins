#!/usr/bin/env python3
"""Anizm + TrAnimeIzle **tam HTML / tam zincir dökümü** (salt-okunur, canlı site).

Eklentilerin dayandığı her şeyi ham hâliyle diske yazar:

* ana sayfa, arama, anime detay, bölüm sayfası HTML'i (tam, kırpılmadan),
* sayfadaki `<script src>` dosyaları,
* `button[data-v3-translator]` / `[data-sources]` / `[data-video][data-url]` etiketleri,
* `meta[name=csrf-token]`,
* `data-sources` → `{videos:[...]}` JSON'u (düz / XHR / XHR+CSRF varyantları),
* `/video/<id>` JSON'u, `/player/<id>` 302 `Location` başlığı,
* anizmplayer `getVideo` yanıtı (referer varyantları),
* TrAnimeIzle `fansubSources` / `sourcePlayer` / `preVideoAd` yanıtları.

Çıktı `site-dump/` altına yazılır; CI bu klasörü `site-dump-data` dalına iter,
böylece site HTML'i tahmin etmeden satır satır incelenebilir.

Kullanım:
    python3 tools/site_deep_dump.py            # ikisi
    python3 tools/site_deep_dump.py anizm
    python3 tools/site_deep_dump.py tranime
"""
from __future__ import annotations

import gzip
import html as html_mod
import io
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
MAX_BYTES = 8 * 1024 * 1024
OUT = os.environ.get("SITE_DUMP_DIR", "site-dump")

LOG: list[str] = []
SECRETS: set[str] = set()


def log(line: str = "") -> None:
    print(line, flush=True)
    LOG.append(line)


class Fetched:
    def __init__(self, url, status, text, headers, final_url, error="", via=""):
        self.url = url
        self.status = status
        self.text = text
        self.headers = headers
        self.final_url = final_url
        self.error = error
        self.via = via

    @property
    def ok(self):
        return 200 <= self.status < 300 and not self.error

    def header(self, name):
        for k, v in (self.headers or {}).items():
            if k.lower() == name.lower():
                return v
        return ""


def _decompress(raw: bytes, encoding: str) -> str:
    if "gzip" in (encoding or "").lower():
        try:
            raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
        except Exception:  # noqa: BLE001
            pass
    return raw.decode("utf-8", "replace")


def _curl(url, referer="", data=None, headers_extra=None, follow=True):
    cmd = ["curl", "-sS", "--compressed", "--max-time", "30", "-A", UA]
    cmd += ["-L"] if follow else []
    cmd += ["-w", "\n__STATUS__%{http_code}"]
    if referer:
        cmd += ["-e", referer]
    for k, v in (headers_extra or {}).items():
        if k.lower() in ("user-agent",):
            continue
        cmd += ["-H", f"{k}: {v}"]
    if data is not None:
        cmd += ["--data-raw", data]
    cmd.append(url)
    try:
        out = subprocess.run(cmd, capture_output=True, timeout=60).stdout.decode("utf-8", "replace")
    except Exception as exc:  # noqa: BLE001
        return 0, "", "", str(exc)
    m = re.search(r"\n__STATUS__(\d+)$", out)
    status = int(m.group(1)) if m else 0
    body = out[: m.start()] if m else out
    return status, body, "", ""


def fetch(url, referer="", data=None, xhr=False, json_body=False, extra=None, follow=True):
    headers = dict(BASE_HEADERS)
    if referer:
        headers["Referer"] = referer
    if data is not None:
        headers["Content-Type"] = (
            "application/json; charset=UTF-8" if json_body
            else "application/x-www-form-urlencoded; charset=UTF-8"
        )
        headers["Accept"] = "application/json, text/plain, */*"
    if xhr:
        headers["X-Requested-With"] = "XMLHttpRequest"
    if extra:
        headers.update(extra)

    status, body, _, err = _curl(url, referer, data, headers, follow)
    if 200 <= status < 300 and len(body) > 200:
        return Fetched(url, status, body, {}, url, "", "curl")

    req = urllib.request.Request(url, data=data.encode() if isinstance(data, str) else data, headers=headers)
    opener = urllib.request.build_opener()
    if not follow:
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **kw):  # noqa: D401
                return None

        opener = urllib.request.build_opener(NoRedirect)
    try:
        with opener.open(req, timeout=30) as resp:
            raw = resp.read(MAX_BYTES)
            return Fetched(url, resp.status, _decompress(raw, resp.headers.get("Content-Encoding", "")),
                           dict(resp.headers), resp.geturl(), "", "urllib")
    except urllib.error.HTTPError as exc:
        raw = exc.read(MAX_BYTES)
        return Fetched(url, exc.code,
                       _decompress(raw, (exc.headers or {}).get("Content-Encoding", "")),
                       dict(exc.headers or {}), exc.geturl(), "", "urllib")
    except Exception as exc:  # noqa: BLE001
        return Fetched(url, 0, "", {}, url, str(exc) or err, "urllib")


def fetch_via_jina(url: str, raw_html: bool = True) -> Fetched:
    """Cloudflare'ı aşan okuma katmanı. `x-respond-with: html` ham HTML döndürür."""
    headers = {"x-respond-with": "html" if raw_html else "markdown"}
    status, body, _, err = _curl("https://r.jina.ai/" + url, "", None, headers)
    return Fetched(url, status, body, {}, url, err, "jina-html")


def fetch_any(candidates: list[str], referer="", xhr=False, extra=None, allow_jina=True) -> Fetched:
    """Sırayla dener; 403/0 alırsa jina katmanına düşer."""
    last = None
    for url in candidates:
        r = fetch(url, referer=referer, xhr=xhr, extra=extra)
        last = r
        if r.ok and len(r.text) > 800 and "just a moment" not in r.text.lower():
            return r
    if allow_jina and last is not None and not last.ok:
        ju = candidates[0]
        j = fetch_via_jina(ju)
        if j.ok and len(j.text) > 800 and "<html" in j.text.lower():
            return j
        return j if j.ok else last
    return last


def save(name: str, text: str) -> str:
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    try:
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text or "")
    except Exception as exc:  # noqa: BLE001
        log(f"  ! yazılamadı {path}: {exc}")
    return path


def save_resp(name: str, r: Fetched, prefix: str = "") -> None:
    head = (
        f"<!-- {r.url}\n  status={r.status} via={r.via} final={r.final_url} error={r.error}\n"
        f"  content-type={r.header('Content-Type')} location={r.header('Location')}\n"
        f"  bytes={len(r.text)} -->\n"
    )
    save(name, (prefix + "\n" if prefix else "") + head + (r.text or ""))
    log(f"  → {name}: status={r.status} via={r.via} bytes={len(r.text)}")


def uniq(seq):
    seen, out = set(), []
    for x in seq:
        if x and x not in seen:
            seen.add(x)
            out.append(x)
    return out


def scripts_of(html: str, base: str) -> list[str]:
    return uniq(urllib.parse.urljoin(base, html_mod.unescape(u))
                for u in re.findall(r"""<script[^>]+src=["']([^"']+)["']""", html, re.I))


def attrs_block(html: str, pattern: str, limit: int = 12) -> list[str]:
    return uniq(re.findall(pattern, html, re.I))[:limit]


def looks_blocked(text: str) -> bool:
    low = (text or "").lower()
    return ("just a moment" in low and "challenge-platform" in low) or "cf-browser-verification" in low


# ══════════════════════════════════════════════════════════════════════════
# ANIZM
# ══════════════════════════════════════════════════════════════════════════
ANIZM_DOMAINS = ["https://anizm.com.tr", "https://anizm.net", "https://anizm.org", "https://anizm.tv"]


def dump_anizm() -> None:
    log("\n## ANIZM")
    base, home = "", None
    for dom in ANIZM_DOMAINS:
        r = fetch(dom + "/", allow_jina=True) if False else fetch(dom + "/")
        save_resp("anizm__home__" + dom.split("//")[1].replace(".", "_") + ".html", r)
        log(f"- {dom}/ → {r.status} via={r.via} bytes={len(r.text)}")
        if r.ok and not looks_blocked(r.text) and len(r.text) > 20000:
            base, home = dom, r
            break
    if not home:
        log("- !! Anizm ana sayfasına erişilemedi")
        return

    html = home.text
    for sel in ["v3-series-card", "v3-episode-card", "v3-catalog-card", "data-v3-deferred-src",
                "v3-card-copy", "v3-poster-rail", "searchAnime", "/arama"]:
        log(f"  · '{sel}': {html.count(sel)}")

    # --- arama uçları ---
    for q in ["naruto", "attack on titan"]:
        for path in ["/arama?q={q}", "/arama?arama={q}",
                     "/searchAnime?query={q}&page=1&type=detailed&limit=10", "/searchAnime?q={q}"]:
            url = base + path.format(q=urllib.parse.quote(q))
            r = fetch(url, referer=base + "/")
            if r.header("Content-Type").startswith("application/json") or (r.text or "").lstrip().startswith("{"):
                save_resp("anizm__search_json__" + re.sub(r"[^a-z0-9]+", "_", path.format(q=q).lower())[:50] + ".json", r)
            else:
                save_resp("anizm__search__" + re.sub(r"[^a-z0-9]+", "_", path.format(q=q).lower())[:50] + ".html", r)
            log(f"  · arama {url} → {r.status} {len(r.text)}B ctype={r.header('Content-Type')[:40]}")

    # --- detay sayfası ---
    slug_links = uniq(
        urllib.parse.urljoin(base, h) for h in re.findall(r"""href=["'](/(?!arama|yeniler|takvim|magaza|girisyap|uyeol|profil|fansub|storage|images|assets|js|css|api)[a-z0-9][a-z0-9-]{2,})["']""", html, re.I)
    )
    card_links = uniq(
        urllib.parse.urljoin(base, h) for h in re.findall(
            r"""<a[^>]+class=["'][^"']*(?:v3-card|poster|card-title|v3-series)[^"']*["'][^>]*href=["']([^"']+)["']""", html, re.I)
    )
    detail_candidates = uniq(card_links + slug_links)
    log(f"- detay adayı: {len(detail_candidates)} → {detail_candidates[:4]}")

    detail = None
    detail_url = ""
    for cand in detail_candidates[:10]:
        r = fetch(cand, referer=base + "/")
        if r.ok and "-bolum-izle" not in cand:
            detail, detail_url = r, cand
            break
    if detail is None:
        log("- !! detay sayfası açılamadı")
        # doğrudan bilinen seri sayfası
        r = fetch(base + "/one-piece", referer=base + "/")
        if r.ok:
            detail, detail_url = r, base + "/one-piece"

    if detail is not None:
        save_resp("anizm__detail.html", detail)
        d = detail.text
        for sel in ["v3-anime-detail-cover", "v3-anime-facts", "v3-anime-detail-meta",
                    "v3-anime-detail-tags", "v3-anime-summary-content", "#bolumler",
                    "data-anime-episode-grid", "data-episode-number", "data-episode-id",
                    "-bolum-izle", "bolumKutucugu", "episodeListTabContent"]:
            log(f"  · detay '{sel}': {d.count(sel)}")
        for s in scripts_of(d, base)[:8]:
            save_resp("anizm__js__" + re.sub(r"[^A-Za-z0-9]+", "_", s.split("//")[-1])[:70], fetch(s, referer=detail_url))

        ep_links = uniq(
            urllib.parse.urljoin(base, h) for h in re.findall(
                r"""<a[^>]+href=["']([^"']*-bolum-izle[^"']*)["']""", d, re.I)
        )
        log(f"  · bölüm bağlantısı: {len(ep_links)} → {ep_links[:3]}")
        # "sıradaki bölüm" en iyi örnek: ilk bölüm
        if ep_links:
            dump_anizm_episode(ep_links[0], base)


def dump_anizm_episode(ep_url: str, base: str) -> None:
    r = fetch(ep_url, referer=base + "/")
    save_resp("anizm__episode.html", r)
    if not r.ok or looks_blocked(r.text):
        log(f"  !! bölüm sayfası alınamadı: {r.status}")
        return
    page = r.text
    for sel in ["data-v3-translator", "data-sources", "cevirmen", "translator",
                "data-video", "data-url", "csrf-token", "videoPlayerButtons", "player"]:
        log(f"  · bölüm '{sel}': {page.count(sel)}")

    csrf_m = re.search(r"""<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)""", page, re.I)
    csrf = csrf_m.group(1) if csrf_m else ""
    log(f"  · csrf-token: {'VAR' if csrf else 'YOK'}")

    for tag in attrs_block(page, r"<button[^>]*(?:data-sources|data-v3-translator)[^>]*>", 8):
        log(f"  · buton: {tag[:400]}")
    for tag in attrs_block(page, r"<[a-z]+[^>]+data-video[^>]*>", 8):
        log(f"  · video etiketi: {tag[:300]}")

    inline = re.findall(r"<script(?![^>]*src)[^>]*>([\s\S]{0,4000}?)</script>", page)
    interesting = [s for s in inline if "/bolum/" in s or "cevirmen" in s or "video" in s.lower()]
    save("anizm__episode_inline_scripts.js", "\n\n/* ---- */\n\n".join(interesting))
    log(f"  · ilgili inline script: {len(interesting)}")

    for s in scripts_of(page, base)[:8]:
        save_resp("anizm__epjs__" + re.sub(r"[^A-Za-z0-9]+", "_", s.split("//")[-1])[:70], fetch(s, referer=ep_url))

    # çevirmen kaynak uçları
    trs = uniq(
        re.findall(r'data-sources=["\']([^"\']+)["\']', page)
        + re.findall(r'data-v3-translator=["\']([^"\']+)["\']', page)
        + re.findall(r'translator=["\']([^"\']+)["\']', page)
    )
    log(f"  · çevirmen yolu: {len(trs)} → {trs[:4]}")

    videos: list[str] = []
    for i, tr in enumerate(trs[:6]):
        turl = urllib.parse.urljoin(base, html_mod.unescape(tr))
        variants = [
            ("plain", dict(xhr=False, extra={})),
            ("xhr", dict(xhr=True, extra={})),
            ("xhrcsrf", dict(xhr=True, extra={"X-CSRF-TOKEN": csrf, "Accept": "application/json"})),
        ]
        for tag, kw in variants:
            rr = fetch(turl, referer=ep_url, **kw)
            save_resp(f"anizm__translator_{i}_{tag}.json", rr)
            body = rr.text or ""
            log(f"    · çevirmen[{i}]/{tag} → {rr.status} bytes={len(body)} ilk={body.strip()[:90]!r}")
            try:
                payload = json.loads(body)
                if isinstance(payload, dict) and isinstance(payload.get("data"), str):
                    body = payload["data"]
            except Exception:  # noqa: BLE001
                pass
            videos += [f"/bolum/{a}/video/{b}" for a, b in re.findall(r"/bolum/(\d+)/video/(\d+)", body)]
            videos += [f"/video/{v}" for v in re.findall(r'video=["\']/video/(\d+)', body)]
            videos += [f"/video/{v}" for v in re.findall(r'data-video=["\'](\d+)', body)]
            if tag == "xhrcsrf" and videos:
                break

    videos = uniq(videos)
    log(f"  · video kimliği: {len(videos)} → {videos[:6]}")

    hashes: set[str] = set()
    for i, v in enumerate(videos[:12]):
        vurl = urllib.parse.urljoin(ep_url, html_mod.unescape(v))
        vr = fetch(vurl, referer=ep_url, xhr=True, extra={"Accept": "application/json"})
        save_resp(f"anizm__video_{i}.json", vr)
        try:
            payload = json.loads(vr.text or "{}")
        except Exception:  # noqa: BLE001
            payload = {}
        log(f"    · video[{i}] {vurl} → {vr.status} keys={list(payload)[:6] if isinstance(payload, dict) else '-'}")
        if isinstance(payload, dict):
            for key in ("url", "player", "playerOrigin", "videoSource", "securedLink", "sources"):
                val = payload.get(key)
                if isinstance(val, str):
                    hashes |= set(re.findall(r"\b[a-f0-9]{32}\b", val))
                    if key in ("url", "player") and i < 3:
                        log(f"      · {key}={val[:180]}")
        pid = re.search(r"/video/(\d+)", vurl)
        if pid:
            purl = f"{base}/player/{pid.group(1)}"
            pr = fetch(purl, referer=ep_url, follow=False)
            save_resp(f"anizm__player_{i}_head.txt", pr)
            loc = pr.header("Location")
            if loc:
                log(f"      · /player/{pid.group(1)} → 302 {loc[:170]}")
                hashes |= set(re.findall(r"\b[a-f0-9]{32}\b", loc))

    for h in sorted(hashes)[:5]:
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
            log(f"  · getVideo {h[:8]}/{variant} → {r2.status} {len(r2.text)}B {r2.text.strip()[:100]!r}")


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
        save_resp("tizle__home_direct__" + dom.split("//")[1].replace(".", "_") + ".html", r)
        log(f"- {dom}/ → {r.status} via={r.via} bytes={len(r.text)}")
        if r.ok and not looks_blocked(r.text) and len(r.text) > 5000:
            base, home = dom, r
            break
    if not home:
        log("- doğrudan erişim yok; jina-html katmanı deneniyor")
        for dom in TIZLE_DOMAINS:
            j = fetch_via_jina(dom + "/")
            save_resp("tizle__home_jina__" + dom.split("//")[1].replace(".", "_") + ".html", j)
            log(f"- jina {dom}/ → {j.status} bytes={len(j.text)}")
            if j.ok and len(j.text) > 5000 and not looks_blocked(j.text):
                base, home = dom, j
                break
    if not home:
        log("- !! TrAnimeIzle ana sayfasına erişilemedi (tüm katmanlar)")
        return

    html = home.text
    for sel in ["fansubSelector", "sourceBtn", "data-fid", "data-fad", "flx-block", "data-href",
                "/api/fansubSources", "/api/sourcePlayer"]:
        log(f"  · '{sel}': {html.count(sel)}")

    for q in ["naruto"]:
        for path in ["/arama?q={q}", "/arama/{q}", "/search?q={q}"]:
            url = base + path.format(q=urllib.parse.quote(q))
            r = fetch(url, referer=base + "/")
            if not r.ok:
                r = fetch_via_jina(url)
            save_resp("tizle__search__" + re.sub(r"[^a-z0-9]+", "_", path.format(q=q).lower())[:50] + ".html", r)
            log(f"  · arama {url} → {r.status} via={r.via} bytes={len(r.text)}")

    links = uniq(urllib.parse.urljoin(base, h) for h in re.findall(
        r"""href=["'](/[^"'#?]*(?:anime|seri)[^"'#?]*)["']""", html, re.I))
    log(f"- detay adayı: {len(links)} → {links[:3]}")
    for cand in links[:4]:
        d = fetch(cand, referer=base + "/")
        if not d.ok or looks_blocked(d.text):
            d = fetch_via_jina(cand)
        if not d.ok:
            continue
        save_resp("tizle__detail.html", d)
        body = d.text
        for sel in ["fansubSelector", "data-fid", "data-fad", "-bolum-izle", "bolum"]:
            log(f"  · detay '{sel}': {body.count(sel)}")
        eps = uniq(urllib.parse.urljoin(base, h) for h in re.findall(
            r"""href=["']([^"'#?]*bolum[^"'#?]*)["']""", body, re.I))
        log(f"  · bölüm: {len(eps)} → {eps[:3]}")
        if eps:
            dump_tizle_episode(eps[0], base)
            break


def dump_tizle_episode(ep_url: str, base: str) -> None:
    r = fetch(ep_url, referer=base + "/")
    if not r.ok or looks_blocked(r.text):
        r = fetch_via_jina(ep_url)
    save_resp("tizle__episode.html", r)
    if not r.ok:
        return
    page = r.text
    for sel in ["fansubSelector", "data-fid", "data-fad", "sourceBtn", "data-id"]:
        log(f"  · bölüm '{sel}': {page.count(sel)}")
    for tag in attrs_block(page, r"<[a-z]+[^>]+data-fid[^>]*>", 8):
        log(f"  · fansub etiketi: {tag[:300]}")

    for s in scripts_of(page, base)[:8]:
        save_resp("tizle__epjs__" + re.sub(r"[^A-Za-z0-9]+", "_", s.split("//")[-1])[:70], fetch(s, referer=ep_url))

    fansubs = uniq(re.findall(r'data-fid=["\'](\d+)["\'][^>]*?data-fad=["\'](\d+)["\']', page))
    if not fansubs:
        fansubs = uniq((a, b) for b, a in re.findall(
            r'data-fad=["\'](\d+)["\'][^>]*?data-fid=["\'](\d+)["\']', page))
    log(f"  · fansub (fid,fad): {len(fansubs)} → {fansubs[:4]}")

    ids: list[str] = []
    for fid, fad in fansubs[:4]:
        bodies = [
            ("named", json.dumps({"fansub_id": fid, "fansub_adi": "", "anime_id": fad, "anime_adi": ""})),
            ("raw", json.dumps({"fid": fid, "fad": fad})),
            ("form", urllib.parse.urlencode({"fansub_id": fid, "anime_id": fad})),
        ]
        for variant, body in bodies:
            sr = fetch(base + "/api/fansubSources", referer=ep_url, data=body, xhr=True,
                       json_body=(variant != "form"))
            save_resp(f"tizle__fansubSources_{fid}_{fad}_{variant}.json", sr)
            ids += re.findall(r'data-id=["\'](\d+)["\']', sr.text or "")
            ids += re.findall(r'"id"\s*:\s*"?(\d+)"?', sr.text or "")
            log(f"    · fansubSources({fid},{fad},{variant}) → {sr.status} {sr.text.strip()[:80]!r}")

    ids = uniq(ids)
    log(f"  · kaynak id: {len(ids)} → {ids[:6]}")
    for sid in ids[:12]:
        for path in (f"/api/sourcePlayer/{sid}", f"/api/preVideoAd/{sid}"):
            sr = fetch(base + path, referer=ep_url, xhr=True)
            save_resp("tizle__" + path.strip("/").replace("/", "_") + ".json", sr)
            iframe = re.search(r'<iframe[^>]+src=["\']([^"\']+)', sr.text or "")
            log(f"    · {path} → {sr.status} iframe={(iframe.group(1)[:120] if iframe else '-')}")


# ══════════════════════════════════════════════════════════════════════════
# DIZIBOX & HDFILMIZLE — oynatıcı zinciri ham HTML dökümü
# ══════════════════════════════════════════════════════════════════════════
DIZIBOX_BASE = "https://www.dizibox.live"
HDFILM_BASE = "https://www.hdfilmizle.to"
DIZIBOX_COOKIE_HEADER = {"Cookie": "LockUser=true; isTrustedUser=true; dbxu=1743289650198"}
_IFRAME_RE = re.compile(r"(?i)<iframe[^>]+(?:src|data-src)\s*=\s*[\"']([^\"']+)[\"']")
_OPTION_RE = re.compile(r"(?i)<option[^>]+value\s*=\s*[\"']([^\"']+)[\"']")


def _is_template(url: str) -> bool:
    """JS'in doldurması gereken ölü şablonlar (ör. .../player/${src}) gerçek çerçeve değildir."""
    return "${" in url or "{{" in url
_PLAYER_HINT = re.compile(r"(?i)vidrame|vidmoxy|vidmoly|setplay|fastplay|stfplay|molystream|king\.php|moly\.php|haydi\.php")


def _first_anchor(html: str, pattern: str, base: str) -> str | None:
    for href in re.findall(pattern, html or "", re.I):
        absolute = urllib.parse.urljoin(base, html_mod.unescape(href))
        if absolute.startswith("http"):
            return absolute
    return None


def _dump_player_hop(label: str, url: str, referer: str, extra: dict | None, depth: int = 0) -> None:
    r = fetch_any([url], referer=referer, extra=extra)
    save_resp(f"{label}.html", r)
    body = r.text or ""
    player_iframe = body.count('id="Player"') + body.count("id='Player'")
    log(
        f"    · {label} → {r.status} bytes={len(body)} "
        f"EE.dd={body.count('EE.dd') + body.count('ee(')} SPG={body.count('SPG.cerceve')} "
        f"CryptoJS={body.count('CryptoJS')} unescape={body.lower().count('unescape(')} "
        f"PlayerIframe={player_iframe} "
        f"STREAM-INF={body.count('#EXT-X-STREAM-INF')} manifest={body.count('/manifests/')}"
    )
    if depth >= 1:
        return
    nested = _IFRAME_RE.findall(body)
    for j, frame in enumerate(nested[:2]):
        absolute = urllib.parse.urljoin(r.final_url or url, html_mod.unescape(frame).replace(r"\/", "/"))
        if absolute.startswith("http") and absolute != url:
            _dump_player_hop(f"{label}_nested{j}", absolute, url, extra, depth + 1)


def dump_dizibox() -> None:
    log("══ DiziBox oynatıcı zinciri ══")
    episode = os.environ.get("DIZIBOX_EPISODE", "").strip()
    if not episode:
        listing = fetch_any([f"{DIZIBOX_BASE}/tum-bolumler/page/1/"], referer=DIZIBOX_BASE + "/",
                            extra=DIZIBOX_COOKIE_HEADER)
        episode = _first_anchor(listing.text, r"href=[\"']([^\"']*-sezon-\d+-bolum[^\"']*)", DIZIBOX_BASE) or ""
    if not episode:
        log("  !! bölüme ait çapa bulunamadı")
        return
    log(f"  · bölüm: {episode}")
    page = fetch_any([episode], referer=DIZIBOX_BASE + "/", extra=DIZIBOX_COOKIE_HEADER)
    save_resp("dizibox__episode.html", page)
    html = page.text or ""
    if not page.ok or looks_blocked(html):
        log(f"  !! bölüm sayfası alınamadı: {page.status}")
        return
    video_area = html.count('id="video-area"')
    log(f"  · video-area iframe={video_area} toolbar-option={len(_OPTION_RE.findall(html))} "
        f"archive-box={html.count('archive-box')}")

    frames = [m.group(1) for m in _IFRAME_RE.finditer(html) if _PLAYER_HINT.search(m.group(1))]
    alternates = [v for v in _OPTION_RE.findall(html) if v.startswith("http") or v.startswith("/")]
    save("dizibox__toolbar_options.txt", "\n".join(uniq(alternates)))
    for i, alt in enumerate(uniq(alternates)[:3]):
        alt_url = urllib.parse.urljoin(episode, html_mod.unescape(alt))
        alt_page = fetch_any([alt_url], referer=episode, extra=DIZIBOX_COOKIE_HEADER)
        save_resp(f"dizibox__altpage_{i}.html", alt_page)
        frames += [m.group(1) for m in _IFRAME_RE.finditer(alt_page.text or "") if _PLAYER_HINT.search(m.group(1))]

    seen: set[str] = set()
    for i, raw in enumerate(uniq(f for f in frames if not _is_template(f))[:5]):
        frame = urllib.parse.urljoin(episode, html_mod.unescape(raw).replace(r"\/", "/"))
        if frame in seen:
            continue
        seen.add(frame)
        log(f"  · oynatıcı[{i}]: {frame}")
        _dump_player_hop(f"dizibox__player_{i}", frame, episode, DIZIBOX_COOKIE_HEADER)
        if "molystream" in frame.lower() and "/embed/sheila/" not in frame:
            sheila = re.sub(r"/embed/", "/embed/sheila/", frame)
            _dump_player_hop(f"dizibox__player_{i}_sheila", sheila, frame, DIZIBOX_COOKIE_HEADER)


def dump_hdfilmizle() -> None:
    log("══ HDFilmİzle oynatıcı zinciri ══")
    episode = os.environ.get("HDFILM_EPISODE", "").strip()
    if not episode:
        home = fetch_any([HDFILM_BASE + "/"], referer=HDFILM_BASE + "/")
        episode = _first_anchor(home.text, r"href=[\"']([^\"']*(?:/dizi/|/film/)[^\"']*)", HDFILM_BASE) or ""
    if not episode:
        log("  !! içerik çapası bulunamadı")
        return
    log(f"  · içerik: {episode}")
    page = fetch_any([episode], referer=HDFILM_BASE + "/")
    save_resp("hdfilmizle__episode.html", page)
    html = page.text or ""
    if not page.ok or looks_blocked(html):
        log(f"  !! içerik sayfası alınamadı: {page.status}")
        return

    parts_blocks = re.findall(r"(?:let|var|const)\s+parts\s*=\s*(\[[\s\S]*?\]);", html)
    save("hdfilmizle__parts.json", "\n\n".join(parts_blocks))
    log(f"  · parts bloğu={len(parts_blocks)} iframe={len(_IFRAME_RE.findall(html))} "
        f"admin-ajax={html.count('admin-ajax')} data-nonce={html.count('data-nonce')}")

    frames = [m.group(1) for m in _IFRAME_RE.finditer(html)]
    for block in parts_blocks:
        frames += _IFRAME_RE.findall(block.replace(r"\/", "/").replace(r"\"", '"'))
    seen: set[str] = set()
    for i, raw in enumerate(uniq(f for f in frames if not _is_template(f))[:5]):
        frame = urllib.parse.urljoin(episode, html_mod.unescape(raw).replace(r"\/", "/"))
        if not frame.startswith("http") or frame in seen:
            continue
        seen.add(frame)
        log(f"  · oynatıcı[{i}]: {frame}")
        _dump_player_hop(f"hdfilmizle__player_{i}", frame, episode, None)


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    which = args[0].lower() if args else "all"
    os.makedirs(OUT, exist_ok=True)
    # Önce PR'ın öznesi olan iki site dökülsün: uzun anizm dökümü iş
    # bütçesini tüketip bizimkileri aç bırakmasın.
    if which in ("all", "dizibox"):
        try:
            dump_dizibox()
        except Exception as exc:  # noqa: BLE001
            log(f"- !! DiziBox dökümü çöktü: {exc!r}")
    if which in ("all", "hdfilmizle", "hdfilm"):
        try:
            dump_hdfilmizle()
        except Exception as exc:  # noqa: BLE001
            log(f"- !! HDFilmİzle dökümü çöktü: {exc!r}")
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
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
