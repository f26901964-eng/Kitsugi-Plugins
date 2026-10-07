import json
import os
import sys

# ─────────────────────────────────────────────────────────────────────────────
# Eklenti manifestini birlestirir ve URL'leri GERCEK DOSYALARA gore dogrular.
#
# 2026-10 duzeltmesi — neden gerekliydi:
#   Bu betik daha once dosya varligini hic kontrol etmiyordu. Bir kayit manifeste bir
#   kez girdiginde .cs3 dosyasi silinse/derlenmese bile kayit kaliyordu. Sonuc:
#   portalda gorunen ama indirilemeyen eklentiler ("eklenti bos donuyor / kurulamiyor").
#   Ayrica .cs3 dosyalari `builds/` kokunde ya da `builds/prebuilt/` altinda olabiliyor;
#   URL ile gercek konum uyusmadiginda indirme 404 aliyordu (or. `WFilmİzle.cs3`).
#
# Yeni davranis:
#   1. Birlestirme sirasi: mevcut builds -> prebuilt_plugins.json -> yeni derlenenler.
#   2. Her kaydin URL'indeki dosya adi icin GERCEK konum aranir (`builds/` ve `builds/prebuilt/`).
#   3. Dosya bulunursa ve URL baska bir konumu gosteriyorsa URL OTOMATIK duzeltilir.
#   4. Dosya hicbir yerde yoksa kayit KORUNUR ama yuksek sesle rapor edilir
#      (depo sahibinin derleme tetiklemesi icin).
# ─────────────────────────────────────────────────────────────────────────────

BRANCH = "builds"
PLACEHOLDER_SLUG = "f26901964-eng/Kitsugi-Plugins"


def repo_slug():
    """GITHUB_REPOSITORY (owner/repo) — yoksa varsayilan depo."""
    return os.environ.get("GITHUB_REPOSITORY") or PLACEHOLDER_SLUG


def raw_base(slug):
    return f"https://raw.githubusercontent.com/{slug}/{BRANCH}"


def builds_dir():
    """builds dalinin checkout edildigi dizini bul."""
    ws = os.environ.get("GITHUB_WORKSPACE", ".")
    for cand in (os.path.join(ws, "builds"), os.path.join(".", "builds"), "."):
        if os.path.isdir(os.path.join(cand, "prebuilt")) or os.path.isfile(os.path.join(cand, "plugins.json")):
            return cand
    return os.path.join(ws, "builds")


def index_cs3_files(root):
    """builds/ altindaki tum .cs3 dosyalarini {dosya_adi: yol} olarak indeksler."""
    index = {}
    if not os.path.isdir(root):
        return index
    for dirpath, _dirnames, filenames in os.walk(root):
        if ".git" in dirpath.split(os.sep):
            continue
        for fn in filenames:
            if fn.endswith(".cs3"):
                index.setdefault(os.path.basename(fn), dirpath)
    return index


def filename_of_url(url):
    if not url:
        return ""
    clean = url.split("?")[0].split("#")[0]
    return clean.rsplit("/", 1)[-1]


def load_json(path, default=None):
    if not os.path.exists(path):
        return default
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as exc:  # bozuk JSON islemi durdurmaz
        print(f"  ! {path} okunamadi: {exc}")
        return default


def main():
    workspace = os.environ.get("GITHUB_WORKSPACE", ".")
    slug = repo_slug()
    base = raw_base(slug)
    builds_root = builds_dir()

    builds_json_path = os.path.join(builds_root, "plugins.json")
    prebuilt_json_path = "prebuilt_plugins.json"          # kaynak: src/ (main) icinde
    compiled_json_path = "build/plugins.json"             # kaynak: makePluginsJson ciktisi

    existing = load_json(builds_json_path, []) or []
    prebuilt = load_json(prebuilt_json_path, []) or []
    compiled = load_json(compiled_json_path, []) or []
    print(f"  mevcut builds: {len(existing)} | prebuilt: {len(prebuilt)} | yeni derlenen: {len(compiled)}")

    # ── Birlestirme (onceki davranis korunuyor: son yazan kazanir) ────────────
    combined = {}
    for group in (existing, prebuilt, compiled):
        for plugin in group:
            name = plugin.get("internalName") or plugin.get("name")
            if not name:
                continue
            if plugin.get("url"):
                # Derlenen kayitlarin URL'i yeni konumu gosterebilir; onlari oldugu gibi al.
                combined[name] = plugin
            elif name not in combined:
                combined[name] = plugin

    # ── Dogrulama: URL'leri gercek dosya konumuna gore duzelt ────────────────
    file_index = index_cs3_files(builds_root)
    print(f"  builds dizininde bulunan .cs3 dosyasi: {len(file_index)}")

    repaired = []
    still_missing = []
    for name, plugin in combined.items():
        url = plugin.get("url") or ""
        fname = filename_of_url(url)
        if not fname:
            still_missing.append((name, url, "URL yok"))
            continue

        # Yalnizca kendi depomuzu isaret eden URL'leri dogrulariz; diger depolar
        # (or. Kraptor) bu calisma alaninda bulunmaz.
        is_ours = slug in url or "Kitsugi-Plugins" in url or "KitsugiPlugins" in url
        if not is_ours:
            continue

        actual_dir = file_index.get(fname)
        if actual_dir is None:
            # Dosya adi farkli olabilir: ayni eklenti icin alternatif yazimlari dene.
            alt = _alternative_names(fname)
            for candidate in alt:
                if candidate in file_index:
                    actual_dir = file_index[candidate]
                    print(f"  ~ {name}: '{fname}' bulunamadi, '{candidate}' kullaniliyor")
                    plugin["url"] = f"{base}/{_rel_path(builds_root, actual_dir, candidate)}"
                    repaired.append(name)
                    break
            if actual_dir is None:
                still_missing.append((name, url, "dosya hicbir yerde yok"))
            continue

        expected_rel = _rel_path(builds_root, actual_dir, fname)
        if not url.split("?")[0].endswith("/" + expected_rel):
            new_url = f"{base}/{expected_rel}"
            print(f"  ~ {name}: URL duzeltildi -> {new_url}")
            plugin["url"] = new_url
            repaired.append(name)

    # ── Cikti ────────────────────────────────────────────────────────────────
    try:
        os.makedirs(os.path.dirname(compiled_json_path) or ".", exist_ok=True)
        with open(compiled_json_path, "w", encoding="utf-8") as f:
            json.dump(list(combined.values()), f, indent=4, ensure_ascii=False)
        print(
            f"  => plugins.json yazildi: {len(combined)} eklenti "
            f"(duzeltilen URL: {len(repaired)})"
        )
    except Exception as exc:
        print(f"  ! plugins.json yazilamadi: {exc}")
        return 1

    if still_missing:
        print("")
        print(f"  !! DIKKAT: {len(still_missing)} kaydin .cs3 dosyasi depoda BULUNAMADI.")
        print("     Bu eklentiler kurulamaz — ilgili dizin icin derleme tetiklenmelidir:")
        for name, url, reason in still_missing:
            print(f"       - {name:24s} ({reason})  {url}")
        # Bilincli olarak kayit silinmiyor: derleme tamamlandiginda kayit kendiliginden calisir.
    else:
        print("  OK: tum kayitlarin dosyasi mevcut.")
    return 0


def _rel_path(builds_root, dirpath, filename):
    """builds koku icindeki goreli yolu URL biciminde dondurur (or. prebuilt/X.cs3)."""
    rel_dir = os.path.relpath(dirpath, builds_root)
    if rel_dir in (".", ""):
        return filename
    return rel_dir.replace(os.sep, "/") + "/" + filename


def _alternative_names(fname):
    """Dosya adi varyantlari: Turkce karakter katlama, kisaltma katlama, bosluk temizligi.

    Gercek olaylar: `WFilmİzle.cs3` ↔ `WFilmizle.cs3`, `CanliTV.cs3` ↔ `CanliTv.cs3`,
    `DDizi.cs3` ↔ `Ddizi.cs3`, `Kanal 7.cs3` ↔ `Kanal7.cs3`.
    """
    if not fname.endswith(".cs3"):
        return []
    stem = fname[:-4]
    folded = stem
    for src, dst in (("İ", "i"), ("I", "i"), ("ı", "i"), ("Ş", "s"), ("ş", "s"),
                     ("Ğ", "g"), ("ğ", "g"), ("Ü", "u"), ("ü", "u"),
                     ("Ö", "o"), ("ö", "o"), ("Ç", "c"), ("ç", "c")):
        folded = folded.replace(src, dst)

    # Kisaltma katlama: CanliTV -> CanliTv, DDizi -> Ddizi
    collapsed = []
    i = 0
    while i < len(folded):
        ch = folded[i]
        if ch.isupper():
            j = i
            while j < len(folded) and folded[j].isupper():
                j += 1
            if j - i >= 2:
                collapsed.append(folded[i] + folded[i + 1:j].lower())
            else:
                collapsed.append(ch)
            i = j
        else:
            collapsed.append(ch)
            i += 1
    collapsed = "".join(collapsed)

    no_space = folded.replace(" ", "")
    variants = [folded, collapsed, no_space, no_space.lower(), no_space.upper()]
    seen, out = set(), []
    for v in variants:
        if v and v != stem and v not in seen:
            seen.add(v)
            out.append(v + ".cs3")
    return out


if __name__ == "__main__":
    sys.exit(main())
