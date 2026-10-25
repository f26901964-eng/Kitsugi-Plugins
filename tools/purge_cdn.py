#!/usr/bin/env python3
"""jsDelivr (ve benzeri) onbellegini yayin sonrasi temizler.

NEDEN GEREKLI:
  Kitsugi uygulamasi resmi depoyu okurken sirasiyla
    1) raw.githubusercontent.com
    2) api.github.com muadili
    3) cdn.jsdelivr.net  (kullanici "GitHub Vekil Sunucu" ayarini actiysa 1. sirada)
    4) codeberg aynasi
  adreslerini dener ve ILK basarili yaniti kullanir.

  `Derleyici` is akisi `builds` dalini `git commit --amend` + force-push ile
  gunceller. jsDelivr dal (branch) icerigini ~12 saat onbellekledigi icin, yeni
  eklenti eklendiginde (or. OpenAnime / TrAnimeIzle) jsDelivr ESKI plugins.json'i
  dondurmeye devam ediyordu. Sonuc: manifestte olan eklentiler uygulamada
  "depoda yok" gibi gorunuyordu.

  Bu betik yayindan sonra jsDelivr onbellegini acikca temizler; boylece vekil
  sunucu ayari acik olan kullanicilar da guncel listeyi gorur.

Kullanim:
  python3 tools/purge_cdn.py            # builds/plugins.json + repo.json + .cs3
  python3 tools/purge_cdn.py --check    # temizlemez, aynalari karsilastirir
"""

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

SLUG = os.environ.get("GITHUB_REPOSITORY") or "f26901964-eng/Kitsugi-Plugins"
BRANCH = "builds"
RAW = f"https://raw.githubusercontent.com/{SLUG}/{BRANCH}"
JSDELIVR = f"https://cdn.jsdelivr.net/gh/{SLUG}@{BRANCH}"
PURGE = "https://purge.jsdelivr.net/gh/{slug}@{branch}/{path}"
UA = "Kitsugi-Publish-Verifier/1.0"


def _get(url, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:300]
    except Exception as e:  # noqa: BLE001
        return None, str(e).encode()


def purge(paths):
    print(f"jsDelivr önbelleği temizleniyor ({len(paths)} dosya)...")
    failed = 0
    for path in paths:
        url = PURGE.format(slug=SLUG, branch=BRANCH, path=urllib.parse.quote(path))
        status, body = _get(url, timeout=25)
        ok = status == 200
        if not ok:
            failed += 1
        print(f"  {'✓' if ok else '✗'} {path} (HTTP {status})")
    return failed


def published_paths():
    """builds/plugins.json içindeki tüm dosya yollarını (göreli) toplar."""
    status, body = _get(f"{RAW}/plugins.json")
    paths = ["repo.json", "plugins.json"]
    if status != 200:
        print(f"  ! plugins.json okunamadı (HTTP {status}) — yalnız manifestler temizlenecek")
        return paths
    try:
        plugins = json.loads(body)
    except Exception as e:  # noqa: BLE001
        print(f"  ! plugins.json JSON hatası: {e}")
        return paths
    for plugin in plugins:
        url = plugin.get("url") or ""
        if f"/{BRANCH}/" not in url:
            continue
        rel = url.split(f"/{BRANCH}/", 1)[1]
        if rel and rel not in paths:
            paths.append(rel)
    return paths


def check():
    """Ham GitHub ile jsDelivr kopyasını karşılaştırır; fark varsa listeler."""
    print("Ayna karşılaştırması:")
    _, raw_body = _get(f"{RAW}/plugins.json")
    _, jd_body = _get(f"{JSDELIVR}/plugins.json")
    try:
        raw_names = {p.get("internalName") for p in json.loads(raw_body)}
    except Exception:  # noqa: BLE001
        raw_names = set()
    try:
        jd_names = {p.get("internalName") for p in json.loads(jd_body)}
    except Exception:  # noqa: BLE001
        jd_names = set()

    print(f"  raw : {len(raw_names)} kayıt")
    print(f"  jsDelivr: {len(jd_names)} kayıt")
    missing = sorted(raw_names - jd_names)
    if missing:
        print(f"  ✗ jsDelivr'da EKSİK: {', '.join(missing)}")
        return 1
    if raw_names != jd_names:
        print(f"  ! jsDelivr'da FAZLA: {', '.join(sorted(jd_names - raw_names))}")
    print("  ✓ aynalar aynı")
    return 0


def main():
    if "--check" in sys.argv:
        return check()
    paths = published_paths()
    failed = purge(paths)
    if failed:
        print(f"  ! {failed} yol temizlenemedi (jsDelivr purge API'si kritik değil).")
    return check()


if __name__ == "__main__":
    sys.exit(main())
