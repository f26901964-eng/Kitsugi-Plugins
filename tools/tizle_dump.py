#!/usr/bin/env python3
"""TrAnimeIzle **tam zincir HTML dökümü** (salt-okunur, JS çalıştırmaz).

Neden var?
    Site, veri merkezi IP'lerine Cloudflare **Error 1005** döndüğü için
    (depodaki `site-dump-data` dalı bunu kanıtlıyor) CI/sandbox'tan HTML
    okunamıyor. Bu araç, **ev/mobil bağlantıdan** çalıştırıldığında sitenin
    her adımının ham HTML'ini diske yazar; böylece eklenti seçicileri tahminle
    değil **ölçümle** güncellenebilir.

Ne dökümler?
    1. ana sayfa + popüler + son bölüm + yeni animeler listeleri
    2. arama sayfası
    3. anime detayı (bölüm listesiyle)
    4. bölüm sayfası (EpisodeId + fansub seçiciler)
    5. her fansub için  POST /api/fansubSources   (JSON gövde)
    6. her kaynak için   POST /api/sourcePlayer/{id}
    7. her kaynak için   POST /api/preVideoAd/{id}
    8. oynatıcı HTML'inden çıkan her iframe ve `embed2/?id=` iç adresi
    9. (isteğe bağlı) `--probe` ile bulunan medya adreslerinin oynayıp oynamadığı

Kullanım:
    python3 tools/tizle_dump.py                       # varsayılan bölümle
    python3 tools/tizle_dump.py --query "one piece"
    python3 tools/tizle_dump.py --url https://www.tranimeizle.io/naruto-1-bolum-izle
    python3 tools/tizle_dump.py --out tranime-dump --probe

Çıktı:
    <out>/...            ham HTML/JSON dosyaları (adım adı dosya adında)
    <out>/SUMMARY.md     hangi adım ne döndürdü, hangi seçici kaç düğüm buldu
"""

from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import http.cookiejar
import json
import os
import re
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from typing import Iterable

try:  # paket içinden (tools.tizle_dump) ve doğrudan (python3 tools/tizle_dump.py) çalışsın
    from tools import tizle_reach
except ImportError:  # pragma: no cover
    import tizle_reach  # type: ignore[no-redef]

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) "
    "Gecko/20100101 Firefox/133.0"
)
BASE_HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
    "Accept-Encoding": "gzip",
    "Upgrade-Insecure-Requests": "1",
}
XHR_HEADERS = {
    "Accept": "application/json, text/plain, */*",
    "X-Requested-With": "XMLHttpRequest",
    "Sec-Fetch-Dest": "empty",
    "Sec-Fetch-Mode": "cors",
    "Sec-Fetch-Site": "same-origin",
}
DOMAINS = [
    "https://www.tranimeizle.io",
    "https://www.tranimeizle.live",
    "https://www.tranimeizle.top",
]
DEFAULT_EPISODE = "https://www.tranimeizle.io/naruto-1-bolum-izle"
MAX_BYTES = 8 * 1024 * 1024
TIMEOUT = 25

LOG: list[str] = []


# ---------------------------------------------------------------------------
# Yardımcılar
# ---------------------------------------------------------------------------

def log(line: str = "") -> None:
    print(line, flush=True)
    LOG.append(line)


def slug(text: str, limit: int = 60) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "-", text).strip("-")
    return (cleaned or "x")[:limit]


def host_of(url: str) -> str:
    try:
        return (urllib.parse.urlsplit(url).hostname or "").lower()
    except ValueError:
        return ""


# ---------------------------------------------------------------------------
# Saf yardımcılar (testler bunları doğrular)
# ---------------------------------------------------------------------------

def embed_inner_url(url: str) -> str | None:
    """`…/embed2/?id=<gerçek adres>` → gerçek adres (ham veya URL-çözülmüş)."""
    marker = "embed2/?id="
    index = url.lower().find(marker)
    if index < 0:
        return None
    tail = url[index + len(marker):]
    for stop in ('"', "'", "<"):
        tail = tail.split(stop, 1)[0]
    tail = tail.replace("\\/", "/").replace("&amp;", "&").strip()
    if not tail:
        return None
    decoded = urllib.parse.unquote(tail)
    for candidate in (tail, decoded):
        if candidate.startswith("//"):
            candidate = "https:" + candidate
        if candidate.startswith("http://") or candidate.startswith("https://"):
            return candidate
    return None


def download_bridge_url(url: str) -> str | None:
    """`downloadru2.php?url=<base64>` → iç adres."""
    if "downloadru" not in url.lower():
        return None
    match = re.search(r"[?&]url=([^&\"'\s<>]+)", url)
    if not match:
        return None
    raw = match.group(1)
    try:
        padded = raw.replace("-", "+").replace("_", "/")
        padded += "=" * (-len(padded) % 4)
        decoded = base64.b64decode(padded).decode("utf-8", "replace").strip()
    except Exception:
        return None
    return decoded if decoded.startswith("http") else None


def episode_id_from(html: str) -> int | None:
    for pattern in (
        r'id="EpisodeId"[^>]*value="(\d+)"',
        r'value="(\d+)"[^>]*id="EpisodeId"',
        r"animeWatch\s*\.\s*initialize\s*\(\s*\d+\s*,\s*(\d+)",
        r'EpisodeId"?\s*[:=]\s*"?(\d{2,7})',
    ):
        match = re.search(pattern, html)
        if match:
            return int(match.group(1))
    return None


def fansubs_from(html: str) -> list[dict]:
    """`div/li.fansubSelector[data-fid]` → [{id,label}]."""
    out: list[dict] = []
    seen: set[int] = set()
    for match in re.finditer(r"<[^>]*class=\"[^\"]*fansubSelector[^\"]*\"[^>]*>", html):
        tag = match.group(0)
        fid = re.search(r'data-fid="(\d+)"', tag)
        if not fid:
            continue
        value = int(fid.group(1))
        if value in seen:
            continue
        seen.add(value)
        label = re.search(r'data-fad="([^"]*)"', tag)
        out.append({"id": value, "label": (label.group(1) if label else "") or f"Fansub {value}"})
    if not out:
        for match in re.finditer(r'data-fid="(\d+)"', html):
            value = int(match.group(1))
            if value not in seen:
                seen.add(value)
                out.append({"id": value, "label": f"Fansub {value}"})
    return out


def sources_from(html: str) -> list[dict]:
    """`li.sourceBtn[data-id]` (+ `p.title`) → [{id,label}]."""
    out: list[dict] = []
    seen: set[str] = set()
    for match in re.finditer(r"<li[^>]*class=\"[^\"]*sourceBtn[^\"]*\"[^>]*>(.*?)</li>", html, re.S):
        block = match.group(0)
        sid = re.search(r'data-id="([^"]+)"', block)
        if not sid:
            continue
        value = sid.group(1).strip()
        if not value or value in seen:
            continue
        seen.add(value)
        title = re.search(r"<p[^>]*class=\"[^\"]*title[^\"]*\"[^>]*>(.*?)</p>", block, re.S)
        label = re.sub(r"<[^>]+>", "", title.group(1)).strip() if title else ""
        out.append({"id": value, "label": label or f"Kaynak {value}"})
    if not out:
        for match in re.finditer(r'class="[^"]*sourceBtn[^"]*"[^>]*data-id="([^"]+)"', html):
            value = match.group(1).strip()
            if value and value not in seen:
                seen.add(value)
                out.append({"id": value, "label": value})
    return out


def iframes_from(html: str, base: str) -> list[str]:
    out: list[str] = []
    for match in re.finditer(r"<iframe[^>]+src=\"([^\"]+)\"", html, re.I):
        raw = match.group(1).replace("\\/", "/").replace("&amp;", "&").strip()
        if raw.startswith("//"):
            raw = "https:" + raw
        elif raw.startswith("/"):
            raw = urllib.parse.urljoin(base, raw)
        if raw.startswith("http") and raw not in out:
            out.append(raw)
    return out


def media_from(html: str, base: str) -> list[str]:
    out: list[str] = []
    text = html.replace("\\/", "/").replace("&amp;", "&")
    for match in re.finditer(r"[\"'](https?://[^\"'\s]+?\.(?:m3u8|mp4|mpd)[^\"'\s]*)[\"']", text, re.I):
        url = match.group(1)
        if url not in out:
            out.append(url)
    for match in re.finditer(r"[\"'](/[^\"'\s]+?\.(?:m3u8|mp4)[^\"'\s]*)[\"']", text, re.I):
        url = urllib.parse.urljoin(base, match.group(1))
        if url not in out:
            out.append(url)
    return out


def detect_captcha(html: str) -> bool:
    low = html.lower()
    return (
        "captcha-holder" in low
        or "iconcaptcha" in low
        or "captchachallenge" in low
    )


class _CardParser(HTMLParser):
    """`div.flx-block` kartlarını ve `data-href` adreslerini toplar."""

    def __init__(self) -> None:
        super().__init__()
        self.cards: list[dict] = []
        self._depth = 0
        self._current: dict | None = None
        self._in_h4 = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = {k: (v or "") for k, v in attrs}
        classes = values.get("class", "")
        if tag == "div" and "flx-block" in classes and self._current is None:
            self._current = {"href": values.get("data-href", ""), "title": "", "poster": ""}
            self._depth = 0
            return
        if self._current is not None:
            if tag == "div":
                self._depth += 1
            if tag == "h4":
                self._in_h4 = True
            if tag == "img" and not self._current["poster"]:
                self._current["poster"] = values.get("data-src") or values.get("src") or ""
            if tag == "a" and not self._current["href"]:
                self._current["href"] = values.get("href", "")

    def handle_endtag(self, tag: str) -> None:
        if self._current is None:
            return
        if tag == "h4":
            self._in_h4 = False
        if tag == "div":
            if self._depth == 0:
                card = self._current
                self._current = None
                if card["href"]:
                    self.cards.append(card)
            else:
                self._depth -= 1

    def handle_data(self, data: str) -> None:
        if self._current is not None and self._in_h4:
            self._current["title"] += data


def cards_from(html: str) -> list[dict]:
    parser = _CardParser()
    try:
        parser.feed(html)
    except Exception:
        return []
    return parser.cards


# ---------------------------------------------------------------------------
# Ağ
# ---------------------------------------------------------------------------

class Fetcher:
    def __init__(self, outdir: str, via: str = "direct") -> None:
        self.outdir = outdir
        #: "direct" = kendi çerez kavanozumuz; diğerleri = tizle_reach katmanı
        self.via = via
        self.jar = http.cookiejar.CookieJar()
        context = ssl.create_default_context()
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar),
            urllib.request.HTTPSHandler(context=context),
        )
        self.count = 0

    def request(
        self,
        url: str,
        referer: str | None = None,
        json_body: dict | None = None,
        form: dict | None = None,
        xhr: bool = True,
        post: bool = False,
    ) -> tuple[int, str, str]:
        headers = dict(BASE_HEADERS)
        if xhr:
            headers.update(XHR_HEADERS)
        if referer:
            headers["Referer"] = referer
            headers["Origin"] = "{0.scheme}://{0.netloc}".format(urllib.parse.urlsplit(referer))
        data = None
        if json_body is not None:
            data = json.dumps(json_body).encode()
            headers["Content-Type"] = "application/json; charset=utf-8"
        elif form is not None:
            data = urllib.parse.urlencode(form).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        elif post:
            data = b""
        request = urllib.request.Request(url, data=data, headers=headers)
        if post or data is not None:
            request.get_method = lambda: "POST"  # type: ignore[assignment]

        if self.via != "direct":
            # Cloudflare/IP engeli: okuma katmanı (jina / cors proxy / wayback).
            method = "POST" if (post or data is not None) else "GET"
            result = tizle_reach.fetch(
                url, method=method, headers=headers, data=data, via=self.via
            )
            return result.status, result.text, result.final_url or url

        try:
            with self.opener.open(request, timeout=TIMEOUT) as response:
                raw = response.read(MAX_BYTES)
                if response.headers.get("Content-Encoding") == "gzip":
                    raw = gzip.decompress(raw)
                return response.status, raw.decode("utf-8", "replace"), response.geturl()
        except urllib.error.HTTPError as error:
            try:
                raw = error.read(MAX_BYTES)
            except Exception:
                raw = b""
            return error.code, raw.decode("utf-8", "replace"), url
        except Exception as error:  # noqa: BLE001 - ağ hataları rapora yazılır
            return 0, f"<<istek hatası: {error}>>", url

    def save(self, name: str, body: str) -> str:
        filename = f"{self.count:02d}_{slug(name, 80)}.html"
        self.count += 1
        path = os.path.join(self.outdir, filename)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(body)
        return filename

    def solve_captcha(self, site: str) -> bool:
        """Aniyomi `ShittyCaptchaInterceptor` akışı (cID=0, tM=light)."""
        status, body, _ = self.request(
            f"{site}/api/Captcha/", referer=f"{site}/", form={"cID": "0", "rT": "1", "tM": "light"}
        )
        self.save("captcha_list", body)
        try:
            ids = json.loads(body)
        except Exception:
            ids = re.findall(r'"([^"]{4,})"', body)
        if not isinstance(ids, list) or len(ids) < 3:
            log(f"  !! captcha listesi çözülemedi (status={status}, {len(body)}B)")
            return False
        digests: list[tuple[str, str]] = []
        for image_id in ids:
            status, raw, _ = self.request(f"{site}/api/Captcha/?cid=0&hash={image_id}", referer=f"{site}/")
            digest = hashlib.md5(raw.encode("utf-8", "replace")).hexdigest()
            digests.append((image_id, digest))
        counts: dict[str, int] = {}
        for _, digest in digests:
            counts[digest] = counts.get(digest, 0) + 1
        unique_digest = min(counts, key=counts.get)
        chosen = next(image_id for image_id, digest in digests if digest == unique_digest)
        self.request(
            f"{site}/api/Captcha/", referer=f"{site}/", form={"cID": "0", "rT": "2", "pC": chosen}
        )
        log(f"  · captcha çözüldü ({len(ids)} görsel)")
        return True


# ---------------------------------------------------------------------------
# Zincir
# ---------------------------------------------------------------------------

def pick_domain(fetcher: Fetcher) -> str:
    """Canlı domain + erişim katmanı seçer.

    1. Doğrudan bağlantı (kendi çerez kavanozu) — cihaz/ev bağlantısında bu yol.
    2. Doğrudan yol engelliyse (Cloudflare 1005) okuma katmanları denenir:
       jina → cors proxy → wayback. Bulunan katman `fetcher.via` olarak sabitlenir.
    """
    blocked_note = ""
    if fetcher.via != "direct":
        # Katman elle seçildi (ör. --via wayback): doğrudan yolu denemeye gerek yok.
        for domain in DOMAINS:
            result = tizle_reach.fetch(domain + "/", via=fetcher.via)
            log(f"- {domain} → {result.summary()} ({fetcher.via})")
            if result.status == 200 and result.has_site_content and not result.blocked:
                return domain
        log(f"  !! {fetcher.via} katmanı içerik getirmedi")
        return DOMAINS[0]

    for domain in DOMAINS:
        status, body, final = fetcher.request(domain, referer=domain + "/", xhr=False)
        cards = cards_from(body)
        log(f"- {domain} → HTTP {status}, {len(body)}B, {len(cards)} kart (direct)")
        if status == 200 and len(cards) >= 3:
            return domain
        if "1005" in body or "you have been blocked" in body.lower():
            blocked_note = "Cloudflare/IP engeli (direct)"
            log(f"  !! {blocked_note}")

    log(f"- doğrudan erişim yok ({blocked_note or 'cevap yok'}) → okuma katmanları matrisi")
    for domain in DOMAINS:
        for via in tizle_reach.AUTO_ORDER:
            if via == "direct":
                continue
            result = tizle_reach.fetch(domain + "/", via=via)
            log(f"  · {domain.split('//')[-1]:<26} {result.summary()}")
            if result.status == 200 and result.has_site_content and not result.blocked:
                fetcher.via = via
                log(f"  ✔ erişim katmanı: {via} (domain: {domain})")
                return domain
    log("  !! hiçbir okuma katmanı içeriği getiremedi")
    return DOMAINS[0]


def dump_player(
    fetcher: Fetcher,
    url: str,
    referer: str,
    tag: str,
    depth: int,
    seen: set[str],
) -> list[str]:
    """Oynatıcı sayfasını (ve bir katman iç iframe'ini) döker; bulunan medyayı döndürür."""
    if url in seen or depth > 2:
        return []
    seen.add(url)
    status, body, _ = fetcher.request(url, referer=referer, xhr=False)
    fetcher.save(f"{tag}_player_{host_of(url)}", body)
    log(f"    · oynatıcı {host_of(url)} → {status}, {len(body)}B")
    media = media_from(body, url)
    for item in media[:4]:
        log(f"      → medya: {item.split('?')[0][:100]}")
    for frame in iframes_from(body, url)[:2]:
        media += dump_player(fetcher, frame, url, tag + "_ic", depth + 1, seen)
    return media


def dump_chain(fetcher: Fetcher, site: str, episode_url: str, query: str, probe: bool) -> None:
    status, home, _ = fetcher.request(site + "/", referer=site + "/", xhr=False)
    fetcher.save("home", home)
    log(f"- ana sayfa → {status}, {len(home)}B, {len(cards_from(home))} kart")

    for path in ("/listeler/populer/sayfa-1", "/listeler/yenibolum/sayfa-1", "/listeler/eklenen/sayfa-1"):
        status, body, _ = fetcher.request(site + path, referer=site + "/", xhr=False)
        fetcher.save(path.replace("/", "_"), body)
        log(f"- {path} → {status}, {len(body)}B, {len(cards_from(body))} kart")

    if query:
        search_url = f"{site}/arama/{urllib.parse.quote(query)}"
        status, body, _ = fetcher.request(search_url, referer=site + "/", xhr=False)
        fetcher.save("arama", body)
        cards = cards_from(body)
        log(f"- arama '{query}' → {status}, {len(body)}B, {len(cards)} kart")
        if cards and not episode_url:
            href = cards[0]["href"]
            episode_url = urllib.parse.urljoin(site + "/", href)
            log(f"  · ilk kart: {episode_url}")

    episode_url = episode_url or DEFAULT_EPISODE
    status, episode, _ = fetcher.request(episode_url, referer=site + "/", xhr=False)
    fetcher.save("bolum", episode)
    if detect_captcha(episode):
        log("  !! bölüm sayfası captcha kapısı → çözülüyor")
        if fetcher.solve_captcha(site):
            status, episode, _ = fetcher.request(episode_url, referer=site + "/", xhr=False)
            fetcher.save("bolum_captcha_sonrasi", episode)

    episode_id = episode_id_from(episode)
    fansubs = fansubs_from(episode)
    log(f"- bölüm → {status}, {len(episode)}B, EpisodeId={episode_id}, fansub={len(fansubs)}")
    if not episode_id:
        log("  !! EpisodeId bulunamadı — döküm burada biter")
        return

    sources: list[dict] = []
    for fansub in fansubs:
        status, body, _ = fetcher.request(
            f"{site}/api/fansubSources",
            referer=episode_url,
            json_body={"EpisodeId": episode_id, "FansubId": fansub["id"]},
        )
        filename = fetcher.save(f"fansubSources_f{fansub['id']}", body)
        found = sources_from(body)
        log(f"  · fansub {fansub['id']} ({fansub['label']}) → {status}, {len(body)}B, {len(found)} kaynak [{filename}]")
        for item in found:
            item["fansub"] = fansub["label"]
            sources.append(item)

    seen_players: set[str] = set()
    media_all: list[str] = []
    for source in sources:
        tag = f"src{source['id']}"
        status, body, _ = fetcher.request(
            f"{site}/api/sourcePlayer/{source['id']}", referer=episode_url, post=True
        )
        fetcher.save(f"sourcePlayer_{source['id']}", body)
        log(f"  · kaynak {source['id']} ({source['label']}) → sourcePlayer {status}, {len(body)}B")
        status2, alt, _ = fetcher.request(
            f"{site}/api/preVideoAd/{source['id']}", referer=episode_url, post=True
        )
        fetcher.save(f"preVideoAd_{source['id']}", alt)
        log(f"    preVideoAd → {status2}, {len(alt)}B")

        for body_text in (body, alt):
            for frame in iframes_from(body_text, episode_url):
                inner = embed_inner_url(frame)
                if inner:
                    media_all += dump_player(fetcher, inner, episode_url, tag, 0, seen_players)
                media_all += dump_player(fetcher, frame, episode_url, tag + "_sarmal", 1, seen_players)
            media_all += media_from(body_text, episode_url)

    media_all = list(dict.fromkeys(media_all))
    log(f"- toplam {len(media_all)} medya adresi bulundu")

    if probe:
        log("- akış probu (ilk 1 bayt aralığı):")
        for item in media_all[:12]:
            status, body, _ = fetcher.request(item, referer=episode_url, xhr=False)
            kind = body[:16].replace("\n", " ")[:16]
            log(f"    {status} {host_of(item)} {item.split('?')[0][-40:]} | {kind}")


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="TrAnimeIzle tam zincir HTML dökümü")
    parser.add_argument("--out", default="tranime-dump", help="çıktı klasörü")
    parser.add_argument("--url", default="", help="bölüm sayfası (boşsa arama/ilk kart)")
    parser.add_argument("--query", default="naruto", help="arama sorgusu")
    parser.add_argument("--probe", action="store_true", help="bulunan akışları test et")
    parser.add_argument("--domain", default="", help="site kökü (boşsa otomatik)")
    parser.add_argument(
        "--via",
        default="auto",
        help="erişim katmanı: auto (önce doğrudan) | direct | jina | codetabs | corsproxy | allorigins | corssh | wayback",
    )
    args = parser.parse_args(list(argv) if argv is not None else None)

    os.makedirs(args.out, exist_ok=True)
    fetcher = Fetcher(args.out, via="direct" if args.via in ("auto", "direct") else args.via)
    started = time.strftime("%Y-%m-%d %H:%M:%S")
    log(f"TrAnimeIzle dökümü — {started}")

    site = args.domain.rstrip("/") if args.domain else pick_domain(fetcher)
    log(f"- site: {site}")
    try:
        dump_chain(fetcher, site, args.url, args.query, args.probe)
    except KeyboardInterrupt:
        log("!! kullanıcı iptal etti")
    except Exception as error:  # noqa: BLE001 - rapor her koşulda yazılır
        log(f"!! zincir hatası: {type(error).__name__}: {error}")

    summary_path = os.path.join(args.out, "SUMMARY.md")
    with open(summary_path, "w", encoding="utf-8") as handle:
        handle.write("# TrAnimeIzle dökümü\n\n```\n")
        handle.write("\n".join(LOG))
        handle.write("\n```\n")
    log(f"\nÖzet: {summary_path}")

    # Ham HTML'de akış adresleri (token) bulunur: paylaşmadan önce gözden geçirin.
    print("\nNot: döküm dosyaları ham HTML içerir; paylaşmadan önce gözden geçirin.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
