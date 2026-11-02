#!/usr/bin/env python3
"""Dizipal **canlı sözleşme dökümü** (salt-okunur, JS çalıştırmaz).

Neden var?
    `DiziPal` eklentisi, sitenin **eski PHP temasını** (`.episode-item`,
    `article.type2 ul li`, `/api/search-autocomplete`) hedefliyor. Site ise
    artık **Next.js / Turbopack** bir uygulama: `/_next/static/chunks/*.js`,
    `self.__next_f.push(...)` RSC akışı, `/series/...`, `/bolum/...`,
    `/arama-yap?s=...` yolları. Eklenti bu yüzden ana sayfada, aramada, detayda
    ve bölümde **hiçbir şey bulamıyor**.

    Bu araç, eklentinin dayandığı her şeyi **ham hâliyle** diske yazar; seçiciler
    tahminle değil ölçümle yazılabilsin diye.

Ne dökümler?
    1. erişim matrisi (direct / jina / translate / cors-proxy / wayback)
    2. ana sayfa, listeler (`/trendler`, `/yeni-eklenen-dizi-bolumler`,
       `/platform-bolumler`), arama (`/arama-yap?s=`), dizi detayı
       (`/series/<slug>`), film detayı (`/movies/<slug>`), bölüm (`/bolum/<slug>`)
    3. sayfadaki **tüm** `<a href>` yolları (rota haritası)
    4. sayfadaki **tüm** iframe / video / source / data-* düğümleri
    5. `/_next/static/chunks/*.js` dosyaları ve içlerinden çıkan
       **API uçları** (`/api/...`, `fetch("...")`, `player`, `m3u8`)
    6. `/robots.txt`, `/sitemap.xml`
    7. bölüm sayfasında bulunan aday API uçlarının denendiği kayıt

Kullanım:
    python3 tools/dizipal_dump.py                  # varsayılan hedefler
    python3 tools/dizipal_dump.py --fast           # erişim matrisini hızlı geç
    python3 tools/dizipal_dump.py --base https://dizipal1593.com

Çıktı:
    site-dump/dizipal/...    ham HTML/JS/JSON (adım adı dosya adında)
    site-dump/dizipal/SUMMARY.md
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.parse
from typing import Iterable

try:  # paket içinden (tools.dizipal_dump) ve doğrudan çalışsın
    from tools import tizle_reach
except ImportError:  # pragma: no cover
    import tizle_reach  # type: ignore[no-redef]

OUT = os.environ.get("SITE_DUMP_DIR", "site-dump") + "/dizipal"

CONTENT_MARKERS = (
    "/series/",
    "/bolum/",
    "arama-yap",
    "data-dp",
    "__next_f",
)
BLOCK_MARKERS = (
    "you have been blocked",
    "error 1005",
    "error code: 1005",
    "attention required",
    "just a moment",
    "access denied",
    "cf-error-details",
)

LOG: list[str] = []


def log(line: str = "") -> None:
    print(line, flush=True)
    LOG.append(line)


def slugify(url: str) -> str:
    parsed = urllib.parse.urlparse(url)
    path = (parsed.path or "/").strip("/").replace("/", "_") or "root"
    if parsed.query:
        path += "_" + re.sub(r"[^A-Za-z0-9]+", "-", parsed.query).strip("-")
    return re.sub(r"[^A-Za-z0-9._-]+", "-", path)[:120]


def is_blocked(text: str) -> bool:
    low = (text or "")[:8000].lower()
    return any(marker in low for marker in BLOCK_MARKERS)


def looks_like_site(text: str) -> bool:
    return any(marker in (text or "") for marker in CONTENT_MARKERS)


def save(name: str, text: str) -> str:
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8", errors="replace") as handle:
        handle.write(text or "")
    return path


def fetch_report(url: str, via: str | None = None, save_as: str | None = None,
                 headers: dict | None = None) -> str:
    """Tek adres çek, sonucu raporla, istenirse diske yaz. Dönen: gövde."""
    result = tizle_reach.fetch(url, via=via, headers=headers)
    flags = []
    if is_blocked(result.text):
        flags.append("ENGELLİ")
    if looks_like_site(result.text):
        flags.append("SITE-ICERIK")
    log(
        f"  {url} → via={result.via or '?'} HTTP {result.status} "
        f"{len(result.text)}B{' ' + ','.join(flags) if flags else ''}"
        + (f" — {result.note}" if result.note else "")
    )
    if save_as:
        save(save_as, result.text)
    return result.text


def reachability(base: str, fast: bool) -> str:
    """Hangi katmandan okunuyor? Kazanan katmanın adını döndürür."""
    log("## Erişim matrisi")
    probe = base.rstrip("/") + "/"
    if fast:
        for via in ("direct", "translate", "jina", "allorigins", "codetabs"):
            result = tizle_reach.fetch(probe, via=via)
            log(f"  {via:12s} HTTP {result.status} {len(result.text)}B"
                + (" ENGELLİ" if is_blocked(result.text) else "")
                + (" SITE-ICERIK" if looks_like_site(result.text) else ""))
            if result.status == 200 and not is_blocked(result.text) and len(result.text) > 2000:
                return via
        return "direct"

    for result in tizle_reach.reachability_matrix(probe):
        log(f"  {result.summary()}")
        if result.status == 200 and not result.blocked and looks_like_site(result.text):
            return result.via or "direct"
    return "direct"


def route_map(html: str) -> list[str]:
    """HTML'deki tüm iç bağlantıları tekilleştir."""
    hrefs = re.findall(r'href="(/[^"#?]*)(\?[^"#]*)?"', html or "")
    paths: list[str] = []
    for path, query in hrefs:
        full = path + (query or "")
        if full.startswith("/_next") or full.startswith("/fonts"):
            continue
        if full not in paths:
            paths.append(full)
    return paths


def node_report(html: str) -> list[str]:
    """Bölüm sayfasında oynatıcı izi bırakan düğümleri listele."""
    lines: list[str] = []
    for tag in ("iframe", "video", "source", "embed", "object"):
        for match in re.finditer(rf"<{tag}\b[^>]*>", html or "", re.IGNORECASE):
            lines.append(match.group(0)[:400])
    for attr in ("data-src", "data-url", "data-player", "data-video", "data-file",
                 "data-sources", "data-id", "data-episode", "data-series"):
        for match in re.finditer(rf'{attr}="([^"]{{0,300}})"', html or ""):
            value = match.group(1)
            if value.strip():
                lines.append(f'{attr}="{value[:300]}"')
    # script içindeki oynatıcı/akış izleri
    for pattern in (r'"file"\s*:\s*"([^"]+\.m3u8[^"]*)"',
                    r'"sources?"\s*:\s*\[(.{0,300}?)\]',
                    r'"(?:m3u8|hls|dash|mp4)"\s*:\s*"([^"]{10,300})"',
                    r'https?://[^"\'\s<>]+\.m3u8[^"\'\s<>]*',
                    r'/api/[A-Za-z0-9_\-./?=&%]+'):
        for match in re.finditer(pattern, html or ""):
            lines.append("script: " + match.group(0)[:300])
    seen: list[str] = []
    for line in lines:
        if line not in seen:
            seen.append(line)
    return seen


def chunk_urls(html: str) -> list[str]:
    chunks = re.findall(r'/_next/static/chunks/[A-Za-z0-9_\-.]+\.js', html or "")
    unique: list[str] = []
    for chunk in chunks:
        if chunk not in unique:
            unique.append(chunk)
    return unique


API_PATTERNS = (
    r'["\'`](/api/[A-Za-z0-9_\-./{}$:]+)["\'`]',
    r'["\'`](https?://[A-Za-z0-9_\-.]+/api/[A-Za-z0-9_\-./{}$:]+)["\'`]',
    r'["\'`](/[a-z0-9\-]+/(?:player|source|sources|video|izle|embed)[A-Za-z0-9_\-./{}$:]*)["\'`]',
)


def api_candidates(text: str) -> list[str]:
    found: list[str] = []
    for pattern in API_PATTERNS:
        for match in re.finditer(pattern, text or ""):
            value = match.group(1)
            if value not in found:
                found.append(value)
    return found


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Dizipal canlı HTML dökümü")
    parser.add_argument("--base", default="https://dizipal1593.com")
    parser.add_argument("--fast", action="store_true", help="hızlı erişim matrisi")
    parser.add_argument("--max-chunks", type=int, default=25)
    parser.add_argument("--query", default="Nocturne")
    args = parser.parse_args(list(argv) if argv is not None else None)

    base = args.base.rstrip("/")
    os.makedirs(OUT, exist_ok=True)

    log(f"# Dizipal dökümü — {base}")
    log()
    via = reachability(base, args.fast)
    log(f"\nSeçilen katman: **{via}**\n")

    targets = [
        ("/", "home.html"),
        ("/trendler", "trendler.html"),
        ("/yeni-eklenen-dizi-bolumler", "yeni-bolumler.html"),
        ("/platform-bolumler", "platform-bolumler.html"),
        (f"/arama-yap?s={urllib.parse.quote(args.query)}", "arama.html"),
        ("/series/nocturne", "series-nocturne.html"),
        ("/series/gassal-c11", "series-gassal.html"),
        ("/bolum/gassal-4x1", "bolum-gassal-4x1.html"),
        ("/bolum/gassal-4x1?lang=altyazi", "bolum-gassal-4x1-altyazi.html"),
        ("/robots.txt", "robots.txt"),
        ("/sitemap.xml", "sitemap.xml"),
    ]

    pages: dict[str, str] = {}
    for path, name in targets:
        url = base + path
        log(f"## {url}")
        body = fetch_report(url, via=via, save_as=name)
        pages[name] = body
        if looks_like_site(body):
            routes = route_map(body)
            interesting = [r for r in routes if not r.startswith(("/series/", "/bolum/", "/movies/"))]
            seeded = [r for r in routes if r.startswith(("/series/", "/bolum/", "/movies/"))][:8]
            log(f"  rota: {len(routes)} benzersiz bağlantı")
            for route in (interesting[:25] + seeded):
                log(f"    - {route}")
        nodes = node_report(body)
        if nodes:
            log(f"  oynatıcı/akış izi: {len(nodes)} düğüm")
            for node in nodes[:20]:
                log(f"    * {node}")
        log()

    # ── JS parçaları: API uçlarını buradan çıkarıyoruz ──────────────────────
    log("## Next.js parçaları")
    all_chunks: list[str] = []
    for html in pages.values():
        for chunk in chunk_urls(html):
            if chunk not in all_chunks:
                all_chunks.append(chunk)
    log(f"  {len(all_chunks)} parça bulundu")
    api_hits: list[str] = []
    for chunk in all_chunks[: args.max_chunks]:
        body = fetch_report(base + chunk, via=via, save_as="chunks/" + slugify(chunk))
        for candidate in api_candidates(body):
            if candidate not in api_hits:
                api_hits.append(candidate)
    log(f"  {len(api_hits)} API adayı")
    for candidate in api_hits:
        log(f"    - {candidate}")

    save("api-candidates.json", json.dumps(api_hits, indent=2, ensure_ascii=False))

    # ── Aday API uçlarını dene (bölüm sayfasından türetilen kimliklerle) ────
    log("\n## Aday uçların denenmesi")
    episode_html = pages.get("bolum-gassal-4x1.html", "")
    ids = sorted(set(re.findall(r'"id"\s*:\s*"?(\d{3,})"?', episode_html)))[:5]
    probe_urls = [c for c in api_hits if "{id}" not in c][:12]
    for candidate in api_hits:
        if "{id}" in candidate and ids:
            probe_urls.append(candidate.replace("{id}", ids[0]))
    for url in probe_urls[:16]:
        if not url.startswith("http"):
            url = base + url
        body = fetch_report(url, via=via, save_as="api/" + slugify(url))
        if body:
            log(f"    gövde: {body[:200].replace(chr(10), ' ')}")

    # ── Özet ────────────────────────────────────────────────────────────────
    summary = [
        "# Dizipal döküm özeti",
        "",
        f"- taban: `{base}`",
        f"- erişim katmanı: **{via}**",
        "",
        "## Sayfalar",
        "",
    ]
    for name, body in pages.items():
        summary.append(f"- `{name}`: {len(body)}B — site içeriği: "
                       f"{'evet' if looks_like_site(body) else 'HAYIR'}")
    summary += ["", "## API adayları", ""]
    summary += [f"- `{hit}`" for hit in api_hits] or ["- (yok)"]
    summary += ["", "## Konsol", "", "```", *LOG[-400:], "```", ""]
    save("SUMMARY.md", "\n".join(summary))
    log(f"\nÖzet yazıldı: {OUT}/SUMMARY.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
