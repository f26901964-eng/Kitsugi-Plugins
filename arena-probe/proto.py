#!/usr/bin/env python3
"""Kotlin cozucusunun Python prototipi.

Amac: gercek fixture'lar uzerinde algoritmayi hizli test etmek, sonra
birebir Kotlin'e tasimak. Bagimsiz calisir (repo'ya dahil degil).
"""
import base64
import re
import sys

ALPHA = "0123456789abcdefghijklmnopqrstuvwxyz"


def enc(num, a):
    s = ""
    while num >= a:
        s += chr(num % a + 29) if num % a > 35 else ALPHA[num % a]
        num //= a
    s += chr(num + 29) if num > 35 else ALPHA[num]
    return s[::-1]


def unpack_packer(p, a, c, k):
    d = {}
    for i in range(c - 1, -1, -1):
        tok = enc(i, a)
        d[tok] = k[i] if i < len(k) and k[i] else tok
    def repl(m):
        return d.get(m.group(0), m.group(0))
    return re.sub(r"[A-Za-z0-9_]+", repl, p)


PACKER = re.compile(
    r"eval\(function\(p,a,c,k,e,d\)\{(.*?)\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\),0,\{\}\)",
    re.S,
)


def unescape_js(s):
    return s.replace("\\\\", "\x00").replace("\\'", "'").replace("\x00", "\\")


def sources(html):
    """Paketli bloklari acar; acilan + ham metni dondurur."""
    out = []
    for m in PACKER.finditer(html):
        try:
            out.append(unpack_packer(unescape_js(m.group(2)), int(m.group(3)),
                                     int(m.group(4)), m.group(5).split("|")))
        except Exception:
            pass
    out.append(html)
    return out


ARRAY_CALL = re.compile(r"=\s*[A-Za-z0-9_$]+\(\s*\[\s*((?:(['\"])[^'\"]*\2\s*,\s*)*(['\"])[^'\"]*\3)\s*\]\)")


def payloads(src):
    res = []
    for m in ARRAY_CALL.finditer(src):
        raw = m.group(1)
        vals = [v for _, v in re.findall(r"([\"'])(.*?)\1", raw)]
        if vals:
            res.append(vals)
    # dizi cagrisinin degisken adini ve fonksiyon adini da dondur
    return res


def function_body(src, fn_name):
    i = src.find("function " + fn_name + "(")
    if i < 0:
        return None
    b = src.find("{", i)
    if b < 0:
        return None
    depth = 0
    for j in range(b, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[b:j + 1]
    return src[b:]


def called_function(src):
    """`var X = FN([...])` -> FN adi"""
    m = re.search(r"=\s*([A-Za-z0-9_$]+)\(\s*\[\s*['\"]", src)
    return m.group(1) if m else None


OP_REVERSE = re.compile(r"\.reverse\s*\(\s*\)")
OP_ATOB = re.compile(r"(?<!function )\batob\s*\(")
OP_BTOA = re.compile(r"(?<!function )\bbtoa\s*\(")
OP_ROT = re.compile(r"\(?\s*([A-Za-z_$][\w$]*)\s*-\s*(65|97)\s*\+\s*(\d+)\s*\)?\s*%\s*26")
OP_ROT2 = re.compile(r"\(\s*([A-Za-z_$][\w$]*)\s*-\s*base\s*\+\s*(\d+)\s*\)\s*%\s*26")
BASE_DEF = re.compile(r"base\s*=\s*\(?\s*([A-Za-z_$][\w$]*)\s*<=\s*(90|122)\s*\)?\s*\?\s*(65|97)\s*:\s*(97|65)")
SHIFT = re.compile(r"(\d{5,13})\s*%\s*\(\s*i\s*([+-])\s*(\d+)\s*\)")


def detect_ops(body):
    ops = []
    if not body:
        return ops
    for m in OP_REVERSE.finditer(body):
        ops.append((m.start(), ("reverse",)))
    for m in OP_ATOB.finditer(body):
        ops.append((m.start(), ("atob",)))
    for m in OP_BTOA.finditer(body):
        ops.append((m.start(), ("btoa",)))
    for m in OP_ROT.finditer(body):
        ops.append((m.start(), ("rot", int(m.group(3)))))
    for m in OP_ROT2.finditer(body):
        base = 65
        bm = BASE_DEF.search(body)
        if bm:
            base = int(bm.group(3))
        shift = int(m.group(2))
        ops.append((m.start(), ("rot", shift, base)))
    ops.sort(key=lambda x: x[0])
    return [o[1] for o in ops]


def parse_magic(text):
    m = SHIFT.search(text)
    if m:
        return int(m.group(1)), int(m.group(3)), m.group(2)
    return 399756995, 5, "-"


def b64d(s):
    s = s.strip().replace("-", "+").replace("_", "/")
    s += "=" * (-len(s) % 4)
    try:
        return base64.b64decode(s, validate=False).decode("latin-1")
    except Exception:
        return None


def apply_ops(text, ops):
    r = text
    for op in ops:
        if op[0] == "reverse":
            r = r[::-1]
        elif op[0] == "atob":
            d = b64d(r)
            if d is None:
                return None
            r = d
        elif op[0] == "btoa":
            r = base64.b64encode(r.encode("latin-1")).decode()
        elif op[0] == "rot":
            shift = op[1] if len(op) < 3 else op[1]
            out = []
            for ch in r:
                if "a" <= ch <= "z":
                    out.append(chr((ord(ch) - 97 + shift) % 26 + 97))
                elif "A" <= ch <= "Z":
                    out.append(chr((ord(ch) - 65 + shift) % 26 + 65))
                else:
                    out.append(ch)
            r = "".join(out)
    return r


def byte_shift(text, magic, offset, sign="-"):
    out = []
    for i, ch in enumerate(text):
        sh = magic % (i + offset)
        code = ord(ch) - sh if sign == "-" else ord(ch) + sh
        out.append(chr(((code % 256) + 256) % 256))
    return "".join(out)


VALID = re.compile(r"^https?://[^\s\"'<>]{6,}$")


def valid(u):
    return bool(u) and bool(VALID.match(u)) and all(9 < ord(c) for c in u)


def decode(parts, body, whole):
    magic, offset, sign = parse_magic(body or whole)
    ops = detect_ops(body)
    tries = []
    if ops:
        tries.append((ops, magic, offset, sign))
    # yedek: sabit islem sirasi
    standard = [
        [("atob",), ("reverse",), ("rot", 15), ("atob",)],
        [("atob",), ("reverse",), ("atob",)],
        [("reverse",), ("atob",), ("atob",)],
        [("atob",), ("atob",), ("reverse",)],
        [("reverse",), ("atob",)],
        [("atob",), ("reverse",)],
    ]
    joined = "".join(parts)
    for order in standard:
        for rot in (15, 13, 8, 21, 5, 3, 12, 7, 17, 25, 1, 22):
            ops2 = [(o[0], rot) if o[0] == "rot" else o for o in order]
            tries.append((ops2, magic, offset, sign))
            tries.append((ops2, 399756995, 5, "-"))
    for t_ops, mag, off, sg in tries:
        r = apply_ops(joined, t_ops)
        if r is None:
            continue
        for s in (sg, "-" if sg == "+" else "+"):
            u = byte_shift(r, mag, off, s)
            if valid(u):
                return u
    return None


def extract(html):
    html = (html.replace("\\/", "/").replace("\\u002f", "/", )
                .replace("\\u003a", ":").replace("\\u0026", "&"))
    for src in sources(html):
        fn = called_function(src)
        body = function_body(src, fn) if fn else None
        for parts in payloads(src):
            u = decode(parts, body, src)
            if u:
                return u
    # son care: duz adres
    m = re.search(r"https?://[^\s\"'<>]+\.(?:m3u8|mp4)(?:\?[^\s\"'<>]*)?", html)
    return m.group(0) if m else None


if __name__ == "__main__":
    for path in sys.argv[1:]:
        data = open(path, encoding="utf-8", errors="replace").read()
        print(f"{path} -> {extract(data)}")
