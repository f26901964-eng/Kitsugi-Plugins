# AnimeciX · Anizm · TrAnimeIzle — "her zaman veri gelmiyor" sorununun tam tanımı

**Tarih:** 04.11.2026 · **Dal:** `arena/03f7617a-kitsugi-plugins`
**Amaç:** Üç eklentideki arızanın **nerede** olduğunu, hangi durumda
tetiklendiğini ve hangi dökümün kanıt olacağını netleştirmek.
**Durum:** Kod + depodaki gerçek dökümler + Cloudstream 3 çekirdeği incelendi.
Canlı siteye istek atılmadı (bu ortamda dış ağ yok).

---

## Ortak payda: üçü de "tek koşulda çalışır, koşul bozulunca sessizce susar"

Üç eklentinin de hata davranışı **aynı**: bir şey ters gittiğinde kullanıcıya
sebep göstermeden **boş liste** dönerler (`return false` / `return null`).
Bu yüzden "bazen veriyor bazen vermiyor" hissi oluşuyor. Aşağıda her biri için
**tetikleyici koşul** tek tek yazılı.

---

## 1) AnimeciX — 4 bağımsız arıza noktası

### A1 · Hızlı aramada eklenti hiç çağrılmıyor ⛔ *(en sık şikâyet)*
```kotlin
// AnimeciX.kt:27
override val hasQuickSearch = false

// SearchViewModel.kt:233 (ÇEKİRDEK)
(!isQuickSearch || a.hasQuickSearch)
```
Arama kutusuna yazdığın anda yapılan hızlı aramada AnimeciX **filtreleniyor**.
Tam aramada (Enter) çıkıyor. → "AnimeciX arama yapınca sonuç vermiyor"
şikâyetinin **1 numaralı nedeni** bu.

**Döküm kanıtı:** gerek yok, kod sabit.

### A2 · Tüm `/secure/*` uçları TEK sabit token'a bağlı ⛔
```kotlin
// AnimeciX.kt:13
private const val X_E_H = "7Y2ozlO+QysR5w9Q6Tupmtvl9jJp7ThFH8SB+Lo7NvZjgjqRSqOgcT2v4ISM9sP10LmnlYI8WQ==.xrlyOBFS5BHjQ2Lk"
```
`secureHeaders()` her istekte bu **sabit** değeri gönderiyor. Site bu değeri
döndürürse **arama + ana sayfa + detay + bölüm listesi + loadLinks** aynı anda
susar. Dinamik yenileme **yok**.

Not: Depoda **devre dışı** `AnimeSiteleri/AnimeciX.kt` dosyasında bunun
**çözümü zaten var**:
```kotlin
private var cachedToken: String? = null
// ana sayfa HTML'inden `"x-e-h":"…"` regex'i ile canlı token çeker,
// bulamazsa sabit değere düşer
```
Aktif eklentiye bu mantık **taşınmamış**. Yani düzeltme elimizde hazır.

**Döküm kanıtı:** ana sayfa HTML'i (`<script>` içinde `x-e-h` alanı) →
token'ın sayfadan gerçekten okunabildiğini doğrular.

### A3 · `loadLinks` tamamen SIRALI ⛔ *(senin "tıkanıyor" dediğin yer)*
`AnimeciX.kt:183-240` akışı:
```
1) bölüm sayfası        → app.get
2) /secure/best-video   → app.get   (yönlendirme)
3) loadExtractor        → bekler
4) iframe'ler           → her biri sırayla bekler
5) regex ile m3u8/mp4   → bekler
```
`async` = **0**, `awaitAll` = **0**, `withTimeoutOrNull` = **0**.
Yani her adım bir öncekini bekliyor. Kaynak 2. adımda çıkarsa 3-5
**hiç denenmiyor** (doğru), ama 2. adım yavaşsa kullanıcı 30-60 sn boş ekran
görür. Cloudstream varsayılan `loadLinks` bütçesi 120 sn — yani "sonsuza kadar
yükleniyor" hissi buradan.

### A4 · Tek domain, yedek yok
`mainUrl = "https://animecix.tv"`. `animecix` alan adı değişirse **tamamen ölür**.
Diğer eklentilerde (`Anizm`, `TrAnimeIzle`, `FilmMakinesi`, `HDFilmCehennemi`)
`ensureBase`/`remapDomain`/`domainCandidates` var; AnimeciX'te **yok**.

---

## 2) Anizm — mimari sağlam, 3 somut boşluk

Anizm bu üçü içinde **en iyi durumda** (paralel çevirmen, 8 eşzamanlı kaynak,
18 sn/öğe zaman aşımı, 10 dk TTL domain probu, Kitsu eşadları). Sorunları
"çalışmıyor" değil, **"bazı bölümlerde sonuç çıkmıyor"** cinsinden.

### N1 · Çözücü kapsamı 94 kaynağın hepsini kapsamıyor
Repo'nun kendi ölçümü (`research/anizm-probe/resolve-summary.txt`):

| Ölçüm | Değer |
| --- | --- |
| Taranan kaynak | **94** |
| Çözücü kapsamında | **81** |
| Ölçülen oynatılabilir | **9** |

Ölçülemeyenlerin bir kısmı veri merkezi IP engeli (cihazda çalışır) — ama
`Sistenn ×3` kaydı dikkat çekici:
```
Sistenn   api 500
Sistenn1  çözülemedi: Odd-length string
Sistenn2  çözülemedi: Odd-length string
```
"Odd-length string" = **AES hex çözümleyicide tek karakter sorunu**. Yani
Sistenn oynatıcısı 3 kaynakta **kod hatası** yüzünden düşüyor, site hatası
yüzünden değil. Bu düzeltilebilir bir hata.

### N2 · "Sonuç < 3 ise >3 MB katalog indir" — ilk aramada 3-8 sn
`Anizm.kt:395` `loadCatalogue()` yalnızca `collected.size < 3` iken çağrılıyor
ve **6 saat** önbellekleniyor. İlk aramada yavaşlık buradan. İkinci aramada
hızlı — bu yüzden "bazen hızlı bazen yavaş".

### N3 · Tek "kapı" davranışı: çevirmen yoksa tamamen susuyor
`translators.isEmpty()` ve `pageLevelSources` de boşsa `return 0` → kullanıcı
"bu bölüm için kaynak yok" görür. Site bölüm sayfasında çevirmen listesi
JS ile geç yüklüyorsa (ya da A/B testinde gizliyse) sonuç boş kalır.
**Döküm kanıtı:** bölüm sayfasının ham HTML'i — `data-sources` /
`data-translator-id` alanları var mı, yoksa başka bir yol mu kullanıyor.

---

## 3) TrAnimeIzle — 3 arıza, biri "kesin hata"

### T1 · CAPTCHA çözümü serileştirilmiş ve pahalı ⚠️
`TrAnimeIzle.kt:450` `solveCaptcha()` = `captchaLock.withLock { … }`
= **2 cID × 2 tema × (1 liste + N ikon indirme + 1 gönderim)**.
Kaba hesap **10-20 sn**, ve mutex yüzünden **eşzamanlı 10 kaynak çözümü
CAPTCHA'da sıraya girer**. Captcha kabul edilmezse zincir tamamen durur.

### T2 · `loadLinks` sonuç yoksa **istisna atıyor** ⛔
```kotlin
// TrAnimeIzle.kt:295
if (count > 0) return true
throw ErrorLoadingException("Bu bölüm için kaynak bulunamadı …")
```
Çekirdek bunu yakalıyor (`APIRepository.loadLinks` → `catch → false`), ama
`RepoLinkGenerator`'da istisna **tüm bölüm için** akış üretimini sonlandırır.
Kullanıcı "kaynak bulunamadı" görür; **oysa çözüm hiç denenmemiş** olabilir
(ör. CAPTCHA düştü, ağ hatası oldu). Diğer iki eklenti `false` dönüyor —
davranış **tutarsız**.

### T3 · Bölüm → seri çözümünde 2-4 ekstra istek
`load()` bir bölüm adresinden açıldığında sırayla breadcrumb → slug kuralı →
sezon eki atılmış varyant deniyor. Her aday **ayrı `get()`**. Bölüm sayfası
ağırlaştığında bu 4 istek **~8 sn** ekliyor. Arama sonuçları çoğunlukla
**bölüm adresi** döndüğü için bu yol **sık** tetikleniyor.

---

## 4) Senin HTML dökümlerinden ihtiyacım olanlar (Drive klasörü)

Aşağıdaki liste **minimum**; fazlası zarar vermez. Her dosyanın adı kaynağı
belli etsin (`animecix_ana.html` gibi).

| Eklenti | Dosya | Neden gerekli | Öncelik |
| --- | --- | --- | --- |
| **AnimeciX** | ana sayfa HTML (ham, `view-source` → kaydet) | `x-e-h` token'ı sayfada nerede? A2 düzeltmesinin doğrulanması | **1** |
| AnimeciX | bir anime detay sayfası (`/secure/titles/{id}?titleId={id}`) | `seasons[]`, `videos[]` alanlarının gerçek biçimi | 2 |
| AnimeciX | bir **bölüm** oynatıcı sayfası (`best-video` sonrası) | iframe mi, tau-video embed mi, yoksa JS mi | 2 |
| **Anizm** | bir bölüm sayfası (`/one-piece-1-bolum-izle` gibi) | `data-sources` / `data-translator-id` gerçekten var mı (N3) | **1** |
| Anizm | aynı bölümün `{data-sources}` JSON yanıtı | Sistenn kayıtlarının ham `videos[]` hali (N1) | 2 |
| **TrAnimeIzle** | bir bölüm sayfası (ham, **captcha sonrası**) | `sourceBtn[data-id]` var mı, fansub listesi nerede (T1) | **1** |
| TrAnimeIzle | `POST /api/sourcePlayer/{id}` yanıtı | oynatıcı gövdesi hangi biçimde (T2 doğrulaması) | 2 |
| Üçü de | bir **arama sonuçları** sayfası (aynı Türkçe sorgu: ör. `one piece`) | başlık eşleştirme kalitesi | 3 |

> **Nasıl kaydedilir:** Chrome → sayfa açıkken `Ctrl+S` → "Webpage, Complete"
> → `.html` dosyası Drive'a. Ya da `Ctrl+U` (kaynağı görüntüle) → tümünü
> kopyala → metin editörüne yapıştır → `.html` olarak kaydet. **Cookie/token
> içeren sayfaları** kopyalarken `x-e-h` değerini **gizlemene gerek yok**,
> zaten eklentide sabit duruyor.

---

## 5. Dökümler gelince yapılacak (sıra)

1. **AnimeciX/A1** — `hasQuickSearch = true` + `quickSearch()` hızlandırma
   *(kod düzeltmesi, döküm gerekmez — hemen yapılabilir)*
2. **AnimeciX/A2** — canlı token çekme (`AnimeSiteleri/AnimeciX.kt` mantığı)
3. **AnimeciX/A3** — `loadLinks` paralelleştirme (`async` + `withTimeoutOrNull`)
4. **AnimeciX/A4** — domain yedeği (aynı desen `FilmMakinesi`'den kopyalanabilir)
5. **TrAnimeIzle/T2** — istisna yerine `false` + teşhis logu
6. **Anizm/N1** — Sistenn hex çözümleyici düzeltmesi
7. **Anizm/N2** — katalog indirmesini ana aramadan çıkar (yalnızca
   "sonuç gerçekten 0" durumunda, arka planda)
8. **TrAnimeIzle/T1-T3** — captcha bütçesini tek seferlik yap + bölüm→seri
   adaylarını paralelleştir

Her adım sonunda `./gradlew :Eklenti:test` ve depo Python testleri
(`pytest tests/`) çalıştırılacak; yeni regresyon testleri dökümlerden
beslenecek.
