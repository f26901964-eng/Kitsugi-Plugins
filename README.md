# 🔌 Kitsugi Plugins

Kitsugi Android & Android TV uygulaması için özel olarak derlenmiş, optimize edilmiş ve güncellenmiş Cloudstream 3 (CS3) eklenti havuzudur.

---

## ✅ Aktif Kaynaklar (4)

Depo artık **yalnızca aşağıdaki 4 kaynağı** yayınlar. Bunların dışındaki tüm eklentiler **silinmemiştir**; sadece devre dışıdır (manifeste yazılmaz, derlenmez).

| Kategori | Eklenti | Sürüm |
| --- | --- | --- |
| Anime | **Anizium** | 25 |
| Anime | **AnimeciX** | 21 |
| Film / Dizi | **FilmMakinesi** | 58 |
| Film / Dizi | **HDFilmCehennemi** | 48 |

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
| `merge_plugins.py` | `plugins.json`'a sadece listedekileri yazar, URL/hash/sürümü gerçek `.cs3` dosyasından üretir |
| `.github/workflows/Derleyici.yml` | Sadece listedeki dizinler için derleme tetikler |

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
Eski eklenti depolarını (`keyiflerolsun`, `maarrem/cs-Kekik` vb.) kullanan kullanıcılar için Kitsugi uygulaması otomatik yönlendirme sunar. Eski depoları eklediğinizde uygulama bunu algılar ve otomatik olarak bu güncel depoya (`KitsugiBeta-dev/Kitsugi-Plugins`) yönlendirerek eklentileri en güncel sürümleriyle günceller.

---

## 🛠️ Geliştiriciler İçin Derleme Süreci

Bu depodaki eklentiler Kotlin diliyle yazılmış kaynak kodlarından otomatik olarak derlenmektedir.
- **`main` Branch**: Eklentilerin kaynak kodlarını barındırır.
- **`builds` Branch**: GitHub Actions (`Derleyici.yml`) tarafından otomatik olarak derlenen `.cs3` eklenti dosyalarını ve eklentilerin listelendiği `plugins.json` / `repo.json` dosyalarını barındırır.

Eklentiler üzerinde değişiklik yapmak için `main` branch'ine yapılan push işlemleri, eklentileri otomatik olarak derleyip `builds` branch'ine aktaracaktır.
