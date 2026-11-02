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
import time
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
    path = os.path.join(OUT, name)
    os.makedirs(os.path.dirname(path) or OUT, exist_ok=True)
    with open(path, "w", encoding="utf-8", errors="replace") as handle:
        handle.write(text or "")
    return path


LAYER_ORDER = ("direct", "translate", "jina", "allorigins", "codetabs")


def best_fetch(url: str, via: str | None = None, headers: dict | None = None):
    """Katmanları sırayla dene; **site içeriği taşıyan** ilk cevabı döndür."""
    layers = (via,) if via else LAYER_ORDER
    fallback = None
    for name in layers:
        if name in ("translate", "jina"):
            time.sleep(5)  # vekiller hız sınırı koyuyor
        result = tizle_reach.fetch(url, via=name, headers=headers)
        if result.status == 429:
            time.sleep(10)
            result = tizle_reach.fetch(url, via=name, headers=headers)
        if result.status == 200 and result.text and not is_blocked(result.text):
            fallback = fallback or result
            if looks_like_site(result.text) or url.endswith((".js", ".txt", ".xml", ".json")):
                return result
        elif result.status == 200 and result.text and fallback is None:
            fallback = result
    return fallback


def fetch_report(url: str, via: str | None = None, save_as: str | None = None,
                 headers: dict | None = None) -> str:
    """Tek adres çek, sonucu raporla, istenirse diske yaz. Dönen: gövde."""
    result = best_fetch(url, via=via, headers=headers)
    if result is None:
        log(f"  {url} → hiçbir katman cevap vermedi")
        if save_as:
            save(save_as, "")
        return ""
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




# ── Kullanıcı dökümleri (Google Drive) ──────────────────────────────────────
# Site veri merkezi IP'lerine Cloudflare 403 döndüğü için runner'dan canlı HTML
# alınamıyor. Kullanıcının kendi tarayıcısından (residans IP) alınmış ham HTML
# dökümleri Drive'da duruyor; CI bunları indirip depoya iter, seçiciler oradan
# ölçülür.
DRIVE_FILES = {
    "message12-home.html": "1RA7i5mQdMft5qLx4XCKGz4f-p3leFjJs",
    "message13-bolum.html": "1gSxp9fsKM7Xgd2hkbIzzBHFmuJyFq2X9",
    "message14-arama.html": "17KpqHfraV6G-Hpnh6OzO2cYms1fO3pro",
}


def fetch_drive_files(out_dir: str) -> dict[str, int]:
    """Drive'daki kullanıcı dökümlerini indir (link paylaşımlı)."""
    import http.cookiejar
    import urllib.request

    os.makedirs(out_dir, exist_ok=True)
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(jar),
        urllib.request.HTTPRedirectHandler(),
    )
    opener.addheaders = [
        ("User-Agent", tizle_reach.UA),
        ("Accept", "text/html,application/xhtml+xml,text/plain,*/*"),
        ("Accept-Language", "tr-TR,tr;q=0.9"),
    ]
    sizes: dict[str, int] = {}
    for name, file_id in DRIVE_FILES.items():
        url = f"https://drive.usercontent.google.com/download?id={file_id}&export=download"
        text = ""
        try:
            with opener.open(url, timeout=90) as response:
                raw = response.read()
                text = raw.decode("utf-8", errors="replace")
            # Büyük dosyalarda Drive onay sayfası döndürür → confirm token'ı ile tekrar dene
            if "confirm=" in text and "<html" in text[:400].lower():
                match = re.search(r"confirm=([0-9A-Za-z_\-]+)", text)
                if match:
                    url2 = (f"https://drive.usercontent.google.com/download?id={file_id}"
                            f"&export=download&confirm={match.group(1)}")
                    with opener.open(url2, timeout=120) as response:
                        text = response.read().decode("utf-8", errors="replace")
        except Exception as error:  # pragma: no cover - ağ hatası
            log(f"  Drive {name}: HATA {error}")
            sizes[name] = -1
            continue
        path = os.path.join(out_dir, name)
        with open(path, "w", encoding="utf-8", errors="replace") as handle:
            handle.write(text)
        sizes[name] = len(text)
        log(f"  Drive {name}: {len(text)}B\n    → {path}")
    return sizes


def digest(html: str, label: str) -> list[str]:
    """Bir sayfanın yapısal özeti: kart/bölüm bağlantıları, oynatıcı, sınıflar."""
    lines = [f"### {label}"]
    lines.append(f"- uzunluk: {len(html)}B")
    for pattern, what in (
        (r'href="(/series/[^"]+)"', "series"),
        (r'href="(/movies/[^"]+)"', "movies"),
        (r'href="(/bolum/[^"]+)"', "bolum"),
        (r'href="(/[a-z0-9\-]+)"', "diger"),
    ):
        hits = re.findall(pattern, html or "")
        unique = sorted(set(hits))
        lines.append(f"- {what}: {len(hits)} bağlantı, {len(unique)} benzersiz")
        for hit in unique[:10]:
            lines.append(f"    - {hit}")
    classes = re.findall(r'class="([^"]{1,160})"', html or "")
    flat: list[str] = []
    for chunk in classes:
        for token in chunk.split():
            if token not in flat:
                flat.append(token)
    lines.append(f"- sınıf adları: {len(flat)}")
    lines.append("    `" + " ".join(flat[:80]) + "`")
    nodes = node_report(html)
    lines.append(f"- oynatıcı/akış izi: {len(nodes)}")
    for node in nodes[:25]:
        lines.append(f"    * {node}")
    return lines



JS_KEYWORDS = (
    "data-player-frame", "playerFrame", "iframe", "m3u8", "hls", "playlist",
    "sources", "embed", "/api/mj", "action:", "subtitle", "altyazi", "dublaj",
    "cdnhipter", "rapidvideo", "sibnet", "vidmoly", "voe", "uqload", "dood",
)


def js_findings(chunks_dir: str, limit_per_keyword: int = 25) -> str:
    """JS parçalarında oynatıcı/akış izlerini topla (kanıt niteliğinde)."""
    lines: list[str] = ["# JS bulguları", ""]
    files = sorted(
        os.path.join(chunks_dir, name) for name in os.listdir(chunks_dir)
        if name.endswith(".js")
    ) if os.path.isdir(chunks_dir) else []
    lines.append(f"- parça sayısı: {len(files)}")
    actions: list[str] = []
    for path in files:
        try:
            body = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        for match in re.finditer(r'"action"\s*:\s*"([A-Za-z0-9_]+)"', body):
            if match.group(1) not in actions:
                actions.append(match.group(1))
        for match in re.finditer(r'\b[a-zA-Z_$]{1,3}\("([a-z][A-Za-z0-9_]{4,40})",\[', body):
            if match.group(1) not in actions:
                actions.append(match.group(1))
    lines += ["", "## /api/mj eylem adayları", ""]
    lines += [f"- `{name}`" for name in sorted(actions)] or ["- (yok)"]
    for keyword in JS_KEYWORDS:
        hits: list[str] = []
        for path in files:
            body = open(path, encoding="utf-8", errors="replace").read()
            for match in re.finditer(re.escape(keyword), body):
                start = max(0, match.start() - 220)
                snippet = body[start:match.start() + 320].replace("\n", " ")
                snippet = re.sub(r"\s+", " ", snippet)
                entry = f"`{os.path.basename(path)}`: …{snippet}…"
                if entry not in hits:
                    hits.append(entry)
                if len(hits) >= limit_per_keyword:
                    break
            if len(hits) >= limit_per_keyword:
                break
        lines += ["", f"## {keyword} ({len(hits)})", ""]
        lines += [f"- {hit}" for hit in hits] or ["- (yok)"]
    return "\n".join(lines)


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Dizipal canlı HTML dökümü")
    parser.add_argument("--base", default="https://dizipal1593.com")
    parser.add_argument("--fast", action="store_true", help="hızlı erişim matrisi")
    parser.add_argument("--max-chunks", type=int, default=80)
    parser.add_argument("--query", default="Nocturne")
    parser.add_argument("--only-chunks", default="",
                        help="Virgülle ayrılmış parça yolları; verilirse yalnız onları çeker")
    parser.add_argument("--skip-api", action="store_true",
                        help="Aday API uçlarını deneme (site 403 döndüğünde zaman kaybı)")
    parser.add_argument("--reuse", action="store_true",
                        help="Diske yazılmış sayfaları yeniden çekme")
    args = parser.parse_args(list(argv) if argv is not None else None)

    base = args.base.rstrip("/")
    os.makedirs(OUT, exist_ok=True)

    if args.only_chunks:
        chunk_dir = os.path.join(OUT, "chunks")
        os.makedirs(chunk_dir, exist_ok=True)
        for raw in args.only_chunks.split(","):
            chunk = raw.strip()
            if not chunk:
                continue
            if not chunk.startswith("/"):
                chunk = "/_next/static/chunks/" + chunk
            body = fetch_report(base + chunk, save_as="chunks/" + slugify(chunk))
            if not body:
                log(f"  ! {chunk} alınamadı")
        report = js_findings(chunk_dir)
        save("JS-FINDINGS.md", report)
        print(report[:200000], flush=True)
        return 0

    log(f"# Dizipal dökümü — {base}")
    log()
    via = reachability(base, args.fast)
    log(f"\nMatris kazananı: **{via}** (sayfa başına otomatik yedekleme açık)\n")
    via = None

    # ── Kullanıcı dökümleri (Drive) ve yapısal özet ─────────────────────────
    log("## Drive dökümleri")
    drive_sizes = fetch_drive_files(os.path.join(OUT, "drive"))
    log("")
    log("## Yapısal özet (Drive dökümleri)")
    digest_lines: list[str] = []
    for name in DRIVE_FILES:
        path = os.path.join(OUT, "drive", name)
        if not os.path.exists(path):
            continue
        body = open(path, encoding="utf-8", errors="replace").read()
        digest_lines += digest(body, name)
        for line in digest(body, name):
            log(line)
    save("DIGEST.md", "\n".join(digest_lines))


    targets = [
        ("/", "home.html"),
        ("/trendler", "trendler.html"),
        ("/yeni-eklenen-dizi-bolumler", "yeni-bolumler.html"),
        ("/platform-bolumler", "platform-bolumler.html"),
        (f"/arama-yap?s={urllib.parse.quote(args.query)}", "arama.html"),
        ("/series/nocturne", "series-nocturne.html"),
        ("/series/gassal-c11", "series-gassal.html"),
        ("/movies/nocturnal", "movie-nocturnal.html"),
        ("/yabanci-dizi-izle", "yabanci-dizi-izle.html"),
        ("/film-izle", "film-izle.html"),
        ("/kategori/komedi", "kategori-komedi.html"),
        ("/bolum/gassal-4x1", "bolum-gassal-4x1.html"),
        ("/bolum/gassal-4x1?lang=altyazi", "bolum-gassal-4x1-altyazi.html"),
        ("/robots.txt", "robots.txt"),
        ("/sitemap.xml", "sitemap.xml"),
    ]

    pages: dict[str, str] = {}
    for path, name in targets:
        url = base + path
        log(f"## {url}")
        cached = os.path.join(OUT, name)
        if args.reuse and os.path.exists(cached) and os.path.getsize(cached) > 5000:
            body = open(cached, encoding="utf-8", errors="replace").read()
            log(f"  (yeniden kullanıldı: {cached}, {len(body)}B)")
        else:
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
    save("JS-FINDINGS.md", js_findings(os.path.join(OUT, "chunks")))

    # ── Aday API uçlarını dene (bölüm sayfasından türetilen kimliklerle) ────
    log("\n## Aday uçların denenmesi")
    episode_html = pages.get("bolum-gassal-4x1.html", "")
    ids = sorted(set(re.findall(r'"id"\s*:\s*"?(\d{3,})"?', episode_html)))[:5]
    probe_urls = [c for c in api_hits if "{id}" not in c][:12]
    for candidate in api_hits:
        if "{id}" in candidate and ids:
            probe_urls.append(candidate.replace("{id}", ids[0]))
    probe_urls = [] if args.skip_api else probe_urls
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
    summary += ["", "## Drive dökümleri", ""]
    summary += [f"- `{name}`: {size}B" for name, size in drive_sizes.items()]
    summary += ["", "## API adayları", ""]
    summary += [f"- `{hit}`" for hit in api_hits] or ["- (yok)"]
    summary += ["", "## Konsol", "", "```", *LOG[-400:], "```", ""]
    save("SUMMARY.md", "\n".join(summary))
    log(f"\nÖzet yazıldı: {OUT}/SUMMARY.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
