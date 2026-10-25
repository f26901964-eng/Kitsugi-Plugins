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


def purge_response_succeeded(status, body, path):
    """HTTP 200 tek basina basari degildir; jsDelivr throttle bilgisini de denetle."""
    if status != 200:
        return False, f"HTTP {status}"
    try:
        payload = json.loads(body)
    except Exception as exc:  # noqa: BLE001
        return False, f"gecersiz JSON: {exc}"

    if payload.get("status") != "finished":
        return False, f"purge durumu: {payload.get('status')}"

    key = f"/gh/{SLUG}@{BRANCH}/{path}"
    result = (payload.get("paths") or {}).get(key)
    if not isinstance(result, dict):
        return False, "yanitta hedef yol yok"
    if result.get("throttled"):
        reset = result.get("throttlingReset")
        suffix = f"; {reset}s sonra tekrar deneyin" if reset is not None else ""
        return False, f"istek kisitlandi{suffix}"

    providers = result.get("providers")
    if not isinstance(providers, dict) or not providers:
        return False, "CDN saglayici sonucu yok"
    failed_providers = sorted(name for name, ok in providers.items() if ok is not True)
    if failed_providers:
        return False, "temizlenemeyen saglayicilar: " + ", ".join(failed_providers)
    return True, "tum CDN saglayicilari temizlendi"


def purge(paths):
    print(f"jsDelivr önbelleği temizleniyor ({len(paths)} dosya)...")
    failed = 0
    for path in paths:
        url = PURGE.format(slug=SLUG, branch=BRANCH, path=urllib.parse.quote(path))
        status, body = _get(url, timeout=25)
        ok, detail = purge_response_succeeded(status, body, path)
        if not ok:
            failed += 1
        print(f"  {'✓' if ok else '✗'} {path} ({detail})")
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
    raw_status, raw_body = _get(f"{RAW}/plugins.json")
    jd_status, jd_body = _get(f"{JSDELIVR}/plugins.json")
    if raw_status != 200:
        print(f"  ✗ raw manifest okunamadı (HTTP {raw_status})")
        return 1
    if jd_status != 200:
        print(f"  ✗ jsDelivr manifest okunamadı (HTTP {jd_status})")
        return 1
    try:
        raw_names = {p.get("internalName") for p in json.loads(raw_body)}
        jd_names = {p.get("internalName") for p in json.loads(jd_body)}
    except Exception as exc:  # noqa: BLE001
        print(f"  ✗ manifest JSON okunamadı: {exc}")
        return 1

    if not raw_names or None in raw_names or not jd_names or None in jd_names:
        print("  ✗ en az bir manifest boş veya internalName alanı eksik")
        return 1

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
    mirror_status = check()
    if failed:
        print(f"  ✗ {failed} yolun purge isteği başarısız veya throttled.")
    # Ayna güncel olsa bile başarısız purge'leri sıfır koduyla raporlamayalım:
    # .cs3 dosyaları da aynı mutable CDN yollarını kullanıyor.
    return 1 if failed or mirror_status else 0


if __name__ == "__main__":
    sys.exit(main())
