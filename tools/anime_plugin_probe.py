#!/usr/bin/env python3
"""Anizm ve TrAnimeIzle için salt-okunur yapı probu (JavaScript çalıştırmaz).

Amaç: eklentilerin kullandığı seçicilerin/URL uçlarının **canlı sitede** var olup
olmadığını sayısal olarak görmek. Ham HTML ve akış adresleri rapora yazılmaz;
yalnızca durum kodu, sayım ve ilk birkaç anahtar yazılır.

Kullanım:
    python3 tools/anime_plugin_probe.py

Notlar:
- Bazı siteler veri merkezi IP'lerine 403 döndürür; o zaman rapor "403" der ve
  cihaz doğrulaması gerekir. Bu bir eklenti hatası değildir.
- Hiçbir POST/oynatıcı isteği yapılmaz; yalnızca herkese açık HTML/JSON okunur.
"""
from __future__ import annotations

import json
import re
import ssl
import urllib.error
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
)
HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
}
SSL_CTX = ssl.create_default_context()
MAX_BYTES = 3 * 1024 * 1024
RE_BOLUM_HREF = r'href="[^"]*-bolum'


def fetch(url: str, referer: str = "", xhr: bool = False) -> tuple[int, str, str]:
    headers = dict(HEADERS)
    if referer:
        headers["Referer"] = referer
    if xhr:
        headers["Accept"] = "application/json, text/plain, */*"
        headers["X-Requested-With"] = "XMLHttpRequest"
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=20, context=SSL_CTX) as response:
            raw = response.read(MAX_BYTES)
            charset = response.headers.get_content_charset() or "utf-8"
            return response.status, raw.decode(charset, "replace"), response.geturl()
    except urllib.error.HTTPError as error:
        raw = error.read(65536)
        return error.code, raw.decode("utf-8", "replace"), url
    except Exception as error:  # noqa: BLE001 - prob aracı, hata türü rapora yazılır
        return 0, "", f"{type(error).__name__}: {error}"


def count(html: str, pattern: str) -> int:
    return len(re.findall(pattern, html, re.IGNORECASE))


def title_of(html: str) -> str:
    match = re.search(r"<title[^>]*>(.*?)</title>", html, re.IGNORECASE | re.DOTALL)
    return re.sub(r"\s+", " ", match.group(1)).strip()[:80] if match else "-"


def line(label: str, value: object) -> str:
    return f"- {label}: {value}"


def probe_anizm() -> list[str]:
    out = ["### Anizm", ""]
    base = None
    for candidate in ("https://anizm.net", "https://puffytr.com"):
        status, html, final = fetch(candidate + "/")
        out.append(f"**{candidate}** → HTTP {status} · son adres: {final}")
        if status != 200 or not html:
            out.append("")
            continue
        out.append(line("title", title_of(html)))
        out.append(line("`a[href*=-bolum]`", count(html, RE_BOLUM_HREF)))
        out.append(line("`div.posterBlock`", count(html, "posterBlock")))
        out.append(line("`a.slideAnimeLink`", count(html, "slideAnimeLink")))
        out.append(line("`div#episodesMiddle`", count(html, "episodesMiddle")))
        out.append(line("`div.anime-card` / `.ui.card`", count(html, "anime-card|ui card")))
        out.append("")
        if base is None:
            base = candidate
    if base is None:
        return out

    status, body, _ = fetch(base + "/getAnimeListForSearch", base + "/", xhr=True)
    out.append(f"**`/getAnimeListForSearch`** → HTTP {status} · {len(body)} bayt")
    slug = None
    if status == 200 and body.strip().startswith("["):
        try:
            data = json.loads(body)
            out.append(line("kayıt sayısı", len(data)))
            if data:
                first = data[0]
                out.append(line("ilk kayıt anahtarları", ", ".join(sorted(first.keys())[:10])))
                slug = first.get("info_slug") or first.get("slug")
                out.append(line("örnek slug", slug))
        except Exception as error:  # noqa: BLE001
            out.append(line("JSON hatası", error))
    else:
        out.append(line("JSON değil (başlangıç)", body[:40].replace("\n", " ")))
    out.append("")

    for query in ("one piece", "naruto"):
        url = base + "/searchAnime?" + urllib.parse.urlencode(
            {
                "query": query,
                "page": 1,
                "type": "detailed",
                "limit": 24,
                "priorityField": "info_title",
                "orderBy": "info_id",
                "orderDirection": "DESC",
            }
        )
        status, body, _ = fetch(url, base + "/", xhr=True)
        has_data = '"data"' in body
        out.append(
            f"**`/searchAnime?query={query}`** → HTTP {status} · {len(body)} bayt · "
            f"`data` anahtarı: {'var' if has_data else 'yok'}"
        )
    out.append("")

    for path in ("/arama?q=one+piece", "/arama/one%20piece", "/fullViewSearch?search=one+piece&skip=0"):
        status, body, _ = fetch(base + path, base + "/")
        card_links = count(body, RE_BOLUM_HREF)
        posters = count(body, "posterBlock")
        out.append(
            f"**`{path}`** → HTTP {status} · {len(body)} bayt · "
            f"kart linki: {card_links} · posterBlock: {posters}"
        )
    out.append("")

    if slug:
        series_url = f"{base}/{urllib.parse.quote(slug)}"
        status, body, final = fetch(series_url, base + "/")
        out.append(f"**`/{slug}`** → HTTP {status} · son adres: {final} · {len(body)} bayt")
        if status == 200 and body:
            out.append(line("title", title_of(body)))
            out.append(line("`h1/h2.anizm_pageTitle`", count(body, "anizm_pageTitle")))
            out.append(line("`div.infoPosterImg`", count(body, "infoPosterImg")))
            out.append(line("`div.infoDesc`", count(body, "infoDesc")))
            out.append(line("`div#fansec a[translator]`", count(body, "translator=")))
            out.append(line("`div.episodeListTabContent`", count(body, "episodeListTabContent")))
            out.append(line("`data-sources` / v3", count(body, "data-sources=|data-v3-translator")))
            episodes = re.findall(r'href="(/' + re.escape(slug) + r'-\d{1,4}-bolum[^"]*)"', body)
            out.append(line("bölüm bağlantısı", len(episodes)))
            if episodes:
                ep_url = base + episodes[-1]
                estatus, ebody, _ = fetch(ep_url, series_url)
                out.append(f"**bölüm sayfası** `{episodes[-1]}` → HTTP {estatus} · {len(ebody)} bayt")
                if estatus == 200 and ebody:
                    out.append(line("`a[translator]`", count(ebody, r'translator=')))
                    out.append(line("`div#fansec`", count(ebody, r'id="fansec"')))
                    out.append(line("`a.videoPlayerButtons`", count(ebody, r'videoPlayerButtons')))
                    out.append(line("`iframe`", count(ebody, r'<iframe')))
                    out.append(line("`anizmplayer`", count(ebody, r'anizmplayer')))
                    out.append(line("m3u8/mp4 izi", count(ebody, r'\.(m3u8|mp4)')))
    out.append("")
    return out


def probe_tranimeizle() -> list[str]:
    out = ["### TrAnimeIzle", ""]
    base = None
    for candidate in ("https://www.tranimeizle.io", "https://www.tranimeizle.live"):
        status, html, final = fetch(candidate + "/")
        out.append(f"**{candidate}** → HTTP {status} · son adres: {final}")
        if status != 200 or not html:
            out.append("")
            continue
        out.append(line("title", title_of(html)))
        out.append(line("`div.flx-block`", count(html, "flx-block")))
        out.append(line("`data-href`", count(html, "data-href=")))
        out.append(line("`-bolum-izle` bağlantısı", count(html, "-bolum-izle")))
        out.append(line("`ul.pagination`", count(html, "pagination")))
        out.append("")
        if base is None:
            base = candidate
    if base is None:
        return out

    for path in ("/arama/one%20piece?page=1", "/listeler/populer/sayfa-1", "/listeler/yenibolum/sayfa-1"):
        status, body, _ = fetch(base + path, base + "/")
        blocks = count(body, "flx-block")
        hrefs = count(body, "data-href=")
        out.append(
            f"**`{path}`** → HTTP {status} · {len(body)} bayt · "
            f"flx-block: {blocks} · data-href: {hrefs}"
        )
    out.append("")

    series = "/anime/one-piece-izle"
    status, body, final = fetch(base + series, base + "/")
    out.append(f"**`{series}`** → HTTP {status} · son adres: {final} · {len(body)} bayt")
    if status == 200 and body:
        out.append(line("title", title_of(body)))
        out.append(line("`div.playlist-title h1`", count(body, "playlist-title")))
        out.append(line("`div.poster .social-icon img`", count(body, "social-icon")))
        out.append(line("`a.genre`", count(body, "genre")))
        out.append(line("`div.animeDetail-items`", count(body, "animeDetail-items")))
        out.append(line("`div.episode-li`", count(body, "episode-li")))
        out.append(line("`input#EpisodeId`", count(body, "EpisodeId")))
        out.append(line("`div.fansubSelector`", count(body, "fansubSelector")))
        episodes = re.findall(r'href="([^"]*-\d{1,4}-bolum-izle[^"]*)"', body)
        out.append(line("bölüm bağlantısı", len(episodes)))
        if episodes:
            ep = episodes[-1]
            ep_url = ep if ep.startswith("http") else base + ep
            estatus, ebody, _ = fetch(ep_url, base + series)
            out.append(f"**bölüm sayfası** `{ep[:60]}` → HTTP {estatus} · {len(ebody)} bayt")
            if estatus == 200 and ebody:
                out.append(line("`input#EpisodeId`", count(ebody, "EpisodeId")))
                out.append(line("`animeWatch.initialize`", count(ebody, "animeWatch")))
                out.append(line("`div.fansubSelector`", count(ebody, "fansubSelector")))
                out.append(line("`data-fid`", count(ebody, "data-fid")))
                out.append(line("`li.sourceBtn`", count(ebody, "sourceBtn")))
                out.append(line("`/api/fansubSources` izi", count(ebody, "fansubSources")))
    out.append("")
    return out


def main() -> None:
    print("# Anime eklentisi canlı yapı probu", flush=True)
    print("", flush=True)
    for section in (probe_anizm(), probe_tranimeizle()):
        print("\n".join(section), flush=True)


if __name__ == "__main__":
    main()
