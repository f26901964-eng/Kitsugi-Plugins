#!/usr/bin/env python3
"""Yayin dogrulamasi — "manifestte var" demek yetmez, uctan uca kontrol eder.

Ne yapar:
  1. `repo.json` -> `pluginLists` -> `plugins.json` zincirini UYGULAMANIN
     kullandigi adreslerden (raw.githubusercontent, api.github.com, jsDelivr)
     okur.
  2. `enabled_plugins.txt` icindeki HER eklenti icin:
       * kayit var mi, status 1 mi,
       * URL bu deponun `builds` dalini mi gosteriyor,
       * .cs3 indirilebiliyor mu, boyut + sha256 manifestle ayni mi,
       * ZIP icinde manifest.json + classes.dex var mi, ic manifest'in
         name/version degeri plugins.json ile ayni mi.
  3. Aynalar (jsDelivr) geri kalmisSA once purge eder, sonra tekrar bakar;
     hala eksikse HATA verir.

Kullanim:
  python3 tools/verify_publish.py            # tam dogrulama
  python3 tools/verify_publish.py --no-http  # yalnizca yerel dosyalar (offline)
"""

import hashlib
import io
import json
from collections import Counter
import os
import sys
import urllib.error
import urllib.request
import zipfile

SLUG = os.environ.get("GITHUB_REPOSITORY") or "f26901964-eng/Kitsugi-Plugins"
BRANCH = "builds"
RAW = f"https://raw.githubusercontent.com/{SLUG}/{BRANCH}"
API = f"https://api.github.com/repos/{SLUG}/contents"
JSDELIVR = f"https://cdn.jsdelivr.net/gh/{SLUG}@{BRANCH}"
UA = "Kitsugi-Publish-Verifier/1.0"
ENABLED_FILE = "enabled_plugins.txt"


def get(url, timeout=45):
    headers = {"User-Agent": UA}
    if "api.github.com/repos/" in url:
        headers["Accept"] = "application/vnd.github.raw"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:300]
    except Exception as e:  # noqa: BLE001
        return None, str(e).encode()


def enabled_plugins():
    if not os.path.exists(ENABLED_FILE):
        return []
    names = []
    with open(ENABLED_FILE, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                names.append(line)
    return names


def load_plugins(url):
    status, body = get(url)
    if status != 200:
        return status, None
    try:
        return status, json.loads(body)
    except Exception:  # noqa: BLE001
        return status, None


def main():
    offline = "--no-http" in sys.argv
    errors = []
    warnings = []
    rows = []

    wanted = enabled_plugins()
    if not wanted:
        print("enabled_plugins.txt boş — doğrulanacak eklenti yok.")
        return 0

    # ── 1) repo.json ────────────────────────────────────────────────────────
    if not offline:
        status, body = get(f"{RAW}/repo.json")
        if status != 200:
            errors.append(f"repo.json okunamadı (HTTP {status})")
        else:
            try:
                repo = json.loads(body)
                lists = repo.get("pluginLists") or []
                if not any(l.endswith("/plugins.json") for l in lists):
                    errors.append(f"repo.json pluginLists plugins.json içermiyor: {lists}")
                print(f"repo.json → {repo.get('name')} | {len(lists)} liste")
            except Exception as e:  # noqa: BLE001
                errors.append(f"repo.json JSON hatası: {e}")

    # ── 2) Yayındaki plugins.json ───────────────────────────────────────────
    published = None
    if offline:
        local = os.path.join("build", "plugins.json")
        if os.path.exists(local):
            published = json.load(open(local, encoding="utf-8"))
    else:
        status, published = load_plugins(f"{RAW}/plugins.json")
        if published is None:
            errors.append(f"plugins.json okunamadı (HTTP {status})")

    by_name = {}
    published_names = []
    for plugin in published or []:
        name = (plugin.get("internalName") or "").strip()
        if not name:
            errors.append("plugins.json: internalName boş olan kayıt var")
            continue
        published_names.append(name)
        by_name[name.casefold()] = plugin

    expected_counts = Counter(wanted)
    published_counts = Counter(published_names)
    folded_counts = Counter(name.casefold() for name in published_names)
    duplicates = [key for key, count in folded_counts.items() if count > 1]
    if duplicates:
        for key in duplicates:
            variants = [name for name in published_names if name.casefold() == key]
            errors.append(
                "plugins.json: büyük/küçük harf farkıyla yinelenen internalName: "
                + ", ".join(variants)
            )

    missing_records = list((expected_counts - published_counts).elements())
    unexpected_records = list((published_counts - expected_counts).elements())
    if missing_records or unexpected_records:
        errors.append(
            "plugins.json etkin listeyle aynı değil "
            f"(eksik: {missing_records or 'yok'}; fazla/yanlış yazım: "
            f"{unexpected_records or 'yok'})"
        )

    print(f"\nYayındaki kayıt sayısı: {len(published_names)} | beklenen: {len(wanted)}\n")
    print(f"{'Eklenti':22s} {'Kayıt':6s} {'Durum':6s} {'Dosya':6s} {'Hash':6s} {'ZIP':4s} {'Manifest':8s}")
    print("-" * 70)

    for name in wanted:
        plugin = by_name.get(name.casefold())
        if plugin is None:
            rows.append((name, "YOK", "-", "-", "-", "-", "-"))
            errors.append(f"{name}: yayındaki manifestte YOK")
            continue
        row = {"name": name, "record": "OK"}
        status_val = plugin.get("status")
        if status_val != 1:
            errors.append(f"{name}: status={status_val} (1 olmalı; 3=Beta only uygulamada gizlenir)")
            row["status"] = f"HATA({status_val})"
        else:
            row["status"] = "OK"

        url = plugin.get("url") or ""
        if offline:
            row["file"] = row["hash"] = row["zip"] = row["manifest"] = "?"
            rows.append(tuple(row.get(k, "-") for k in ("name", "record", "status", "file", "hash", "zip", "manifest")))
            continue
        if f"/{BRANCH}/" not in url:
            errors.append(f"{name}: URL bu deponun {BRANCH} dalını göstermiyor: {url}")
            row["file"] = "HATA"
        status, body = get(url)
        if status != 200:
            errors.append(f"{name}: .cs3 indirilemedi (HTTP {status}) → {url}")
            rows.append((name, "OK", row["status"], f"HTTP{status}", "-", "-", "-"))
            continue
        size_ok = len(body) == plugin.get("fileSize")
        hash_ok = "sha256-" + hashlib.sha256(body).hexdigest() == plugin.get("fileHash")
        row["file"] = "OK" if size_ok else f"HATA({len(body)}≠{plugin.get('fileSize')})"
        row["hash"] = "OK" if hash_ok else "HATALI"
        if not size_ok:
            errors.append(f"{name}: boyut uyuşmuyor ({len(body)} ≠ {plugin.get('fileSize')})")
        if not hash_ok:
            errors.append(f"{name}: sha256 uyuşmuyor")

        try:
            with zipfile.ZipFile(io.BytesIO(body)) as zf:
                names = zf.namelist()
                meta = json.load(zf.open("manifest.json"))
            ok_zip = "manifest.json" in names and "classes.dex" in names
            ok_meta = (meta.get("name") == plugin.get("internalName")
                       and meta.get("version") == plugin.get("version"))
            row["zip"] = "OK" if ok_zip else "EKSİK"
            row["manifest"] = "OK" if ok_meta else f"HATA({meta.get('name')} v{meta.get('version')})"
            if not ok_zip:
                errors.append(f"{name}: .cs3 içinde manifest.json/classes.dex eksik: {names}")
            if not ok_meta:
                errors.append(
                    f"{name}: iç manifest uyuşmuyor (name={meta.get('name')} v{meta.get('version')})"
                )
        except Exception as e:  # noqa: BLE001
            row["zip"] = "BOZUK"
            row["manifest"] = "-"
            errors.append(f"{name}: .cs3 ZIP olarak açılamadı: {e}")

        print(f"{name:22s} {row['record']:6s} {row['status']:6s} {row['file']:6s} "
              f"{row['hash']:6s} {row['zip']:4s} {row['manifest']:8s}")

    # ── 3) Aynalar (api.github.com + jsDelivr) ──────────────────────────────
    if not offline:
        print("\nAyna kontrolü:")
        mirrors = [("api.github.com", f"{API}/plugins.json?ref={BRANCH}"),
                   ("jsDelivr", f"{JSDELIVR}/plugins.json")]
        for label, url in mirrors:
            status, plugins = load_plugins(url)
            if plugins is None:
                warnings.append(f"{label}: okunamadı (HTTP {status})")
                print(f"  {label:16s} HTTP {status} — atlandı")
                continue

            have = Counter(
                (p.get("internalName") or "").strip()
                for p in plugins
                if (p.get("internalName") or "").strip()
            )
            missing = list((expected_counts - have).elements())
            extra = list((have - expected_counts).elements())
            print(f"  {label:16s} {len(plugins)} kayıt — "
                  f"{'eksik: ' + ', '.join(missing) + '; fazla: ' + ', '.join(extra) if missing or extra else 'tamam'}")

            if (missing or extra) and label == "jsDelivr":
                # Bayat CDN önbelleği: purge edip tekrar kontrol et.
                print("    → jsDelivr önbelleği temizlenip tekrar denenecek")
                try:
                    sys.path.insert(0, os.path.join(os.path.dirname(__file__)))
                    import purge_cdn  # type: ignore
                    purge_cdn.purge(purge_cdn.published_paths())
                except Exception as e:  # noqa: BLE001
                    print(f"    ! purge başarısız: {e}")
                status, plugins = load_plugins(url)
                have = Counter(
                    (p.get("internalName") or "").strip()
                    for p in plugins or []
                    if (p.get("internalName") or "").strip()
                )
                missing = list((expected_counts - have).elements())
                extra = list((have - expected_counts).elements())
                print(f"    purge sonrası: {len(plugins or [])} kayıt — "
                      f"{'eksik: ' + ', '.join(missing) + '; fazla: ' + ', '.join(extra) if missing or extra else 'tamam'}")

            if missing or extra:
                errors.append(
                    f"{label} aynası etkin listeyle aynı değil "
                    f"(eksik: {missing or 'yok'}; fazla/yanlış yazım: {extra or 'yok'})"
                )

    # ── Sonuç ───────────────────────────────────────────────────────────────
    print()
    if warnings:
        print("Uyarılar:")
        for w in warnings:
            print(f"  ! {w}")
    if errors:
        print("\nHATALAR:")
        for e in errors:
            print(f"  ✗ {e}")
        print(f"\nSONUÇ: BAŞARISIZ ({len(errors)} hata)")
        return 1
    print(f"SONUÇ: BAŞARILI — {len(wanted)} etkin eklentinin tamamı yayında, "
          "indirilebilir ve hash'leri tutarlı.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
