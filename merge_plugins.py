import hashlib
import json
import os
import sys
import zipfile

# ─────────────────────────────────────────────────────────────────────────────
# Eklenti manifestini birlestirir ve URL'leri GERCEK DOSYALARA gore dogrular.
#
# 2026-10 — AKTIF EKLENTI FILTRESI:
#   Artik uygulamada yalnizca `enabled_plugins.txt` icindeki kaynaklar gorunur.
#   Diger eklentiler SILINMEZ; sadece manifeste yazilmaz (devre disi).
#   Aktif eklentilerin URL/hash/boyut/surum bilgisi depodaki GERCEK .cs3
#   dosyasindan okunur. Boylece baska depolardan (or. Kraptor) gelen kayitlarin
#   bizim dosyamizin uzerine yazip hash uyusmazligi olusturmasi engellenir.
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
#
# 2026-10 — TANITIM VERISI (KURATOR KAYDI):
#   Aktif eklentilerin description / iconUrl / tvTypes / language / authors gibi
#   "tanitim" alanlari `prebuilt_plugins.json` icindeki KURATORLU kayittan alinir.
#   Neden gerekliydi:
#     * Gradle'in `makePluginsJson` ciktisi bu alanlari uretmez. Kayit builds/plugins.json
#       uzerinden geliyorsa tanitim verisi oradaki (bazen bozuk) degerde kaliyordu.
#       Gercek ornekler: `iconUrl: ...&sz=%size%` (yer tutucu hic doldurulmamis),
#       FilmMakinesi tvTypes=['Movie'] (dizi kaynagi ama sadece film gorunuyor),
#       Anizium icin description/iconUrl/language/tvTypes HIC yok.
#     * status=3 ("Beta only") kayitlari uygulamanin normal surumunde listelenmez;
#       Anizium builds/plugins.json'da status=3 oldugu icin gorunmuyordu.
#   Kurator kaydindan ALINMAZ: url / fileSize / fileHash / version.
#   Bunlar depodaki GERCEK .cs3 dosyasindan uretilir (_sync_from_local_cs3);
#   aksi halde hash uyusmazligi kurulumu sessizce bozar.
# ─────────────────────────────────────────────────────────────────────────────

BRANCH = "builds"
PLACEHOLDER_SLUG = "f26901964-eng/Kitsugi-Plugins"
ENABLED_FILE = "enabled_plugins.txt"

# CloudStream SitePlugin status degerleri (RepositoryManager.kt):
#   0: Down | 1: Ok | 2: Slow | 3: Beta only
# Bu depo bilincli olarak "aktif liste" mantigiyla calisir; devre disi birakma isi
# enabled_plugins.txt'ye aittir. Bu yuzden 3 (Beta only) -> 1 (Ok) yapilir.
STATUS_OK = 1
STATUS_BETA_ONLY = 3

# prebuilt_plugins.json'dan (kurator kaydindan) devralinan tanitim alanlari.
# Bilincli olarak DISARIDA birakilanlar:
#   url / fileSize / fileHash / version -> GERCEK .cs3 dosyasindan uretilir.
#   internalName / repositoryUrl        -> birlestirme anahtari ve bu deponun kimligi.
CURATED_META_FIELDS = (
    "name",
    "description",
    "iconUrl",
    "tvTypes",
    "language",
    "status",
    "apiVersion",
)


def load_enabled_plugins():
    """enabled_plugins.txt'deki eklenti adlarini dondurur.

    Dosya yoksa None doner -> eski davranis (tum eklentiler acik).
    '#' ile baslayan satirlar ve bos satirlar yorum kabul edilir.
    """
    if not os.path.exists(ENABLED_FILE):
        return None
    names = []
    with open(ENABLED_FILE, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            names.append(line)
    return names


def filter_enabled_plugins(combined, enabled):
    """Etkin listeye uyan tek kaydı bırakır; harf farkıyla yineleneni sızdırmaz.

    Eski builds/prebuilt kayıtları, yeni derlenen bir eklentinin internalName
    değerinden yalnızca harf büyüklüğüyle ayrılabilir (ör. HDFilmizle ve
    HDFilmIzle). Tam eşleşen etkin kayıt varsa onu seçer; tek bir harf-duyarsız
    eşleşme varsa geriye uyumluluk için onu korur. Birden fazla eşleşme var ama
    kanonik tam eşleşme yoksa yanlış eklentiyi sessizce yayımlamak yerine durur.
    """
    if enabled is None:
        return combined

    canonical_names = {}
    for name in enabled:
        key = name.casefold()
        if key in canonical_names:
            raise ValueError(
                f"{ENABLED_FILE} aynı eklentiyi harf farkıyla birden fazla içeriyor: "
                f"{canonical_names[key]} / {name}"
            )
        canonical_names[key] = name

    matches = {key: [] for key in canonical_names}
    for name in combined:
        key = name.casefold()
        if key in matches:
            matches[key].append(name)

    selected = set()
    for key, canonical in canonical_names.items():
        candidates = matches[key]
        if canonical in candidates:
            selected.add(canonical)
        elif len(candidates) == 1:
            selected.add(candidates[0])
        elif len(candidates) > 1:
            raise ValueError(
                f"{canonical}: manifestte harf farkıyla birden fazla kayıt var "
                "ve etkin adla tam eşleşen bulunamadı"
            )

    return {name: plugin for name, plugin in combined.items() if name in selected}


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


def _cs3_version(path):
    """.cs3 icindeki manifest surumunu dondurur; okunamazsa -1."""
    meta = _read_cs3_manifest(path)
    if isinstance(meta, dict) and isinstance(meta.get("version"), int):
        return meta["version"]
    return -1


def index_cs3_files(root):
    """builds/ altindaki tum .cs3 dosyalarini {dosya_adi: yol} olarak indeksler.

    Ayni dosya adi hem builds/ kokunde hem builds/prebuilt/ altinda bulunabilir.
    Eskiden ilk bulunan (kok) kazanıyordu; prebuilt kopyasi daha yeni surum
    tasiyorsa URL o dosyaya sabitlenip surum geriye dusebiliyordu. Artik ayni
    ad icin EN YUKSEK manifest surumune sahip kopya secilir.
    """
    index = {}
    if not os.path.isdir(root):
        return index
    for dirpath, _dirnames, filenames in os.walk(root):
        if ".git" in dirpath.split(os.sep):
            continue
        for fn in filenames:
            if not fn.endswith(".cs3"):
                continue
            base = os.path.basename(fn)
            current = index.get(base)
            if current is None:
                index[base] = dirpath
                continue
            new_version = _cs3_version(os.path.join(dirpath, fn))
            old_version = _cs3_version(os.path.join(current, base))
            if new_version > old_version:
                index[base] = dirpath
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

    # Kuratorlu kayitlar: {kucuk_harfli internalName: kayit}
    curated = {}
    for plugin in prebuilt:
        key = (plugin.get("internalName") or plugin.get("name") or "").lower().strip()
        if key:
            curated[key] = plugin

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

    # ── AKTIF EKLENTI FILTRESI ───────────────────────────────────────────────
    # Uygulamada gorunecek kaynaklar yalnizca enabled_plugins.txt'dekiler.
    # Digerleri manifestten cikarilir ama ne kaynaklari ne .cs3 dosyalari silinir.
    # enabled_plugins.txt yoksa (eski davranis) filtre uygulanmaz.
    enabled = load_enabled_plugins()
    if enabled is None:
        print(f"  i {ENABLED_FILE} yok -> tum eklentiler etkin (eski davranis).")
    else:
        before = len(combined)
        combined = filter_enabled_plugins(combined, enabled)
        print(
            f"  aktif liste: {len(enabled)} eklenti | "
            f"devre disi birakilan kayit: {before - len(combined)}"
        )
        present = {key.casefold() for key in combined}
        for name in enabled:
            if name.casefold() not in present:
                print(f"  !! AKTIF ama manifestte yok: {name} (kaynak/.cs3 eksik olabilir)")

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

    # ── Aktif eklentileri KENDI depomuza sabitle ─────────────────────────────
    # prebuilt_plugins.json baska depolarin (or. Kraptor) kayitlarini icerir ve
    # birlestirme sirasinda bunlar bizim kaydimizin uzerine yazar. Etkin her
    # kaynak icin URL / dosya boyutu / sha256 / surum bilgisini depodaki GERCEK
    # .cs3 dosyasindan yeniden uretmezsek uygulama indirdigi dosyayi
    # dogrulayamaz ve kurulum sessizce basarisiz olur.
    if enabled is not None:
        pinned = 0
        for name in enabled:
            plugin = next((p for k, p in combined.items() if k.lower() == name.lower()), None)
            if plugin is None:
                continue
            fname = (plugin.get("internalName") or name) + ".cs3"
            dirpath = file_index.get(fname)
            if dirpath is None:
                for candidate in _alternative_names(fname):
                    if candidate in file_index:
                        dirpath, fname = file_index[candidate], candidate
                        break
            if dirpath is None:
                continue
            path = os.path.join(dirpath, fname)
            _sync_from_local_cs3(
                plugin, path, base, _rel_path(builds_root, dirpath, fname)
            )
            pinned += 1
        print(f"  kendi depomuza sabitlenen aktif eklenti: {pinned}")

    # ── Tanitim verisini KURATOR kaydindan al + status normalizasyonu ────────
    # Siralama onemli: .cs3 senkronundan SONRA calisir, boylece url/fileSize/
    # fileHash/version dosyadan gelen degerinde kalir; sadece tanitim alanlari
    # prebuilt_plugins.json'daki editor kontrolunden gecmis kayitla degisir.
    curated_hits = 0
    if enabled is not None:
        allowed = {n.lower() for n in enabled}
        for name, plugin in combined.items():
            if name.lower() not in allowed:
                continue
            record = curated.get(name.lower())
            if record is not None:
                curated_hits += 1
                _apply_curated_meta(plugin, record)
            else:
                print(f"  ~ {name}: prebuilt_plugins.json'da kurator kaydi yok, mevcut alanlar korundu")
        print(f"  tanitim verisi prebuilt_plugins.json'dan alinan aktif eklenti: {curated_hits}")

    # ── Depo kimligi: repositoryUrl HER ZAMAN bu depo ────────────────────────
    # prebuilt_plugins.json'daki kurator kayitlari baska depolarin (or. Kraptor,
    # KitsugiBeta-dev) kaydini tasiyor. Derleme yapilmayan bir calistirmada
    # birlestirme sirasinda bu kayit bizimkinin uzerine yaziyor ve uygulama
    # eklentiyi yanlis depoya bagliyordu. Aktif kayitlarin tamamini sabitliyoruz.
    repo_url = f"https://github.com/{slug}"
    pinned_repo = 0
    for name, plugin in combined.items():
        if plugin.get("repositoryUrl") != repo_url:
            print(f"  ~ {name}: repositoryUrl -> {repo_url}")
            plugin["repositoryUrl"] = repo_url
            pinned_repo += 1
    print(f"  repositoryUrl bu depoya sabitlenen kayit: {pinned_repo}")

    # status=3 (Beta only) kayitlar uygulamanin normal surumunde listelenmez.
    # Ayni sekilde status hic yazilmamissa SitePlugin.status nullable olmadigi
    # icin kayit cozumlenemeyebilir -> varsayilan 1 (Ok).
    for name, plugin in combined.items():
        if plugin.get("status") in (STATUS_BETA_ONLY, None):
            print(f"  ~ {name}: status {plugin.get('status')} -> {STATUS_OK}")
            plugin["status"] = STATUS_OK

    # ── Cikti ────────────────────────────────────────────────────────────────
    try:
        os.makedirs(os.path.dirname(compiled_json_path) or ".", exist_ok=True)
        with open(compiled_json_path, "w", encoding="utf-8") as f:
            json.dump(list(combined.values()), f, indent=4, ensure_ascii=False)
        print(
            f"  => plugins.json yazildi: {len(combined)} eklenti "
            f"(duzeltilen URL: {len(repaired)})"
        )

        # Depo tanitimi da guncellenir; aksi halde uygulamada "166+ eklenti"
        # gibi gercekle uyusmayan bir aciklama gorunur.
        repo_json_path = os.path.join(os.path.dirname(compiled_json_path) or ".", "repo.json")
        repo_obj = {
            "name": "⚡ Kitsugi Eklentileri (Resmi)",
            "description": (
                f"{len(combined)} aktif Türkçe CloudStream eklentisi "
                "(anime, dizi ve film kaynakları)."
            ),
            "manifestVersion": 1,
            "pluginLists": [f"{base}/plugins.json"],
        }
        with open(repo_json_path, "w", encoding="utf-8") as f:
            json.dump(repo_obj, f, indent=2, ensure_ascii=False)
        print(f"  => repo.json yazildi: {repo_json_path}")
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


def _sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _read_cs3_manifest(path):
    """.cs3 (zip) icindeki manifest.json'u okur; okunamazsa None doner."""
    try:
        with zipfile.ZipFile(path) as zf:
            with zf.open("manifest.json") as fh:
                return json.load(fh)
    except Exception:
        return None


def _sync_from_local_cs3(plugin, path, base, rel_path):
    """Kaydi depodaki gercek .cs3 dosyasiyla birebir tutarli hale getirir."""
    plugin["url"] = f"{base}/{rel_path}"
    plugin["fileSize"] = os.path.getsize(path)
    plugin["fileHash"] = "sha256-" + _sha256(path)

    meta = _read_cs3_manifest(path)
    if isinstance(meta, dict) and isinstance(meta.get("version"), int):
        # Manifestteki surum ile dosyadaki surum ayni olmali; aksi halde
        # uygulama guncellemeyi hic gormez ya da kurulumu reddeder.
        plugin["version"] = meta["version"]


def _usable(value):
    """Kurator kaydindaki deger gercekten kullanilabilir mi?

    Bos string ve bos liste kalintilari yok sayilir; `%size%` gibi doldurulmamis
    yer tutucu iceren degerler de gecersizdir (prebuilt_plugins.json'da 19 kayitta
    `.../s2/favicons?...&sz=%size%` biciminde bozuk iconUrl var).
    """
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip()) and "%" not in value
    if isinstance(value, (list, tuple)):
        return len(value) > 0
    return True


def _apply_curated_meta(plugin, curated):
    """prebuilt_plugins.json'daki kurator kaydin tanitim alanlarini uygular.

    url / fileSize / fileHash / version BILINCLI olarak devralinmaz: onlar depodaki
    gercek .cs3 dosyasindan uretilir. Kurator kaydi baska bir deponun (or. Kraptor)
    dosyasini isaret ediyor olabilir; o degerler buraya kopyalanirsa uygulama
    indirdigi dosyayi dogrulayamaz.
    """
    for field in CURATED_META_FIELDS:
        if field not in curated:
            continue
        value = curated[field]
        if not _usable(value):
            continue
        plugin[field] = value

    # Yazarlar: kurator listesi esas alinir, derleme ciktisindaki ek yazarlar sona eklenir.
    curated_authors = curated.get("authors")
    if isinstance(curated_authors, list) and curated_authors:
        merged = list(curated_authors)
        seen = {str(a).lower() for a in merged}
        for author in plugin.get("authors") or []:
            if str(author).lower() not in seen:
                merged.append(author)
                seen.add(str(author).lower())
        plugin["authors"] = merged

    # Kurator kaydinda da status=3 olabilir; orada da 1'e cevrilir.
    if plugin.get("status") == STATUS_BETA_ONLY:
        plugin["status"] = STATUS_OK


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
