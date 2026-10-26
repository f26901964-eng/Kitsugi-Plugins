#!/usr/bin/env python3
"""Anizm + TrAnimeIzle **derin zincir probu** (salt-okunur, canlı site).

Amaç: eklentilerin video kaynak zincirini gerçek HTML ile doğrulamak.
Rapor; sayfa yapısını, çevirmen/fansub butonlarını, oynatıcı uçlarını ve
`.m3u8/.mp4` çıktısına kadar giden zinciri **ham parçalarla** gösterir.

Kullanım (CI'da `player-source-probe` etiketiyle ya da yerelde):

    python3 tools/anime_chain_probe.py            # ikisi
    python3 tools/anime_chain_probe.py anizm      # sadece Anizm
    python3 tools/anime_chain_probe.py tranime    # sadece TrAnimeIzle

Notlar:
- Hiçbir hesap/çerez kullanılmaz; yalnızca herkese açık sayfalar okunur.
- Zincir için sitenin KENDİ uçları çağrılır (fansubSources / sourcePlayer /
  translator). Bunlar uygulamanın da yaptığı salt-okuma istekleridir.
- Çıktı PR yorumuna yazılacağı için her ham parça kısaltılır.
"""
from __future__ import annotations

import gzip
import io
import json
import re
import ssl
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
MAX_BYTES = 4 * 1024 * 1024
SNIP = 500


class Fetched:
    def __init__(self, url: str, status: int, text: str, headers: dict, final_url: str, error: str = ""):
        self.url = url
        self.status = status
        self.text = text
        self.headers = headers
        self.final_url = final_url
        self.error = error

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300 and not self.error


def fetch(
    url: str,
    referer: str = "",
    json_body: str | None = None,
    form_body: dict | None = None,
    follow: bool = True,
    xhr: bool = False,
) -> Fetched:
    headers = dict(BASE_HEADERS)
    if referer:
        headers["Referer"] = referer
    data = None
    if json_body is not None:
        data = json_body.encode()
        headers["Content-Type"] = "application/json;charset=UTF-8"
        headers["Accept"] = "application/json, text/plain, */*"
    if form_body is not None:
        data = urllib.parse.urlencode(form_body).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        headers["Accept"] = "application/json, text/plain, */*"
    if xhr:
        headers["X-Requested-With"] = "XMLHttpRequest"
    request = urllib.request.Request(url, data=data, headers=headers)
    opener = urllib.request.build_opener()
    if not follow:
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs):  # noqa: D401
                return None
        opener = urllib.request.build_opener(NoRedirect)
    try:
        with opener.open(request, timeout=25) as response:
            raw = response.read(MAX_BYTES)
            if response.headers.get("Content-Encoding") == "gzip":
                raw = gzip.decompress(raw)
            charset = response.headers.get_content_charset() or "utf-8"
            return Fetched(
                url,
                response.status,
                raw.decode(charset, "replace"),
                dict(response.headers),
                response.geturl(),
            )
    except urllib.error.HTTPError as error:
        raw = b""
        try:
            raw = error.read(256 * 1024)
            if error.headers.get("Content-Encoding") == "gzip":
                raw = gzip.decompress(raw)
        except Exception:  # noqa: BLE001
            pass
        return Fetched(url, error.code, raw.decode("utf-8", "replace"), dict(error.headers or {}), url)
    except Exception as error:  # noqa: BLE001
        return Fetched(url, 0, "", {}, url, f"{type(error).__name__}: {error}")


def snip(text: str, anchor: str, before: int = 120, after: int = SNIP) -> str:
    text = re.sub(r"\s+", " ", text or "")
    index = text.lower().find(anchor.lower())
    if index < 0:
        return ""
    return text[max(0, index - before): index + after]


def tags(html: str, pattern: str, limit: int = 8) -> list[str]:
    """Açılış etiketlerini ham olarak döndürür (regex, HTML parser yok)."""
    out: list[str] = []
    for match in re.finditer(pattern, html, re.IGNORECASE):
        raw = match.group(0)
        if raw not in out:
            out.append(raw)
        if len(out) >= limit:
            break
    return out


def report_page(name: str, page: Fetched, lines: list[str]) -> None:
    lines.append(f"### {name}")
    lines.append("")
    lines.append(f"- URL: `{page.url}`")
    lines.append(f"- HTTP: `{page.status}`" + (f" — {page.error}" if page.error else ""))
    lines.append(f"- Boyut: `{len(page.text)}` karakter")
    title = re.search(r"<title[^>]*>(.*?)</title>", page.text, re.S | re.I)
    if title:
        clean_title = re.sub(r"\s+", " ", title.group(1)).strip()[:120]
        lines.append(f"- Başlık: `{clean_title}`")
    scripts = re.findall(r'<script[^>]+src=["\']([^"\']+)["\']', page.text, re.I)
    if scripts:
        lines.append("- Script dosyaları: " + ", ".join(f"`{s}`" for s in scripts[:12]))
    lines.append("")


def dump_js_hits(html: str, label: str, needles: list[str], lines: list[str], limit: int = 6) -> None:
    hits = []
    for needle in needles:
        for match in re.finditer(re.escape(needle), html, re.I):
            start = max(0, match.start() - 160)
            raw = re.sub(r"\s+", " ", html[start: match.start() + 260])
            if raw not in hits:
                hits.append(raw)
            if len(hits) >= limit:
                break
        if len(hits) >= limit:
            break
    if hits:
        lines.append(f"**{label}**")
        for hit in hits:
            lines.append(f"- `{hit[:420]}`")
        lines.append("")


# ---------------------------------------------------------------------------
# Anizm
# ---------------------------------------------------------------------------

ANIZM_EPISODE = "https://anizm.net/shingeki-no-kyojin-1-bolum-izle"


def probe_anizm(lines: list[str]) -> None:
    lines.append("## Anizm")
    lines.append("")
    page = fetch(ANIZM_EPISODE)
    report_page("Bölüm sayfası", page, lines)
    if not page.ok:
        lines.append("> Sayfa alınamadı; zincir denenemedi.")
        lines.append("")
        return

    html = page.text
    dump_js_hits(
        html,
        "Sayfada geçen uçlar (api/translator/player)",
        ["/api/", "translator", "videoPlayerButtons", "player/", "cevirmen"],
        lines,
    )

    translator_tags = tags(html, r"<[a-z]+[^>]*\btranslator=[\"'][^\"']*[\"'][^>]*>", 10)
    lines.append(f"**`translator` öznitelikli etiketler: {len(translator_tags)}**")
    for tag in translator_tags:
        lines.append(f"- `{tag[:300]}`")
    lines.append("")

    fansec_tags = tags(html, r"<[a-z]+[^>]*(?:id|class)=[\"'][^\"']*(?:fansec|translator|cevirmen)[^\"']*[\"'][^>]*>", 10)
    lines.append(f"**fansub kabı etiketleri: {len(fansec_tags)}**")
    for tag in fansec_tags:
        lines.append(f"- `{tag[:300]}`")
    lines.append("")

    # 18+ kapısı
    dump_js_hits(html, "Yaş kapısı izleri", ["localStorage", "cookie", "18", "age"], lines, limit=4)

    # Çevirmen zinciri
    if translator_tags:
        raw_url = re.search(r"translator=[\"']([^\"']+)[\"']", translator_tags[0], re.I)
        if raw_url:
            translator_url = urllib.parse.urljoin(page.url, raw_url.group(1))
            first = fetch(translator_url, referer=page.url, xhr=True)
            lines.append("### Çevirmen cevabı")
            lines.append(f"- URL: `{translator_url}`")
            lines.append(f"- HTTP: `{first.status}` — `{len(first.text)}` karakter")
            head = re.sub(r"\s+", " ", first.text)[:500]
            lines.append(f"- Baş: `{head}`")
            buttons = tags(first.text, r"<a[^>]*\bvideo=[\"'][^\"']*[\"'][^>]*>", 12)
            lines.append(f"**`a[video]` butonları: {len(buttons)}**")
            for tag in buttons:
                lines.append(f"- `{tag[:300]}`")
            lines.append("")

            if buttons:
                video_url = re.search(r"\bvideo=[\"']([^\"']+)[\"']", buttons[0], re.I)
                if video_url:
                    absolute = urllib.parse.urljoin(translator_url, video_url.group(1))
                    player = absolute.replace("/video/", "/player/")
                    hop = fetch(player, referer=page.url, follow=False)
                    location = hop.headers.get("Location") or hop.headers.get("location") or ""
                    lines.append("### `/player/` yönlendirmesi")
                    lines.append(f"- İstek: `{player}`")
                    lines.append(f"- HTTP: `{hop.status}`")
                    lines.append(f"- Location: `{location}`")
                    lines.append("")
                    if location:
                        target = urllib.parse.urljoin(player, location)
                        landing = fetch(target, referer=page.url)
                        lines.append(f"- Hedef: `{target}` → HTTP `{landing.status}`, {len(landing.text)} karakter")
                        media = re.findall(
                            r"""(?:file|src|source|hls|url)\s*[:=]\s*["']([^"']+?\.(?:m3u8|mp4)[^"']*)["']""",
                            landing.text,
                            re.I,
                        )
                        iframe = tags(landing.text, r"<iframe[^>]*src=[\"'][^\"']+[\"'][^>]*>", 4)
                        lines.append(f"- Doğrudan medya: {media[:3]}")
                        lines.append(f"- İç iframe: {[t[:160] for t in iframe]}")
                        lines.append("")


# ---------------------------------------------------------------------------
# TrAnimeIzle
# ---------------------------------------------------------------------------

TRANIME_CANDIDATES = [
    "https://www.tranimeizle.io/shingeki-no-kyojin-1-bolum-izle",
    "https://www.tranimeizle.io/arama/shingeki",
    "https://www.tranimeizle.io/",
]


def probe_tranime(lines: list[str]) -> None:
    lines.append("## TrAnimeIzle")
    lines.append("")
    page = None
    for candidate in TRANIME_CANDIDATES:
        fetched = fetch(candidate)
        report_page(f"Sayfa: {candidate}", fetched, lines)
        if fetched.ok:
            page = fetched
            break
    if page is None:
        lines.append("> Hiçbir adres açılamadı (Cloudflare ASN banı olabilir).")
        lines.append("")
        return

    html = page.text
    dump_js_hits(
        html,
        "Sayfada geçen uçlar (api/fansub/sourcePlayer/embed2)",
        ["/api/fansubSources", "/api/sourcePlayer", "sourceBtn", "fansubSelector", "embed2/?id="],
        lines,
    )

    fansub_tags = tags(html, r"<[a-z]+[^>]*\bdata-fid=[\"'][^\"']*[\"'][^>]*>", 10)
    lines.append(f"**`data-fid` etiketleri: {len(fansub_tags)}**")
    for tag in fansub_tags:
        lines.append(f"- `{tag[:300]}`")
    lines.append("")

    source_tags = tags(html, r"<[a-z]+[^>]*\bclass=[\"'][^\"']*sourceBtn[^\"']*[\"'][^>]*>", 10)
    lines.append(f"**`sourceBtn` etiketleri: {len(source_tags)}**")
    for tag in source_tags:
        lines.append(f"- `{tag[:300]}`")
    lines.append("")

    episode_id = re.search(r'id=["\']EpisodeId["\'][^>]*value=["\'](\d+)', html, re.I) or re.search(
        r"animeWatch\s*\.\s*initialize\s*\(\s*\d+\s*,\s*(\d+)", html
    )
    lines.append(f"- EpisodeId: `{episode_id.group(1) if episode_id else 'YOK'}`")
    lines.append("")

    dump_js_hits(html, "Yaş kapısı izleri", ["localStorage", "document.cookie", "18"], lines, limit=3)


def main() -> int:
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    lines: list[str] = [
        "# Anime akış zinciri — canlı prob",
        "",
        f"UA: `{UA}`",
        "",
    ]
    try:
        if which in ("all", "anizm"):
            probe_anizm(lines)
        if which in ("all", "tranime"):
            probe_tranime(lines)
    except Exception as error:  # noqa: BLE001
        lines.append(f"> Prob hatası: `{type(error).__name__}: {error}`")
    text = "\n".join(lines)
    # PR yorumu sınırı (65536) — güvenli kes.
    print(text[:60000])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
