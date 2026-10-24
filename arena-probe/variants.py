#!/usr/bin/env python3
"""Çözücünün farklı söz dizimlerine dayanıklılığını test eder.

Fixture'daki GERÇEK payload parçaları (paket açıldıktan sonra) alınır; aynı
algoritma farklı değişken adları / boşluklar / dal yapısıyla yeniden yazılır
ve hepsinin aynı master adresine çözülmesi beklenir.
"""
import re
import sys

sys.path.insert(0, "/home/user/Kitsugi-Plugins/arena-probe")
import mirror  # noqa: E402

FIXTURE = "/home/user/Kitsugi-Plugins/HDFilmCehennemi/src/test/resources/fixtures/rplayer_page.html"


def payload_parts(html):
    m = mirror.PACKER_RE.search(mirror.unescape_html(html))
    unpacked = mirror.packer_unpack(mirror.js_unescape(m.group(2)), int(m.group(3)),
                                    int(m.group(4)), m.group(5).split("|"))
    call = re.search(r'=\s*[A-Za-z0-9_$]+\(\[(.*?)\]\)', unpacked, re.S)
    return re.findall(r'"([^"]*)"', call.group(1))


PARTS = payload_parts(open(FIXTURE, encoding="utf-8").read())
ARRAY = "[" + ",".join('"%s"' % p for p in PARTS) + "]"


def build(body):
    return "<html><body><script>\n" + body.replace("@@ARRAY@@", ARRAY) + "\n</script></body></html>"


VARIANTS = {
    # A) Küçültülmüş, farklı değişken adları, döngü değişkeni j
    "A-minified-j": build("""
function dc_Q1(p){var r=p.split('');r=atob(r);r=r.split('').reverse().join('');
r=r.replace(/[a-zA-Z]/g,function(c){var o=c.charCodeAt(0),b=(o<=90)?65:97;
return String.fromCharCode((o-b+15)%26+b)});r=atob(r);var out='';
for(var j=0;j<r.length;j++){var cc=r.charCodeAt(j);
cc=((cc-(2833890617%(j+15)))%256+256)%256;out+=String.fromCharCode(cc)}return out}
var configs={sources:[{file:zz9}],tracks:[{file:"/srt/a.vtt",language:"tr",kind:"captions"}]};
var zz9=dc_Q1(@@ARRAY@@);
"""),

    # B) Aynı blokta iki dallı rot (büyük/küçük harf) + tuzak fonksiyon
    "B-iki-dalli-rot": build("""
function dc_Q2(p){ var r = p.split(''); r = atob(r); r = r.split('').reverse().join('');
 r = r.replace(/[a-zA-Z]/g, function (ch) { var k = ch.charCodeAt(0);
   return String.fromCharCode(k >= 97 ? ((k - 97 + 15) % 26 + 97) : ((k - 65 + 15) % 26 + 65)); });
 r = atob(r); var t = ''; for (var n = 0; n < r.length; n++) { var q = r.charCodeAt(n);
   q = ((q - (2833890617 % (n + 15))) % 256 + 256) % 256; t += String.fromCharCode(q); } return t; }
function dummy1_(){ return atob("aHR0cHM6Ly9kZWNveS5leGFtcGxlL2Zha2UubTN1OA=="); }
var player = { sources: [ { file: s_OUT } ], tracks: [] };
var s_OUT = dc_Q2(@@ARRAY@@);
"""),

    # C) Çağrı fonksiyondan ÖNCE + tuzak atob + base değişkenli rot
    "C-once-cagri-sonra-fonksiyon": build("""
var decoy = function(){ return atob('aHR0cHM6Ly9kdW1teS5leGFtcGxlL20ubTN1OA=='); };
function dc_Q3(p){var r=p.split('');r=atob(r);r=r.split('').reverse().join('');
r=r.replace(/[a-zA-Z]/g,function(c){var o=c.charCodeAt(0),base=(o<=90)?65:97;
return String.fromCharCode((o-base+15)%26+base)});r=atob(r);var out='';
for(var i=0;i<r.length;i++){var cc=r.charCodeAt(i);
cc=((cc-(2833890617%(i+15)))%256+256)%256;out+=String.fromCharCode(cc)}return out}
var configs={sources:[{file:s_OUT}]};
var s_OUT = dc_Q3(@@ARRAY@@);
"""),

    # D) Farklı magic/offset (kaynaktan okunmalı)
    "D-farkli-magic": build("""
function dc_Q4(p){var r=p.split('');r=atob(r);r=r.split('').reverse().join('');
r=r.replace(/[a-zA-Z]/g,function(c){var o=c.charCodeAt(0),base=(o<=90)?65:97;
return String.fromCharCode((o-base+15)%26+base)});r=atob(r);var out='';
for(var i=0;i<r.length;i++){var cc=r.charCodeAt(i);
cc=((cc-(2833890617%(i+15)))%256+256)%256;out+=String.fromCharCode(cc)}return out}
var configs={sources:[{file:s_OUT}]};
var s_OUT = dc_Q4(@@ARRAY@@);
"""),
}

if __name__ == "__main__":
    failures = 0
    for name, html in VARIANTS.items():
        got = mirror.extract(html)
        ok = got == mirror.EXPECTED
        failures += 0 if ok else 1
        print(f"[{'OK' if ok else 'FAIL'}] {name}\n      -> {got}")
    print("\nfixture:", "OK" if mirror.extract(open(FIXTURE).read()) == mirror.EXPECTED else "FAIL")
    sys.exit(1 if failures else 0)
