# 🔌 Kitsugi Plugins

Kitsugi Android & Android TV uygulaması için özel olarak derlenmiş, optimize edilmiş ve güncellenmiş Cloudstream 3 (CS3) eklenti havuzudur.

---

## ✅ Aktif Kaynaklar (6)

Depo artık **yalnızca aşağıdaki 6 kaynağı** yayınlar. Bunların dışındaki tüm eklentiler **silinmemiştir**; sadece devre dışıdır (manifeste yazılmaz, derlenmez).

| Kategori | Eklenti | Sürüm |
| --- | --- | --- |
| Anime | **Anizium** | 25 |
| Anime | **AnimeciX** | 24 |
| Film / Dizi | **FilmMakinesi** | 71 |
| Film / Dizi | **HDFilmCehennemi** | 62 |
| Film / Dizi | **FilmEkseni** | 51 |
| Film / Dizi | **HDFilmDelisi** | 19 |

> **FilmEkseni** ve **HDFilmDelisi**'nin Kotlin kaynakları bu depodaki kendi modüllerinde tutulur ve `.cs3` paketleri bu kaynaklardan derlenir. Çalışma anında başka bir eklenti deposundan kod veya alan adı listesi indirmezler. Video siteleri, oynatıcılar ve altyazı/metadata servisleri doğal olarak dış sağlayıcılar olmaya devam eder; bunların erişilebilirliği ayrıca canlı test gerektirir. Kaynak atıfları için [`THIRD_PARTY_SOURCES.md`](THIRD_PARTY_SOURCES.md) dosyasına bakın.

> Sürüm sütunu bilgilendirme amaçlıdır; `plugins.json`'a yazılan gerçek sürüm her zaman `.cs3` dosyasının içindeki `manifest.json`'dan okunur.

> ⚠️ Depoyu uygulamada güncelledikten sonra daha önce kurduğunuz diğer eklentiler listeden kaybolur; cihazda kurulu kalanları **Ayarlar → Eklentiler** üzerinden kaldırabilirsiniz.

---

## 🔁 Eklenti Açma / Kapatma

Tüm eklenti yönetimi tek dosyadan yapılır: **`enabled_plugins.txt`**

- **Devre dışı bırakmak** → satırı silin (klasörü silmeyin).
- **Tekrar açmak** → klasör adını bir satır olarak ekleyin.
- Push sonrası `Derleyici` iş akışı `builds` dalındaki `plugins.json`'ı otomatik yeniden üretir.

Bu dosyayı okuyan yerler:

| Dosya | Görevi |
| --- | --- |
| `enabled_plugins.txt` | Tek doğruluk kaynağı (eklenti listesi) |
| `settings.gradle.kts` | Sadece listedeki dizinleri derler |
| `merge_plugins.py` | `plugins.json`'a sadece listedekileri yazar, URL/hash/sürümü gerçek `.cs3` dosyasından üretir, tanıtım verisini `prebuilt_plugins.json`'daki küratörlü kayıttan alır |
| `.github/workflows/Derleyici.yml` | Sadece listedeki dizinler için derleme tetikler |

---

## 🏷️ Tanıtım Verisi ve `status`

Aktif eklentilerin `plugins.json` kaydındaki **tanıtım alanları** (`description`, `iconUrl`, `tvTypes`, `language`, `authors`, `name`, `apiVersion`) `prebuilt_plugins.json` içindeki **küratörlü kayıttan** alınır. Gradle'ın `makePluginsJson` çıktısı bu alanları üretmediği için eskiden kayıt ya boş kalıyordu ya da bozuk değer taşıyordu (`&sz=%size%` gibi doldurulmamış ikon adresi, sadece `["Movie"]` görünen dizi kaynakları, hiç açıklaması olmayan Anizium).

**Küratörlü kayıttan alınmayanlar:** `url`, `fileSize`, `fileHash`, `version`. Bunlar depodaki gerçek `.cs3` dosyasından üretilir; başka bir depodan (ör. Kraptor) gelen değer kopyalanırsa uygulama indirdiği dosyayı doğrulayamaz ve kurulum sessizce başarısız olur.

`status` değerleri CloudStream karşılıklarıyla: `0` Down, `1` Ok, `2` Slow, `3` Beta only. **`status: 3` kayıtlar uygulamanın normal sürümünde listelenmez**; bu depoda devre dışı bırakma işi `enabled_plugins.txt`'ye ait olduğu için `merge_plugins.py` manifeste yazarken `3 → 1` düzeltmesi yapar (status hiç yazılmamışsa da `1` kabul edilir).

---

## 📥 Eklenti Deposunu Uygulamaya Ekleme

Kitsugi uygulamasında eklentileri yükleyebilmek için aşağıdaki depo bağlantı adresini (Repository URL) uygulamanıza eklemeniz gerekmektedir:

### 🔗 Depo Bağlantı Adresi (Repository URL)
```text
https://raw.githubusercontent.com/f26901964-eng/Kitsugi-Plugins/builds/repo.json
```

### 🛠️ Kurulum Adımları
1. **Kitsugi** uygulamasını açın.
2. Ana sayfadan **Ayarlar** (Settings) sekmesine gidin.
3. **Eklentiler** (Addons) -> **Cloudstream Uzantıları** (Cloudstream Extensions) menüsünü açın.
4. Sağ üstte yer alan veya menüdeki **Repo Ekle** (Add Repository) seçeneğine tıklayın.
5. Yukarıdaki **Depo Bağlantı Adresi**'ni kopyalayıp ilgili alana yapıştırın ve **Ekle** (Add) butonuna basın.
6. Depo başarıyla eklendikten sonra listeden dilediğiniz dizi/anime sağlayıcı eklentisini tek tıkla yükleyebilirsiniz.

---

## 🔄 Otomatik Yönlendirme ve Geçiş
Eski eklenti depolarını (`keyiflerolsun`, `maarrem/cs-Kekik` vb.) kullanan kullanıcılar için Kitsugi uygulaması otomatik yönlendirme sunar. Eski depoları eklediğinizde uygulama bunu algılar ve otomatik olarak bu güncel depoya (`f26901964-eng/Kitsugi-Plugins`) yönlendirerek eklentileri en güncel sürümleriyle günceller.

---

## 🛠️ Geliştiriciler İçin Derleme Süreci

Bu depodaki eklentiler Kotlin diliyle yazılmış kaynak kodlarından otomatik olarak derlenmektedir.
- **`main` Branch**: Eklentilerin kaynak kodlarını barındırır.
- **`builds` Branch**: GitHub Actions (`Derleyici.yml`) tarafından otomatik olarak derlenen `.cs3` eklenti dosyalarını ve eklentilerin listelendiği `plugins.json` / `repo.json` dosyalarını barındırır.

Eklentiler üzerinde değişiklik yapmak için `main` branch'ine yapılan push işlemleri, eklentileri otomatik olarak derleyip `builds` branch'ine aktaracaktır.
