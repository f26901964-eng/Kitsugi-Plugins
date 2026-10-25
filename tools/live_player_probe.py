#!/usr/bin/env python3
"""Opt-in, read-only live probe for the DiziBox and HDFilmIzle video chains.

The report intentionally omits query strings and raw HTML/stream URLs. Run this
from a network that can reach the public sites (for example, the opt-in CI step).
It does not execute page JavaScript or download video segments.
"""
from __future__ import annotations

import base64
import hashlib
import http.cookiejar
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from html.parser import HTMLParser
from typing import Iterable

DIZIBOX_PAGE = "https://www.dizibox.live/supernatural-1-sezon-1-bolum-hd-3-izle/"
HDFILM_PAGE = "https://www.hdfilmizle.to/dizi/supernatural-izle/sezon-1/bolum-1/"
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)
MAX_PAGE_BYTES = 2 * 1024 * 1024
MAX_STREAM_BYTES = 128 * 1024
MAX_CHAIN_REQUESTS = 12


@dataclass
class FetchResult:
    url: str
    status: int
    text: str
    content_type: str = ""
    error: str = ""

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300 and not self.error


class PageMarkup(HTMLParser):
    """Collect iframe/script metadata and inline scripts without executing them."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.iframes: list[dict[str, str]] = []
        self.script_urls: list[str] = []
        self.scripts: list[str] = []
        self._script_text: list[str] | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = {key.lower(): value or "" for key, value in attrs}
        if tag.lower() == "iframe":
            self.iframes.append(values)
        elif tag.lower() == "script":
            source = values.get("src", "").strip()
            if source:
                self.script_urls.append(source)
            self._script_text = []

    def handle_data(self, data: str) -> None:
        if self._script_text is not None:
            self._script_text.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "script" and self._script_text is not None:
            value = "".join(self._script_text)
            if value.strip():
                self.scripts.append(value)
            self._script_text = None


def parse_markup(html: str) -> PageMarkup:
    parser = PageMarkup()
    parser.feed(html)
    return parser


def safe_url(url: str) -> str:
    """Return host and route shape only; never include query, fragment, or IDs."""
    try:
        parsed = urllib.parse.urlsplit(url)
        host = (parsed.hostname or "unknown-host").lower()
        path = parsed.path or "/"
        if "/embed/sheila/" in path.lower():
            prefix = path[: path.lower().index("/embed/sheila/")]
            path = prefix + "/embed/sheila/<id>"
        elif "/embed/" in path.lower():
            prefix = path[: path.lower().index("/embed/")]
            path = prefix + "/embed/<id>"
        elif "/player/" in path.lower():
            match = re.search(r"/player/.+?\.php", path, re.IGNORECASE)
            path = match.group(0) if match else "/player/<path>"
        else:
            path = re.sub(r"(?<=/)[0-9a-f]{16,}(?=/|$)", "<id>", path, flags=re.I)
            path = re.sub(r"(?<=/)[a-z0-9_-]{28,}(?=/|$)", "<id>", path, flags=re.I)
        return host + path
    except Exception:
        return "<invalid-url>"


def allowed_host(url: str, domains: Iterable[str]) -> bool:
    try:
        host = (urllib.parse.urlsplit(url).hostname or "").lower().rstrip(".")
    except Exception:
        return False
    return urllib.parse.urlsplit(url).scheme.lower() == "https" and any(
        host == domain or host.endswith("." + domain) for domain in domains
    )


def request_page(url: str, referer: str = "", *, dizibox_cookies: bool = False,
                 max_bytes: int = MAX_PAGE_BYTES) -> FetchResult:
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.5",
    }
    if referer:
        headers["Referer"] = referer

    # Attach the site's legacy cookies only to DiziBox requests. The jar scopes
    # them to that domain and does not forward them to MolyStream/CDN hosts.
    cookie_jar = http.cookiejar.CookieJar()
    if dizibox_cookies:
        for name, value in (
            ("LockUser", "true"),
            ("isTrustedUser", "true"),
            ("dbxu", "1743289650198"),
        ):
            cookie_jar.set_cookie(http.cookiejar.Cookie(
                version=0, name=name, value=value, port=None, port_specified=False,
                domain=".dizibox.live", domain_specified=True, domain_initial_dot=True,
                path="/", path_specified=True, secure=True, expires=None,
                discard=True, comment=None, comment_url=None, rest={}, rfc2109=False,
            ))
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookie_jar))
    request = urllib.request.Request(url, headers=headers)
    try:
        with opener.open(request, timeout=15) as response:
            content = response.read(max_bytes + 1)
            content_type = response.headers.get("Content-Type", "")
            charset = response.headers.get_content_charset() or "utf-8"
            return FetchResult(
                url=response.geturl(), status=response.status,
                text=content[:max_bytes].decode(charset, errors="replace"),
                content_type=content_type,
                error="truncated" if len(content) > max_bytes else "",
            )
    except urllib.error.HTTPError as error:
        content = error.read(min(max_bytes, 8192))
        return FetchResult(error.geturl(), error.code,
                           content.decode("utf-8", errors="replace"),
                           error.headers.get("Content-Type", ""), "http-error")
    except Exception as error:  # Do not print exception strings: they can contain signed URLs.
        return FetchResult(url, 0, "", error=type(error).__name__)


def iframe_urls(html: str, base_url: str, *, prefer_data_src: bool = False,
                domains: Iterable[str]) -> list[str]:
    markup = parse_markup(html)
    result: list[str] = []
    for iframe in markup.iframes:
        candidates = [iframe.get("data-src", ""), iframe.get("src", "")]
        if not prefer_data_src:
            candidates.reverse()
        for raw in candidates:
            raw = raw.strip()
            if not raw or raw in {"#", "about:blank"} or raw.lower().startswith(("javascript:", "data:")):
                continue
            resolved = urllib.parse.urljoin(base_url, raw)
            if allowed_host(resolved, domains) and resolved not in result:
                result.append(resolved)
                break
    return result[:4]


def hls_manifest(text: str) -> bool:
    header = text.removeprefix("\ufeff").lstrip()
    return header.startswith("#EXTM3U") and (len(header) == 7 or header[7].isspace())


def plain_https_target(text: str) -> str | None:
    candidate = text.removeprefix("\ufeff").strip()
    if not candidate.lower().startswith("https://") or any(ch.isspace() or ord(ch) < 0x20 for ch in candidate):
        return None
    parsed = urllib.parse.urlsplit(candidate)
    if not parsed.hostname or parsed.username or parsed.password:
        return None
    return candidate


def decode_vidrame_file(value: str) -> str | None:
    """Decode the documented reverse(ROT13(Base64URL(url))) EE.dd payload."""
    match = re.search(r"EE\s*\.\s*dd\s*\(\s*(['\"])([^'\"]+)\1\s*\)", value, re.I)
    if not match:
        return value.strip() or None
    encoded = match.group(2)
    try:
        raw = base64.urlsafe_b64decode(encoded + "=" * ((4 - len(encoded) % 4) % 4))
        decoded = raw.decode("utf-8")
    except Exception:
        return None
    rot13 = decoded.translate(str.maketrans(
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
        "NOPQRSTUVWXYZABCDEFGHIJKLMnopqrstuvwxyzabcdefghijklm",
    ))
    return rot13[::-1]


def js_value(value: str) -> str:
    return (value.replace(r"\/", "/").replace(r"\u0026", "&")
            .replace(r"\x26", "&").replace("&amp;", "&"))


def extract_array(source: str, marker: re.Pattern[str]) -> str | None:
    match = marker.search(source)
    if not match:
        return None
    start = source.find("[", match.start())
    depth = 0
    quote = ""
    escaped = False
    for index in range(start, len(source)):
        char = source[index]
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = ""
            continue
        if char in "'\"`":
            quote = char
        elif char == "[":
            depth += 1
        elif char == "]":
            depth -= 1
            if depth == 0:
                return source[start + 1:index]
    return None


def source_objects(script: str) -> list[dict[str, str]]:
    array = extract_array(script, re.compile(r"\bsources\s*:\s*\[", re.I))
    if array is None:
        return []
    found: list[dict[str, str]] = []
    for match in re.finditer(r"\{[^{}]*\}", array, re.S):
        obj = match.group(0)
        encoded = re.search(
            r"(?:['\"]?file['\"]?)\s*:\s*(EE\s*\.\s*dd\s*\(\s*['\"][^'\"]+['\"]\s*\))",
            obj, re.I,
        )
        quoted = re.search(r"(?:['\"]?file['\"]?)\s*:\s*(['\"])((?:\\.|[^'\"])*?)\1", obj, re.I | re.S)
        file_value = encoded.group(1) if encoded else (quoted.group(2) if quoted else "")
        if not file_value:
            continue
        def prop(name: str) -> str:
            value = re.search(rf"(?:['\"]?{re.escape(name)}['\"]?)\s*:\s*(['\"])((?:\\.|[^'\"])*?)\1", obj, re.I | re.S)
            return js_value(value.group(2)) if value else ""
        kind = prop("kind")
        if kind.lower() in {"captions", "subtitles"}:
            continue
        found.append({"file": js_value(file_value), "type": prop("type"), "label": prop("label"), "kind": kind})
    return found


def decrypt_cryptojs(html: str) -> str | None:
    """Read only literal CryptoJS AES calls and decrypt the OpenSSL salted form."""
    call = re.search(
        r"CryptoJS\s*\.\s*AES\s*\.\s*decrypt\s*\(\s*(['\"])([^'\"]+)\1\s*,\s*(['\"])([^'\"]+)\3\s*\)",
        html, re.I,
    )
    if not call or len(call.group(2)) > 400_000:
        return None
    try:
        raw = base64.b64decode(call.group(2), validate=True)
        if raw[:8] != b"Salted__" or len(raw) < 32:
            return None
        salt, ciphertext = raw[8:16], raw[16:]
        derived = b""
        previous = b""
        password = call.group(4).encode()
        while len(derived) < 48:
            previous = hashlib.md5(previous + password + salt).digest()
            derived += previous
        result = subprocess.run(
            ["openssl", "enc", "-d", "-aes-256-cbc", "-K", derived[:32].hex(),
             "-iv", derived[32:48].hex()],
            input=ciphertext, capture_output=True, check=False, timeout=5,
        )
        if result.returncode:
            return None
        return result.stdout.decode("utf-8", errors="replace")
    except Exception:
        return None


def fetch_stream(url: str, referer: str) -> tuple[bool, str]:
    result = request_page(url, referer, max_bytes=MAX_STREAM_BYTES)
    if not result.ok:
        return False, f"HTTP {result.status or result.error}"
    if hls_manifest(result.text):
        return True, f"HTTP {result.status}; #EXTM3U verified at {safe_url(result.url)}"
    target = plain_https_target(result.text)
    if target and target != url:
        follow = request_page(target, referer, max_bytes=MAX_STREAM_BYTES)
        if follow.ok and hls_manifest(follow.text):
            return True, f"HTTP {follow.status}; plain-URL hop resolved to verified #EXTM3U at {safe_url(follow.url)}"
        return False, f"plain-URL hop did not return HLS (HTTP {follow.status or follow.error})"
    signature = "HTML/challenge" if "<html" in result.text[:1024].lower() else "not an HLS playlist"
    return False, f"HTTP {result.status}; {signature}"


def fetch_description(result: FetchResult) -> str:
    if result.error and result.status == 0:
        return f"network error ({result.error})"
    details = f"HTTP {result.status}; {len(result.text.encode('utf-8'))} bytes"
    if result.content_type:
        details += f"; {result.content_type.split(';', 1)[0]}"
    if result.error == "truncated":
        details += "; body truncated at probe limit"
    return details


def parse_and_probe_player(url: str, referer: str, *, site: str,
                           lines: list[str], visited: set[str], depth: int = 0) -> bool:
    if depth > 4 or url in visited or len(visited) >= MAX_CHAIN_REQUESTS:
        return False
    visited.add(url)
    response = request_page(url, referer, dizibox_cookies=(site == "DiziBox"))
    lines.append(f"- Player `{safe_url(response.url)}`: {fetch_description(response)}")
    if not response.ok:
        return False
    if hls_manifest(response.text):
        lines.append(f"  - **Playable HLS manifest verified** (#EXTM3U; Referer is the parent page).")
        return True

    direct_target = plain_https_target(response.text)
    if direct_target:
        ok, status = fetch_stream(direct_target, referer)
        lines.append(f"  - Plain HTTPS response target `{safe_url(direct_target)}`: {status}.")
        return ok

    html = response.text
    if site == "DiziBox":
        decoded = decrypt_cryptojs(html)
        if decoded is not None:
            html = decoded
            lines.append("  - CryptoJS OpenSSL-salted player config: decrypted successfully (raw content omitted).")
        elif "CryptoJS" in html and "AES" in html:
            lines.append("  - CryptoJS player config detected but could not be decrypted by the probe.")

    markup = parse_markup(html)
    sources = source_objects("\n".join(markup.scripts) + "\n" + html)
    if sources:
        lines.append(f"  - Parsed video source entries: {len(sources)}.")
        for source in sources[:4]:
            decoded_file = decode_vidrame_file(source["file"]) if site == "HDFilmIzle" else source["file"]
            if not decoded_file:
                lines.append("  - A source entry exists but its file value could not be decoded.")
                continue
            media_url = urllib.parse.urljoin(response.url, decoded_file)
            label = source["label"] or source["type"] or "unlabeled"
            lines.append(f"  - Source ({label}; kind={source['kind'] or 'unspecified'}): `{safe_url(media_url)}`.")
            if (site == "DiziBox" and re.search(r"/embed/sheila/", urllib.parse.urlsplit(media_url).path, re.I)) or \
                    re.search(r"\.m3u8(?:[?#]|$)", media_url, re.I):
                ok, status = fetch_stream(media_url, response.url)
                lines.append(f"  - Playlist check: {status}.")
                if ok:
                    return True

    frames = iframe_urls(
        html, response.url,
        prefer_data_src=(site == "HDFilmIzle"),
        domains=("dizibox.live", "molystream.org", "molystream.net") if site == "DiziBox"
                else ("hdfilmizle.to", "vidrame.pro"),
    )
    for frame in frames:
        if parse_and_probe_player(frame, response.url, site=site, lines=lines,
                                  visited=visited, depth=depth + 1):
            return True

    # The source resolver documents /embed/sheila/{id} as a stream route and
    # /embed/{id} as a companion player page. Try the latter only if the former
    # returned HTML but exposed no source/playlist.
    if site == "DiziBox" and re.search(r"/embed/sheila/[^/]+/?$", urllib.parse.urlsplit(url).path, re.I):
        companion = re.sub(r"/embed/sheila/", "/embed/", url, count=1, flags=re.I)
        if companion != url and parse_and_probe_player(companion, referer, site=site, lines=lines,
                                                       visited=visited, depth=depth + 1):
            return True
    return False


def probe_site(name: str, page_url: str) -> str:
    lines = [f"## {name}", f"Page: `{safe_url(page_url)}`"]
    result = request_page(page_url, dizibox_cookies=(name == "DiziBox"))
    lines.append(f"- Episode page: {fetch_description(result)}")
    if not result.ok:
        lines.append("- Player chain not followed because the episode page was not accessible from this runner.")
        return "\n".join(lines)
    allowed = ("dizibox.live", "molystream.org", "molystream.net") if name == "DiziBox" \
        else ("hdfilmizle.to", "vidrame.pro")
    frames = iframe_urls(result.text, result.url, prefer_data_src=(name == "HDFilmIzle"), domains=allowed)
    lines.append(f"- Supported player iframes found: {len(frames)}.")
    if not frames:
        markup = parse_markup(result.text)
        lines.append(f"- Inline scripts: {len(markup.scripts)}; external scripts: {len(markup.script_urls)}.")
        lines.append("- No supported iframe/source was found in the fetched episode markup.")
        return "\n".join(lines)

    verified = False
    visited: set[str] = set()
    for frame in frames:
        lines.append(f"- Episode iframe: `{safe_url(frame)}`.")
        if parse_and_probe_player(frame, result.url, site=name, lines=lines,
                                  visited=visited, depth=0):
            verified = True
            break
    lines.append(
        "- **At least one playable HLS manifest was verified.**" if verified
        else "- **No playable HLS manifest was verified by this live probe.**"
    )
    return "\n".join(lines)


def main() -> None:
    reports = [
        "# Live video-source probe",
        "Read-only public-page requests; query strings, raw HTML and signed stream URLs are omitted.",
        "No JavaScript was executed and no video segments were downloaded.",
        "",
        probe_site("DiziBox", DIZIBOX_PAGE),
        "",
        probe_site("HDFilmIzle", HDFILM_PAGE),
        "",
    ]
    print("\n".join(reports))


if __name__ == "__main__":
    main()
