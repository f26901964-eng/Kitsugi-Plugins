# AniList Kotası — Kök Neden Analizi ve Kalıcı Çözüm

**Tarih:** 2026-10-24
**Kapsam:** `f26901964-eng/Kitsugi-Beta` (uygulama kodu, `main` @ `00cc76e` sonrası) + AniList ekosistemi
**Belirti:** Bildirimler açılmıyor — *"AniList bildirim isteği yanıtsız kaldı (ağ/sunucu hatası)"* / **KTS-ANL-2002 — AniList çok fazla istek (GraphQL 429)**
**Ek dosyalar:**

| Dosya | Ne işe yarar |
|---|---|
| `AniListQuotaGate.kt` | Tek kota kapısı (uygulamaya doğrudan eklenebilir) |
| `kitsugi-beta-anilist-quota-gate.patch` | Kitsugi-Beta'ya uygulanabilir entegrasyon yaması (8 dosya) |
| `anilist_kota_sim.py` | Algoritmanın Python portu + 5 garantinin testi + 3 mimarinin karşılaştırması |

---

## 0. Yönetici özeti (30 saniye)

1. **AniList "esnek" bir API değil.** Dokümanda 90 istek/dk yazıyor ama **yıllardır fiilen 30 istek/dk** çalışıyor; üstüne **burst limiter** var. Limit **IP başına** — mobil operatör CGNAT'ında bu IP'yi başkalarıyla paylaşıyor olabilirsin.
2. **Uygulamanın kendi içinde kota yönetimi çelişkili.** `PlatformRateLimiter` 28/dk'yı biliyor ama yalnızca eşitleme/giriş yollarında devrede; metadata trafiği `KitsugiApiBase` "normal mod"da **350 ms aralık = 171 istek/dk** ile gidiyor; "degraded" modu ise 1 500 ms = **40 istek/dk**, yani hâlâ limitin üstünde. Degraded moda geçiş, limiti *harcadıktan sonra* gelen başlıklara bakıyor.
3. **Kota tek yerden yönetilmiyor.** 54 çağrı noktası `executeAniListQuery`'den geçerken **5 ayrı yol doğrudan `graphql.anilist.co`'ya POST atıyor** ve pacing'i tamamen atlıyor (eşitleme, içe aktarma, iki profil ekranı, teşhis).
4. **429 geldiğinde uygulama kendini kilitliyor.** Cooldown 6 sn'den uzunsa *her* çağıran anında `null` alıyor; bildirim ekranı bunu "ağ hatası" diye gösteriyor. Retry-After 30–60 sn olabildiği için ekran çoğu zaman boş kalıyor.
5. **Talep gereksiz yüksek.** Bildirim ekranı her açılışta **2 istek**, her sekme değişiminde **+1 istek** atıyor; önbellek yok, tek-uçuş (single-flight) yok, aynı veriyi iki ekran ayrı ayrı istiyor.
6. **Çözüm "daha çok IP" değil, "daha az ve sıralı istek".** Ekteki `AniListQuotaGate` kayan pencere + minimum aralık + öncelik + 429 penceresi uygular; yama bildirim akışını 2 istekten 1'e indirir, 60 sn önbellek ve istemci tarafı sekme filtresi getirir.
7. **Simülasyon sonucu:** aynı kullanıcı davranışında mevcut mimari **6 × HTTP 429**, 60 sn'lik pencerede **41 istek**; yeni mimari **0 × 429**, pencerede **19 istek**, gönderilen istek **122 → 45**. Garanti testleri 5/5 geçiyor.

---

## 1. "Diğer kaynaklar kadar esnek bir API yok mu?" — Kısa cevap: hayır

AniList'in public GraphQL API'si **ücretsiz, anahtarsız ve son derece cömert bir şema** sunuyor; esnek olmayan tarafı şema değil, **kota rejimi**. Bugünkü gerçek durum:

| Konu | Doküman ne diyor | Gerçekte olan |
|---|---|---|
| Dakikalık limit | 90 istek/dk | **30 istek/dk** (degraded; yanıt başlığı `X-RateLimit-Limit: 30`) |
| Ek limit | "burst limiter" (eşik yayınlanmadı) | Kısa sürede ardışık istekte 429 |
| Limit kimliği | — | **IP başına** (uygulama başına değil) |
| Aşım cezası | 1 dakika timeout + `Retry-After` | Aynı; pencere içindeki her istek de 429 |
| Ağır kesinti | 403 + "temporarily disabled" | 2026 Eylül'de haftalarca yaşandı |
| Kalıcı blok | "nadiren, manuel IP bloğu" | Veri merkezi/Cloudflare Workers IP'leri engellendi |
| Limit artırımı | E-posta ile başvuru | **"Şu anda kabul etmiyoruz"** |
| Ücretli tier | — | Yok |

**Neden böyle:** AniList sahiplerinden nazo, 2026-09'da API trafiğinin 2,5 ayda **ikiye katlandığını**, bir günde normal site trafiğine ek olarak **~36 milyon API isteği** işlediklerini duyurdu. Limit düşürmeleri keyfi değil; ücretsiz API'yi ayakta tutma tedbiri. Yani "daha esnek API" beklemek gerçekçi değil; **uygulamanın kotayı bir bütçe gibi yönetmesi** gerekiyor.

> **Mobil için kritik ayrıntı:** limit **IP başına** sayıldığı için, operatörün CGNAT'ı yüzünden aynı public IP'yi yüzlerce abone paylaşabilir. Yani uygulama kusursuz pacing yapsa bile 429 gelebilir. Bu yüzden mimari **"429'u hiç görmemek"** değil, **"429'u ucuz atlatmak"** üzerine kurulmalı: bayat önbellek, otomatik geri sayım, düşük öncelikli işi erteleme.

---

## 2. Ekrandaki hatanın zinciri (KTS-ANL-2002)

```
başka bir ekran (Keşfet / Profil / arka plan eşitleme) kotayı tüketti
        ↓
AniList → HTTP 429 + Retry-After: 43
        ↓
KitsugiApiBase.anilistRateLimitedUntil = now + 30 sn  (cap!)   ← gerçek pencere 60 sn
        ↓
executeAniListQuery: cooldown 6 sn'den uzun → istek GÖNDERİLMEDEN null
        ↓
KitsugiAniListNotificationClient: null → "AniList bildirim isteği yanıtsız kaldı"
        ↓
VM: R.string.notif_error_load + PlatformFailureRegistry → KTS-ANL-2002
        ↓
Hata kataloğu: "Otomatik yeniden denenecek"  ← ama bildirim akışında zamanlayıcı YOK
```

Üç ayrı kusur üst üste biniyor:
- **Yanlış sınıflandırma:** kota hatası "ağ/sunucu hatası" olarak gösteriliyor, kullanıcı ne yapacağını bilmiyor.
- **Yanlış süre:** cooldown 30 sn'de kesiliyor (`ANILIST_RATE_LIMIT_COOLDOWN_CAP_MS`), AniList'in penceresi 60 sn → kesildikten sonra yapılan istek *yine* 429.
- **Tutulmayan vaat:** katalog "otomatik yeniden denenecek" diyor; `KitsugiNotificationsViewModel` içinde `delay`/retry yok.

---

## 3. Kitsugi-Beta'nın AniList trafiğinin röntgeni

Aşağıdaki bulgular klon üzerinde statik olarak doğrulandı; satır numaraları `main` anına aittir.

### 3.1 İki ayrı hız rejimi birbiriyle çelişiyor

| Yer | Sabit | Etkin hız |
|---|---|---|
| `data/auth/PlatformRateLimiter.kt` | `"anilist" to 2100L` | ~28 istek/dk ✅ |
| `data/remote/KitsugiApiBase.kt` | `ANILIST_MIN_INTERVAL_NORMAL_MS = 350` | **~171 istek/dk** ❌ |
| `KitsugiApiBase.kt` | `ANILIST_MIN_INTERVAL_DEGRADED_MS = 1_500` | **40 istek/dk** ❌ |
| `KitsugiApiBase.kt` | `ANILIST_MAX_CONCURRENCY = 3` | burst limiter'ı tetikleyen eşzamanlılık |

`PlatformRateLimiter` doğru sabiti biliyor ama **kapsamı dar**: `anilist` için `acquire()` yalnızca `ExternalListSyncManager` ve `AuthViewModel` (giriş) yollarında çağrılıyor. Yani **metadata trafiğinin tamamı** (Keşfet, detay, bildirim, profil, arama, takvim — 54 çağrı noktası) `KitsugiApiBase`'in 350 ms rejiminde gidiyor. İki rejim aynı IP'yi paylaştığı için `PlatformRateLimiter`'ın 2,1 sn'si hiçbir işe yaramıyor: diğer 54 nokta bütçeyi çoktan bitirmiş oluyor.

### 3.2 "Degraded" algısı geç ve yetersiz

```kotlin
aniListDegradedMode = (limit != null && limit <= 45) || (remaining != null && remaining <= 12)
```

- `remaining <= 12` eşiği, 30'luk bütçenin **18 isteği harcandıktan sonra** devreye giriyor.
- Devreye girdiğinde uygulanan hız **40 istek/dk** — hâlâ limitin üstünde.
- Yani "kendini frenleyen" mekanizma, frenlediğini sandığı yerde bile limit aşıyor.

### 3.3 Global donma: 429 → 6 sn'den uzunsa herkes fail-fast

```kotlin
if (permit.reason != QUOTA_RESERVED) { /* ... */ return null }   // yeni kod öncesi hâli:
if (cooldownRemainingMs <= ANILIST_SHORT_COOLDOWN_WAIT_MS) delay(...) else return null
```

`isAniListUnavailableNow()` bu eşiği (`> 6 sn`) Keşfet'in yedek zincirini ve bildirim çağrısını da kapatıyor. Sonuç: **tek bir 429, uygulamanın tamamını ~1 dakika "boş" gösteriyor.**

### 3.4 Kotayı atlayan 5 yol

| Dosya | Satır | Ne yapıyor | Etki |
|---|---|---|---|
| `data/auth/AniListSyncManager.kt` | 865 | doğrudan POST (OkHttp) | toplu eşitleme, hız bütçesini görmez |
| `data/auth/AniListImportManager.kt` | 319 | doğrudan POST | içe aktarma, sayfa sayfa istek |
| `ui/app/KitsugiProfileViewModel.kt` | 1244 | doğrudan POST | profil istatistikleri |
| `ui/screens/profile/KitsugiUserProfileViewModel.kt` | 660 | doğrudan POST | başka kullanıcı profili |
| `data/notifications/NotificationDiagnostics.kt` | 135 | doğrudan POST | "teşhis" çalıştırmak kotayı yiyor |

Bu yollar `executeAniListQuery`'yi atladığı için pencere muhasebesine **hiç girmiyor**. Uygulamanın "hız sınırı var" sanması ile gerçek trafik arasındaki farkın en büyük sebebi bu.

### 3.5 Talep şişkinliği (asıl mesele)

| Ekran | Bugün | Olması gereken |
|---|---|---|
| Bildirimler — açılış | 2 istek (sayfa + sayaç) | **1** (sayfa + `Viewer { unreadNotificationCount }` aynı belgede) |
| Bildirimler — sekme değişimi | Her sekme **+1** (`type_in` sunucuda) | **0** ("Tümü" önbelleği + istemci filtresi) |
| Detay sayfası | `fetchDetail` + `fetchSynopsis` + `fetchTagsAndAiringInfo` + sekmeler (karakter/ekip/ilişkiler) → **3–6 belge** | **2** (alias'lı tek belge + synopsis) |
| Arama | debounce yok, yazarken ~4-5 istek | **1** (400 ms debounce) |
| Profil istatistikleri | 3 istek | **1** (`User.statistics`) |
| Keşfet rafları | 6'lı alias ✅ | 6'lı alias (doğru; korunmalı) |

Önbellek ve tek-uçuş yokluğu bunu büyütüyor: aynı detay iki farklı ekrandan istenirse iki kez gidiyor; aynı ekran geri açıldığında yine gidiyor.

### 3.6 İyi olan taraflar (korunmalı)

- `KitsugiHttpClient.aniListClient` **RetryInterceptor'sız** ve 15 sn `callTimeout`'lu ✅ (12 denemelik çarpan kaldırılmış)
- Keşfet'te alias'lı toplu sorgu (`s0: Page(...)`, 6'lı öbek) ✅
- 403 "temporarily disabled" için ayrı istisna + devre kesici ✅
- `PlatformFailureInterceptor` ile merkezi hata kaydı ✅

---

## 4. Ekosistem bu sorunu nasıl çözüyor?

| Proje / PR | Yaklaşım | Bizim için ders |
|---|---|---|
| **animitchures** (#15) | 429'u asla boş sayfa bırakmaz: RetryLink + üstel geri çekilme + **jitter**; "aynı anda birden çok hata aynı milisaniyede yeniden denemesin" | Jitter şart: paralel istekler aynı anda geri dönerse taze bütçeyi birlikte yakar |
| **Mihon** (#372, Ağu-2026 sürüm notları) | Takipçi katmanında pacing; 429 üretmeyen sürüm notu | Pacing **takipçi katmanında tek yerde** olmalı |
| **anime-extensions** (#1115) | 85/dk → **25/dk** + **2,4 sn minimum aralık** | Gerçek limit 30 ise 25'te kalmak doğru; "limit-2" prensibi |
| **SeerrNG** (#2) | Tüm süreç için **tek paylaşımlı limiter**: 30/60 sn, istekler **en az 1 sn** arayla; 429'dan sonra Retry-After bitene kadar **hiç istek yok** | Tek limiter + tek 429 penceresi = bizim mimarinin aynısı |
| **uchiyomi** (#273) | `60 / (limit - 2)` aralık, limit başlıktan okunur; 40 serilik backfill 30/dk'nın altında kalır | Aralığı **başlıktan öğrenilen limit** belirlemeli |
| **anilist-arr-sync** (#23) | 80/dk throttle (90 varsayımıyla), 429'da `Retry-After` (hem saniye hem HTTP-date) | Retry-After formatı çeşitli olabilir |
| **anilist-mcp** (#1, #2) | Sınırsız `Promise.all` → **3 eşzamanlı**; `id_in` ile **8 kimlik tek istek** (238 ms vs 1,4 sn fan-out) | Fan-out yerine toplu sorgu |
| **AniSequel** (#2) | ≤5 sn Retry-After tek denemede yutulur, uzunu kullanıcıya süreyle gösterilir | Kısa beklemeyi gizle, uzunu göster |
| **ani-gui** (#235) | "Detay sayfası açmak = kaç istek?" ölçümü; takipçi 429'u `RateLimited` olarak taşınır | **Ölçüm**: ekran başına istek sayısı bir metrik olmalı |
| **animeishi** (#227) | AniList, **Cloudflare Workers IP'lerini** 403 ile bloke ediyor | **Proxy sunucu çözüm DEĞİL** |

Ortak desen tek cümlede: **tek bir kota muhasebesi + limiti başlıktan öğrenme + istek sayısını azaltma + 429'u beklemek (retry etmemek) + jitter.**

---

## 5. Çözüm mimarisi (5 katman)

### Katman 1 — Tek kota kapısı (`AniListQuotaGate`)
- Kayar 60 sn penceresi, bütçe = `limit - max(2, limit/10)` → 30'da **27**.
- Minimum aralık = `ceil(60 000 / bütçe)` = **2 223 ms**. (`round` kullanmak 28. isteği pencereye sokar; bu yüzden **ceil**.)
- Limit `X-RateLimit-Limit`'ten öğrenilir; **90 varsayımı yok**.
- 429 → `Retry-After` (yoksa `X-RateLimit-Reset`, yoksa 60 sn) boyunca kapı **tamamen kapalı**. Pencere içinde retry yok.
- `X-RateLimit-Remaining <= 1` → pencere sıfırlanana kadar düşük öncelikli iş durur.

### Katman 2 — Öncelik
| Öncelik | Örnek | Bütçe kuralı | Bekleme tavanı |
|---|---|---|---|
| `CRITICAL` | bildirim, liste güncelleme, giriş | her zaman | 25 sn |
| `INTERACTIVE` | detay, arama, açık sekme | her zaman | 12 sn |
| `PREFETCH` | görünmeyen raf, sonraki sayfa | bütçenin %60'ı dolduysa **gönderilmez** | 3 sn |
| `BACKGROUND` | toplu eşitleme, içe aktarma, teşhis | bütçenin %40'ı dolduysa **gönderilmez** | 2 sn |

Bekleme tavanı aşılırsa kapı **reddeder** — sessizce 45 sn bekletip ANR üretmez; çağıran önbellekten cevap verir veya geri sayım gösterir.

### Katman 3 — Talebi azalt (kotadan önce buraya bak)
1. **Bildirim:** sayfa + sayacı tek belgede iste (`Viewer` kardeş kök alan). 2 → 1.
2. **Bildirim sekmeleri:** "Tümü"yü bir kez çek, diğer sekmeleri **istemci tarafında** filtrele. 6 → 0.
3. **Detay:** medya + ilişkiler + karakter + ekip + öneriler tek alias'lı belgede. 6 → 2.
4. **Arama:** 400 ms debounce. 5 → 1.
5. **Profil:** `User.statistics` ile istatistikleri tek istekte al. 3 → 1.
6. **`id_in` toplu kimlik çözümü:** 8 ayrı `Media(id:)` yerine 1 `Page(media(id_in: [...]))`.

### Katman 4 — Önbellek + tek-uçuş
- `executeAniListQueryCached(cacheKey, ttlMs, …, allowStaleMs)`.
- TTL önerileri: bildirim 60 sn · okunmamış sayaç 60 sn · medya detayı 6 saat · arama 10 dk · profil 5 dk · sezon/takvim 30 dk.
- Aynı anahtarı isteyen ikinci çağrı **kilit bekler** ve birincinin sonucunu alır (single-flight) — çift istek yok.
- `allowStaleMs`: kota dolduğunda bayat veri döner. **Boş ekran yerine eski veri.**
- Sonraki adım (Faz 3): Room'a yaz → soğuk açılışta uçak modunda bile liste görünür.

### Katman 5 — Dayanıklılık ve UX
- 429 → "**~N sn sonra yeniden denenecek**" geri sayımı (uydurma "ağ hatası" değil).
- 403 "temporarily disabled" → devre kesici (zaten var) + **Jikan/MAL / Kitsu / Bangumi** yedeği (detay ekranlarında zaten var).
- Jitter: yeniden denemeler `retry + random(0..1 sn)`.
- Teşhis: `AniListQuotaGate.snapshot()` → limit, bütçe, pencerede kullanılan, cooldown. Hata kataloğu ekranına "kota" sekmesi.

---

## 6. Uygulama — dosya dosya

Yama: `kitsugi-beta-anilist-quota-gate.patch` (8 dosya, +578/−33 satır). Kitsugi-Beta klonunda:

```bash
git apply --check kitsugi-beta-anilist-quota-gate.patch   # temiz
git apply kitsugi-beta-anilist-quota-gate.patch
```

| Dosya | Değişiklik |
|---|---|
| `data/remote/AniListQuotaGate.kt` | **YENİ** — kapı (322 satır, tek bağımlılık: kotlinx.coroutines) |
| `data/remote/KitsugiApiBase.kt` | `executeAniListQuery`'ye `priority`; cooldown fail-fast'i kapıya devret; başlıkları kapıya bildir; 429'da kapıyı kapat; `executeAniListQueryCached` + tek-uçuş + `aniListGate{,Blocking}` köprüleri |
| `data/remote/KitsugiAniListNotificationClient.kt` | Sayfa + sayaç tek belgede; 60 sn ortak önbellek; sekme filtresi istemciye; kota hatası geri sayımlı mesaj; `retryInMs` taşıyan istisna |
| `data/auth/AniListSyncManager.kt` | Doğrudan POST öncesi `aniListGateBlocking(BACKGROUND)` |
| `data/auth/AniListImportManager.kt` | aynı |
| `ui/app/KitsugiProfileViewModel.kt` | `aniListGate(INTERACTIVE)` |
| `ui/screens/profile/KitsugiUserProfileViewModel.kt` | `aniListGate(INTERACTIVE)` |
| `data/notifications/NotificationDiagnostics.kt` | Teşhis isteği de kapıdan geçer |

> ⚠️ **Derleme notu:** Bu ortamda Android SDK/Gradle yok; yama **uygulanabilirlik** (`git apply --check`) ve parantez/dil yapısı açısından doğrulandı, **derlenmedi**. Uygulamadan önce `./gradlew :app:compileDebugKotlin` çalıştırın. `AniListQuotaGate.kt` bağımsız bir dosya olduğu için derleme hatası çıkarsa tek tek düzeltmek kolaydır.

### Faz 2 (yamadan bağımsız, en yüksek kazanç)
1. Bildirim sekmesi filtrelerini istemciye taşı (yama bunu zaten "Tümü" önbelleği üzerinden yapıyor; sunucu tarafı `type_in` yalnızca önbellek boşken kullanılıyor).
2. `KitsugiDetailClient` / `KitsugiMediaTabsClient` sorgularını alias'lı tek belgeye indir.
3. Arama kutusuna 400 ms debounce + `searchAniListPaged` için istek iptali.
4. Keşfet'te önden yükleme (`PREFETCH`) yalnızca "bütçede yer var" ise çalışsın.

### Faz 3
5. Room tabanlı kalıcı önbellek + TTL + bayat-veri ile açılış.
6. Bildirim VM'ine gerçek otomatik yeniden deneme (`retryInMs` kadar bekle, 1 kez) — katalog metniyle uyum için.
7. Telemetri: ekran başına istek sayısı, 429 oranı, ortalama bekleme; hata kataloğuna kota paneli.

---

## 7. Doğrulama

### 7.1 Garanti testleri (`python3 anilist_kota_sim.py`)

```
G1  Kayan pencere bütçeyi aşmıyor            PASS   (60 sn'de en çok 27 istek)
G2  Minimum istek aralığı korunuyor          PASS   (2 223 ms)
G3  429 / Retry-After'a uyuluyor             PASS
G4  Öncelik rezervi (arka plan öne geçmiyor) PASS
G5  X-RateLimit-Limit öğreniliyor            PASS
```

### 7.2 Aynı kullanıcı davranışı, üç mimari (10 dakikalık yoğun oturum)

| Ölçüt | A: Bugünkü | B: Yalnızca kapı | C: Kapı + birleştirme + önbellek |
|---|---:|---:|---:|
| Uygulamanın ürettiği istek | 123 | 123 | 82 |
| Gerçekten gönderilen HTTP isteği | 122 | 74 | **45** |
| Önbellekten karşılanan | 0 | 10 | 4 (+ bayat) |
| 60 sn'lik pencerede en yüksek istek | **41** | 27 | 19 |
| HTTP 429 | **6** | **0** | **0** |
| 429 yüzünden tekrar deneme | 6 | 0 | 0 |
| Kullanıcının gördüğü hata | 13 | 0 | 0 |
| Kullanıcı isteğinin ort. beklemesi | 0 | 9,9 sn | 8,3 sn |

**Yorum:** (A) 429 üretiyor ve her 429 sonrası uygulama 5–30 sn donuyor. (B) tek başına 429'u sıfırlıyor ama soğuk açılışta sıra uzun olduğu için bekleme yükseliyor; bu yüzden (C) gibi **talep azaltma** şart. Simülasyon, burst limiter eşiği yayınlanmadığı için 60 sn kayan pencere modelini kullanıyor; kapı ayrıca minimum aralık uyguladığı için burst'e karşı da korumalı.

### 7.3 Sahada ölçüm planı (yayın sonrası ilk hafta)
- `AniListQuotaGate.snapshot()` değerlerini hata kataloğu ekranında göster.
- Log'la: `429 oranı = 429 / toplam istek` (hedef: **< %0,5**), `pencere tepe ≤ 27`, `bildirim ekranı istek sayısı = 1`.
- Kabul kriteri: 10 dakikalık tipik oturumda 429 **0**, bildirim açılışı **≤ 1,2 sn** (önbellek sıcakken 0 istek).

---

## 8. Yapılmaması gerekenler (ve nedenleri)

| Fikir | Neden yapılmamalı |
|---|---|
| **Kendi proxy/mirror sunucun** | Limit IP başına → tüm kullanıcılar **tek** 30/dk bütçesini paylaşır. AniList ayrıca Cloudflare Workers/veri merkezi IP'lerini 403 ile bloke ediyor. 2 kullanıcıda bile çöker. |
| **Resmî olmayan AniList API klonu/kazıyıcı** | ToS riski + veri tazeliği + AniList zaten "manuel IP bloğu" uyguluyor. |
| **IP rotasyonu / VPN** | AniList'in "manual blocking" maddesi tam bunun için var. Hesap riski. |
| **Çoklu token / çoklu hesap** | Limit token'a değil IP'ye bağlı; faydası yok, riski var. |
| **429'da hemen tekrar denemek** | Pencere içindeki her istek 429'dur. Retry = bütçeyi çöpe atmak. |
| **Sadece "aralığı 2 sn yap"** | Tek başına yetmez: 54 çağrı noktası + 5 kaçak yol + önbelleksiz tekrarlar aynı ekranda 30 isteği aşabiliyor. |
| **Uzun süre sessizce bekletmek** | ANR + "uygulama dondu" algısı. Doğru davranış: önbellek/bayat veri + geri sayım. |

---

## 9. AniList ile iyi geçinme kontrol listesi

- [x] Tanımlayıcı `User-Agent` (`KitsugiAnimeList/1.0`) — var.
- [x] Düşük eşzamanlılık (≤ 3) — var; kapı sonrası fiilen 1–2.
- [x] `Retry-After` / `X-RateLimit-Reset`'e uymak — yama ile tam.
- [x] `X-RateLimit-Limit`'i okumak, 90 varsaymamak — yama ile.
- [ ] Ekran başına istek sayısını ölçmek (Faz 2).
- [ ] Toplu alias / `id_in` kullanımını yaygınlaştırmak (Faz 2).
- [ ] 429'u kullanıcıya süreyle bildirmek (Faz 3).
- [ ] AniList'e **rate limit artırımı başvurusu yapmamak** (şu an kabul edilmiyor; uygulama istemci tarafında çalışıyor, sunucu değil).

---

## 10. Yol haritası

| Faz | İş | Efor | Beklenen kazanç |
|---|---|---|---|
| **0** | Yamayı uygula, derle, bildirim ekranını doğrula | ~1 gün | 429 kaynaklı bildirim hatası biter; 2 istek → 1 |
| **1** | Faz 2 birleştirmeleri (detay, arama debounce, profil) | 2–3 gün | istek hacmi ~%50 azalır |
| **2** | Kalıcı önbellek + bayat-veri ile açılış | 2 gün | soğuk açılışta 0 istek, uçak modunda liste |
| **3** | Kota telemetrisi + hata kataloğu paneli + otomatik retry | 1–2 gün | ölçülebilirlik ve destek yükünün azalması |

---

## 11. Kaynaklar

**AniList resmî**
- Rate Limiting (30/dk degraded, burst limiter, başlıklar, Retry-After) — https://docs.anilist.co/guide/rate-limiting
- Considerations (403 "temporarily disabled", manuel IP bloğu) — https://docs.anilist.co/guide/considerations
- Pagination (Page içinde tek veri alanı kısıtı) — https://docs.anilist.co/guide/graphql/pagination
- Page nesnesi (5 000 giriş sınırı) — https://docs.anilist.co/reference/object/page
- API trafiği artışı duyurusu (~36 M istek/gün) — https://pesky.moe/links/2026-09-11-anilist-website-performance-issues-api-outages/

**Açık kaynak uygulamalar / PR'lar**
- animitchures #15 — 429 sonrası dayanıklılık, jitter, "30/dk gerçek limit" — https://github.com/Mitchures/animitchures/pull/15
- upbrr #255 — canlı `X-RateLimit-Limit: 30`, `Retry-After: 51` kanıtı — https://github.com/autobrr/upbrr/issues/255
- anime-extensions #1115 — 85/dk → 25/dk + 2,4 sn aralık — https://github.com/yuzono/anime-extensions/pull/1115
- SeerrNG #2 — süreç geneli tek limiter (30/60 sn, ≥1 sn aralık) — https://github.com/Boykai/seerrng/pull/2
- uchiyomi #273 — `60/(limit-2)` aralık, başlıktan öğrenilen limit — https://github.com/AngeloSha/uchiyomi/pull/273
- anilist-arr-sync #23 — 80/dk throttle + Retry-After — https://github.com/lucasskywalker64/anilist-arr-sync/pull/23
- anilist-mcp #1 — sınırsız `Promise.all` → 3 eşzamanlı — https://github.com/Raikiri1310/anilist-mcp/pull/1
- anilist-mcp #2 — `id_in` toplu sorgu (1 istek vs 8) — https://github.com/Raikiri1310/anilist-mcp/pull/2
- AniSequel #2 — kısa Retry-After'ı yut, uzunu göster — https://github.com/LOST-4EVER/AniSequel/pull/2
- ani-gui #235 — "detay sayfası kaç istek?" ölçümü — https://github.com/JoaoPucci/ani-gui/pull/235
- Mihon #372 (AniList 429) — https://github.com/mihonapp/mihon/issues/372
- Mihon sürüm notları (takipçi 429 düzeltmeleri) — https://mihon.app/download/

**Kesinti / blok kanıtları**
- animeishi #227 — Cloudflare Workers IP'leri 403 — https://github.com/KOU050223/animeishi/issues/227
- mymangatracker #215 — Eylül 2026'da GraphQL 403 — https://github.com/ArthurM4rtins/mymangatracker/issues/215
- seadexerr #36 / #52 — 403 örnekleri — https://github.com/Ryder-C/seadexerr/issues/36

**IP paylaşımı (CGNAT)**
- Zuplo — "Don't rate limit by IP" — https://zuplo.com/blog/dont-rate-limit-by-ip
