#!/usr/bin/env python3
"""RplayerDecoder.kt'nin birebir Python aynası — mantık doğrulaması için.

Kotlin tarafı CI'da derlenip test edilecek; burada aynı algoritma gerçek
fixture'lar üzerinde hızlıca denenir (regex/escaped mantık hatalarını yakalar).
"""
import re
import sys

ALPHA = "0123456789abcdefghijklmnopqrstuvwxyz"
DEFAULT_MAGIC = 399756995
DEFAULT_OFFSET = 5
BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

PACKER_RE = re.compile(r"eval\(function\(p,a,c,k,e,d\)\{(.*?)\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\),0,\{\}\)", re.S)
ARRAY_CALL_RE = re.compile(r"""([A-Za-z0-9_$]+)\s*=\s*([A-Za-z0-9_$]+)\(\s*\[\s*((?:"[^"]*"|'[^']*')(?:\s*,\s*(?:"[^"]*"|'[^']*'))*)\s*\]\s*\)""")
QUOTED_RE = re.compile(r"\"([^\"]*)\"|'([^']*)'")
SOURCES_FILE_RE = re.compile(r"sources\s*:\s*\[\s*\{\s*file\s*:\s*([A-Za-z0-9_$]+)", re.I)
VAR_ASSIGN_RE = re.compile(r"(?:var|let|const)\s+([A-Za-z0-9_$]+)\s*=\s*([A-Za-z0-9_$]+)\(")
OP_REVERSE_RE = re.compile(r"\.reverse\s*\(\s*\)")
OP_ATOB_RE = re.compile(r"(?<![\w$])atob\s*\(")
OP_BTOA_RE = re.compile(r"(?<![\w$])btoa\s*\(")
OP_REPLACE_RE = re.compile(r"\.replace\s*\(")
ROT_PATTERNS = [
    re.compile(r"[A-Za-z_$][\w$]*\s*-\s*(?:base|65|97)\s*\+\s*(\d+)"),
    re.compile(r"[A-Za-z_$][\w$]*\s*=\s*[A-Za-z_$][\w$]*\s*\.charCodeAt\(\s*0\s*\)\s*\+\s*(\d+)"),
    re.compile(r"charCodeAt\(\s*0\s*\)\s*\+\s*(\d+)"),
    re.compile(r"\+\s*(\d+)\s*\)\s*%\s*26"),
]
SHIFT_RE = re.compile(r"(\d{5,13})\s*%\s*\(\s*[A-Za-z_$][\w$]*\s*([+-])\s*(\d+)\s*\)")
SHIFT_SIGN_RE = re.compile(r"([+-])\s*\(*\s*\d{5,13}\s*%")
TRACKS_RE = re.compile(r"tracks\s*:\s*\[(.*?)\]", re.S)
TRACK_BLOCK_RE = re.compile(r"\{[^{}]*\}")
FILE_LINK_RE = re.compile(r"file_link\s*=\s*\"([^\"]+)\"")
PLAIN_STREAM_RE = re.compile(r"(https?://[^\s\"'<>]+(?:\.m3u8|\.mp4|master\.txt)(?:\?[^\s\"'<>]*)?)", re.I)


def unescape_html(h):
    return (h.replace("\\/", "/").replace("\\u002f", "/").replace("\\u003a", ":")
             .replace("\\u0026", "&").replace("&amp;", "&"))


def js_unescape(s):
    return s.replace("\\\\", "\x00").replace("\\'", "'").replace("\x00", "\\")


def encode(num, a):
    sb = ""
    n = num
    while n >= a:
        sb += chr(n % a + 29) if n % a > 35 else ALPHA[n % a]
        n //= a
    sb += chr(n + 29) if n > 35 else ALPHA[n]
    return sb[::-1]


def packer_unpack(p, a, c, dictionary):
    out = p
    i = c
    while i > 0:
        i -= 1
        if i < len(dictionary) and dictionary[i]:
            token = encode(i, a)
            out = re.sub(r"\b" + re.escape(token) + r"\b", lambda m: dictionary[i], out)
    return out


def sources_file_variable(src):
    m = SOURCES_FILE_RE.search(src)
    return m.group(1) if m else None


def variable_function(src, variable):
    for m in VAR_ASSIGN_RE.finditer(src):
        if m.group(1) == variable:
            return m.group(2)
    return None


def called_function_name(src):
    m = ARRAY_CALL_RE.search(src)
    if m:
        vf = variable_function(src, m.group(1))
        return vf or m.group(2)
    return None


def payloads(src, prefer):
    preferred, others = [], []
    for m in ARRAY_CALL_RE.finditer(src):
        variable, raw = m.group(1), m.group(3)
        vals = [g1 if g1 else g2 for g1, g2 in QUOTED_RE.findall(raw)]
        if not vals:
            continue
        (preferred if prefer is not None and variable == prefer else others).append(vals)
    return preferred + others


def function_body(src, name):
    start = src.find("function " + name + "(")
    if start < 0:
        return None
    open_ = src.find("{", start)
    if open_ < 0:
        return None
    depth = 0
    for i in range(open_, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[open_:i + 1]
    return src[open_:]


def detect_ops(body):
    if not body:
        return []
    found = []
    for m in OP_REVERSE_RE.finditer(body):
        found.append((m.start(), ("reverse",)))
    for m in OP_ATOB_RE.finditer(body):
        if not body[max(0, m.start() - 9):m.start()].endswith("function "):
            found.append((m.start(), ("atob",)))
    for m in OP_BTOA_RE.finditer(body):
        if not body[max(0, m.start() - 9):m.start()].endswith("function "):
            found.append((m.start(), ("btoa",)))
    for m in OP_REPLACE_RE.finditer(body):
        block = body[m.start():m.start() + 400]
        shift = None
        for pat in ROT_PATTERNS:
            hit = pat.search(block)
            if hit:
                shift = int(hit.group(1))
                break
        if shift is not None:
            found.append((m.start(), ("rot", shift)))
    return [o[1] for o in sorted(found, key=lambda x: x[0])]


def parse_shift(text):
    s = SHIFT_SIGN_RE.search(text)
    sign = s.group(1) if s else "-"
    m = SHIFT_RE.search(text)
    if m:
        off = int(m.group(3))
        if m.group(2) == "-":
            off = -off
        return int(m.group(1)), off, sign
    return DEFAULT_MAGIC, DEFAULT_OFFSET, sign


def b64d(value):
    clean = value.strip().replace("-", "+").replace("_", "/")
    out = bytearray()
    buf = bits = 0
    for ch in clean:
        if ch == "=":
            break
        idx = BASE64_ALPHABET.find(ch)
        if idx < 0:
            continue
        buf = (buf << 6) | idx
        bits += 6
        if bits >= 8:
            bits -= 8
            out.append((buf >> bits) & 0xFF)
    return out.decode("latin-1")


def apply_ops(text, ops):
    r = text
    for op in ops:
        if op[0] == "reverse":
            r = r[::-1]
        elif op[0] == "atob":
            r = b64d(r)
        elif op[0] == "btoa":
            import base64
            r = base64.b64encode(r.encode("latin-1")).decode()
        elif op[0] == "rot":
            shift = op[1]
            r = "".join(
                chr((ord(c) - 97 + shift) % 26 + 97) if "a" <= c <= "z"
                else chr((ord(c) - 65 + shift) % 26 + 65) if "A" <= c <= "Z" else c
                for c in r)
    return r


def byte_shift(text, magic, offset, sign):
    out = []
    for i, ch in enumerate(text):
        d = i + offset
        if d == 0:
            return ""
        sh = int(magic % d) if d > 0 else -int(magic % abs(d))
        code = ord(ch) + sh if sign == "+" else ord(ch) - sh
        out.append(chr(((code % 256) + 256) % 256))
    return "".join(out)


def valid_url(v):
    if not v or len(v) < 12:
        return False
    for c in v:
        if ord(c) <= 8 or 14 <= ord(c) <= 31 or ord(c) == 127:
            return False
    low = v.strip().lower()
    if not (low.startswith("http://") or low.startswith("https://")):
        return False
    host = low.split("://", 1)[1].split("/")[0].split("?")[0]
    return bool(host) and "." in host and " " not in host


def shift_signs(p):
    return ["+", "-"] if p == "+" else ["-", "+"]


def decode_payload(parts, body, whole):
    joined = "".join(parts)
    if len(joined) < 16 or any(len(x) < 3 for x in parts):
        return None
    magic, offset, sign = parse_shift(body or whole)
    ops = detect_ops(body)
    if ops:
        text = apply_ops(joined, ops)
        for s in shift_signs(sign):
            out = byte_shift(text, magic, offset, s)
            if valid_url(out):
                return out
        for s in shift_signs(sign):
            out = byte_shift(text, DEFAULT_MAGIC, DEFAULT_OFFSET, s)
            if valid_url(out):
                return out
    orders = [
        [("reverse",), ("atob",), ("rot", 13)],
        [("reverse",), ("rot", 13), ("atob",)],
        [("atob",), ("reverse",), ("rot", 13)],
        [("atob",), ("reverse",), ("rot", 15), ("atob",)],
        [("atob",), ("reverse",), ("atob",)],
        [("reverse",), ("atob",), ("atob",)],
        [("atob",), ("atob",), ("reverse",)],
        [("reverse",), ("atob",)],
        [("atob",), ("reverse",)],
    ]
    for order in orders:
        for rot in (15, 13, 8, 21, 5, 3, 12, 7, 17, 25, 1, 22):
            ops2 = [("rot", rot) if o[0] == "rot" else o for o in order]
            text = apply_ops(joined, ops2)
            for s in shift_signs(sign):
                out = byte_shift(text, magic, offset, s)
                if valid_url(out):
                    return out
                out = byte_shift(text, DEFAULT_MAGIC, DEFAULT_OFFSET, s)
                if valid_url(out):
                    return out
    return None



DC_HELLO_RE = re.compile(r"dc_hello\(\s*['\"]([^'\"]+)['\"]", re.I)
DC_PARTS_RE = re.compile(r"dc_[A-Za-z0-9_$]*\(\s*\[(.*?)\]\s*\)", re.S)
ACC_START_RE = re.compile(r"acc\s*=\s*(\d{1,6})(?!\s*[+-])")
ACC_STEP_RE = re.compile(r"acc\s*\+\s*(\d{1,6})")


def clean_decoded(raw):
    if not raw:
        return None
    idx = raw.find("http")
    cand = raw[idx:] if idx >= 0 else raw
    for i, ch in enumerate(cand):
        if ch in " \"'<>" or ord(ch) < 32:
            cand = cand[:i]
            break
    url = cand.strip()
    return url if valid_url(url) else None


def dc_hello(enc):
    twice = b64d(b64d(enc)[::-1])
    for sep in ("+", " ", "|"):
        pass
    link = twice
    for sep in ("+", " ", "|"):
        if sep in twice:
            link = twice.rsplit(sep, 1)[1]
            break
    return clean_decoded(link)


def dc_acc(joined, script):
    rot = "".join(
        chr((ord(c) - 97 + 20) % 26 + 97) if "a" <= c <= "z"
        else chr((ord(c) - 65 + 20) % 26 + 65) if "A" <= c <= "Z" else c
        for c in joined)
    cipher = b64d(b64d(b64d(rot)))
    if not cipher:
        return None
    m = ACC_START_RE.search(script)
    acc = int(m.group(1)) if m else 141
    m = ACC_STEP_RE.search(script)
    step = int(m.group(1)) if m else 6
    out = []
    for ch in cipher:
        value = ord(ch) & 0xFF
        acc = (acc + step) & 0xFF
        out.append(chr(value ^ acc))
        acc = (acc + value) & 0xFF
    return clean_decoded("".join(out))


def dc_new(joined, script):
    magic, off, sign = parse_shift(script)
    orders = [
        [("reverse",), ("atob",), ("rot", 13)],
        [("atob",), ("reverse",), ("rot", 13)],
        [("reverse",), ("atob",), ("rot", 15)],
        [("reverse",), ("rot", 13), ("atob",)],
        [("reverse",), ("rot", 15), ("atob",)],
        [("atob",), ("reverse",), ("rot", 15)],
    ]
    for ops in orders:
        text = apply_ops(joined, ops)
        if not text.strip():
            continue
        for s in shift_signs(sign):
            u = clean_decoded(byte_shift(text, magic, off, s))
            if u:
                return u
            if magic != DEFAULT_MAGIC or off != DEFAULT_OFFSET:
                u = clean_decoded(byte_shift(text, DEFAULT_MAGIC, DEFAULT_OFFSET, s))
                if u:
                    return u
    return None


def decode_known(html):
    if not html.strip():
        return None
    normalized = unescape_html(html)
    script = normalized
    for m in PACKER_RE.finditer(normalized):
        script += "\n" + packer_unpack(js_unescape(m.group(2)), int(m.group(3)),
                                       int(m.group(4)), m.group(5).split("|"))
    m = DC_HELLO_RE.search(script)
    if m:
        u = dc_hello(m.group(1))
        if u:
            return u
    for m in DC_PARTS_RE.finditer(script):
        parts = [a if a else b for a, b in QUOTED_RE.findall(m.group(1))]
        if not parts:
            continue
        joined = "".join(parts)
        u = dc_acc(joined, script) or dc_new(joined, script)
        if u:
            return u
    return None


def extract_packed(html):
    if not html.strip():
        return None
    normalized = unescape_html(html)
    u = decode_known(normalized)
    if u:
        return u
    sources = []
    for m in PACKER_RE.finditer(normalized):
        sources.append(packer_unpack(js_unescape(m.group(2)), int(m.group(3)),
                                     int(m.group(4)), m.group(5).split("|")))
    sources.append(normalized)
    for src in sources:
        prefer = sources_file_variable(src)
        fn = variable_function(src, prefer) if prefer else None
        fn = fn or called_function_name(src)
        body = function_body(src, fn) if fn else None
        for parts in payloads(src, prefer):
            u = decode_payload(parts, body, src)
            if u:
                return u
    m = FILE_LINK_RE.search(normalized)
    if m:
        vals = [a if a else b for a, b in QUOTED_RE.findall(m.group(1))]
        if vals:
            u = decode_payload(vals, None, normalized)
            if u:
                return u
    return None


def extract(html):
    u = extract_packed(html)
    if u:
        return u
    m = PLAIN_STREAM_RE.search(unescape_html(html))
    return m.group(1) if m else None


EXPECTED = ("https://s231.rapidrame.com/hls2/01/00035/nmlen28ewqyo_,l,n,.urlset/master.m3u8"
            "?t=CgmeX3U5UWKNAoShv2EAf7n9L2MeIzSLNOyxm1joICo&s=1783956042&e=86400&f=179989"
            "&srv=s430&i=0.0&sp=0&p1=s231&p2=s231")

if __name__ == "__main__":
    for path in sys.argv[1:]:
        data = open(path, encoding="utf-8", errors="replace").read()
        got = extract(data)
        ok = "OK" if got == EXPECTED else ("URL" if got else "NONE")
        print(f"[{ok}] {path}\n      -> {got}")
